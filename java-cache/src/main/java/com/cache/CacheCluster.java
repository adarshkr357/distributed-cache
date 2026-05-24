package com.cache;

import java.io.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the distributed cache cluster.
 * Reads config from cluster.properties, starts cache nodes in separate threads,
 * sets up replication, WAL, snapshots, and failover monitoring.
 */
public class CacheCluster {
    private static final Logger logger = LoggerFactory.getLogger(CacheCluster.class);

    private final Properties config;
    private final ConsistentHashRing hashRing;
    private final Map<String, CacheNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, ReplicationManager> replicationManagers = new ConcurrentHashMap<>();
    private WALWriter walWriter;
    private SnapshotManager snapshotManager;
    private FailoverManager failoverManager;
    private final ScheduledExecutorService cleanupScheduler;

    public CacheCluster(Properties config) {
        this.config = config;
        int virtualNodes = Integer.parseInt(config.getProperty("cluster.virtual.nodes", "150"));
        this.hashRing = new ConsistentHashRing(virtualNodes);
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ttl-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        logger.info("Starting distributed cache cluster...");

        // Initialize MySQL connections
        String mysqlUrl = config.getProperty("mysql.url");
        String mysqlUser = config.getProperty("mysql.user");
        String mysqlPassword = config.getProperty("mysql.password");
        walWriter = new WALWriter(mysqlUrl, mysqlUser, mysqlPassword);
        snapshotManager = new SnapshotManager(mysqlUrl, mysqlUser, mysqlPassword);

        // Determine which node this instance runs
        String currentNodeId = System.getenv("CACHE_NODE_ID");
        if (currentNodeId == null) {
            currentNodeId = config.getProperty("current.node.id", "1");
        }

        int nodeCount = Integer.parseInt(config.getProperty("cluster.node.count", "3"));
        int maxCapacity = Integer.parseInt(config.getProperty("cluster.max.capacity", "1000"));

        // If running a single node (container mode), only start the current node
        String mode = System.getenv("CACHE_MODE");
        if ("single".equals(mode)) {
            startSingleNode(currentNodeId, maxCapacity);
        } else {
            startAllNodes(nodeCount, maxCapacity);
        }

        // Start failover manager
        int hbInterval = Integer.parseInt(config.getProperty("cluster.heartbeat.interval.ms", "5000"));
        int hbMaxMisses = Integer.parseInt(config.getProperty("cluster.heartbeat.max.misses", "3"));
        failoverManager = new FailoverManager(hashRing, nodes, hbInterval, hbMaxMisses);
        failoverManager.start();

        // Schedule TTL cleanup every 10 seconds
        cleanupScheduler.scheduleAtFixedRate(() -> {
            for (CacheNode node : nodes.values()) {
                node.cleanExpiredEntries();
            }
        }, 10, 10, TimeUnit.SECONDS);

        logger.info("Cache cluster started with {} node(s)", nodes.size());

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown-hook"));
    }

    private void startSingleNode(String nodeIdNum, int maxCapacity) {
        String prefix = "node." + nodeIdNum;
        String host = config.getProperty(prefix + ".host", "0.0.0.0");
        int port = Integer.parseInt(config.getProperty(prefix + ".port", "6001"));
        String slaveHost = config.getProperty(prefix + ".slave.host");
        int slavePort = Integer.parseInt(config.getProperty(prefix + ".slave.port", "6002"));

        String nodeId = host + ":" + port;
        CacheNode node = new CacheNode(nodeIdNum, host, port, maxCapacity);
        node.setWalWriter(walWriter);

        // Setup replication
        if (slaveHost != null) {
            ReplicationManager rm = new ReplicationManager(nodeIdNum, slaveHost, slavePort);
            node.setReplicationManager(rm);
            replicationManagers.put(nodeIdNum, rm);
            rm.startHeartbeat();
        }

        nodes.put(nodeIdNum, node);
        hashRing.addNode(nodeIdNum);

        // Restore from snapshot and replay WAL
        Timestamp snapshotTs = snapshotManager.restoreFromSnapshot(nodeIdNum, node);
        walWriter.replayWAL(nodeIdNum, node, snapshotTs);

        // Start node in a thread
        Thread nodeThread = new Thread(node, "cache-node-" + nodeIdNum);
        nodeThread.setDaemon(true);
        nodeThread.start();
        logger.info("Started single node {} on port {}", nodeIdNum, port);
    }

    private void startAllNodes(int nodeCount, int maxCapacity) {
        for (int i = 1; i <= nodeCount; i++) {
            String prefix = "node." + i;
            String host = config.getProperty(prefix + ".host", "localhost");
            int port = Integer.parseInt(config.getProperty(prefix + ".port", String.valueOf(6000 + i)));
            String slaveHost = config.getProperty(prefix + ".slave.host");
            int slavePort = Integer.parseInt(config.getProperty(prefix + ".slave.port", String.valueOf(6000 + (i % nodeCount) + 1)));

            String nodeIdNum = String.valueOf(i);
            CacheNode node = new CacheNode(nodeIdNum, host, port, maxCapacity);
            node.setWalWriter(walWriter);

            if (slaveHost != null) {
                ReplicationManager rm = new ReplicationManager(nodeIdNum, slaveHost, slavePort);
                node.setReplicationManager(rm);
                replicationManagers.put(nodeIdNum, rm);
                rm.startHeartbeat();
            }

            nodes.put(nodeIdNum, node);
            hashRing.addNode(nodeIdNum);

            Timestamp snapshotTs = snapshotManager.restoreFromSnapshot(nodeIdNum, node);
            walWriter.replayWAL(nodeIdNum, node, snapshotTs);

            Thread nodeThread = new Thread(node, "cache-node-" + i);
            nodeThread.setDaemon(true);
            nodeThread.start();
        }
    }

    public void shutdown() {
        logger.info("Shutting down cache cluster...");
        cleanupScheduler.shutdownNow();
        if (failoverManager != null) failoverManager.shutdown();
        for (ReplicationManager rm : replicationManagers.values()) rm.shutdown();
        for (CacheNode node : nodes.values()) node.shutdown();
        if (walWriter != null) walWriter.shutdown();
        if (snapshotManager != null) snapshotManager.shutdown();
        logger.info("Cache cluster shut down complete");
    }

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "cluster.properties";

        // Also check classpath
        Properties config = new Properties();
        File configFile = new File(configPath);
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                config.load(fis);
            }
        } else {
            try (InputStream is = CacheCluster.class.getClassLoader().getResourceAsStream(configPath)) {
                if (is != null) {
                    config.load(is);
                } else {
                    logger.error("Config file not found: {}", configPath);
                    System.exit(1);
                }
            }
        }

        // Allow env overrides
        overrideFromEnv(config, "MYSQL_URL", "mysql.url");
        overrideFromEnv(config, "MYSQL_USER", "mysql.user");
        overrideFromEnv(config, "MYSQL_PASSWORD", "mysql.password");
        overrideFromEnv(config, "CACHE_NODE_ID", "current.node.id");
        overrideFromEnv(config, "MAX_CAPACITY", "cluster.max.capacity");

        CacheCluster cluster = new CacheCluster(config);
        cluster.start();

        // Keep main thread alive
        Thread.currentThread().join();
    }

    private static void overrideFromEnv(Properties config, String envKey, String propKey) {
        String val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) {
            config.setProperty(propKey, val);
        }
    }
}
