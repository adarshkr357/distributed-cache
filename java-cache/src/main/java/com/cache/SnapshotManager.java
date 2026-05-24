package com.cache;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.sql.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages snapshots of cache node state to MySQL.
 * On demand, dumps entire in-memory state to snapshots table.
 * On startup, loads the latest snapshot then replays WAL entries after it.
 */
public class SnapshotManager {
    private static final Logger logger = LoggerFactory.getLogger(SnapshotManager.class);
    private static final Gson gson = new Gson();

    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private Connection connection;

    public SnapshotManager(String jdbcUrl, String dbUser, String dbPassword) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        connect();
    }

    private void connect() {
        int retries = 0;
        while (retries < 10) {
            try {
                this.connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
                logger.info("Snapshot manager connected to MySQL");
                return;
            } catch (SQLException e) {
                retries++;
                logger.warn("MySQL connection attempt {} failed: {}", retries, e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        logger.error("Snapshot manager failed to connect to MySQL after 10 attempts");
    }

    private void ensureConnection() {
        try {
            if (connection == null || connection.isClosed()) connect();
        } catch (SQLException e) {
            connect();
        }
    }

    /**
     * Create a snapshot of the given node's in-memory state.
     */
    public void createSnapshot(String nodeId, CacheNode node) {
        ensureConnection();
        Map<String, CacheNode.CacheEntry> entries = node.getSnapshot();
        Map<String, SnapshotEntry> snapshotData = new HashMap<>();
        for (Map.Entry<String, CacheNode.CacheEntry> entry : entries.entrySet()) {
            CacheNode.CacheEntry ce = entry.getValue();
            if (!ce.isExpired()) {
                snapshotData.put(entry.getKey(), new SnapshotEntry(ce.value, ce.ttlSeconds));
            }
        }
        String json = gson.toJson(snapshotData);
        String sql = "INSERT INTO snapshots (node_id, snapshot_data) VALUES (?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, nodeId);
            stmt.setString(2, json);
            stmt.executeUpdate();
            logger.info("Snapshot created for node {} with {} entries", nodeId, snapshotData.size());
        } catch (SQLException e) {
            logger.error("Failed to create snapshot for node {}: {}", nodeId, e.getMessage());
        }
    }

    /**
     * Restore a node from the latest snapshot, then replay WAL entries after it.
     */
    public Timestamp restoreFromSnapshot(String nodeId, CacheNode node) {
        ensureConnection();
        String sql = "SELECT snapshot_data, created_at FROM snapshots WHERE node_id = ? ORDER BY created_at DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, nodeId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String json = rs.getString("snapshot_data");
                Timestamp createdAt = rs.getTimestamp("created_at");
                Type type = new TypeToken<Map<String, SnapshotEntry>>() {}.getType();
                Map<String, SnapshotEntry> data = gson.fromJson(json, type);
                for (Map.Entry<String, SnapshotEntry> entry : data.entrySet()) {
                    node.directSet(entry.getKey(), entry.getValue().value, entry.getValue().ttl);
                }
                logger.info("Restored {} entries from snapshot for node {} (taken at {})", data.size(), nodeId, createdAt);
                return createdAt;
            }
        } catch (SQLException e) {
            logger.error("Failed to restore snapshot for node {}: {}", nodeId, e.getMessage());
        }
        return null;
    }

    public void shutdown() {
        try { if (connection != null) connection.close(); } catch (SQLException ignored) {}
    }

    private static class SnapshotEntry {
        String value;
        Integer ttl;
        SnapshotEntry(String value, Integer ttl) {
            this.value = value;
            this.ttl = ttl;
        }
    }
}
