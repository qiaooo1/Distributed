package io.joblab.messaging;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class JobProtocol {

    public static final String INTERNAL_KEY_HEADER = "X-JobLab-Internal-Key";
    public static final String WORKER_PROTOCOL_HEADER = "X-JobLab-Worker-Protocol";
    public static final String WORKER_PROTOCOL_VERSION = "2";

    private JobProtocol() {
    }

    public enum ReportType { DAILY_TOTALS, SALES_DETAIL }

    public record ClaimRequest(int generation, UUID token, String workerId) {
    }

    public record Claim(UUID tenantId, int attempt, int leaseSeconds, ReportType type, LocalDate fromDate, LocalDate toDate) {
        public Claim {
            Objects.requireNonNull(tenantId, "A tenant-scoped worker requires a tenant ID");
        }
    }

    public record Heartbeat(UUID token) {
    }

    public record Completion(UUID token, boolean success, String csv, String error) {
    }
}
