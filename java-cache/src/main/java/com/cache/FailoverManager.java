package com.cache;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors all cache nodes via heartbeat. If a node misses 3 consecutive
 * heartbeats (15s), marks it dead and reroutes its keyspace to the next
 * node on the hash ring. When a node comes back, rebalances keys.
 */
public class FailoverManager {
    private static final Logger logger = LoggerFactory.getLogger(FailoverManager.class);

    private final ConsistentHashRing hashRing;
    private final Map<String, CacheNode> nodes;
    private final Map<String, Integer> missedHeartbeats = new ConcurrentHashMap<>();
    private final Map<String, Boolean> nodeStatus = new ConcurrentHashMap<>();
    private final int maxMissedHeartbeats;
    private final int heartbeatIntervalMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FailoverManager(ConsistentHashRing hashRing, Map<String, CacheNode> nodes,
                           int heartbeatIntervalMs, int maxMissedHeartbeats) {
        this.hashRing = hashRing;
        this.nodes = nodes;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.maxMissedHeartbeats = maxMissedHeartbeats;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "failover-monitor");
            t.setDaemon(true);
            return t;
        });
        for (String nodeId : nodes.keySet()) {
            missedHeartbeats.put(nodeId, 0);
            nodeStatus.put(nodeId, true);
        }
    }

    public void start() {
        running.set(true);
        scheduler.scheduleAtFixedRate(this::checkNodes, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        logger.info("Failover manager started, monitoring {} nodes", nodes.size());
    }

    private void checkNodes() {
        for (Map.Entry<String, CacheNode> entry : nodes.entrySet()) {
            String nodeId = entry.getKey();
            CacheNode node = entry.getValue();
            if (node.isRunning()) {
                missedHeartbeats.put(nodeId, 0);
                if (!nodeStatus.getOrDefault(nodeId, false)) {
                    handleNodeRecovery(nodeId);
                }
                nodeStatus.put(nodeId, true);
            } else {
                int missed = missedHeartbeats.getOrDefault(nodeId, 0) + 1;
                missedHeartbeats.put(nodeId, missed);
                if (missed >= maxMissedHeartbeats && nodeStatus.getOrDefault(nodeId, true)) {
                    handleNodeFailure(nodeId);
                }
            }
        }
    }

    private void handleNodeFailure(String nodeId) {
        logger.error("Node {} marked as DEAD after {} missed heartbeats", nodeId, maxMissedHeartbeats);
        nodeStatus.put(nodeId, false);
        hashRing.removeNode(nodeId);
        String nextNode = hashRing.getNextNode(nodeId);
        if (nextNode != null) {
            logger.info("Rerouting keyspace from {} to {}", nodeId, nextNode);
        }
    }

    private void handleNodeRecovery(String nodeId) {
        logger.info("Node {} has recovered, adding back to ring", nodeId);
        nodeStatus.put(nodeId, true);
        missedHeartbeats.put(nodeId, 0);
        if (!hashRing.containsNode(nodeId)) {
            hashRing.addNode(nodeId);
            logger.info("Node {} rebalanced back into the ring", nodeId);
        }
    }

    public void shutdown() {
        running.set(false);
        scheduler.shutdownNow();
    }

    public boolean isNodeAlive(String nodeId) {
        return nodeStatus.getOrDefault(nodeId, false);
    }

    public Map<String, Boolean> getAllNodeStatus() {
        return Collections.unmodifiableMap(new HashMap<>(nodeStatus));
    }
}
