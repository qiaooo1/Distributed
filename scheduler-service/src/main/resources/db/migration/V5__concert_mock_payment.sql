CREATE TABLE concert_merchandise (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sku VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    label VARCHAR(80) NOT NULL,
    price_fen INT NOT NULL,
    UNIQUE KEY uk_merch_scope (tenant_id,event_id,id),
    UNIQUE KEY uk_merch_sku (tenant_id,event_id,sku),
    CONSTRAINT fk_merch_event FOREIGN KEY (tenant_id,event_id) REFERENCES concert_event(tenant_id,id),
    CONSTRAINT ck_merch_price CHECK (price_fen BETWEEN 0 AND 100000000)
) ENGINE=InnoDB;

CREATE TABLE concert_pickup_point (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    label VARCHAR(80) NOT NULL,
    UNIQUE KEY uk_pickup_scope (tenant_id,event_id,id),
    CONSTRAINT fk_pickup_event FOREIGN KEY (tenant_id,event_id) REFERENCES concert_event(tenant_id,id)
) ENGINE=InnoDB;

CREATE TABLE concert_payment (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchandise_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    pickup_point_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ticket_price_fen INT NOT NULL,
    merch_price_fen INT NOT NULL,
    paid_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_paid_reservation (reservation_id),
    UNIQUE KEY uk_payment_idempotency (tenant_id,buyer_id,payment_key),
    CONSTRAINT fk_payment_ticket FOREIGN KEY (reservation_id) REFERENCES ticket_reservation(id),
    CONSTRAINT fk_payment_merch FOREIGN KEY (tenant_id,event_id,merchandise_id)
        REFERENCES concert_merchandise(tenant_id,event_id,id),
    CONSTRAINT fk_payment_pickup FOREIGN KEY (tenant_id,event_id,pickup_point_id)
        REFERENCES concert_pickup_point(tenant_id,event_id,id),
    CONSTRAINT ck_payment_components CHECK (
        (merchandise_id IS NULL AND pickup_point_id IS NULL AND merch_price_fen = 0)
        OR (merchandise_id IS NOT NULL AND pickup_point_id IS NOT NULL AND merch_price_fen >= 0)
    )
) ENGINE=InnoDB;
