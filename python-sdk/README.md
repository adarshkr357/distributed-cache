# Distributed Cache Python Client SDK

A Python client for the distributed in-memory cache system with consistent hashing, automatic retries, and exponential backoff.

## Installation

```bash
pip install distributed-cache-client
```

Or install from source:

```bash
git clone https://github.com/example/distributed-cache.git
cd distributed-cache/python-sdk
pip install -e ".[dev]"
```

## Quick Start

```python
from cache_client import CacheClient

# Connect to a 3-node cluster
client = CacheClient(nodes=[
    ("localhost", 6001),
    ("localhost", 6002),
    ("localhost", 6003),
])

# Set a value with a 1-hour TTL
client.set("user:1001", '{"name": "Alice"}', ttl=3600)

# Get a value
value = client.get("user:1001")
print(value)  # '{"name": "Alice"}'

# Delete a key
client.delete("user:1001")

# Ping a specific node
alive = client.ping(node_id="localhost:6001")
```

## API Reference

### `CacheClient(nodes, virtual_nodes=150, max_retries=3, base_timeout=1.0, socket_timeout=5.0)`

- **nodes**: List of `(host, port)` tuples for cache nodes
- **virtual_nodes**: Virtual nodes per physical node on the hash ring (default: 150)
- **max_retries**: Max retry attempts on connection failure (default: 3)
- **base_timeout**: Base timeout for exponential backoff in seconds (default: 1.0)
- **socket_timeout**: TCP socket timeout in seconds (default: 5.0)

### Methods

| Method | Description | Returns |
|--------|-------------|---------|
| `get(key)` | Get value by key | `str` or `None` |
| `set(key, value, ttl=None)` | Set key-value pair with optional TTL | `bool` |
| `delete(key)` | Delete a key | `bool` |
| `ping(node_id=None)` | Check if node(s) are alive | `bool` |
| `stats(node_id)` | Get node statistics | `dict` or `None` |
| `add_node(host, port)` | Add a node to the hash ring | `None` |
| `remove_node(host, port)` | Remove a node from the hash ring | `None` |

## Running Tests

```bash
cd python-sdk
pip install -e ".[dev]"
pytest tests/ -v
```

## License

MIT
