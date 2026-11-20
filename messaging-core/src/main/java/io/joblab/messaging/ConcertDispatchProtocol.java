package io.joblab.messaging;

import java.util.UUID;

public final class ConcertDispatchProtocol {

    private ConcertDispatchProtocol() {
    }

    public record Lease(UUID orderId, UUID attemptId, UUID token) {
    }

    public record WarehouseReceipt(UUID attemptId, boolean accepted, String reason) {
    }
}
