package io.joblab.scheduler.ticket;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentModels {

    private PaymentModels() {
    }

    public record PayRequest(UUID merchandiseId, UUID pickupPointId) {
    }

    public record Merchandise(UUID id, String label, int priceFen) {
    }

    public record Pickup(UUID id, String label) {
    }

    public record Receipt(UUID id, UUID reservationId, UUID merchandiseId, UUID pickupPointId,
                          String merchandiseLabel, String pickupLabel, int ticketPriceFen,
                          int merchandisePriceFen, long totalFen, Instant paidAt) {
    }

    public record Checkout(Instant serverTime, TicketModels.ReservationView reservation,
                           List<Merchandise> merchandise, List<Pickup> pickupPoints, Receipt payment) {
    }
}
