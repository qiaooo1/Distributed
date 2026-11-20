package io.joblab.scheduler.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static io.joblab.messaging.JobProtocol.*;

@Component
public class WorkerAccess {

    private final byte[] key;

    WorkerAccess(@Value("${joblab.internal-key}") String key,
                 @Value("${joblab.security.demo-accounts}") boolean demo) {
        if (key.isBlank() || (!demo && key.length() < 32)) {
            throw new IllegalArgumentException("INTERNAL_KEY must have at least 32 characters outside demo mode");
        }
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    public void authorize(HttpHeaders headers) {
        String actual = headers.getFirst(INTERNAL_KEY_HEADER);
        if (actual == null || !MessageDigest.isEqual(key,actual.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid worker credentials");
        }
        if (!WORKER_PROTOCOL_VERSION.equals(headers.getFirst(WORKER_PROTOCOL_HEADER))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Tenant-scoped Worker protocol required");
        }
    }
}
