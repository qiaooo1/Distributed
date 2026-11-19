package io.joblab.worker;

import io.joblab.messaging.EventJson;
import io.joblab.messaging.JobDispatch;
import io.joblab.messaging.JobProtocol.Claim;
import io.joblab.messaging.JobProtocol.ClaimRequest;
import io.joblab.messaging.JobProtocol.Completion;
import io.joblab.messaging.MessagingConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final EventJson json;
    private final SchedulerClient scheduler;
    private final ReportGenerator reports;
    private final ScheduledExecutorService heartbeats;
    private final String workerId;
    private final long minRuntimeMs;

    public JobWorker(EventJson json, SchedulerClient scheduler, ReportGenerator reports,
                     ScheduledExecutorService heartbeats, @Value("${joblab.report-min-runtime-ms}") long minRuntimeMs,
                     @Value("${WORKER_ID:${HOSTNAME:${random.uuid}}}") String workerId) {
        if (minRuntimeMs < 0 || minRuntimeMs > 120_000 || !workerId.matches("[A-Za-z0-9_.:-]{1,100}")) {
            throw new IllegalArgumentException("Invalid worker ID or demo runtime");
        }
        this.json = json;
        this.scheduler = scheduler;
        this.reports = reports;
        this.heartbeats = heartbeats;
        this.minRuntimeMs = minRuntimeMs;
        this.workerId = workerId;
    }

    @RabbitListener(queues = MessagingConfiguration.QUEUE)
    public void execute(String body) throws InterruptedException {
        JobDispatch event = json.read(body);
        UUID id = event.jobId();
        UUID token = UUID.randomUUID();
        Claim job = scheduler.claim(id, new ClaimRequest(event.generation(), token, workerId));
        if (job == null) {
            log.debug("Skipping duplicate or stale dispatch {} generation={}", id, event.generation());
            return;
        }

        log.info("Worker {} claimed job {} attempt={}", workerId, id, job.attempt());
        var lostLease = new AtomicBoolean();
        long period = Math.max(1, job.leaseSeconds() / 3);
        var heartbeat = heartbeats.scheduleWithFixedDelay(() -> heartbeat(id, token, lostLease),
                period, period, TimeUnit.SECONDS);
        try {
            waitForDemo(lostLease);
            if (lostLease.get()) {
                return;
            }
            Completion completion = generate(job, token);
            if (!lostLease.get() && scheduler.complete(id, completion)) {
                log.info("Worker {} finished job {} attempt={} success={}", workerId, id,
                        job.attempt(), completion.success());
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    private Completion generate(Claim job, UUID token) {
        try {
            return new Completion(token, true, reports.generate(job), null);
        } catch (DataAccessException ex) {
            log.warn("Report query failed", ex);
            return new Completion(token, false, null, "Report database error");
        } catch (IllegalArgumentException ex) {
            return new Completion(token, false, null, ex.getMessage());
        }
    }

    private void heartbeat(UUID id, UUID token, AtomicBoolean lostLease) {
        if (lostLease.get()) {
            return;
        }
        try {
            if (!scheduler.renew(id, token)) {
                lostLease.set(true);
                log.info("Worker {} lost lease for job {}", workerId, id);
            }
        } catch (RestClientException ex) {
            log.warn("Unable to renew lease for job {}: {}", id, ex.toString());
        }
    }

    private void waitForDemo(AtomicBoolean lostLease) throws InterruptedException {
        long remaining = minRuntimeMs;
        while (remaining > 0 && !lostLease.get()) {
            long sleepMs = Math.min(remaining, 200);
            Thread.sleep(sleepMs);
            remaining -= sleepMs;
        }
    }
}
