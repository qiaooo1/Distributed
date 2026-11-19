package io.joblab.scheduler;

import io.joblab.messaging.EventJson;
import io.joblab.messaging.JobDispatch;
import io.joblab.messaging.JobProtocol.Claim;
import io.joblab.messaging.JobProtocol.ClaimRequest;
import io.joblab.messaging.JobProtocol.Completion;
import io.joblab.messaging.JobProtocol.ReportType;
import io.joblab.messaging.OutboxRelay;
import io.joblab.messaging.OutboxWriter;
import io.joblab.scheduler.JobModels.Job;
import io.joblab.scheduler.JobModels.JobView;
import io.joblab.scheduler.JobModels.JobPage;
import io.joblab.scheduler.security.TenantActor;
import io.joblab.scheduler.JobModels.Outcome;
import io.joblab.scheduler.JobModels.State;
import io.joblab.scheduler.JobModels.Submission;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {
    private static final int DEFAULT_ATTEMPTS = 3;

    private final JobRepository jobs;
    private final OutboxWriter outbox;
    private final AuditLog audit;
    private final int leaseSeconds;
    private final int queueTimeoutSeconds;

    public JobService(JobRepository jobs, OutboxWriter outbox, AuditLog audit,
                      @Value("${joblab.lease-seconds}") int leaseSeconds,
                      @Value("${joblab.queue-timeout-seconds}") int queueTimeoutSeconds) {
        if (leaseSeconds < 6 || queueTimeoutSeconds < 5) {
            throw new IllegalArgumentException("Lease must be at least 6s and queue timeout at least 5s");
        }
        this.jobs = jobs;
        this.outbox = outbox;
        this.audit = audit;
        this.leaseSeconds = leaseSeconds;
        this.queueTimeoutSeconds = queueTimeoutSeconds;
    }

    @Transactional
    public JobView submit(TenantActor actor, String key, Submission input) {
        validateIdempotencyKey(key);
        long days = ChronoUnit.DAYS.between(input.fromDate(), input.toDate());
        if (days < 0 || days > 30 || input.fromDate().getYear() < 1000 || input.toDate().getYear() > 9999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date range must span 1 to 31 days");
        }
        Instant now = jobs.now();
        Instant requestedAt = input.runAt();
        if (requestedAt != null && requestedAt.isAfter(now.plus(30, ChronoUnit.DAYS))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runAt must be within the next 30 days");
        }
        Instant runAt = requestedAt != null && requestedAt.isAfter(now) ? requestedAt : now;
        int maxAttempts = input.maxAttempts() == null ? DEFAULT_ATTEMPTS : input.maxAttempts();
        String hash = fingerprint(input, maxAttempts);
        var result = jobs.insertOrGet(actor.tenantId(), key, hash, input, runAt, maxAttempts, null, null, null);
        Job job = result.job();
        if (!job.requestHash().equals(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was used with a different request");
        }
        if (result.created()) {
            audit.record(actor, "JOB_SUBMITTED", job.id(), job.reportType().name());
        }
        return view(job);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createOccurrence(UUID tenant, UUID schedule, ReportType type, LocalDate from, LocalDate to,
                                 int attempts, Instant fireAt, Instant now) {
        String key = "schedule:" + schedule + ":" + fireAt.getEpochSecond();
        var input = new Submission(type, from, to, fireAt, attempts);
        var result = jobs.insertOrGet(tenant, key, fingerprint(input, attempts), input, now, attempts, schedule, fireAt, null);
        if (result.created()) {
            audit.system(tenant, "SCHEDULE_FIRED", result.job().id(), "schedule=" + schedule);
        }
        return result.job().id();
    }

    @Transactional
    public JobView replay(TenantActor actor, UUID id, String key) {
        validateIdempotencyKey(key);
        Job original = getTenantJob(actor, id, false);
        if (original.state() != State.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a failed job can be replayed");
        }
        Instant now = jobs.now();
        var input = new Submission(original.reportType(), original.fromDate(), original.toDate(), null, original.maxAttempts());
        String hash = sha256("replay|" + id);
        var result = jobs.insertOrGet(actor.tenantId(), key, hash, input, now, original.maxAttempts(), null, null, id);
        if (!result.job().requestHash().equals(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was used with a different request");
        }
        if (result.created()) {
            audit.record(actor, "JOB_REPLAYED", result.job().id(), "original=" + id);
        }
        return view(result.job());
    }

    @Transactional(readOnly = true)
    public JobView get(TenantActor actor, UUID id) {
        return view(getTenantJob(actor, id, false));
    }

    @Transactional(readOnly = true)
    public JobPage list(TenantActor actor, State state, UUID cursor, int limit) {
        Job anchor = cursor == null ? null : getTenantJob(actor, cursor, false);
        var rows = jobs.page(actor.tenantId(), state, anchor, limit + 1);
        var page = rows.stream().limit(limit).map(Job::summary).toList();
        return new JobPage(page, rows.size() > limit ? page.get(page.size() - 1).id() : null);
    }

    @Transactional(readOnly = true)
    public Map<State, Long> overview(TenantActor actor) {
        return jobs.counts(actor.tenantId());
    }

    @Transactional(readOnly = true)
    public String result(TenantActor actor, UUID id) {
        if (getTenantJob(actor, id, false).state() != State.SUCCEEDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Report is not ready");
        }
        return jobs.result(id);
    }

    @Transactional
    public JobView cancel(TenantActor actor, UUID id) {
        Job job = getTenantJob(actor, id, true);
        switch (job.state()) {
            case SUCCEEDED, FAILED -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Job already finished");
            case CANCELLED -> {
                return view(job);
            }
            case RUNNING -> jobs.settleAttempt(job, Outcome.CANCELLED, null);
            default -> {
                // A job cancelled before a claim has no attempt to close.
            }
        }
        jobs.moveTo(job, State.CANCELLED, null);
        audit.record(actor, "JOB_CANCELLED", id, null);
        return view(getTenantJob(actor, id, true));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Claim> claim(UUID id, ClaimRequest input) {
        if (input.token() == null || input.generation() < 1 || input.workerId() == null
                || !input.workerId().matches("[A-Za-z0-9_.:-]{1,100}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid claim");
        }
        var entry = jobs.find(id, true);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        Job job = entry.get();
        if (job.generation() != input.generation()) {
            return Optional.empty();
        }
        Instant now = jobs.now();
        if (job.owns(input.token(), now)) {
            return Optional.of(claimFor(job, job.attemptCount()));
        }
        if (job.state() != State.QUEUED) {
            return Optional.empty();
        }
        int quota = jobs.lockTenantAndReadLimit(job.tenantId());
        now = jobs.now();
        if (jobs.activeExecutions(job.tenantId()) >= quota) {
            jobs.moveTo(job, State.SCHEDULED, now.plusSeconds(3));
            return Optional.empty();
        }
        int attempt = job.attemptCount() + 1;
        jobs.markRunning(job, attempt, input.token(), input.workerId(), now.plusSeconds(leaseSeconds));
        return Optional.of(claimFor(job, attempt));
    }

    @Transactional
    public boolean renew(UUID id, UUID token) {
        Job job = getJob(id, true);
        Instant now = jobs.now();
        if (!job.owns(token, now)) {
            return false;
        }
        jobs.extendLease(job, now.plusSeconds(leaseSeconds));
        return true;
    }

    @Transactional
    public boolean complete(UUID id, Completion input) {
        if (input.token() == null || (input.success() && (input.csv() == null || input.csv().length() > 524_288))
                || (!input.success() && (input.error() == null || input.error().isBlank() || input.error().length() > 256))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid completion");
        }
        Job job = getJob(id, true);
        Outcome outcome = input.success() ? Outcome.SUCCEEDED : Outcome.TASK_FAILED;
        Instant now = jobs.now();
        if (!job.owns(input.token(), now)) {
            return jobs.outcome(id, input.token()).filter(outcome::equals).isPresent();
        }

        jobs.settleAttempt(job, outcome, input.success() ? null : input.error());
        if (input.success()) {
            jobs.saveResult(id, input.csv());
            jobs.moveTo(job, State.SUCCEEDED, null);
        } else {
            retryOrFail(job, now);
        }
        return true;
    }

    @Transactional
    public boolean dispatchDue() {
        return jobs.nextDue(State.SCHEDULED).map(this::enqueue).orElse(false);
    }

    @Transactional
    public boolean redispatchUnclaimed() {
        return jobs.nextUnclaimed().map(this::enqueue).orElse(false);
    }

    @Transactional
    public boolean recoverExpired() {
        var expired = jobs.nextDue(State.RUNNING);
        if (expired.isEmpty()) {
            return false;
        }
        Job job = expired.get();
        jobs.settleAttempt(job, Outcome.LEASE_EXPIRED, "Worker lease expired");
        retryOrFail(job, jobs.now());
        return true;
    }

    private boolean enqueue(Job job) {
        int generation = job.generation() + 1;
        UUID eventId = outbox.append(new JobDispatch(job.id(), generation));
        jobs.markQueued(job, generation, eventId, jobs.now().plusSeconds(queueTimeoutSeconds));
        return true;
    }

    private void retryOrFail(Job job, Instant now) {
        if (job.attemptCount() >= job.maxAttempts()) {
            jobs.moveTo(job, State.FAILED, null);
            return;
        }
        long backoffSeconds = 1L << job.attemptCount();
        jobs.moveTo(job, State.SCHEDULED, now.plusSeconds(backoffSeconds));
    }

    private Claim claimFor(Job job, int attempt) {
        return new Claim(job.tenantId(), attempt, leaseSeconds, job.reportType(), job.fromDate(), job.toDate());
    }

    private Job getJob(UUID id, boolean lock) {
        return jobs.find(id, lock).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    private Job getTenantJob(TenantActor actor, UUID id, boolean lock) {
        return jobs.findForTenant(actor.tenantId(), id, lock)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    private JobView view(Job job) {
        return job.view(jobs.attempts(job.id()));
    }

    private static String fingerprint(Submission input, int maxAttempts) {
        String canonical = String.join("|", input.reportType().name(), input.fromDate().toString(),
                input.toDate().toString(), String.valueOf(input.runAt()), Integer.toString(maxAttempts));
        return sha256(canonical);
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,64}") || key.startsWith("schedule:")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

@Configuration(proxyBeanMethods = false)
class OutboxConfiguration {

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbc, EventJson json) {
        return new OutboxWriter(jdbc, json);
    }

    @Bean
    OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager transactions, RabbitTemplate rabbit) {
        return new OutboxRelay(jdbc, transactions, rabbit);
    }
}
