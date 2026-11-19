package io.joblab.scheduler.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IdentityStore {

    private static final RowMapper<Account> ACCOUNT = (rows, index) -> new Account(
            UUID.fromString(rows.getString("id")), UUID.fromString(rows.getString("tenant_id")),
            rows.getString("username"), rows.getString("password_hash"), Role.valueOf(rows.getString("role_name")),
            rows.getBoolean("enabled"), rows.getInt("token_version"));

    private final JdbcTemplate jdbc;

    public IdentityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Account> login(String tenantSlug, String username) {
        return jdbc.query("""
                SELECT a.* FROM tenant_account a JOIN tenant t ON a.tenant_id = t.id
                WHERE t.slug = ? AND a.username = ?
                """, ACCOUNT, tenantSlug, username).stream().findFirst();
    }

    Optional<Account> account(TenantActor actor) {
        return jdbc.query("SELECT * FROM tenant_account WHERE tenant_id = ? AND id = ?",
                ACCOUNT, actor.tenantId().toString(), actor.accountId().toString()).stream().findFirst();
    }

    List<MemberView> members(UUID tenant, String afterUsername, int limit) {
        return jdbc.query("""
                SELECT id, username, role_name, enabled FROM tenant_account
                WHERE tenant_id = ? AND username > ? ORDER BY username LIMIT ?
                """, (rs, n) -> new MemberView(UUID.fromString(rs.getString("id")), rs.getString("username"),
                Role.valueOf(rs.getString("role_name")), rs.getBoolean("enabled")), tenant.toString(), afterUsername, limit);
    }

    void insert(UUID tenant, UUID id, String username, String passwordHash, Role role) {
        jdbc.update("""
                INSERT INTO tenant_account (id, tenant_id, username, password_hash, role_name)
                VALUES (?, ?, ?, ?, ?)
                """, id.toString(), tenant.toString(), username, passwordHash, role.name());
    }

    void lockTenant(UUID tenant) {
        jdbc.queryForObject("SELECT id FROM tenant WHERE id = ? FOR UPDATE", String.class, tenant.toString());
    }

    int update(UUID tenant, UUID id, Role role, boolean enabled) {
        return jdbc.update("""
                UPDATE tenant_account SET role_name = ?, enabled = ?, token_version = token_version + 1
                WHERE tenant_id = ? AND id = ?
                """, role.name(), enabled, tenant.toString(), id.toString());
    }

    void seedTenant(UUID id, String slug, int runningLimit) {
        jdbc.update("""
                INSERT INTO tenant (id, slug, running_limit) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, id.toString(), slug, runningLimit);
    }

    void seedAccount(UUID tenant, String username, String hash, Role role) {
        jdbc.update("""
                INSERT INTO tenant_account (id, tenant_id, username, password_hash, role_name)
                VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = id
                """, UUID.randomUUID().toString(), tenant.toString(), username, hash, role.name());
    }

    public enum Role { ADMIN, OPERATOR, VIEWER }

    record Account(UUID id, UUID tenantId, String username, String passwordHash, Role role,
                   boolean enabled, int tokenVersion) {
    }

    public record MemberView(UUID id, String username, Role role, boolean enabled) {
    }
}
