CREATE TABLE IF NOT EXISTS attraction_queue (
    attraction_queue_id BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attraction_cycle_id BIGINT       NULL,
    user_id             BIGINT       NOT NULL,
    attraction_id       BIGINT       NOT NULL,
    issued_ticket_id    BIGINT       NOT NULL,
    ticket_type         VARCHAR(10)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'WAITING',
    ride_code           VARCHAR(50)  NULL,
    defer_count         INT          NOT NULL DEFAULT 0,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_attraction_status (attraction_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
