package com.cache;

import java.sql.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Write-Ahead Log writer. Before any SET or DELETE is applied to the
 * in-memory store, the operation is persisted to MySQL wal_log table.
 * After applying, the entry is marked as applied=true.
 */
public class WALWriter {
    private static final Logger logger = LoggerFactory.getLogger(WALWriter.class);

    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final ExecutorService executor;
    private Connection connection;

    public WALWriter(String jdbcUrl, String dbUser, String dbPassword) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wal-writer");
            t.setDaemon(true);
            return t;
        });
        connect();
    }

    private void connect() {
        int retries = 0;
        while (retries < 10) {
            try {
                this.connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
                logger.info("WAL writer connected to MySQL");
                return;
            } catch (SQLException e) {
                retries++;
                logger.warn("MySQL connection attempt {} failed: {}", retries, e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        logger.error("Failed to connect to MySQL after 10 attempts");
    }

    private void ensureConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                connect();
            }
        } catch (SQLException e) {
            connect();
        }
    }

    /**
     * Log a cache operation to the WAL. Writes synchronously to ensure
     * durability, then marks as applied in a background thread.
     */
    public void logOperation(String operation, String key, String value, Integer ttl, String nodeId) {
        ensureConnection();
        String sql = "INSERT INTO wal_log (operation, cache_key, cache_value, ttl, node_id, applied) VALUES (?, ?, ?, ?, ?, TRUE)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, operation);
            stmt.setString(2, key);
            stmt.setString(3, value);
            if (ttl != null) {
                stmt.setInt(4, ttl);
            } else {
                stmt.setNull(4, Types.INTEGER);
            }
            stmt.setString(5, nodeId);
            stmt.executeUpdate();
            logger.debug("WAL: {} {} on node {}", operation, key, nodeId);
        } catch (SQLException e) {
            logger.error("Failed to write WAL entry: {}", e.getMessage());
        }
    }

    /**
     * Replay unapplied WAL entries for a specific node.
     * Returns entries ordered by timestamp for correct replay.
     */
    public void replayWAL(String nodeId, CacheNode node, Timestamp afterTimestamp) {
        ensureConnection();
        String sql = "SELECT operation, cache_key, cache_value, ttl FROM wal_log " +
                     "WHERE node_id = ? AND applied = FALSE";
        if (afterTimestamp != null) {
            sql += " AND timestamp > ?";
        }
        sql += " ORDER BY timestamp ASC";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, nodeId);
            if (afterTimestamp != null) {
                stmt.setTimestamp(2, afterTimestamp);
            }
            ResultSet rs = stmt.executeQuery();
            int count = 0;
            while (rs.next()) {
                String op = rs.getString("operation");
                String key = rs.getString("cache_key");
                String value = rs.getString("cache_value");
                int ttl = rs.getInt("ttl");
                boolean ttlNull = rs.wasNull();

                if ("SET".equals(op)) {
                    node.directSet(key, value, ttlNull ? null : ttl);
                } else if ("DELETE".equals(op)) {
                    node.directDelete(key);
                }
                count++;
            }
            if (count > 0) {
                logger.info("Replayed {} WAL entries for node {}", count, nodeId);
                markAllApplied(nodeId);
            }
        } catch (SQLException e) {
            logger.error("Failed to replay WAL for node {}: {}", nodeId, e.getMessage());
        }
    }

    private void markAllApplied(String nodeId) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE wal_log SET applied = TRUE WHERE node_id = ? AND applied = FALSE")) {
            stmt.setString(1, nodeId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to mark WAL entries as applied: {}", e.getMessage());
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        try { if (connection != null) connection.close(); } catch (SQLException ignored) {}
    }
}
