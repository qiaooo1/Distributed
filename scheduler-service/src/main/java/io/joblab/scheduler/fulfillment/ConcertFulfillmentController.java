package io.joblab.scheduler.fulfillment;

import io.joblab.messaging.ConcertDispatchProtocol.Lease;
import io.joblab.messaging.ConcertDispatchProtocol.WarehouseReceipt;
import io.joblab.scheduler.security.TenantActor;
import io.joblab.scheduler.security.WorkerAccess;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
class ConcertFulfillmentController {

    private final ConcertFulfillmentService dispatch;
    private final WorkerAccess access;
    private final ApplicationAvailability availability;

    ConcertFulfillmentController(ConcertFulfillmentService dispatch, WorkerAccess access, ApplicationAvailability availability) {
        this.dispatch = dispatch;
        this.access = access;
        this.availability = availability;
    }

    @GetMapping("/api/operations/concert-fulfillment/orders")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public List<ConcertFulfillmentService.OrderSummary> orders(@AuthenticationPrincipal Jwt jwt) {
        return dispatch.list(TenantActor.from(jwt).tenantId());
    }

    @GetMapping("/api/operations/concert-fulfillment/orders/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ConcertFulfillmentService.OrderDetails details(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return dispatch.details(TenantActor.from(jwt).tenantId(),id);
    }

    @PostMapping("/internal/concert-dispatch/claim")
    ResponseEntity<Lease> claim(@RequestHeader HttpHeaders headers) {
        access.authorize(headers);
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            return ResponseEntity.noContent().build();
        }
        return dispatch.claim().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/internal/concert-dispatch/receipts/{attempt}")
    ResponseEntity<WarehouseReceipt> receipt(@RequestHeader HttpHeaders headers, @PathVariable UUID attempt) {
        access.authorize(headers);
        return dispatch.receipt(attempt).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/internal/concert-dispatch/receipts")
    WarehouseReceipt send(@RequestHeader HttpHeaders headers, @RequestBody Lease lease) {
        access.authorize(headers);
        return dispatch.submitToWarehouse(lease);
    }

    @PostMapping("/internal/concert-dispatch/complete")
    ResponseEntity<Void> complete(@RequestHeader HttpHeaders headers, @RequestBody Lease lease) {
        access.authorize(headers);
        dispatch.complete(lease);
        return ResponseEntity.noContent().build();
    }
}
