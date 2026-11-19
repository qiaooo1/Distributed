package io.joblab.messaging;

import java.util.Objects;
import java.util.UUID;

public record JobDispatch(UUID jobId, int generation) {
    public JobDispatch {
        Objects.requireNonNull(jobId, "jobId");
        if (generation < 1) {
            throw new IllegalArgumentException("Generation must be positive");
        }
    }
}
