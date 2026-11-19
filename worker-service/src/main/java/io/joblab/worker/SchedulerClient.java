package io.joblab.worker;

import io.joblab.messaging.JobProtocol.Claim;
import io.joblab.messaging.JobProtocol.ClaimRequest;
import io.joblab.messaging.JobProtocol.Completion;
import io.joblab.messaging.JobProtocol.Heartbeat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static io.joblab.messaging.JobProtocol.INTERNAL_KEY_HEADER;
import static io.joblab.messaging.JobProtocol.WORKER_PROTOCOL_HEADER;
import static io.joblab.messaging.JobProtocol.WORKER_PROTOCOL_VERSION;

@Component
public class SchedulerClient {
    private final RestClient rest;

    public SchedulerClient(@LoadBalanced RestClient.Builder builder, @Value("${joblab.internal-key}") String key) {
        this.rest = builder.baseUrl("http://scheduler-service/internal/jobs")
                .defaultHeader(INTERNAL_KEY_HEADER, key)
                .defaultHeader(WORKER_PROTOCOL_HEADER, WORKER_PROTOCOL_VERSION).build();
    }

    Claim claim(UUID id, ClaimRequest request) {
        return rest.post().uri("/{id}/claim", id).body(request).retrieve().body(Claim.class);
    }

    boolean renew(UUID id, UUID token) {
        return send(id, "/{id}/heartbeat", new Heartbeat(token));
    }

    boolean complete(UUID id, Completion completion) {
        return send(id, "/{id}/complete", completion);
    }

    private boolean send(UUID id, String path, Object request) {
        try {
            rest.post().uri(path, id).body(request).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.Conflict ex) {
            return false;
        }
    }
}

@Configuration(proxyBeanMethods = false)
class WorkerConfiguration {

    @Bean
    @LoadBalanced
    RestClient.Builder schedulerRestClient() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().requestFactory(factory);
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService leaseHeartbeats() {
        return Executors.newScheduledThreadPool(2);
    }
}
