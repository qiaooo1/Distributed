package io.joblab.scheduler.ticket;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TicketModels {

    private TicketModels() {
    }

    public enum SaleState { UPCOMING, ON_SALE, CLOSED }

    public enum ReservationState { LOCKED, EXPIRED, PAID }

    public record ReserveRequest(@NotNull UUID offerId) {
    }

    public record OfferView(UUID id, String label, int priceFen, int available) {
    }

    public record EventView(UUID id, String title, String venue, String description,
                            Instant startsAt, Instant saleStartsAt, Instant saleEndsAt,
                            SaleState saleState, List<OfferView> offers) {
    }

    public record EventListing(Instant serverTime, List<EventView> events) {
    }

    public record ReservationView(UUID id, UUID eventId, UUID offerId, String eventTitle, String offerLabel,
                                  int priceFen, ReservationState state, Instant expiresAt, Instant createdAt) {
    }

    public record ReservationResponse(Instant serverTime, ReservationView reservation) {
    }

    public record ReservationListing(Instant serverTime, List<ReservationView> items) {
    }

    record Event(UUID id, String title, String venue, String description,
                 Instant startsAt, Instant saleStartsAt, Instant saleEndsAt) {
        SaleState at(Instant now) {
            if (now.isBefore(saleStartsAt)) return SaleState.UPCOMING;
            return now.isBefore(saleEndsAt) ? SaleState.ON_SALE : SaleState.CLOSED;
        }

        EventView view(Instant now, List<OfferView> offers) {
            return new EventView(id, title, venue, description, startsAt, saleStartsAt, saleEndsAt, at(now), offers);
        }
    }

    record DueReservation(UUID id, UUID tenantId, UUID eventId, UUID offerId) {
    }
}
