package io.joblab.scheduler.ticket;

import io.joblab.scheduler.security.TenantActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static io.joblab.scheduler.ticket.TicketModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TicketServiceTest {

    private final TicketStore store = mock(TicketStore.class);
    private final TicketService service = new TicketService(store);
    private final TenantActor actor = new TenantActor(UUID.randomUUID(), UUID.randomUUID());
    private final UUID eventId = UUID.randomUUID();
    private final UUID offerId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-19T12:00:00Z");

    @BeforeEach
    void setup() {
        when(store.now()).thenReturn(now);
    }

    @Test
    void reservationUsesDatabaseClockAndAFiveMinuteLock() {
        var offer = new OfferView(offerId, "早鸟票", 19900, 1);
        when(store.event(actor.tenantId(), eventId)).thenReturn(Optional.of(onSale()));
        when(store.lockOffer(actor.tenantId(), eventId, offerId)).thenReturn(Optional.of(offer));
        when(store.decrement(actor.tenantId(), eventId, offerId)).thenReturn(true);
        when(store.reservation(eq(actor), any(UUID.class))).thenReturn(Optional.of(reservation()));

        assertThat(service.reserve(actor, eventId, offerId, "request-a").reservation().expiresAt())
                .isEqualTo(now.plusSeconds(300));

        var ordered = inOrder(store);
        ordered.verify(store).lockOffer(actor.tenantId(), eventId, offerId);
        ordered.verify(store).insert(eq(actor), any(UUID.class), eq(eventId), eq(offer), eq("request-a"), eq(now.plusSeconds(300)));
        ordered.verify(store).decrement(actor.tenantId(), eventId, offerId);
    }

    @Test
    void identicalRetryResolvesToTheOriginalReservationWithoutNewInventoryChanges() {
        when(store.byKey(actor, "request-a")).thenReturn(Optional.of(reservation()));

        assertThat(service.reserve(actor, eventId, offerId, "request-a").reservation().id()).isEqualTo(reservationId);

        verify(store, never()).lockOffer(any(), any(), any());
        verify(store, never()).decrement(any(), any(), any());
    }

    @Test
    void soldOutOfferCannotCreateAReservation() {
        when(store.event(actor.tenantId(), eventId)).thenReturn(Optional.of(onSale()));
        when(store.lockOffer(actor.tenantId(), eventId, offerId))
                .thenReturn(Optional.of(new OfferView(offerId, "早鸟票", 19900, 0)));

        assertThatThrownBy(() -> service.reserve(actor, eventId, offerId, "request-a"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(store, never()).insert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void expirationReleasesInventoryOnlyForTheWinningTransition() {
        var due = new DueReservation(reservationId, actor.tenantId(), eventId, offerId);
        when(store.lockOffer(actor.tenantId(), eventId, offerId))
                .thenReturn(Optional.of(new OfferView(offerId, "早鸟票", 19900, 0)));
        when(store.expire(due)).thenReturn(true, false);
        when(store.increment(due)).thenReturn(true);

        service.releaseExpired(due);
        service.releaseExpired(due);

        verify(store, times(1)).increment(due);
    }

    private Event onSale() {
        return new Event(eventId, "演出", "主馆", "演示", now.plusSeconds(10000),
                now.minusSeconds(10), now.plusSeconds(5000));
    }

    private ReservationView reservation() {
        return new ReservationView(reservationId, eventId, offerId, "演出", "早鸟票", 19900,
                ReservationState.LOCKED, now.plusSeconds(300), now);
    }
}
