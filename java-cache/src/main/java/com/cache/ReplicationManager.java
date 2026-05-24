package com.cache;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages replication from a master cache node to its slave.
 * Async replication on SET/DELETE plus periodic heartbeats every 5s.
 */
public class ReplicationManager {
    private static final Logger logger = LoggerFactory.getLogger(ReplicationManager.class);

    private final String masterNodeId;
    private final String slaveHost;
    private final int slavePort;
    private final ExecutorService replicationExecutor;
    private final ScheduledExecutorService heartbeatScheduler;
    private final AtomicBoolean slaveAlive = new AtomicBoolean(true);
    private final AtomicLong replicationLagMs = new AtomicLong(0);
    private final AtomicLong lastHeartbeatTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong failedReplications = new AtomicLong(0);

    public ReplicationManager(String masterNodeId, String slaveHost, int slavePort) {
        this.masterNodeId = masterNodeId;
        this.slaveHost = slaveHost;
        this.slavePort = slavePort;
        this.replicationExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "repl-" + masterNodeId);
            t.setDaemon(true);
            return t;
        });
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hb-" + masterNodeId);
            t.setDaemon(true);
            return t;
        });
    }

    public void startHeartbeat() {
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                long start = System.currentTimeMillis();
                String response = sendCommand("PING");
                long elapsed = System.currentTimeMillis() - start;
                if ("+PONG".equals(response)) {
                    slaveAlive.set(true);
                    replicationLagMs.set(elapsed);
                    lastHeartbeatTime.set(System.currentTimeMillis());
                } else {
                    slaveAlive.set(false);
                }
            } catch (Exception e) {
                slaveAlive.set(false);
                logger.warn("Heartbeat failed to slave {}:{}: {}", slaveHost, slavePort, e.getMessage());
            }
        }, 0, 5000, TimeUnit.MILLISECONDS);
        logger.info("Heartbeat started: {} -> {}:{}", masterNodeId, slaveHost, slavePort);
    }

    public void replicateAsync(String operation, String key, String value, Integer ttl) {
        replicationExecutor.submit(() -> {
            try {
                String command;
                if ("SET".equals(operation)) {
                    command = "SET " + key + " " + value + (ttl != null ? " " + ttl : "");
                } else {
                    command = "DELETE " + key;
                }
                long start = System.currentTimeMillis();
                sendCommand(command);
                replicationLagMs.set(System.currentTimeMillis() - start);
            } catch (Exception e) {
                failedReplications.incrementAndGet();
                logger.error("Replication failed {} {} to {}:{}: {}", operation, key, slaveHost, slavePort, e.getMessage());
            }
        });
    }

    private String sendCommand(String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(slaveHost, slavePort), 3000);
            socket.setSoTimeout(5000);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer.println(command);
            String response = reader.readLine();
            writer.println("QUIT");
            return response;
        }
    }

    public void shutdown() {
        heartbeatScheduler.shutdownNow();
        replicationExecutor.shutdownNow();
    }

    public boolean isSlaveAlive() { return slaveAlive.get(); }
    public long getReplicationLagMs() { return replicationLagMs.get(); }
    public long getLastHeartbeatTime() { return lastHeartbeatTime.get(); }
    public long getFailedReplications() { return failedReplications.get(); }
    public String getSlaveHost() { return slaveHost; }
    public int getSlavePort() { return slavePort; }
}
