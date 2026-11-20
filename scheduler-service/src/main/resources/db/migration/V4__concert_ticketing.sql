CREATE TABLE concert_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(100) NOT NULL,
    venue VARCHAR(120) NOT NULL,
    description VARCHAR(300) NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    sale_starts_at TIMESTAMP(6) NOT NULL,
    sale_ends_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_concert_tenant_event (tenant_id, id),
    KEY idx_concert_listing (tenant_id, starts_at, id),
    CONSTRAINT fk_concert_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT ck_concert_dates CHECK (sale_starts_at < sale_ends_at AND sale_ends_at <= starts_at)
) ENGINE=InnoDB;

CREATE TABLE concert_offer (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    label VARCHAR(60) NOT NULL,
    price_fen INT NOT NULL,
    capacity INT NOT NULL,
    available INT NOT NULL,
    UNIQUE KEY uk_offer_scope (tenant_id, event_id, id),
    CONSTRAINT fk_offer_event FOREIGN KEY (tenant_id, event_id) REFERENCES concert_event(tenant_id, id),
    CONSTRAINT ck_offer_stock CHECK (capacity > 0 AND available >= 0 AND available <= capacity),
    CONSTRAINT ck_offer_price CHECK (price_fen >= 0)
) ENGINE=InnoDB;

CREATE TABLE ticket_reservation (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    offer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    price_fen INT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN state IN ('LOCKED', 'PAID') THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uk_ticket_request (tenant_id, buyer_id, request_key),
    UNIQUE KEY uk_ticket_buyer_event (tenant_id, event_id, buyer_id, active_slot),
    KEY idx_ticket_expiry (state, expires_at, id),
    KEY idx_ticket_mine (tenant_id, buyer_id, created_at, id),
    CONSTRAINT fk_reservation_offer FOREIGN KEY (tenant_id, event_id, offer_id)
        REFERENCES concert_offer(tenant_id, event_id, id),
    CONSTRAINT ck_ticket_state CHECK (state IN ('LOCKED', 'EXPIRED', 'PAID')),
    CONSTRAINT ck_ticket_price CHECK (price_fen >= 0)
) ENGINE=InnoDB;
