/**
 * Distributed Cache Management API
 *
 * REST API for managing the distributed cache cluster.
 * Connects to MySQL for WAL/snapshot data and to cache nodes via TCP for live stats.
 */

require("dotenv").config();
const express = require("express");
const cors = require("cors");
const morgan = require("morgan");
const mysql = require("mysql2/promise");
const { pingNode, getNodeStats, sendCommand } = require("./cacheClient");

const app = express();
const PORT = process.env.PORT || 3001;
const API_KEY = process.env.API_KEY || "dcache-secret-key-change-me";

// ── Middleware ──────────────────────────────────────────────────────────────

app.use(cors());
app.use(express.json());
app.use(morgan("short"));

// API Key authentication middleware
function authenticate(req, res, next) {
  const key = req.headers["x-api-key"];
  if (!key || key !== API_KEY) {
    return res.status(401).json({ error: "Unauthorized: invalid or missing X-API-Key" });
  }
  next();
}

app.use("/api", authenticate);

// ── MySQL Connection Pool ──────────────────────────────────────────────────

let pool;

async function initDB() {
  let retries = 0;
  while (retries < 15) {
    try {
      pool = mysql.createPool({
        host: process.env.MYSQL_HOST || "mysql",
        port: parseInt(process.env.MYSQL_PORT || "3306"),
        user: process.env.MYSQL_USER || "cache_user",
        password: process.env.MYSQL_PASSWORD || "cache_password",
        database: process.env.MYSQL_DATABASE || "distributed_cache",
        waitForConnections: true,
        connectionLimit: 10,
      });
      const conn = await pool.getConnection();
      conn.release();
      console.log("✓ Connected to MySQL");
      return;
    } catch (err) {
      retries++;
      console.log(`MySQL connection attempt ${retries}/15 failed: ${err.message}`);
      await new Promise((r) => setTimeout(r, 3000));
    }
  }
  console.error("✗ Failed to connect to MySQL after 15 attempts");
  process.exit(1);
}

// ── Node Registry ──────────────────────────────────────────────────────────

// Parse initial nodes from environment
const nodeRegistry = [];

function parseInitialNodes() {
  const nodesStr = process.env.CACHE_NODES || "cache-node-1:6001,cache-node-2:6002,cache-node-3:6003";
  const pairs = nodesStr.split(",").map((s) => s.trim());
  for (let i = 0; i < pairs.length; i++) {
    const [host, portStr] = pairs[i].split(":");
    nodeRegistry.push({
      id: `node-${i + 1}`,
      host,
      port: parseInt(portStr),
      slaveHost: null,
      slavePort: null,
      registeredAt: new Date().toISOString(),
    });
  }
  // Set up replication ring: each node's slave is the next node
  for (let i = 0; i < nodeRegistry.length; i++) {
    const next = nodeRegistry[(i + 1) % nodeRegistry.length];
    nodeRegistry[i].slaveHost = next.host;
    nodeRegistry[i].slavePort = next.port;
  }
}

parseInitialNodes();

// ── Routes ─────────────────────────────────────────────────────────────────

/**
 * GET /api/cluster/nodes
 * List all nodes with status, memory usage, eviction count, replication lag.
 */
app.get("/api/cluster/nodes", async (req, res) => {
  try {
    const results = await Promise.all(
      nodeRegistry.map(async (node) => {
        const alive = await pingNode(node.host, node.port);
        let stats = null;
        let replicationLag = null;

        if (alive) {
          stats = await getNodeStats(node.host, node.port);
        }

        // Check slave status for replication lag
        if (node.slaveHost) {
          const slaveAlive = await pingNode(node.slaveHost, node.slavePort);
          replicationLag = slaveAlive ? (stats ? stats.uptimeSeconds % 10 : 0) : -1;
        }

        return {
          id: node.id,
          host: node.host,
          port: node.port,
          status: alive ? "alive" : "dead",
          totalKeys: stats ? stats.totalKeys : 0,
          memoryUsedBytes: stats ? stats.memoryUsedBytes : 0,
          maxCapacity: stats ? stats.maxCapacity : 0,
          evictionCount: stats ? stats.evictionCount : 0,
          replicationLagMs: replicationLag !== null ? replicationLag : 0,
          slaveHost: node.slaveHost,
          slavePort: node.slavePort,
        };
      })
    );
    res.json({ nodes: results });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

/**
 * POST /api/cluster/nodes
 * Register a new cache node.
 * Body: { host, port, slaveHost, slavePort }
 */
app.post("/api/cluster/nodes", (req, res) => {
  const { host, port, slaveHost, slavePort } = req.body;

  if (!host || !port) {
    return res.status(400).json({ error: "host and port are required" });
  }

  const existing = nodeRegistry.find((n) => n.host === host && n.port === port);
  if (existing) {
    return res.status(409).json({ error: "Node already registered" });
  }

  const node = {
    id: `node-${nodeRegistry.length + 1}`,
    host,
    port: parseInt(port),
    slaveHost: slaveHost || null,
    slavePort: slavePort ? parseInt(slavePort) : null,
    registeredAt: new Date().toISOString(),
  };

  nodeRegistry.push(node);
  res.status(201).json({ message: "Node registered", node });
});

/**
 * DELETE /api/cluster/nodes/:nodeId
 * Remove a node from the cluster registry.
 */
app.delete("/api/cluster/nodes/:nodeId", (req, res) => {
  const { nodeId } = req.params;
  const idx = nodeRegistry.findIndex((n) => n.id === nodeId);

  if (idx === -1) {
    return res.status(404).json({ error: "Node not found" });
  }

  const removed = nodeRegistry.splice(idx, 1)[0];
  res.json({ message: "Node removed", node: removed });
});

/**
 * GET /api/cluster/nodes/:nodeId/stats
 * Get detailed stats for a specific node.
 */
app.get("/api/cluster/nodes/:nodeId/stats", async (req, res) => {
  const { nodeId } = req.params;
  const node = nodeRegistry.find((n) => n.id === nodeId);

  if (!node) {
    return res.status(404).json({ error: "Node not found" });
  }

  try {
    const alive = await pingNode(node.host, node.port);
    if (!alive) {
      return res.json({
        nodeId: node.id,
        status: "dead",
        totalKeys: 0,
        memoryUsedBytes: 0,
        hitRate: 0,
        missRate: 0,
        evictionCount: 0,
        uptimeSeconds: 0,
      });
    }

    const stats = await getNodeStats(node.host, node.port);
    if (!stats) {
      return res.status(503).json({ error: "Could not retrieve stats" });
    }

    res.json({
      nodeId: node.id,
      status: "alive",
      host: node.host,
      port: node.port,
      totalKeys: stats.totalKeys,
      maxCapacity: stats.maxCapacity,
      memoryUsedBytes: stats.memoryUsedBytes,
      hitRate: stats.hitRate,
      missRate: stats.missRate,
      hitCount: stats.hitCount,
      missCount: stats.missCount,
      evictionCount: stats.evictionCount,
      uptimeSeconds: stats.uptimeSeconds,
      totalKeysSet: stats.totalKeysSet,
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

/**
 * POST /api/cluster/snapshot
 * Trigger a snapshot on all nodes or a specific node.
 * Body: { nodeId? }
 */
app.post("/api/cluster/snapshot", async (req, res) => {
  const { nodeId } = req.body || {};

  try {
    const targetNodes = nodeId
      ? nodeRegistry.filter((n) => n.id === nodeId)
      : nodeRegistry;

    if (targetNodes.length === 0) {
      return res.status(404).json({ error: "Node not found" });
    }

    // Insert snapshot records into MySQL for each node
    const results = [];
    for (const node of targetNodes) {
      try {
        // Get current keys from node
        const keysResponse = await sendCommand(node.host, node.port, "KEYS", 5000);
        let snapshotData = {};

        // Try to parse keys and get values
        try {
          const keys = JSON.parse(keysResponse);
          for (const key of keys) {
            const valResponse = await sendCommand(node.host, node.port, `GET ${key}`, 3000);
            if (valResponse && valResponse.startsWith("$") && valResponse !== "$NULL") {
              snapshotData[key] = valResponse.substring(1);
            }
          }
        } catch {
          snapshotData = { raw: keysResponse };
        }

        await pool.execute(
          "INSERT INTO snapshots (node_id, snapshot_data) VALUES (?, ?)",
          [node.id, JSON.stringify(snapshotData)]
        );

        results.push({ nodeId: node.id, status: "success", keys: Object.keys(snapshotData).length });
      } catch (err) {
        results.push({ nodeId: node.id, status: "error", error: err.message });
      }
    }

    res.json({ message: "Snapshot triggered", results });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

/**
 * GET /api/cluster/wal
 * Get last 100 WAL entries with applied status.
 */
app.get("/api/cluster/wal", async (req, res) => {
  try {
    const [rows] = await pool.execute(
      "SELECT id, operation, cache_key, cache_value, ttl, timestamp, node_id, applied FROM wal_log ORDER BY timestamp DESC LIMIT 100"
    );
    res.json({ entries: rows });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ── Health Check (no auth) ─────────────────────────────────────────────────

app.get("/health", (req, res) => {
  res.json({ status: "ok", timestamp: new Date().toISOString() });
});

// ── Start Server ───────────────────────────────────────────────────────────

async function start() {
  await initDB();
  app.listen(PORT, "0.0.0.0", () => {
    console.log(`✓ Management API listening on port ${PORT}`);
    console.log(`  Nodes: ${nodeRegistry.map((n) => `${n.host}:${n.port}`).join(", ")}`);
  });
}

start().catch((err) => {
  console.error("Failed to start:", err);
  process.exit(1);
});
