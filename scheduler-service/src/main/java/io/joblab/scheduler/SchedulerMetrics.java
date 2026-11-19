package io.joblab.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class SchedulerMetrics {

    SchedulerMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        register(registry, jdbc, "joblab.outbox.pending", "Unconfirmed outbox rows",
                "SELECT COUNT(*) FROM event_outbox WHERE sent_at IS NULL");
        register(registry, jdbc, "joblab.outbox.oldest.seconds", "Age of oldest unconfirmed outbox row in seconds", """
                SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), CURRENT_TIMESTAMP(6)), 0)
                FROM event_outbox WHERE sent_at IS NULL
                """);
        register(registry, jdbc, "joblab.leases.expired", "Expired job leases awaiting recovery", """
                SELECT COUNT(*) FROM job WHERE state = 'RUNNING' AND next_action_at <= CURRENT_TIMESTAMP(6)
                """);
        register(registry, jdbc, "joblab.schedules.due", "Recurring schedules awaiting their next trigger", """
                SELECT COUNT(*) FROM job_schedule WHERE enabled = TRUE AND next_fire_at <= CURRENT_TIMESTAMP(6)
                """);
    }

    private static void register(MeterRegistry registry, JdbcTemplate jdbc, String name, String description, String sql) {
        Gauge.builder(name, jdbc, value -> value.queryForObject(sql, Double.class))
                .description(description).register(registry);
    }
}
