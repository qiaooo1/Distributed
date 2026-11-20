package io.joblab.scheduler;

import io.joblab.messaging.JobProtocol.Claim;
import io.joblab.messaging.JobProtocol.ClaimRequest;
import io.joblab.messaging.JobProtocol.Completion;
import io.joblab.messaging.JobProtocol.Heartbeat;
import io.joblab.scheduler.security.WorkerAccess;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal/jobs")
public class WorkerController {

    private final JobService jobs;
    private final WorkerAccess access;

    public WorkerController(JobService jobs, WorkerAccess access) {
        this.jobs = jobs;
        this.access = access;
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<Claim> claim(@PathVariable UUID id, @RequestHeader HttpHeaders headers,
                                       @RequestBody ClaimRequest input) {
        access.authorize(headers);
        return jobs.claim(id, input).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/heartbeat")
    public ResponseEntity<Void> renew(@PathVariable UUID id, @RequestHeader HttpHeaders headers,
                                      @RequestBody Heartbeat input) {
        access.authorize(headers);
        return leaseResponse(jobs.renew(id, input.token()));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Void> complete(@PathVariable UUID id, @RequestHeader HttpHeaders headers,
                                         @RequestBody Completion input) {
        access.authorize(headers);
        return leaseResponse(jobs.complete(id, input));
    }

    private ResponseEntity<Void> leaseResponse(boolean accepted) {
        return accepted ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
