package io.joblab.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

public final class OutboxWriter {
    private final JdbcTemplate jdbc;
    private final EventJson json;

    public OutboxWriter(JdbcTemplate jdbc, EventJson json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID append(JobDispatch event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox writes must share the business transaction");
        }
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO event_outbox (event_id, routing_key, payload)
                VALUES (?, ?, ?)
                """, eventId.toString(), MessagingConfiguration.ROUTING_KEY, json.write(event));
        return eventId;
    }
}
