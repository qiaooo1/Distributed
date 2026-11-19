CREATE TABLE job (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_type VARCHAR(24) CHARACTER SET ascii NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    run_at TIMESTAMP(6) NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii NOT NULL,
    generation INT NOT NULL DEFAULT 0,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL,
    next_action_at TIMESTAMP(6),
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    current_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_job_request (request_key),
    KEY idx_job_due (state, next_action_at, id)
) ENGINE=InnoDB;

CREATE TABLE job_attempt (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_no INT NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    worker_id VARCHAR(100) CHARACTER SET ascii NOT NULL,
    outcome VARCHAR(16) CHARACTER SET ascii NOT NULL,
    error_message VARCHAR(256),
    started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at TIMESTAMP(6),
    PRIMARY KEY (job_id, attempt_no),
    UNIQUE KEY uk_attempt_token (lease_token),
    CONSTRAINT fk_attempt_job FOREIGN KEY (job_id) REFERENCES job (id)
) ENGINE=InnoDB;

CREATE TABLE job_result (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    csv MEDIUMTEXT NOT NULL,
    CONSTRAINT fk_result_job FOREIGN KEY (job_id) REFERENCES job (id)
) ENGINE=InnoDB;
