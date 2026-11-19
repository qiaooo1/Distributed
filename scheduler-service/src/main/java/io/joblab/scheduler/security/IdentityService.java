package io.joblab.scheduler.security;

import io.joblab.scheduler.AuditLog;
import io.joblab.scheduler.security.IdentityStore.MemberView;
import io.joblab.scheduler.security.IdentityStore.Role;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class IdentityService {

    private static final int TOKEN_SECONDS = 900;
    private final IdentityStore identities;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;
    private final AuditLog audit;
    private final String dummyHash;

    public IdentityService(IdentityStore identities, PasswordEncoder passwords, JwtEncoder tokens, AuditLog audit) {
        this.identities = identities;
        this.passwords = passwords;
        this.tokens = tokens;
        this.audit = audit;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public Token login(String tenant, String username, String password) {
        ensurePasswordBytes(password);
        var record = identities.login(tenant, username);
        var matches = passwords.matches(password, record.map(IdentityStore.Account::passwordHash).orElse(dummyHash));
        var account = record.filter(value -> value.enabled() && matches)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid tenant or credentials"));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(SecurityConfiguration.ISSUER)
                .audience(List.of(SecurityConfiguration.AUDIENCE)).issuedAt(now).expiresAt(now.plusSeconds(TOKEN_SECONDS))
                .subject(account.id().toString()).id(UUID.randomUUID().toString())
                .claim("tenant", account.tenantId().toString()).claim("ver", account.tokenVersion()).build();
        String token = tokens.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        audit.record(new TenantActor(account.tenantId(), account.id()), "LOGIN", account.id(), null);
        return new Token(token, "Bearer", TOKEN_SECONDS, tenant, account.role());
    }

    @Transactional
    public MemberView create(TenantActor actor, String username, String password, Role role) {
        ensurePasswordBytes(password);
        String hash = passwords.encode(password);
        identities.lockTenant(actor.tenantId());
        requireCurrentAdmin(actor);
        UUID id = UUID.randomUUID();
        try {
            identities.insert(actor.tenantId(), id, username, hash, role);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        audit.record(actor, "MEMBER_CREATED", id, role.name());
        return new MemberView(id, username, role, true);
    }

    @Transactional
    public MemberView update(TenantActor actor, UUID id, Role role, boolean enabled) {
        if (actor.accountId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An administrator cannot change their own access");
        }
        identities.lockTenant(actor.tenantId());
        requireCurrentAdmin(actor);
        if (identities.update(actor.tenantId(), id, role, enabled) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
        }
        var updated = identities.account(new TenantActor(actor.tenantId(), id)).orElseThrow();
        audit.record(actor, "MEMBER_ACCESS_CHANGED", id, role.name() + " enabled=" + enabled);
        return new MemberView(id, updated.username(), role, enabled);
    }

    public List<MemberView> list(TenantActor actor, String afterUsername, int limit) {
        return identities.members(actor.tenantId(), afterUsername, limit);
    }

    private void requireCurrentAdmin(TenantActor actor) {
        if (identities.account(actor).filter(account -> account.enabled() && account.role() == Role.ADMIN).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required");
        }
    }

    private static void ensurePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes");
        }
    }

    public record Token(String accessToken, String tokenType, int expiresIn, String tenant, Role role) {
    }
}
