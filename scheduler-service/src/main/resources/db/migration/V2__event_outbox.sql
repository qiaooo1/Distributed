CREATE TABLE event_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    routing_key VARCHAR(80) CHARACTER SET ascii NOT NULL,
    payload JSON NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_outbox_event (event_id),
    KEY idx_outbox_due (sent_at, available_at, id)
) ENGINE=InnoDB;
