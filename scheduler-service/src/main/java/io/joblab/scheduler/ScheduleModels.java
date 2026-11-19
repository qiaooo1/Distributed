package io.joblab.scheduler;

import io.joblab.messaging.JobProtocol.ReportType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ScheduleModels {
    private ScheduleModels() {
    }

    public enum Misfire { SKIP, FIRE_ONCE }

    public enum Overlap { ALLOW, SKIP_IF_ACTIVE }

    public record Definition(@NotBlank @Size(max = 80) String name,
                             @NotBlank @Size(max = 80) String cronExpression,
                             @NotBlank @Size(max = 64) String timeZone,
                             @NotNull ReportType reportType,
                             @Min(1) @Max(31) int lookbackDays,
                             @Min(1) @Max(5) int maxAttempts,
                             @NotNull Misfire misfirePolicy,
                             @NotNull Overlap overlapPolicy) {
    }

    public record ScheduleView(UUID id, String name, String cronExpression, String timeZone,
                               ReportType reportType, int lookbackDays, int maxAttempts, Misfire misfirePolicy,
                               Overlap overlapPolicy, boolean enabled, Instant nextFireAt, Instant lastFireAt) {
    }

    public record SchedulePage(List<ScheduleView> items, String nextAfterName) {
    }

    record Schedule(UUID tenantId, ScheduleView view) {
    }

    public record ChangeScheduleState(@NotNull Boolean enabled) {
    }
}
