package io.joblab.scheduler;

import io.joblab.messaging.JobProtocol.ReportType;
import io.joblab.scheduler.JobModels.AttemptView;
import io.joblab.scheduler.JobModels.Job;
import io.joblab.scheduler.JobModels.Outcome;
import io.joblab.scheduler.JobModels.State;
import io.joblab.scheduler.JobModels.Submission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JobRepository {

    private static final RowMapper<Job> JOB = (rs, n) -> new Job(
            UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("tenant_id")),
            uuid(rs.getString("schedule_id")), instant(rs, "scheduled_fire_at"), uuid(rs.getString("replayed_from_id")), rs.getString("request_hash"), State.valueOf(rs.getString("state")),
            ReportType.valueOf(rs.getString("report_type")), rs.getObject("from_date", LocalDate.class),
            rs.getObject("to_date", LocalDate.class), instant(rs, "run_at"), rs.getInt("generation"),
            rs.getInt("attempt_count"), rs.getInt("max_attempts"), uuid(rs.getString("lease_token")),
            instant(rs, "next_action_at"), instant(rs, "created_at"), instant(rs, "updated_at"));

    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Instant now() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", (rs, n) -> rs.getTimestamp(1).toInstant());
    }

    Upsert insertOrGet(UUID tenant, String key, String hash, Submission input, Instant runAt, int maxAttempts,
                    UUID scheduleId, Instant scheduledFireAt, UUID replayedFromId) {
        UUID proposedId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO job (id, tenant_id, request_key, request_hash, report_type, from_date, to_date,
                                 run_at, state, max_attempts, next_action_at, schedule_id, scheduled_fire_at, replayed_from_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED', ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, proposedId.toString(), tenant.toString(), key, hash, input.reportType().name(),
                input.fromDate(), input.toDate(), Timestamp.from(runAt), maxAttempts, Timestamp.from(runAt),
                scheduleId == null ? null : scheduleId.toString(),
                scheduledFireAt == null ? null : Timestamp.from(scheduledFireAt),
                replayedFromId == null ? null : replayedFromId.toString());
        Job job = jdbc.queryForObject("SELECT * FROM job WHERE tenant_id = ? AND request_key = ? FOR UPDATE",
                JOB, tenant.toString(), key);
        return new Upsert(job, proposedId.equals(job.id()));
    }

    record Upsert(Job job, boolean created) {
    }

    Optional<Job> find(UUID id, boolean lock) {
        String query = lock ? "SELECT * FROM job WHERE id = ? FOR UPDATE" : "SELECT * FROM job WHERE id = ?";
        return jdbc.query(query, JOB, id.toString()).stream().findFirst();
    }

    Optional<Job> findForTenant(UUID tenant, UUID id, boolean lock) {
        String query = "SELECT * FROM job WHERE tenant_id = ? AND id = ?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(query, JOB, tenant.toString(), id.toString()).stream().findFirst();
    }

    List<Job> page(UUID tenant, State state, Job cursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM job WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant.toString());
        if (state != null) {
            sql.append(" AND state = ?");
            args.add(state.name());
        }
        if (cursor != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
            Timestamp timestamp = Timestamp.from(cursor.createdAt());
            args.add(timestamp);
            args.add(timestamp);
            args.add(cursor.id().toString());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), JOB, args.toArray());
    }

    int lockTenantAndReadLimit(UUID tenant) {
        return jdbc.queryForObject("SELECT running_limit FROM tenant WHERE id = ? FOR UPDATE",
                Integer.class, tenant.toString());
    }

    int activeExecutions(UUID tenant) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM job
                WHERE tenant_id = ? AND state = 'RUNNING' AND next_action_at > CURRENT_TIMESTAMP(6)
                """, Integer.class, tenant.toString());
    }

    Map<State, Long> counts(UUID tenant) {
        var counts = new java.util.EnumMap<State, Long>(State.class);
        for (State state : State.values()) {
            counts.put(state, 0L);
        }
        jdbc.query("""
                SELECT state, COUNT(*) AS count FROM job WHERE tenant_id = ? GROUP BY state
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        counts.put(State.valueOf(rs.getString("state")), rs.getLong("count")), tenant.toString());
        return counts;
    }

    Optional<Job> nextDue(State state) {
        return jdbc.query("""
                SELECT * FROM job
                WHERE state = ? AND next_action_at <= CURRENT_TIMESTAMP(6)
                ORDER BY next_action_at, id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, JOB, state.name()).stream().findFirst();
    }

    Optional<Job> nextUnclaimed() {
        return jdbc.query("""
                SELECT j.* FROM job j
                WHERE j.state = 'QUEUED' AND j.next_action_at <= CURRENT_TIMESTAMP(6)
                  AND EXISTS (SELECT 1 FROM event_outbox e
                              WHERE e.event_id = j.current_event_id AND e.sent_at IS NOT NULL)
                ORDER BY j.next_action_at, j.id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, JOB).stream().findFirst();
    }

    void markQueued(Job job, int generation, UUID eventId, Instant due) {
        jdbc.update("""
                UPDATE job SET state = 'QUEUED', generation = ?, current_event_id = ?, next_action_at = ?
                WHERE id = ?
                """, generation, eventId.toString(), Timestamp.from(due), job.id().toString());
    }

    void markRunning(Job job, int attempt, UUID token, String workerId, Instant due) {
        jdbc.update("""
                UPDATE job SET state = 'RUNNING', attempt_count = ?, lease_token = ?, next_action_at = ?
                WHERE id = ?
                """, attempt, token.toString(), Timestamp.from(due), job.id().toString());
        jdbc.update("""
                INSERT INTO job_attempt (job_id, attempt_no, lease_token, worker_id, outcome)
                VALUES (?, ?, ?, ?, 'RUNNING')
                """, job.id().toString(), attempt, token.toString(), workerId);
    }

    void extendLease(Job job, Instant due) {
        jdbc.update("UPDATE job SET next_action_at = ? WHERE id = ?", Timestamp.from(due), job.id().toString());
    }

    void moveTo(Job job, State state, Instant due) {
        jdbc.update("""
                UPDATE job SET state = ?, lease_token = NULL, current_event_id = NULL, next_action_at = ?
                WHERE id = ?
                """, state.name(), due == null ? null : Timestamp.from(due), job.id().toString());
    }

    void settleAttempt(Job job, Outcome outcome, String error) {
        jdbc.update("""
                UPDATE job_attempt SET outcome = ?, error_message = ?, finished_at = CURRENT_TIMESTAMP(6)
                WHERE job_id = ? AND attempt_no = ?
                """, outcome.name(), error, job.id().toString(), job.attemptCount());
    }

    Optional<Outcome> outcome(UUID id, UUID token) {
        return jdbc.query("SELECT outcome FROM job_attempt WHERE job_id = ? AND lease_token = ?",
                (rs, n) -> Outcome.valueOf(rs.getString(1)), id.toString(), token.toString()).stream().findFirst();
    }

    List<AttemptView> attempts(UUID id) {
        return jdbc.query("""
                SELECT * FROM job_attempt WHERE job_id = ? ORDER BY attempt_no
                """, (rs, n) -> new AttemptView(rs.getInt("attempt_no"), rs.getString("worker_id"),
                rs.getString("outcome"), rs.getString("error_message"), instant(rs, "started_at"),
                instant(rs, "finished_at")), id.toString());
    }

    void saveResult(UUID id, String csv) {
        jdbc.update("INSERT INTO job_result (job_id, csv) VALUES (?, ?)", id.toString(), csv);
    }

    String result(UUID id) {
        return jdbc.queryForObject("SELECT csv FROM job_result WHERE job_id = ?", String.class, id.toString());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
