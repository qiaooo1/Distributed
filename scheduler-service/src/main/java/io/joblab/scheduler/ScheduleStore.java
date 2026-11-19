package io.joblab.scheduler;

import io.joblab.messaging.JobProtocol.ReportType;
import io.joblab.scheduler.ScheduleModels.Definition;
import io.joblab.scheduler.ScheduleModels.Misfire;
import io.joblab.scheduler.ScheduleModels.Overlap;
import io.joblab.scheduler.ScheduleModels.Schedule;
import io.joblab.scheduler.ScheduleModels.ScheduleView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class ScheduleStore {

    private static final RowMapper<Schedule> SCHEDULE = (rs, n) -> new Schedule(UUID.fromString(rs.getString("tenant_id")),
            new ScheduleView(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("cron_expression"),
                    rs.getString("time_zone"), ReportType.valueOf(rs.getString("report_type")), rs.getInt("lookback_days"),
                    rs.getInt("max_attempts"), Misfire.valueOf(rs.getString("misfire_policy")),
                    Overlap.valueOf(rs.getString("overlap_policy")), rs.getBoolean("enabled"),
                    instant(rs.getTimestamp("next_fire_at")), instant(rs.getTimestamp("last_fire_at"))));

    private final JdbcTemplate jdbc;

    ScheduleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void create(UUID tenant, UUID id, Definition input, Instant next) {
        jdbc.update("""
                INSERT INTO job_schedule (id, tenant_id, name, cron_expression, time_zone, report_type,
                    lookback_days, max_attempts, misfire_policy, overlap_policy, next_fire_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), tenant.toString(), input.name(), input.cronExpression(), input.timeZone(),
                input.reportType().name(), input.lookbackDays(), input.maxAttempts(), input.misfirePolicy().name(),
                input.overlapPolicy().name(), timestamp(next));
    }

    void update(UUID tenant, UUID id, Definition input, Instant next) {
        jdbc.update("""
                UPDATE job_schedule SET name = ?, cron_expression = ?, time_zone = ?, report_type = ?,
                    lookback_days = ?, max_attempts = ?, misfire_policy = ?, overlap_policy = ?, next_fire_at = ?
                WHERE tenant_id = ? AND id = ?
                """, input.name(), input.cronExpression(), input.timeZone(), input.reportType().name(),
                input.lookbackDays(), input.maxAttempts(), input.misfirePolicy().name(), input.overlapPolicy().name(),
                timestamp(next), tenant.toString(), id.toString());
    }

    void setEnabled(UUID tenant, UUID id, boolean enabled, Instant next) {
        jdbc.update("UPDATE job_schedule SET enabled = ?, next_fire_at = ? WHERE tenant_id = ? AND id = ?",
                enabled, timestamp(next), tenant.toString(), id.toString());
    }

    Optional<Schedule> find(UUID tenant, UUID id, boolean lock) {
        String sql = "SELECT * FROM job_schedule WHERE tenant_id = ? AND id = ?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, SCHEDULE, tenant.toString(), id.toString()).stream().findFirst();
    }

    List<ScheduleView> list(UUID tenant, String afterName, int limit) {
        return jdbc.query("""
                SELECT * FROM job_schedule WHERE tenant_id = ? AND name > ? ORDER BY name LIMIT ?
                """, SCHEDULE, tenant.toString(), afterName, limit).stream().map(Schedule::view).toList();
    }

    Optional<Schedule> due() {
        return jdbc.query("""
                SELECT * FROM job_schedule WHERE enabled = TRUE AND next_fire_at <= CURRENT_TIMESTAMP(6)
                ORDER BY next_fire_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, SCHEDULE).stream().findFirst();
    }

    boolean hasOutstandingJob(UUID tenant, UUID schedule) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM job WHERE schedule_id = ? AND tenant_id = ?
                               AND state IN ('SCHEDULED', 'QUEUED', 'RUNNING'))
                """, Boolean.class, schedule.toString(), tenant.toString()));
    }

    void advance(Schedule schedule, Instant next, Instant firedAt) {
        jdbc.update("""
                UPDATE job_schedule SET next_fire_at = ?, enabled = ?, last_fire_at = COALESCE(?, last_fire_at)
                WHERE id = ?
                """, timestamp(next), next != null, timestamp(firedAt), schedule.view().id().toString());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
