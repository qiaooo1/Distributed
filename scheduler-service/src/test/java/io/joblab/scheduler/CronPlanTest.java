package io.joblab.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static io.joblab.scheduler.ScheduleModels.Misfire.FIRE_ONCE;
import static io.joblab.scheduler.ScheduleModels.Misfire.SKIP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronPlanTest {
    private final CronPlan plan = CronPlan.parse("0 0 9 * * *", "Asia/Shanghai");

    @Test
    void nextOccurrenceUsesTheSchedulesTimeZone() {
        assertThat(plan.next(Instant.parse("2026-02-04T00:59:59Z")))
                .isEqualTo(Instant.parse("2026-02-04T01:00:00Z"));
    }

    @Test
    void skipsAnOccurrenceOutsideTheMisfireWindowWhenConfigured() {
        var planned = Instant.parse("2026-02-04T01:00:00Z");
        assertThat(plan.occurrence(planned, planned.plusSeconds(31), Duration.ofSeconds(30), SKIP, 1)).isNull();
    }

    @Test
    void fireOnceUsesTheCurrentLocalDayAndWholeDayLookback() {
        var planned = Instant.parse("2026-02-04T01:00:00Z");
        var now = Instant.parse("2026-02-06T18:00:05.123Z");
        var occurrence = plan.occurrence(planned, now, Duration.ofSeconds(30), FIRE_ONCE, 3);

        assertThat(occurrence.fireAt()).isEqualTo(Instant.parse("2026-02-06T18:00:05Z"));
        assertThat(occurrence.from()).isEqualTo(LocalDate.of(2026, 2, 4));
        assertThat(occurrence.to()).isEqualTo(LocalDate.of(2026, 2, 6));
    }

    @Test
    void rejectsCadenceBelowFifteenSecondsAndNonIanaZones() {
        assertThatThrownBy(() -> CronPlan.parse("*/5 * * * * *", "UTC")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CronPlan.parse("0 * * * * *", "+08:00")).isInstanceOf(IllegalArgumentException.class);
    }
}
