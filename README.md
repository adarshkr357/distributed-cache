<p align="center">
  <img src="https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" />
  <img src="https://img.shields.io/badge/MySQL-8.1-4479A1?style=for-the-badge&logo=mysql&logoColor=white" />
  <img src="https://img.shields.io/badge/Node.js-20-339933?style=for-the-badge&logo=nodedotjs&logoColor=white" />
  <img src="https://img.shields.io/badge/React-18-61DAFB?style=for-the-badge&logo=react&logoColor=black" />
  <img src="https://img.shields.io/badge/Python-3.11-3776AB?style=for-the-badge&logo=python&logoColor=white" />
  <img src="https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white" />
</p>

# 🚀 Distributed In-Memory Cache

A **production-ready, horizontally scalable** distributed caching system built from scratch. This project implements the same core concepts used by Redis, Memcached, and Amazon ElastiCache - consistent hashing, master-slave replication, write-ahead logging, automatic failover, and LRU eviction - as a fully containerized, observable microservice cluster.

---

## 📋 Table of Contents

- [Why This Exists](#-why-this-exists)
- [Architecture](#-architecture)
- [Tech Stack](#-tech-stack)
- [Features](#-features)
- [Project Structure](#-project-structure)
- [Getting Started](#-getting-started)
- [Python SDK Usage](#-python-sdk-usage)
- [Management API Reference](#-management-api-reference)
- [How It Works](#-how-it-works)
- [Real-World Use Cases](#-real-world-use-cases)
- [Configuration](#-configuration)
- [Testing](#-testing)
- [Contributing](#-contributing)
- [License](#-license)

---

## 🎯 Why This Exists

### The Problem

In any high-traffic application, the database is the bottleneck. Every page load, API call, or user action triggers SQL queries that hit spinning disks or SSDs. Under heavy load, this causes:

- **Response times spike** from 5ms to 500ms+
- **Database connections exhaust**, causing cascading failures
- **Users experience timeouts** during peak traffic
- **Infrastructure costs skyrocket** as you scale the database vertically

### The Solution

This project places a **distributed in-memory layer** between your application and your database. Frequently accessed data is stored in RAM across multiple nodes, reducing database load by up to **95%** and cutting response times to **sub-millisecond**.

But unlike a simple in-memory dictionary, this system handles the hard problems:

| Problem | How We Solve It |
|---|---|
| **Single server RAM limit** | Consistent hashing distributes data across N nodes |
| **Server crashes lose data** | Write-Ahead Log (WAL) persists every mutation to MySQL |
| **Adding/removing nodes breaks cache** | Consistent hashing minimizes key redistribution to ~1/N |
| **Node failure = data loss** | Master-slave replication keeps hot standby copies |
| **Undetected failures** | Heartbeat monitoring auto-detects and reroutes in <15 seconds |
| **Memory overflow** | LRU eviction policy automatically purges least-used keys |
| **No visibility** | Real-time React dashboard with topology maps and metrics |

---

## 🏗 Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CLIENT LAYER                                │
│                                                                     │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                    Python Client SDK                         │   │
│   │        Consistent Hash Ring  ·  TCP Protocol  ·  Retry       │   │
│   └──────────┬──────────────────┬──────────────────┬────────────┘   │
│              │                  │                  │                 │
└──────────────┼──────────────────┼──────────────────┼─────────────────┘
               │                  │                  │
               ▼                  ▼                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        CACHE LAYER (Java 17)                        │
│                                                                     │
│   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐         │
│   │  Node 1       │    │  Node 2       │    │  Node 3       │       │
│   │  :6001        │───▶│  :6002        │───▶│  :6003        │       │
│   │               │    │               │    │               │       │
│   │ ┌───────────┐ │    │ ┌───────────┐ │    │ ┌───────────┐ │       │
│   │ │ LRU Store │ │    │ │ LRU Store │ │    │ │ LRU Store │ │       │
│   │ │ (HashMap) │ │    │ │ (HashMap) │ │    │ │ (HashMap) │ │       │
│   │ └───────────┘ │    │ └───────────┘ │    │ └───────────┘ │       │
│   │ ┌───────────┐ │    │ ┌───────────┐ │    │ ┌───────────┐ │       │
│   │ │ TCP Server│ │    │ │ TCP Server│ │    │ │ TCP Server│ │       │
│   │ └───────────┘ │    │ └───────────┘ │    │ └───────────┘ │       │
│   └──────┬───────┘    └──────┬───────┘    └──────┬───────┘         │
│          │                   │                   │                   │
└──────────┼───────────────────┼───────────────────┼───────────────────┘
           │                   │                   │
           ▼                   ▼                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     PERSISTENCE LAYER (MySQL 8.1)                   │
│                                                                     │
│   ┌─────────────────────┐    ┌─────────────────────┐               │
│   │  wal_log             │    │  snapshots            │             │
│   │  ─────────           │    │  ──────────           │             │
│   │  Every SET/DELETE    │    │  Full state dumps     │             │
│   │  is logged before    │    │  for fast recovery    │             │
│   │  applying to RAM     │    │  on node restart      │             │
│   └─────────────────────┘    └─────────────────────┘               │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
           ▲                                          ▲
           │                                          │
┌──────────┴──────────────────────────────────────────┴────────────────┐
│                    MANAGEMENT LAYER                                   │
│                                                                       │
│   ┌───────────────────────┐    ┌───────────────────────┐             │
│   │  Node.js REST API      │    │  React Dashboard       │           │
│   │  :3001                 │◄───│  :3000                 │           │
│   │                        │    │                        │           │
│   │  • Node registration   │    │  • Live topology map   │           │
│   │  • Cluster stats       │    │  • Memory usage charts │           │
│   │  • Snapshot triggers   │    │  • WAL log viewer      │           │
│   │  • WAL inspection      │    │  • Replication lag     │           │
│   │  • API key auth        │    │  • Node management     │           │
│   └───────────────────────┘    └───────────────────────┘             │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

---

## ⚙ Tech Stack

| Layer | Technology | Purpose |
|---|---|---|
| **Cache Core** | Java 17 | High-performance TCP cache nodes with LRU eviction |
| **Persistence** | MySQL 8.1 | Write-Ahead Log and snapshot storage for durability |
| **Client SDK** | Python 3.11 | Developer-friendly client with consistent hashing built-in |
| **Management API** | Node.js 20 + Express | RESTful admin interface with API key authentication |
| **Dashboard** | React 18 + Recharts | Real-time monitoring UI with live topology visualization |
| **Orchestration** | Docker Compose | Single-command deployment of the entire cluster |

---

## ✨ Features

### Core Cache Engine
- **Consistent Hash Ring** - MD5-based hashing with **150 virtual nodes** per physical node for uniform key distribution
- **LRU Eviction** - Automatic eviction of least-recently-used keys when memory capacity is reached, powered by Java's `LinkedHashMap`
- **TTL Support** - Per-key time-to-live with background cleanup threads
- **TCP Protocol** - Lightweight custom text protocol: `GET`, `SET`, `DELETE`, `PING`, `STATS`

### Durability & Recovery
- **Write-Ahead Log (WAL)** - Every mutation is synchronously persisted to MySQL *before* being applied to memory, guaranteeing zero data loss on crash
- **Snapshot & Replay** - Periodic full-state snapshots to MySQL. On restart, the node loads the latest snapshot and replays only the WAL entries after it, achieving fast recovery
- **Crash Recovery** - Fully automatic: node boots → loads snapshot → replays WAL → resumes serving in seconds

### High Availability
- **Master-Slave Replication** - Asynchronous replication of all writes to a designated slave node
- **Heartbeat Monitoring** - 5-second heartbeat pings between master and slave
- **Automatic Failover** - If a master misses 3 consecutive heartbeats (15s), the hash ring is automatically rebalanced and traffic is rerouted
- **Consistent Hashing Rebalance** - Only ~1/N keys are redistributed when a node joins or leaves the cluster

### Observability
- **React Dashboard** - Dark-themed, glassmorphic monitoring UI with live auto-refresh
- **Topology Visualization** - See all nodes, their status, and replication links in real-time
- **Memory Usage Charts** - Historical memory consumption graphed with Recharts
- **WAL Log Viewer** - Inspect the write-ahead log directly from the dashboard
- **Replication Lag Monitoring** - Track how far behind each slave is from its master

### Security
- **API Key Authentication** - All management API endpoints are protected by an `X-API-Key` header
- **Environment-Based Configuration** - All secrets (DB passwords, API keys) are injected via environment variables, never hardcoded

---

## 📁 Project Structure

```
distributed-cache/
│
├── java-cache/                          # Cache core (Java 17)
│   ├── src/main/java/com/cache/
│   │   ├── CacheCluster.java            # Cluster orchestrator & entry point
│   │   ├── CacheNode.java               # TCP server + LRU store + command handler
│   │   ├── ConsistentHashRing.java      # MD5 consistent hashing with virtual nodes
│   │   ├── ReplicationManager.java      # Async master→slave replication + heartbeats
│   │   ├── FailoverManager.java         # Dead node detection & ring rebalancing
│   │   ├── WALWriter.java               # Write-ahead log persistence to MySQL
│   │   └── SnapshotManager.java         # State snapshot & WAL replay recovery
│   ├── Dockerfile                       # Multi-stage build: Maven → JRE Alpine
│   ├── pom.xml                          # Maven config with MySQL, Gson, SLF4J
│   └── cluster.properties.example       # Sample cluster topology config
│
├── python-sdk/                          # Client SDK (Python 3.11)
│   ├── cache_client/
│   │   ├── __init__.py
│   │   ├── client.py                    # CacheClient with retry & consistent hashing
│   │   └── hash_ring.py                 # MD5 hash ring (mirrors Java implementation)
│   ├── tests/
│   │   └── test_cache_client.py         # 18 pytest tests covering all operations
│   ├── setup.py                         # pip-installable package
│   └── README.md                        # SDK documentation
│
├── management-api/                      # REST API (Node.js 20 + Express)
│   ├── src/
│   │   └── index.js                     # Express server with all routes
│   ├── Dockerfile
│   └── package.json
│
├── dashboard/                           # Monitoring UI (React 18 + Vite)
│   ├── src/
│   │   ├── App.jsx                      # Main dashboard layout
│   │   ├── App.css                      # Dark glassmorphic theme
│   │   └── main.jsx                     # React entry point
│   ├── Dockerfile                       # Vite build → Nginx static serve
│   ├── nginx.conf                       # Reverse proxy config for API
│   └── vite.config.js
│
├── db/                                  # Database schema
│   ├── migrations/
│   │   ├── 001_create_wal_log.sql       # WAL table schema
│   │   └── 002_create_snapshots.sql     # Snapshots table schema
│   └── seed.sql                         # DB initialization script
│
├── docker-compose.yml                   # Full cluster orchestration
├── .env.example                         # Environment variable template
├── .gitignore
└── README.md                            # ← You are here
```

---

## 🚀 Getting Started

### Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (v20+)
- [Docker Compose](https://docs.docker.com/compose/) (v2+)
- [Python 3.8+](https://www.python.org/) (for the SDK, optional)

### 1. Clone the Repository

```bash
git clone https://github.com/adarshkr357/distributed-cache.git
cd distributed-cache
```

### 2. Configure Environment

```bash
cp .env.example .env
# Edit .env to customize passwords, API key, and node capacity
```

### 3. Launch the Cluster

```bash
docker-compose up -d --build
```

This single command spins up:
- **1× MySQL 8.1** database with WAL and snapshot tables
- **3× Java cache nodes** on ports 6001, 6002, 6003
- **1× Node.js management API** on port 3001
- **1× React dashboard** on port 3000

### 4. Verify Everything Is Running

```bash
docker-compose ps
```

Expected output:
```
NAME                    SERVICE          STATUS          PORTS
cache-node-1-1          cache-node-1     Up              0.0.0.0:6001→6001/tcp
cache-node-2-1          cache-node-2     Up              0.0.0.0:6002→6002/tcp
cache-node-3-1          cache-node-3     Up              0.0.0.0:6003→6003/tcp
management-api-1        management-api   Up              0.0.0.0:3001→3001/tcp
dashboard-1             dashboard        Up              0.0.0.0:3000→3000/tcp
mysql-1                 mysql            Up (healthy)    0.0.0.0:3306→3306/tcp
```

### 5. Open the Dashboard

Navigate to **[http://localhost:3000](http://localhost:3000)** to see the live monitoring dashboard.

---

## 🐍 Python SDK Usage

### Installation

```bash
cd python-sdk
pip install -e .
```

### Quick Start

```python
from cache_client import CacheClient

# Connect to the cluster
client = CacheClient([
    ("localhost", 6001),
    ("localhost", 6002),
    ("localhost", 6003),
])

# Store a value with 1-hour TTL
client.set("user:1001", '{"name": "Adarsh", "role": "admin"}', ttl=3600)

# Retrieve it - the SDK automatically routes to the correct node
user = client.get("user:1001")
print(user)  # '{"name": "Adarsh", "role": "admin"}'

# Delete a key
client.delete("user:1001")

# Health check all nodes
results = client.ping()
print(results)  # {('localhost', 6001): True, ('localhost', 6002): True, ...}
```

### How the SDK Routes Keys

The Python SDK contains an identical implementation of the Java consistent hash ring. When you call `client.set("user:1001", ...)`, the SDK:

1. Computes `MD5("user:1001")` → deterministic 128-bit hash
2. Maps that hash onto the ring of 450 virtual nodes (150 per physical node)
3. Finds the nearest node clockwise on the ring
4. Opens a TCP socket directly to that node
5. Sends `SET user:1001 3600 {"name": "Adarsh"}` over the wire
6. If the node is down, retries with exponential backoff (up to 3 attempts)

---

## 📡 Management API Reference

All endpoints require the `X-API-Key` header for authentication.

```bash
# Set your API key (default from .env.example)
export API_KEY="your-secure-api-key-here"
```

### Cluster Status

```bash
# List all nodes with live stats
curl -H "X-API-Key: $API_KEY" http://localhost:3001/api/cluster/nodes
```

**Response:**
```json
{
  "nodes": [
    {
      "id": "node-1",
      "host": "cache-node-1",
      "port": 6001,
      "status": "alive",
      "totalKeys": 142,
      "memoryUsedBytes": 28400,
      "maxCapacity": 10000,
      "evictionCount": 0,
      "uptimeSeconds": 3621
    }
  ]
}
```

### Node Management

```bash
# Register a new node
curl -X POST -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"host": "cache-node-4", "port": 6004}' \
  http://localhost:3001/api/cluster/nodes

# Remove a node
curl -X DELETE -H "X-API-Key: $API_KEY" \
  http://localhost:3001/api/cluster/nodes/node-1
```

### Persistence Operations

```bash
# Trigger a snapshot of all nodes
curl -X POST -H "X-API-Key: $API_KEY" \
  http://localhost:3001/api/cluster/snapshot

# Trigger a snapshot of a specific node
curl -X POST -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"nodeId": "node-1"}' \
  http://localhost:3001/api/cluster/snapshot

# View the Write-Ahead Log
curl -H "X-API-Key: $API_KEY" \
  http://localhost:3001/api/cluster/wal
```

### Health & Metrics

```bash
# Get detailed stats for a specific node
curl -H "X-API-Key: $API_KEY" \
  http://localhost:3001/api/cluster/nodes/node-1/stats

# Check replication lag across the cluster
curl -H "X-API-Key: $API_KEY" \
  http://localhost:3001/api/cluster/replication
```

---

## 🔬 How It Works

### Consistent Hashing

Traditional hashing (`hash(key) % N`) breaks catastrophically when you add or remove a node - nearly every key maps to a different server, causing a **thundering herd** of cache misses that can take down your database.

Consistent hashing solves this by mapping both **keys and nodes** onto a virtual ring (0 to 2³²):

```
                        Node A (v-node 42)
                      ╱
              ●──────●──────●
           ╱                    ╲
     ●───●                        ●───●
   ╱   Node C (v-node 7)              ╲  Node B (v-node 98)
  ●                                     ●
   ╲                                   ╱
     ●───●                        ●───●
           ╲                    ╱
              ●──────●──────●
                        ↑
                   key "user:42"
                   maps here →
                   served by Node A
```

When **Node B dies**, only the keys between Node C and Node B move to Node A. **Every other key stays exactly where it is.** With 150 virtual nodes per physical node, the distribution is nearly perfectly uniform.

### Write-Ahead Log (WAL)

The WAL guarantees **durability** - no acknowledged write is ever lost, even during a crash:

```
Client              Cache Node              MySQL (WAL)
  │                     │                       │
  │── SET key val ──▶  │                       │
  │                     │── INSERT wal_log ──▶  │
  │                     │                       │── fsync ──▶ disk
  │                     │◀── OK ────────────── │
  │                     │                       │
  │                     │── apply to RAM ──▶   │
  │◀── STORED ──────── │                       │
```

The write is **persisted to MySQL before it touches RAM**. If the node crashes between the MySQL write and the RAM write, the WAL replay on restart will re-apply it.

### Crash Recovery Sequence

```
Node Restart
    │
    ▼
┌──────────────────────┐
│ Connect to MySQL     │
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│ Load latest snapshot │◀── Full HashMap state as JSON
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│ Query WAL entries    │◀── WHERE timestamp > snapshot_time
│ after snapshot       │        AND node_id = this_node
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│ Replay WAL entries   │◀── Apply SET/DELETE sequentially
│ into HashMap         │
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│ Start TCP server     │◀── Ready to serve requests
│ Resume heartbeats    │
└──────────────────────┘
```

### Automatic Failover

```
Normal Operation:
  Master (Node 1) ──heartbeat──▶ Slave (Node 2)    ✓ every 5s
  Master (Node 1) ──heartbeat──▶ Slave (Node 2)    ✓ every 5s
  Master (Node 1) ──heartbeat──▶ Slave (Node 2)    ✓ every 5s

Node 1 Crashes:
  Master (Node 1) ──heartbeat──▶ Slave (Node 2)    ✗ MISS #1
  Master (Node 1) ──heartbeat──▶ Slave (Node 2)    ✗ MISS #2
  Master (Node 1) ──heartbeat──▶ Slave (Node 2)    ✗ MISS #3

  FailoverManager detects 3 missed heartbeats (15 seconds):
    → Removes Node 1 from the consistent hash ring
    → Node 2 (slave) now serves Node 1's key range
    → Python SDK clients automatically route to Node 2
    → Zero downtime for the application
```

---

## 🌍 Real-World Use Cases

### 1. User Session Storage
**Problem:** Storing millions of active login sessions in a SQL database causes query bottlenecks during peak hours.
**Solution:** Store session tokens in the cache with a 30-minute TTL. Authentication checks drop from 15ms (DB) to 0.1ms (cache).

```python
# Store session on login
client.set(f"session:{token}", '{"userId": 1001, "role": "admin"}', ttl=1800)

# Validate session on every request
session = client.get(f"session:{token}")
if session is None:
    return redirect("/login")
```

### 2. API Rate Limiting
**Problem:** Preventing abuse by limiting API calls per user per minute without hammering the database.
**Solution:** Use atomic counters in the cache to track request counts with a 60-second TTL.

```python
key = f"ratelimit:{user_id}:{current_minute}"
count = client.get(key)
if count and int(count) > 100:
    return {"error": "Rate limit exceeded"}, 429
client.set(key, str(int(count or 0) + 1), ttl=60)
```

### 3. Database Query Caching
**Problem:** Complex JOIN queries take 200ms+ and are executed thousands of times per second with identical parameters.
**Solution:** Cache the query result with a short TTL, reducing DB load by 95%.

```python
cache_key = f"query:{hash(sql_query + str(params))}"
result = client.get(cache_key)
if result is None:
    result = database.execute(sql_query, params)
    client.set(cache_key, json.dumps(result), ttl=300)  # 5-min cache
```

### 4. E-Commerce Shopping Carts
**Problem:** Shopping cart data needs to persist across page loads during flash sales with 100K+ concurrent users.
**Solution:** Store cart data in the cache with replication, so even if a node dies, the cart survives on the slave.

```python
client.set(f"cart:{user_id}", json.dumps(cart_items), ttl=86400)  # 24h
```

### 5. Real-Time Leaderboards
**Problem:** Gaming leaderboards need to be updated and read by thousands of concurrent players with sub-millisecond latency.
**Solution:** Store scores in the cache, with periodic snapshots ensuring persistence.

```python
client.set(f"score:{player_id}", str(new_score))
```

### 6. Feature Flags & Configuration
**Problem:** Rolling out features to a percentage of users requires instant propagation without redeployments.
**Solution:** Store feature flags in the cache with no TTL. Update them via the management API and they propagate instantly.

```python
flags = json.loads(client.get("feature_flags") or "{}")
if flags.get("dark_mode_enabled"):
    enable_dark_mode()
```

---

## ⚙ Configuration

### Environment Variables

Copy `.env.example` to `.env` and customize:

| Variable | Default | Description |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | *(required)* | MySQL root password |
| `MYSQL_DATABASE` | `distributed_cache` | Database name |
| `MYSQL_USER` | *(required)* | Application database user |
| `MYSQL_PASSWORD` | *(required)* | Application database password |
| `API_KEY` | *(required)* | Secret key for management API auth |
| `MAX_CAPACITY` | `10000` | Max keys per cache node before LRU eviction |
| `CACHE_NODES` | `cache-node-1:6001,...` | Comma-separated list of cache node addresses |

### Scaling the Cluster

To add a 4th cache node, add this to `docker-compose.yml`:

```yaml
cache-node-4:
  build:
    context: ./java-cache
    dockerfile: Dockerfile
  environment:
    - CACHE_NODE_ID=4
    - MYSQL_URL=jdbc:mysql://mysql:3306/${MYSQL_DATABASE}
    - MYSQL_USER=${MYSQL_USER}
    - MYSQL_PASSWORD=${MYSQL_PASSWORD}
    - MAX_CAPACITY=${MAX_CAPACITY:-10000}
  ports:
    - "6004:6004"
  depends_on:
    mysql:
      condition: service_healthy
  networks:
    - cache_net
```

Then update `CACHE_NODES` in the management-api environment and run:

```bash
docker-compose up -d --build cache-node-4
```

---

## 🧪 Testing

### Python SDK Tests

The SDK includes 18 comprehensive tests covering consistent hashing, CRUD operations, TTL behavior, and failure scenarios:

```bash
cd python-sdk
pip install -e .
pip install pytest
pytest tests/ -v
```

```
tests/test_cache_client.py::TestConsistentHashRing::test_add_and_get_node        PASSED
tests/test_cache_client.py::TestConsistentHashRing::test_multiple_nodes           PASSED
tests/test_cache_client.py::TestConsistentHashRing::test_remove_node              PASSED
tests/test_cache_client.py::TestConsistentHashRing::test_consistent_mapping       PASSED
tests/test_cache_client.py::TestCacheClientSetGet::test_set_and_get               PASSED
tests/test_cache_client.py::TestCacheClientSetGet::test_get_missing_key           PASSED
tests/test_cache_client.py::TestCacheClientDelete::test_delete_existing           PASSED
tests/test_cache_client.py::TestCacheClientPing::test_ping_all                    PASSED
tests/test_cache_client.py::TestCacheClientTTL::test_ttl_expiry                   PASSED
tests/test_cache_client.py::TestCacheClientFailure::test_connection_refused       PASSED
... (18 passed)
```

### Integration Test (with cluster running)

```bash
python -c "
from cache_client import CacheClient
client = CacheClient([('localhost', 6001), ('localhost', 6002), ('localhost', 6003)])
assert client.set('test', 'hello')
assert client.get('test') == 'hello'
assert client.delete('test')
assert client.get('test') is None
print('All integration tests passed!')
"
```

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

<p align="center">
  Built with ☕ Java, 🐍 Python, 🟢 Node.js, ⚛️ React, and 🐳 Docker
</p>
