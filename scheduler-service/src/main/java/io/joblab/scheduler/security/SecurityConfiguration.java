package io.joblab.scheduler.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

    static final String ISSUER = "joblab-identity";
    static final String AUDIENCE = "joblab-api";

    @Bean
    SecretKey signingKey(@Value("${joblab.security.jwt-key}") String encoded,
                         @Value("${joblab.security.demo-accounts}") boolean demo) {
        if (!demo && encoded.equals("am9ibGFiLWxvY2FsLWRlbW8ta2V5LW11c3QtYmUtY2hhbmdlZA==")) {
            throw new IllegalArgumentException("Set an independent JWT signing key outside demo mode");
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JOBLAB_JWT_SECRET_B64 must encode at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder tokenEncoder(SecretKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
    }

    @Bean
    JwtDecoder tokenDecoder(SecretKey signingKey) {
        var decoder = NimbusJwtDecoder.withSecretKey(signingKey).macAlgorithm(MacAlgorithm.HS256).build();
        var audience = new JwtClaimValidator<List<String>>("aud", values -> values != null && values.contains(AUDIENCE));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER), audience));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain requests(HttpSecurity http, IdentityStore identities) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(rules -> rules
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/token", "/internal/jobs/*/claim",
                                "/internal/jobs/*/heartbeat", "/internal/jobs/*/complete").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/readiness", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/events/*/reservations", "/api/tickets/reservations/*/payments")
                                .hasAnyRole("ADMIN", "OPERATOR", "BUYER")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/jobs", "/api/jobs/**", "/api/schedules", "/api/schedules/**", "/api/operations/**")
                                .hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(settings -> settings.jwtAuthenticationConverter(jwt -> {
                    TenantActor actor;
                    try {
                        actor = TenantActor.from(jwt);
                    } catch (IllegalArgumentException | NullPointerException ex) {
                        throw invalidToken();
                    }
                    Object version = jwt.getClaims().get("ver");
                    var account = identities.account(actor)
                            .filter(value -> value.enabled() && version instanceof Number number
                                    && value.tokenVersion() == number.longValue())
                            .orElseThrow(SecurityConfiguration::invalidToken);
                    return new JwtAuthenticationToken(jwt,
                            List.of(new SimpleGrantedAuthority("ROLE_" + account.role().name())));
                })))
                .build();
    }

    private static OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(BearerTokenErrors.invalidToken("Account is disabled or credentials have changed"));
    }
}
