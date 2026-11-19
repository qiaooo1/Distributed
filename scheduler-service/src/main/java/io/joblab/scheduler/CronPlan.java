package io.joblab.scheduler;

import io.joblab.scheduler.ScheduleModels.Misfire;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

record CronPlan(CronExpression expression, ZoneId zone) {

    private static final Instant MAX_TIMESTAMP = Instant.parse("2038-01-19T03:14:06Z");

    static CronPlan parse(String expression, String zone) {
        String seconds = expression.strip().split("\\s+", 2)[0];
        if (!seconds.matches("(?:0|15|30|45)(?:,(?:0|15|30|45))*|\\*/(?:15|30)")) {
            throw new IllegalArgumentException("Cron seconds must use the 15-second grid");
        }
        if (!ZoneId.getAvailableZoneIds().contains(zone)) {
            throw new IllegalArgumentException("An IANA time zone is required");
        }
        return new CronPlan(CronExpression.parse(expression), ZoneId.of(zone));
    }

    Instant next(Instant after) {
        var result = expression.next(after.atZone(zone));
        if (result == null || !result.toInstant().isBefore(MAX_TIMESTAMP)) {
            return null;
        }
        return result.toInstant();
    }

    Occurrence occurrence(Instant planned, Instant now, Duration tolerance, Misfire mode, int days) {
        boolean misfire = Duration.between(planned, now).compareTo(tolerance) > 0;
        if (misfire && mode == Misfire.SKIP) {
            return null;
        }
        Instant fireAt = misfire ? now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS) : planned;
        LocalDate end = fireAt.atZone(zone).toLocalDate().minusDays(1);
        return new Occurrence(fireAt, end.minusDays(days - 1L), end);
    }

    record Occurrence(Instant fireAt, LocalDate from, LocalDate to) {
    }
}
