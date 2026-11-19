package io.joblab.scheduler;

import io.joblab.messaging.JobDispatch;
import io.joblab.messaging.JobProtocol.ClaimRequest;
import io.joblab.messaging.JobProtocol.Completion;
import io.joblab.messaging.JobProtocol.ReportType;
import io.joblab.messaging.OutboxWriter;
import io.joblab.scheduler.JobModels.Job;
import io.joblab.scheduler.JobModels.Outcome;
import io.joblab.scheduler.JobModels.State;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobServiceTest {
    private static final UUID ID = UUID.fromString("990da807-ec35-4f18-a570-9e613427bb9b");
    private static final UUID TOKEN = UUID.fromString("2218fb70-788a-4fca-b851-6069debbb5dc");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final JobRepository repository = mock(JobRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final JobService jobs = new JobService(repository, outbox, mock(AuditLog.class), 12, 30);

    @Test
    void schedulesAJobAndWritesTheMatchingOutboxGeneration() {
        var job = job(State.SCHEDULED, 0, 2, null, NOW);
        UUID outboxId = UUID.randomUUID();
        when(repository.nextDue(State.SCHEDULED)).thenReturn(Optional.of(job));
        when(repository.now()).thenReturn(NOW);
        when(outbox.append(new JobDispatch(ID, 3))).thenReturn(outboxId);

        assertThat(jobs.dispatchDue()).isTrue();

        verify(repository).markQueued(job, 3, outboxId, NOW.plusSeconds(30));
    }

    @Test
    void oldDispatchGenerationCannotClaimARequeuedJob() {
        when(repository.find(ID, true)).thenReturn(Optional.of(job(State.QUEUED, 0, 2, null, NOW)));

        assertThat(jobs.claim(ID, new ClaimRequest(1, TOKEN, "worker-a"))).isEmpty();

        verify(repository, never()).markRunning(any(), eq(1), eq(TOKEN), eq("worker-a"), any());
    }

    @Test
    void repeatingAClaimWithTheSameTokenReturnsTheExistingAttempt() {
        when(repository.find(ID, true)).thenReturn(Optional.of(job(State.RUNNING, 1, 2, TOKEN, NOW.plusSeconds(10))));
        when(repository.now()).thenReturn(NOW);

        assertThat(jobs.claim(ID, new ClaimRequest(2, TOKEN, "worker-a"))).hasValueSatisfying(
                claim -> assertThat(claim.attempt()).isEqualTo(1));

        verify(repository, never()).markRunning(any(), eq(2), eq(TOKEN), eq("worker-a"), any());
    }

    @Test
    void expiredOwnerCannotPublishOrExtendItsLease() {
        when(repository.find(ID, true)).thenReturn(Optional.of(job(State.RUNNING, 1, 2, TOKEN, NOW)));
        when(repository.now()).thenReturn(NOW);

        assertThat(jobs.renew(ID, TOKEN)).isFalse();
        assertThat(jobs.complete(ID, new Completion(TOKEN, true, "old report", null))).isFalse();

        verify(repository, never()).saveResult(any(), any());
        verify(repository, never()).extendLease(any(), any());
    }

    @Test
    void expiredFirstAttemptSchedulesRetryWithBackoff() {
        var job = job(State.RUNNING, 1, 2, TOKEN, NOW);
        when(repository.nextDue(State.RUNNING)).thenReturn(Optional.of(job));
        when(repository.now()).thenReturn(NOW);

        assertThat(jobs.recoverExpired()).isTrue();

        verify(repository).settleAttempt(job, Outcome.LEASE_EXPIRED, "Worker lease expired");
        verify(repository).moveTo(job, State.SCHEDULED, NOW.plusSeconds(2));
    }

    @Test
    void expiredFinalAttemptMarksJobFailed() {
        var job = job(State.RUNNING, 3, 3, TOKEN, NOW);
        when(repository.nextDue(State.RUNNING)).thenReturn(Optional.of(job));
        when(repository.now()).thenReturn(NOW);

        assertThat(jobs.recoverExpired()).isTrue();

        verify(repository).moveTo(job, State.FAILED, null);
    }

    @Test
    void acknowledgementOfAlreadyCommittedResultDoesNotInsertAgain() {
        when(repository.find(ID, true)).thenReturn(Optional.of(job(State.SUCCEEDED, 1, 2, null, null)));
        when(repository.now()).thenReturn(NOW);
        when(repository.outcome(ID, TOKEN)).thenReturn(Optional.of(Outcome.SUCCEEDED));

        assertThat(jobs.complete(ID, new Completion(TOKEN, true, "retry", null))).isTrue();

        verify(repository, never()).saveResult(any(), any());
    }

    @Test
    void exhaustedTenantQuotaReschedulesWithoutConsumingAnAttempt() {
        var job = job(State.QUEUED, 0, 2, null, NOW);
        when(repository.find(ID, true)).thenReturn(Optional.of(job));
        when(repository.now()).thenReturn(NOW);
        when(repository.lockTenantAndReadLimit(job.tenantId())).thenReturn(2);
        when(repository.activeExecutions(job.tenantId())).thenReturn(2);

        assertThat(jobs.claim(ID, new ClaimRequest(2, TOKEN, "worker-a"))).isEmpty();

        verify(repository).moveTo(job, State.SCHEDULED, NOW.plusSeconds(3));
        verify(repository, never()).markRunning(any(), eq(1), eq(TOKEN), eq("worker-a"), any());
    }

    private Job job(State state, int attempt, int generation, UUID token, Instant next) {
        return new Job(ID, UUID.fromString("00000000-0000-0000-0000-000000000001"), null, null, null, "hash", state, ReportType.DAILY_TOTALS, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 3), NOW, generation, attempt, 3, token, next, NOW, NOW);
    }
}
