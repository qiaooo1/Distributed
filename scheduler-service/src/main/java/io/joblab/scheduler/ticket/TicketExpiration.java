package io.joblab.scheduler.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TicketExpiration {

    private static final Logger log = LoggerFactory.getLogger(TicketExpiration.class);

    private final TicketStore store;
    private final TicketService service;

    TicketExpiration(TicketStore store, TicketService service) {
        this.store = store;
        this.service = service;
    }

    @Scheduled(fixedDelay = 1000)
    void release() {
        // This scan intentionally holds no reservation lock while acquiring offer locks.
        for (var ticket : store.due()) {
            try {
                service.releaseExpired(ticket);
            } catch (RuntimeException ex) {
                log.error("Unable to release concert ticket {}", ticket.id(), ex);
            }
        }
    }
}
