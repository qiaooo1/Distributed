package io.joblab.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class RequestPolicyFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestPolicyFilter.class);
    private static final String REQUEST_ID = "X-Request-Id";
    private static final Pattern RESERVATION_PATH = Pattern.compile("/api/tickets/events/[^/]+/reservations/?");
    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], 61) end
            return count
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final int limit;
    private final int loginLimit;
    private final int ticketLimit;

    public RequestPolicyFilter(ReactiveStringRedisTemplate redis, @Value("${joblab.rate-limit}") int limit,
                               @Value("${joblab.login-rate-limit}") int loginLimit,
                               @Value("${joblab.ticket-rate-limit:300}") int ticketLimit) {
        if (limit < 1 || loginLimit < 1 || ticketLimit < 1) {
            throw new IllegalArgumentException("Job rate limit must be positive");
        }
        this.redis = redis;
        this.limit = limit;
        this.loginLimit = loginLimit;
        this.ticketLimit = ticketLimit;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = validId(exchange.getRequest().getHeaders().getFirst(REQUEST_ID));
        exchange.getResponse().getHeaders().set(REQUEST_ID, requestId);
        var request = exchange.getRequest().mutate().headers(headers -> headers.set(REQUEST_ID, requestId)).build();
        var forwarded = exchange.mutate().request(request).build();

        String path = request.getPath().pathWithinApplication().value();
        boolean login = "/api/auth/token".equals(path);
        boolean submit = "/api/jobs".equals(path) || "/api/jobs/".equals(path);
        boolean reserve = RESERVATION_PATH.matcher(path).matches();
        if (!HttpMethod.POST.equals(request.getMethod()) || (!login && !submit && !reserve)) {
            return chain.filter(forwarded);
        }

        long now = Instant.now().getEpochSecond();
        int capacity = login ? loginLimit : reserve ? ticketLimit : limit;
        String prefix = login ? "joblab:login:" + remoteAddress(exchange) + ":"
                : reserve ? "joblab:ticket-reserve:" : "joblab:submit:";
        String key = prefix + now / 60;
        return redis.execute(INCREMENT, List.of(key), List.of()).single()
                .onErrorResume(ex -> {
                    log.warn("Redis rate limiter unavailable for request {}", requestId);
                    return Mono.just(login || reserve ? -1L : 0L);
                })
                .flatMap(count -> {
                    if (count < 0) {
                        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                        return exchange.getResponse().setComplete();
                    }
                    if (count <= capacity) {
                        return chain.filter(forwarded);
                    }
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders().set("Retry-After", Long.toString(60 - now % 60));
                    return exchange.getResponse().setComplete();
                });
    }

    private String remoteAddress(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    private String validId(String value) {
        return value != null && value.matches("[a-zA-Z0-9_.-]{1,64}") ? value : UUID.randomUUID().toString();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
