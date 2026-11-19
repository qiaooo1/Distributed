package io.joblab.scheduler;

import io.joblab.scheduler.security.TenantActor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class AuditLog {

    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(TenantActor actor, String action, UUID resourceId, String detail) {
        write(actor.tenantId(), actor.accountId(), action, resourceId, detail);
    }

    void system(UUID tenant, String action, UUID resourceId, String detail) {
        write(tenant, null, action, resourceId, detail);
    }

    private void write(UUID tenant, UUID actor, String action, UUID id, String detail) {
        jdbc.update("""
                INSERT INTO audit_event (tenant_id, actor_id, action, resource_id, request_id, detail)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenant.toString(), actor == null ? null : actor.toString(), action, id.toString(),
                MDC.get("requestId"), detail);
    }

    public List<Entry> page(UUID tenant, Long before, int limit) {
        return jdbc.query("""
                SELECT id, actor_id, action, resource_id, request_id, detail, occurred_at
                FROM audit_event WHERE tenant_id = ? AND id < ?
                ORDER BY id DESC LIMIT ?
                """, (rs, n) -> new Entry(rs.getLong("id"), rs.getString("actor_id"), rs.getString("action"),
                UUID.fromString(rs.getString("resource_id")), rs.getString("request_id"), rs.getString("detail"),
                rs.getTimestamp("occurred_at").toInstant()), tenant.toString(), before == null ? Long.MAX_VALUE : before, limit);
    }

    public record Entry(long id, String actorId, String action, UUID resourceId, String requestId,
                        String detail, Instant occurredAt) {
    }
}
