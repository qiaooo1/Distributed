package io.joblab.scheduler.ticket;

import io.joblab.scheduler.security.TenantActor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.joblab.scheduler.ticket.TicketModels.*;

@Service
public class TicketService {

    private static final Duration HOLD_TIME = Duration.ofMinutes(5);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private final TicketStore store;

    TicketService(TicketStore store) {
        this.store = store;
    }

    public EventListing events(TenantActor actor) {
        var now = store.now();
        var events = store.events(actor.tenantId()).stream()
                .map(event -> event.view(now, store.offers(actor.tenantId(), event.id())))
                .toList();
        return new EventListing(now, events);
    }

    public ReservationListing mine(TenantActor actor) {
        return new ReservationListing(store.now(), store.mine(actor));
    }

    public ReservationResponse reservation(TenantActor actor, UUID id) {
        var reservation = store.reservation(actor, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket reservation not found"));
        return new ReservationResponse(store.now(), reservation);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse reserve(TenantActor actor, UUID eventId, UUID offerId, String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }

        var previous = store.byKey(actor, key);
        if (previous.isPresent()) {
            return replay(previous.get(), eventId, offerId);
        }

        var event = store.event(actor.tenantId(), eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Concert not found"));
        // Reserve and expire take the offer lock before changing the reservation or inventory.
        var offer = store.lockOffer(actor.tenantId(), eventId, offerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket tier not found"));

        previous = store.byKey(actor, key);
        if (previous.isPresent()) {
            return replay(previous.get(), eventId, offerId);
        }
        var now = store.now();
        if (event.at(now) != SaleState.ON_SALE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This concert is outside its ticket sale window");
        }
        if (store.hasActive(actor, eventId)) {
            throw alreadyReserved();
        }
        if (offer.available() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket tier is sold out");
        }

        UUID id = UUID.randomUUID();
        try {
            store.insert(actor, id, eventId, offer, key, now.plus(HOLD_TIME));
        } catch (DuplicateKeyException ex) {
            // A competing request for a different tier can win either unique constraint.
            var concurrent = store.byKey(actor, key);
            if (concurrent.isPresent()) {
                return replay(concurrent.get(), eventId, offerId);
            }
            if (store.hasActive(actor, eventId)) {
                throw alreadyReserved();
            }
            throw ex;
        }
        if (!store.decrement(actor.tenantId(), eventId, offerId)) {
            throw new IllegalStateException("Ticket stock changed while its offer was locked");
        }
        return reservation(actor, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void releaseExpired(DueReservation ticket) {
        if (store.lockOffer(ticket.tenantId(), ticket.eventId(), ticket.offerId()).isEmpty()) {
            throw new IllegalStateException("Reservation points to a missing offer");
        }
        if (store.expire(ticket) && !store.increment(ticket)) {
            throw new IllegalStateException("Releasing this reservation would exceed offer capacity");
        }
    }

    private ReservationResponse replay(ReservationView previous, UUID eventId, UUID offerId) {
        if (!previous.eventId().equals(eventId) || !previous.offerId().equals(offerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was used for a different offer");
        }
        return new ReservationResponse(store.now(), previous);
    }

    private static ResponseStatusException alreadyReserved() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "You already have an active reservation for this concert");
    }
}
