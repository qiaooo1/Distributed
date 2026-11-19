package io.joblab.scheduler.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

public record TenantActor(UUID tenantId, UUID accountId) {
    public static TenantActor from(Jwt jwt) {
        return new TenantActor(UUID.fromString(jwt.getClaimAsString("tenant")), UUID.fromString(jwt.getSubject()));
    }
}
