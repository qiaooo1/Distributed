package io.joblab.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

public final class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 25;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RabbitTemplate rabbit;

    public OutboxRelay(JdbcTemplate jdbc, PlatformTransactionManager transactions, RabbitTemplate rabbit) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${joblab.outbox.delay-ms:250}")
    public void publishDueEvents() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            if (!Boolean.TRUE.equals(transaction.execute(status -> publishNext()))) {
                return;
            }
        }
    }

    private boolean publishNext() {
        var rows = jdbc.query("""
                SELECT id, event_id, routing_key, payload, attempts
                FROM event_outbox
                WHERE sent_at IS NULL AND available_at <= CURRENT_TIMESTAMP(6)
                ORDER BY available_at, id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, n) -> new OutboxRow(rs.getLong("id"), rs.getString("event_id"),
                rs.getString("routing_key"), rs.getString("payload"), rs.getInt("attempts")));
        if (rows.isEmpty()) {
            return false;
        }

        OutboxRow row = rows.get(0);
        try {
            publish(row);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Outbox relay interrupted", ex);
        } catch (Exception ex) {
            int delaySeconds = Math.min(60, 1 << Math.min(row.attempts(), 6));
            jdbc.update("""
                    UPDATE event_outbox
                    SET attempts = attempts + 1,
                        available_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6))
                    WHERE id = ?
                    """, delaySeconds, row.id());
            log.warn("Outbox event {} will retry in {} seconds: {}", row.eventId(), delaySeconds, ex.toString());
            return true;
        }

        jdbc.update("UPDATE event_outbox SET sent_at = CURRENT_TIMESTAMP(6) WHERE id = ?", row.id());
        return true;
    }

    private void publish(OutboxRow row) throws Exception {
        var correlation = new CorrelationData(row.eventId());
        rabbit.convertAndSend(MessagingConfiguration.EXCHANGE, row.routingKey(), row.payload(), message -> {
            message.getMessageProperties().setMessageId(row.eventId());
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        }, correlation);
        var confirm = correlation.getFuture().get(2, TimeUnit.SECONDS);
        if (!confirm.isAck()) {
            throw new IllegalStateException("Broker rejected publish: " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("Broker returned unroutable event: " + row.routingKey());
        }
    }

    private record OutboxRow(long id, String eventId, String routingKey, String payload, int attempts) {
    }
}
