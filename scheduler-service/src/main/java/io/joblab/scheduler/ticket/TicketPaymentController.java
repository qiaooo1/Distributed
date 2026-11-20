package io.joblab.scheduler.ticket;

import io.joblab.scheduler.security.TenantActor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/tickets/reservations/{id}")
class TicketPaymentController {

    private final TicketPaymentService payments;

    TicketPaymentController(TicketPaymentService payments) {
        this.payments = payments;
    }

    @GetMapping("/checkout")
    PaymentModels.Checkout checkout(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.checkout(TenantActor.from(jwt), id);
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentModels.Receipt pay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                              @RequestHeader("Idempotency-Key") String key,
                              @RequestBody PaymentModels.PayRequest request) {
        return payments.pay(TenantActor.from(jwt), id, key, request);
    }
}
