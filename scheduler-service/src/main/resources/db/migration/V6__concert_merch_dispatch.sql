ALTER TABLE concert_payment
    ADD COLUMN fulfillment_seen_at TIMESTAMP(6) NULL,
    ADD KEY idx_payment_fulfillment (fulfillment_seen_at, paid_at, id);

CREATE TABLE concert_pickup_demand (
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchandise_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pickup_point_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    paid_qty INT NOT NULL DEFAULT 0,
    local_qty INT NOT NULL DEFAULT 0,
    planned_qty INT NOT NULL DEFAULT 0,
    deficit INT GENERATED ALWAYS AS (GREATEST(paid_qty - local_qty - planned_qty, 0)) STORED,
    PRIMARY KEY (tenant_id,event_id,merchandise_id,pickup_point_id),
    KEY idx_pickup_deficit (deficit,tenant_id,event_id,merchandise_id,pickup_point_id),
    CONSTRAINT ck_pickup_counts CHECK (paid_qty >= 0 AND local_qty >= 0 AND planned_qty >= 0),
    CONSTRAINT fk_demand_merch FOREIGN KEY (tenant_id,event_id,merchandise_id)
        REFERENCES concert_merchandise(tenant_id,event_id,id),
    CONSTRAINT fk_demand_pickup FOREIGN KEY (tenant_id,event_id,pickup_point_id)
        REFERENCES concert_pickup_point(tenant_id,event_id,id)
) ENGINE=InnoDB;

CREATE TABLE concert_warehouse (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    label VARCHAR(80) NOT NULL,
    eta_minutes SMALLINT NOT NULL,
    accepting BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE KEY uk_warehouse_tenant (tenant_id,id),
    CONSTRAINT fk_warehouse_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT ck_warehouse_eta CHECK (eta_minutes BETWEEN 1 AND 1440)
) ENGINE=InnoDB;

CREATE TABLE concert_warehouse_stock (
    warehouse_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchandise_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    available INT NOT NULL,
    PRIMARY KEY (warehouse_id,merchandise_id),
    KEY idx_warehouse_stock_merch (merchandise_id,warehouse_id),
    CONSTRAINT fk_stock_warehouse FOREIGN KEY (warehouse_id) REFERENCES concert_warehouse(id),
    CONSTRAINT fk_stock_merch FOREIGN KEY (merchandise_id) REFERENCES concert_merchandise(id),
    CONSTRAINT ck_warehouse_stock CHECK (available >= 0)
) ENGINE=InnoDB;

CREATE TABLE concert_restock_order (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchandise_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pickup_point_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity INT NOT NULL,
    deadline_at TIMESTAMP(6) NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    accepted_warehouse_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    note VARCHAR(80),
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    lease_until TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_restock_queue (state,lease_until,created_at,id),
    KEY idx_restock_tenant (tenant_id,created_at,id),
    CONSTRAINT fk_restock_demand FOREIGN KEY (tenant_id,event_id,merchandise_id,pickup_point_id)
        REFERENCES concert_pickup_demand(tenant_id,event_id,merchandise_id,pickup_point_id),
    CONSTRAINT fk_restock_accepted_warehouse FOREIGN KEY (tenant_id,accepted_warehouse_id)
        REFERENCES concert_warehouse(tenant_id,id),
    CONSTRAINT ck_restock_qty CHECK (quantity > 0),
    CONSTRAINT ck_restock_state CHECK (state IN ('PENDING','DISPATCHING','ACCEPTED','MANUAL')),
    CONSTRAINT ck_restock_lease CHECK (
        (state='DISPATCHING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state<>'DISPATCHING' AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB;

CREATE TABLE concert_restock_attempt (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    warehouse_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REQUESTED',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_restock_warehouse_attempt (order_id,warehouse_id),
    KEY idx_pending_attempt (order_id,state,created_at,id),
    CONSTRAINT fk_attempt_order FOREIGN KEY (order_id) REFERENCES concert_restock_order(id),
    CONSTRAINT fk_attempt_warehouse FOREIGN KEY (warehouse_id) REFERENCES concert_warehouse(id),
    CONSTRAINT ck_restock_attempt CHECK (state IN ('REQUESTED','ACCEPTED','REJECTED'))
) ENGINE=InnoDB;

CREATE TABLE concert_warehouse_receipt (
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    accepted BOOLEAN NOT NULL,
    reason VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_receipt_attempt FOREIGN KEY (attempt_id) REFERENCES concert_restock_attempt(id)
) ENGINE=InnoDB;
