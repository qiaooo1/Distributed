package io.joblab.scheduler;

import io.joblab.messaging.JobProtocol.Claim;
import io.joblab.messaging.JobProtocol.ClaimRequest;
import io.joblab.messaging.JobProtocol.Completion;
import io.joblab.messaging.JobProtocol.Heartbeat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import static io.joblab.messaging.JobProtocol.INTERNAL_KEY_HEADER;
import static io.joblab.messaging.JobProtocol.WORKER_PROTOCOL_HEADER;
import static io.joblab.messaging.JobProtocol.WORKER_PROTOCOL_VERSION;

@RestController
@RequestMapping("/internal/jobs")
public class WorkerController {

    private final JobService jobs;
    private final byte[] internalKey;

    public WorkerController(JobService jobs, @Value("${joblab.internal-key}") String internalKey,
                            @Value("${joblab.security.demo-accounts}") boolean demo) {
        if (internalKey.isBlank() || (!demo && internalKey.length() < 32)) {
            throw new IllegalArgumentException("INTERNAL_KEY must have at least 32 characters outside demo mode");
        }
        this.jobs = jobs;
        this.internalKey = internalKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<Claim> claim(@PathVariable UUID id, @RequestHeader HttpHeaders headers,
                                       @RequestBody ClaimRequest input) {
        authorize(headers);
        return jobs.claim(id, input).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/heartbeat")
    public ResponseEntity<Void> renew(@PathVariable UUID id, @RequestHeader HttpHeaders headers,
                                      @RequestBody Heartbeat input) {
        authorize(headers);
        return leaseResponse(jobs.renew(id, input.token()));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Void> complete(@PathVariable UUID id, @RequestHeader HttpHeaders headers,
                                         @RequestBody Completion input) {
        authorize(headers);
        return leaseResponse(jobs.complete(id, input));
    }

    private void authorize(HttpHeaders headers) {
        String key = headers.getFirst(INTERNAL_KEY_HEADER);
        if (key == null || !MessageDigest.isEqual(internalKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid worker credentials");
        }
        if (!WORKER_PROTOCOL_VERSION.equals(headers.getFirst(WORKER_PROTOCOL_HEADER))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Tenant-scoped Worker protocol required");
        }
    }

    private ResponseEntity<Void> leaseResponse(boolean accepted) {
        return accepted ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
