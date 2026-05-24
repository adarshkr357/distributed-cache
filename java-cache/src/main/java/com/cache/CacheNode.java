package com.cache;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Individual cache node that stores key-value pairs with LRU eviction.
 * Exposes a TCP server for handling cache commands (GET, SET, DELETE, PING, STATS).
 * Uses a LinkedHashMap configured for access-order to provide LRU semantics.
 */
public class CacheNode implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CacheNode.class);

    private final String nodeId;
    private final String host;
    private final int port;
    private final int maxCapacity;

    // LRU cache: access-order LinkedHashMap with eviction on overflow
    private final LinkedHashMap<String, CacheEntry> store;
    private final Map<String, CacheEntry> synchronizedStore;

    // Statistics
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);
    private final AtomicLong totalKeysSet = new AtomicLong(0);
    private final long startTime = System.currentTimeMillis();

    // Server state
    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService clientHandler;

    // Dependencies (injected by CacheCluster)
    private WALWriter walWriter;
    private ReplicationManager replicationManager;

    /**
     * Represents a cached entry with optional TTL.
     */
    public static class CacheEntry implements Serializable {
        public final String value;
        public final long createdAt;
        public final Integer ttlSeconds; // null means no expiry

        public CacheEntry(String value, Integer ttlSeconds) {
            this.value = value;
            this.createdAt = System.currentTimeMillis();
            this.ttlSeconds = ttlSeconds;
        }

        public boolean isExpired() {
            if (ttlSeconds == null) return false;
            return System.currentTimeMillis() > createdAt + (ttlSeconds * 1000L);
        }
    }

    public CacheNode(String nodeId, String host, int port, int maxCapacity) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.maxCapacity = maxCapacity;

        // Create LRU LinkedHashMap: access-order=true, removeEldestEntry when over capacity
        this.store = new LinkedHashMap<>(maxCapacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                if (size() > maxCapacity) {
                    evictionCount.incrementAndGet();
                    logger.debug("Evicting key '{}' from node {}", eldest.getKey(), nodeId);
                    return true;
                }
                return false;
            }
        };
        this.synchronizedStore = Collections.synchronizedMap(store);

        this.clientHandler = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "cache-client-" + nodeId);
                t.setDaemon(true);
                return t;
            }
        );
    }

    public void setWalWriter(WALWriter walWriter) {
        this.walWriter = walWriter;
    }

    public void setReplicationManager(ReplicationManager replicationManager) {
        this.replicationManager = replicationManager;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
            running.set(true);
            logger.info("Cache node {} started on {}:{}", nodeId, host, port);

            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(30000);
                    clientHandler.submit(() -> handleClient(clientSocket));
                } catch (SocketException e) {
                    if (running.get()) {
                        logger.error("Socket error on node {}: {}", nodeId, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to start cache node {} on port {}: {}", nodeId, port, e.getMessage());
        }
    }

    /**
     * Handle a single client connection. Processes commands line by line.
     * Protocol:
     *   SET key value [ttl]  -> +OK
     *   GET key              -> $value or $NULL
     *   DELETE key           -> +OK or -ERR key not found
     *   PING                 -> +PONG
     *   STATS                -> JSON stats object
     *   KEYS                 -> JSON array of all keys
     *   QUIT                 -> +BYE
     */
    private void handleClient(Socket socket) {
        try (
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                String response = processCommand(trimmed);
                writer.println(response);

                if (response.equals("+BYE")) break;
            }
        } catch (SocketTimeoutException e) {
            logger.debug("Client connection timed out on node {}", nodeId);
        } catch (IOException e) {
            logger.debug("Client disconnected from node {}: {}", nodeId, e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Process a single command string and return the response.
     */
    public String processCommand(String command) {
        String[] parts = command.split("\\s+", 4);
        String cmd = parts[0].toUpperCase();

        return switch (cmd) {
            case "SET" -> handleSet(parts);
            case "GET" -> handleGet(parts);
            case "DELETE" -> handleDelete(parts);
            case "PING" -> "+PONG";
            case "STATS" -> handleStats();
            case "KEYS" -> handleKeys();
            case "QUIT" -> "+BYE";
            default -> "-ERR unknown command: " + cmd;
        };
    }

    private String handleSet(String[] parts) {
        if (parts.length < 3) {
            return "-ERR SET requires key and value: SET key value [ttl]";
        }

        String key = parts[1];
        String value = parts[2];
        Integer ttl = null;

        if (parts.length >= 4) {
            try {
                ttl = Integer.parseInt(parts[3]);
                if (ttl <= 0) {
                    return "-ERR TTL must be a positive integer";
                }
            } catch (NumberFormatException e) {
                return "-ERR Invalid TTL value: " + parts[3];
            }
        }

        // Write to WAL before applying
        if (walWriter != null) {
            walWriter.logOperation("SET", key, value, ttl, nodeId);
        }

        // Apply to in-memory store
        synchronizedStore.put(key, new CacheEntry(value, ttl));
        totalKeysSet.incrementAndGet();

        // Mark WAL as applied (done inside walWriter)

        // Replicate asynchronously
        if (replicationManager != null) {
            final Integer finalTtl = ttl;
            replicationManager.replicateAsync("SET", key, value, finalTtl);
        }

        return "+OK";
    }

    private String handleGet(String[] parts) {
        if (parts.length < 2) {
            return "-ERR GET requires a key: GET key";
        }

        String key = parts[1];
        CacheEntry entry = synchronizedStore.get(key);

        if (entry == null) {
            missCount.incrementAndGet();
            return "$NULL";
        }

        // Check TTL expiration
        if (entry.isExpired()) {
            synchronizedStore.remove(key);
            missCount.incrementAndGet();
            return "$NULL";
        }

        hitCount.incrementAndGet();
        return "$" + entry.value;
    }

    private String handleDelete(String[] parts) {
        if (parts.length < 2) {
            return "-ERR DELETE requires a key: DELETE key";
        }

        String key = parts[1];

        // Write to WAL before applying
        if (walWriter != null) {
            walWriter.logOperation("DELETE", key, null, null, nodeId);
        }

        CacheEntry removed = synchronizedStore.remove(key);

        // Replicate asynchronously
        if (replicationManager != null) {
            replicationManager.replicateAsync("DELETE", key, null, null);
        }

        if (removed != null) {
            return "+OK";
        } else {
            return "-ERR key not found";
        }
    }

    private String handleStats() {
        long totalHits = hitCount.get();
        long totalMisses = missCount.get();
        long total = totalHits + totalMisses;
        double hitRate = total > 0 ? (double) totalHits / total * 100.0 : 0.0;
        double missRate = total > 0 ? (double) totalMisses / total * 100.0 : 0.0;

        // Estimate memory usage (rough: 200 bytes per entry average)
        long estimatedMemory = (long) synchronizedStore.size() * 200L;

        return String.format(
            "{\"nodeId\":\"%s\",\"host\":\"%s\",\"port\":%d,\"totalKeys\":%d,\"maxCapacity\":%d," +
            "\"memoryUsedBytes\":%d,\"hitCount\":%d,\"missCount\":%d,\"hitRate\":%.2f,\"missRate\":%.2f," +
            "\"evictionCount\":%d,\"uptimeSeconds\":%d,\"totalKeysSet\":%d}",
            nodeId, host, port,
            synchronizedStore.size(), maxCapacity,
            estimatedMemory,
            totalHits, totalMisses, hitRate, missRate,
            evictionCount.get(),
            (System.currentTimeMillis() - startTime) / 1000,
            totalKeysSet.get()
        );
    }

    private String handleKeys() {
        synchronized (store) {
            StringBuilder sb = new StringBuilder("[");
            Iterator<String> it = store.keySet().iterator();
            while (it.hasNext()) {
                sb.append("\"").append(it.next().replace("\"", "\\\"")).append("\"");
                if (it.hasNext()) sb.append(",");
            }
            sb.append("]");
            return sb.toString();
        }
    }

    /**
     * Directly set a key-value pair (used for replication and snapshot restore).
     * Bypasses WAL and replication to avoid infinite loops.
     */
    public void directSet(String key, String value, Integer ttl) {
        synchronizedStore.put(key, new CacheEntry(value, ttl));
    }

    /**
     * Directly delete a key (used for replication).
     * Bypasses WAL and replication.
     */
    public void directDelete(String key) {
        synchronizedStore.remove(key);
    }

    /**
     * Get a snapshot of all current entries for persistence.
     */
    public Map<String, CacheEntry> getSnapshot() {
        synchronized (store) {
            return new LinkedHashMap<>(store);
        }
    }

    /**
     * Clean up expired entries. Called periodically.
     */
    public void cleanExpiredEntries() {
        List<String> expiredKeys = new ArrayList<>();
        synchronized (store) {
            for (Map.Entry<String, CacheEntry> entry : store.entrySet()) {
                if (entry.getValue().isExpired()) {
                    expiredKeys.add(entry.getKey());
                }
            }
            for (String key : expiredKeys) {
                store.remove(key);
            }
        }
        if (!expiredKeys.isEmpty()) {
            logger.debug("Cleaned {} expired entries from node {}", expiredKeys.size(), nodeId);
        }
    }

    public void shutdown() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.error("Error shutting down node {}: {}", nodeId, e.getMessage());
        }
        clientHandler.shutdownNow();
        logger.info("Cache node {} shut down", nodeId);
    }

    // Getters
    public String getNodeId() { return nodeId; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public int getMaxCapacity() { return maxCapacity; }
    public boolean isRunning() { return running.get(); }
    public int getCurrentSize() { return synchronizedStore.size(); }
    public long getHitCount() { return hitCount.get(); }
    public long getMissCount() { return missCount.get(); }
    public long getEvictionCount() { return evictionCount.get(); }
    public long getUptimeSeconds() { return (System.currentTimeMillis() - startTime) / 1000; }
}
