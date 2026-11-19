CREATE TABLE tenant (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    slug VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    running_limit INT NOT NULL,
    CONSTRAINT ck_tenant_running_limit CHECK (running_limit BETWEEN 1 AND 1000)
) ENGINE=InnoDB;

INSERT INTO tenant (id, slug, running_limit)
VALUES ('00000000-0000-0000-0000-000000000001', 'acme', 4);

CREATE TABLE tenant_account (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(100) CHARACTER SET ascii NOT NULL,
    role_name VARCHAR(16) CHARACTER SET ascii NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    token_version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_account_tenant_username (tenant_id, username),
    CONSTRAINT fk_account_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE=InnoDB;

CREATE TABLE job_schedule (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(80) NOT NULL,
    cron_expression VARCHAR(80) CHARACTER SET ascii NOT NULL,
    time_zone VARCHAR(64) CHARACTER SET ascii NOT NULL,
    report_type VARCHAR(24) CHARACTER SET ascii NOT NULL,
    lookback_days INT NOT NULL,
    max_attempts INT NOT NULL,
    misfire_policy VARCHAR(16) CHARACTER SET ascii NOT NULL,
    overlap_policy VARCHAR(24) CHARACTER SET ascii NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_fire_at TIMESTAMP(6),
    last_fire_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_schedule_tenant_name (tenant_id, name),
    KEY idx_schedule_due (enabled, next_fire_at, id),
    CONSTRAINT fk_schedule_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE=InnoDB;

ALTER TABLE job ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
UPDATE job SET tenant_id = '00000000-0000-0000-0000-000000000001' WHERE tenant_id IS NULL;
ALTER TABLE job
    MODIFY COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    DROP INDEX uk_job_request,
    ADD UNIQUE KEY uk_job_tenant_request (tenant_id, request_key),
    ADD COLUMN schedule_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN scheduled_fire_at TIMESTAMP(6),
    ADD COLUMN replayed_from_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ADD UNIQUE KEY uk_job_schedule_fire (schedule_id, scheduled_fire_at),
    ADD KEY idx_job_schedule_state (schedule_id, state),
    ADD KEY idx_job_tenant_cursor (tenant_id, created_at, id),
    ADD KEY idx_job_tenant_state_cursor (tenant_id, state, created_at, id),
    ADD KEY idx_job_tenant_state_lease (tenant_id, state, next_action_at),
    ADD CONSTRAINT fk_job_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    ADD CONSTRAINT fk_job_schedule FOREIGN KEY (schedule_id) REFERENCES job_schedule (id),
    ADD CONSTRAINT fk_job_replay_source FOREIGN KEY (replayed_from_id) REFERENCES job (id);

CREATE TABLE audit_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    action VARCHAR(32) CHARACTER SET ascii NOT NULL,
    resource_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii,
    detail VARCHAR(256),
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_audit_tenant_id (tenant_id, id),
    CONSTRAINT fk_audit_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE=InnoDB;

ALTER TABLE event_outbox ADD COLUMN created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
