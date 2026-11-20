package io.joblab.scheduler.ticket;

import io.joblab.scheduler.security.TenantActor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

import static io.joblab.scheduler.ticket.TicketModels.*;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService service;

    public TicketController(TicketService service) {
        this.service = service;
    }

    @GetMapping("/events")
    EventListing events(@AuthenticationPrincipal Jwt jwt) {
        return service.events(TenantActor.from(jwt));
    }

    @PostMapping("/events/{eventId}/reservations")
    ResponseEntity<ReservationResponse> reserve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId,
                                                 @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody ReserveRequest input) {
        var result = service.reserve(TenantActor.from(jwt), eventId, input.offerId(), key);
        return ResponseEntity.created(URI.create("/api/tickets/reservations/" + result.reservation().id())).body(result);
    }

    @GetMapping("/reservations")
    ReservationListing mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(TenantActor.from(jwt));
    }

    @GetMapping("/reservations/{id}")
    ReservationResponse reservation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.reservation(TenantActor.from(jwt), id);
    }
}
