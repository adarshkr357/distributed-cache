-- Migration 001: Create WAL log table
-- Write-Ahead Log for distributed cache operations

CREATE TABLE IF NOT EXISTS wal_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation ENUM('SET', 'DELETE') NOT NULL,
    cache_key VARCHAR(512) NOT NULL,
    cache_value LONGTEXT NULL,
    ttl INT NULL COMMENT 'Time to live in seconds, NULL means no expiry',
    timestamp DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    node_id VARCHAR(128) NOT NULL,
    applied BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_node_id (node_id),
    INDEX idx_applied (applied),
    INDEX idx_timestamp (timestamp),
    INDEX idx_node_applied (node_id, applied)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
