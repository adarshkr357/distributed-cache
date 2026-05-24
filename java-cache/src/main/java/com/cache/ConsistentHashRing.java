package com.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consistent Hash Ring implementation with virtual nodes.
 * Uses MD5 hashing to distribute keys evenly across nodes.
 * Each physical node gets 150 virtual nodes on the ring for uniform distribution.
 */
public class ConsistentHashRing {

    private static final Logger logger = LoggerFactory.getLogger(ConsistentHashRing.class);

    private final int virtualNodeCount;
    private final ConcurrentSkipListMap<Long, String> ring;
    private final CopyOnWriteArrayList<String> physicalNodes;

    public ConsistentHashRing(int virtualNodeCount) {
        this.virtualNodeCount = virtualNodeCount;
        this.ring = new ConcurrentSkipListMap<>();
        this.physicalNodes = new CopyOnWriteArrayList<>();
    }

    public ConsistentHashRing() {
        this(150);
    }

    /**
     * Add a physical node to the hash ring.
     * Creates virtualNodeCount virtual nodes distributed around the ring.
     *
     * @param nodeId identifier for the node (e.g., "cache-node-1:6001")
     */
    public synchronized void addNode(String nodeId) {
        if (physicalNodes.contains(nodeId)) {
            logger.warn("Node {} already exists in the ring", nodeId);
            return;
        }

        physicalNodes.add(nodeId);

        for (int i = 0; i < virtualNodeCount; i++) {
            String virtualKey = nodeId + "#VN" + i;
            long hash = hash(virtualKey);
            ring.put(hash, nodeId);
        }

        logger.info("Added node {} with {} virtual nodes. Ring size: {}", nodeId, virtualNodeCount, ring.size());
    }

    /**
     * Remove a physical node and all its virtual nodes from the ring.
     *
     * @param nodeId identifier for the node to remove
     */
    public synchronized void removeNode(String nodeId) {
        if (!physicalNodes.contains(nodeId)) {
            logger.warn("Node {} not found in the ring", nodeId);
            return;
        }

        physicalNodes.remove(nodeId);

        for (int i = 0; i < virtualNodeCount; i++) {
            String virtualKey = nodeId + "#VN" + i;
            long hash = hash(virtualKey);
            ring.remove(hash);
        }

        logger.info("Removed node {} from ring. Ring size: {}", nodeId, ring.size());
    }

    /**
     * Get the node responsible for the given key.
     * Walks clockwise around the ring from the key's hash position.
     *
     * @param key the cache key to look up
     * @return the node ID responsible for this key, or null if ring is empty
     */
    public String getNode(String key) {
        if (ring.isEmpty()) {
            return null;
        }

        long hash = hash(key);

        // Find the first node clockwise from the hash position
        Map.Entry<Long, String> entry = ring.ceilingEntry(hash);

        // If we've gone past the highest hash, wrap around to the first node
        if (entry == null) {
            entry = ring.firstEntry();
        }

        return entry.getValue();
    }

    /**
     * Get the next node on the ring after the given node (used for failover).
     *
     * @param nodeId the current node
     * @return the next physical node on the ring
     */
    public String getNextNode(String nodeId) {
        List<String> nodes = getPhysicalNodes();
        if (nodes.size() <= 1) {
            return null;
        }

        int index = nodes.indexOf(nodeId);
        if (index == -1) {
            return nodes.get(0);
        }

        return nodes.get((index + 1) % nodes.size());
    }

    /**
     * Get all physical nodes currently in the ring.
     *
     * @return unmodifiable list of physical node IDs
     */
    public List<String> getPhysicalNodes() {
        return Collections.unmodifiableList(new ArrayList<>(physicalNodes));
    }

    /**
     * Check if a node exists in the ring.
     */
    public boolean containsNode(String nodeId) {
        return physicalNodes.contains(nodeId);
    }

    /**
     * Get the number of physical nodes in the ring.
     */
    public int getNodeCount() {
        return physicalNodes.size();
    }

    /**
     * Compute MD5 hash and return as a long value.
     * Uses the first 8 bytes of the MD5 digest for the hash.
     */
    public static long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));

            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }
            return hash;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }
}
