package io.joblab.scheduler;

import io.joblab.messaging.JobProtocol.ReportType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class JobModels {
    private JobModels() {
    }

    public enum State {
        SCHEDULED, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED
    }

    enum Outcome {
        RUNNING, SUCCEEDED, TASK_FAILED, LEASE_EXPIRED, CANCELLED
    }

    public record Submission(@NotNull ReportType reportType, @NotNull LocalDate fromDate,
                             @NotNull LocalDate toDate, Instant runAt, @Min(1) @Max(5) Integer maxAttempts) {
    }

    public record JobView(UUID id, State state, ReportType reportType, LocalDate fromDate, LocalDate toDate,
                          Instant runAt, int attemptCount, int maxAttempts, Instant createdAt, Instant updatedAt,
                          UUID scheduleId, Instant scheduledFireAt, UUID replayedFromId, List<AttemptView> attempts) {
    }

    public record JobSummary(UUID id, State state, ReportType reportType, Instant runAt, int attemptCount,
                             Instant createdAt, UUID scheduleId, UUID replayedFromId) {
    }

    public record JobPage(List<JobSummary> items, UUID nextCursor) {
    }

    public record AttemptView(int attempt, String workerId, String outcome, String error,
                              Instant startedAt, Instant finishedAt) {
    }

    record Job(UUID id, UUID tenantId, UUID scheduleId, Instant scheduledFireAt, UUID replayedFromId, String requestHash, State state, ReportType reportType, LocalDate fromDate,
               LocalDate toDate, Instant runAt, int generation, int attemptCount, int maxAttempts,
               UUID leaseToken, Instant nextActionAt, Instant createdAt, Instant updatedAt) {
        boolean owns(UUID token, Instant now) {
            return state == State.RUNNING && leaseToken.equals(token) && nextActionAt.isAfter(now);
        }

        JobView view(List<AttemptView> attempts) {
            return new JobView(id, state, reportType, fromDate, toDate, runAt, attemptCount, maxAttempts,
                    createdAt, updatedAt, scheduleId, scheduledFireAt, replayedFromId, attempts);
        }
        JobSummary summary() {
            return new JobSummary(id, state, reportType, runAt, attemptCount, createdAt, scheduleId, replayedFromId);
        }
    }
}
