package io.joblab.scheduler;

import io.joblab.scheduler.JobModels.JobView;
import io.joblab.scheduler.JobModels.JobPage;
import io.joblab.scheduler.JobModels.State;
import io.joblab.scheduler.security.TenantActor;
import io.joblab.scheduler.JobModels.Submission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);
    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<JobView> submit(@AuthenticationPrincipal Jwt jwt,
                                          @RequestHeader("Idempotency-Key") String key,
                                          @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                          @Valid @RequestBody Submission input) {
        JobView job = jobs.submit(TenantActor.from(jwt), key, input);
        log.info("Accepted job {} requestId={}", job.id(), requestId);
        return ResponseEntity.accepted().location(URI.create("/api/jobs/" + job.id())).body(job);
    }

    @GetMapping
    public JobPage list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) State state,
                        @RequestParam(required = false) UUID cursor,
                        @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        return jobs.list(TenantActor.from(jwt), state, cursor, limit);
    }

    @GetMapping("/{id}")
    public JobView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return jobs.get(TenantActor.from(jwt), id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public JobView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return jobs.cancel(TenantActor.from(jwt), id);
    }

    @PostMapping("/{id}/replay")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<JobView> replay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @RequestHeader("Idempotency-Key") String key) {
        JobView job = jobs.replay(TenantActor.from(jwt), id, key);
        return ResponseEntity.accepted().location(URI.create("/api/jobs/" + job.id())).body(job);
    }

    @GetMapping("/{id}/result")
    public ResponseEntity<byte[]> result(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        byte[] csv = jobs.result(TenantActor.from(jwt), id).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("report-" + id + ".csv").build().toString())
                .body(csv);
    }
}
