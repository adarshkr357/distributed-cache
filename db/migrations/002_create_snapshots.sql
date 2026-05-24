-- Migration 002: Create snapshots table
-- Stores full in-memory state snapshots for cache nodes

CREATE TABLE IF NOT EXISTS snapshots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    node_id VARCHAR(128) NOT NULL,
    snapshot_data JSON NOT NULL COMMENT 'Full key-value state as JSON object',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_node_id (node_id),
    INDEX idx_created_at (created_at),
    INDEX idx_node_created (node_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
