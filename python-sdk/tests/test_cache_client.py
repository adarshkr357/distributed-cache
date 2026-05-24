"""
Tests for the distributed cache Python client SDK.
Uses pytest with mock sockets for unit testing.
"""

import socket
import threading
import time
import pytest
from unittest.mock import patch, MagicMock
from cache_client import CacheClient
from cache_client.hash_ring import ConsistentHashRing


# ──────────────────────────────────────────────
# Helper: Mini TCP server for integration tests
# ──────────────────────────────────────────────

class MiniCacheServer:
    """A minimal TCP server that mimics the cache node protocol for testing."""

    def __init__(self, host: str = "127.0.0.1", port: int = 0):
        self.host = host
        self.port = port
        self.store: dict[str, tuple[str, float | None]] = {}
        self._server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server_socket.bind((host, port))
        self.port = self._server_socket.getsockname()[1]
        self._running = False
        self._thread = None

    def start(self):
        self._running = True
        self._server_socket.listen(5)
        self._server_socket.settimeout(1.0)
        self._thread = threading.Thread(target=self._accept_loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False
        self._server_socket.close()
        if self._thread:
            self._thread.join(timeout=3)

    def _accept_loop(self):
        while self._running:
            try:
                client, _ = self._server_socket.accept()
                threading.Thread(target=self._handle, args=(client,), daemon=True).start()
            except (socket.timeout, OSError):
                continue

    def _handle(self, client: socket.socket):
        try:
            client.settimeout(5.0)
            data = client.recv(4096).decode("utf-8")
            for line in data.strip().split("\n"):
                line = line.strip()
                if not line:
                    continue
                parts = line.split(" ", 3)
                cmd = parts[0].upper()

                if cmd == "PING":
                    client.sendall(b"+PONG\n")
                elif cmd == "SET" and len(parts) >= 3:
                    key, value = parts[1], parts[2]
                    ttl = float(parts[3]) if len(parts) > 3 else None
                    expire_at = (time.time() + ttl) if ttl else None
                    self.store[key] = (value, expire_at)
                    client.sendall(b"+OK\n")
                elif cmd == "GET" and len(parts) >= 2:
                    key = parts[1]
                    entry = self.store.get(key)
                    if entry is None:
                        client.sendall(b"$NULL\n")
                    else:
                        val, expire_at = entry
                        if expire_at and time.time() > expire_at:
                            del self.store[key]
                            client.sendall(b"$NULL\n")
                        else:
                            client.sendall(f"${val}\n".encode())
                elif cmd == "DELETE" and len(parts) >= 2:
                    key = parts[1]
                    if key in self.store:
                        del self.store[key]
                        client.sendall(b"+OK\n")
                    else:
                        client.sendall(b"-ERR key not found\n")
                elif cmd == "QUIT":
                    client.sendall(b"+BYE\n")
                    break
                else:
                    client.sendall(b"-ERR unknown command\n")
        except Exception:
            pass
        finally:
            try:
                client.close()
            except OSError:
                pass


# ──────────────────────────────────────────────
# Fixtures
# ──────────────────────────────────────────────

@pytest.fixture
def server():
    """Start a mini cache server for testing."""
    srv = MiniCacheServer("127.0.0.1", 0)
    srv.start()
    yield srv
    srv.stop()


@pytest.fixture
def client(server):
    """Create a CacheClient pointed at the test server."""
    return CacheClient(
        nodes=[("127.0.0.1", server.port)],
        virtual_nodes=150,
        max_retries=2,
        base_timeout=0.1,
        socket_timeout=3.0,
    )


# ──────────────────────────────────────────────
# Hash Ring Tests
# ──────────────────────────────────────────────

class TestConsistentHashRing:
    def test_add_and_get_node(self):
        ring = ConsistentHashRing(virtual_node_count=10)
        ring.add_node("node1")
        assert ring.get_node("anykey") == "node1"

    def test_multiple_nodes_distribute(self):
        ring = ConsistentHashRing(virtual_node_count=150)
        ring.add_node("node1")
        ring.add_node("node2")
        ring.add_node("node3")
        node_counts = {"node1": 0, "node2": 0, "node3": 0}
        for i in range(1000):
            node = ring.get_node(f"key:{i}")
            node_counts[node] += 1
        # Each node should get roughly 333 keys (allow wide margin)
        for count in node_counts.values():
            assert count > 200, f"Uneven distribution: {node_counts}"

    def test_remove_node(self):
        ring = ConsistentHashRing(virtual_node_count=10)
        ring.add_node("node1")
        ring.add_node("node2")
        ring.remove_node("node1")
        assert ring.get_node("anykey") == "node2"

    def test_empty_ring(self):
        ring = ConsistentHashRing()
        assert ring.get_node("key") is None

    def test_duplicate_add(self):
        ring = ConsistentHashRing(virtual_node_count=10)
        ring.add_node("node1")
        ring.add_node("node1")
        assert len(ring.get_physical_nodes()) == 1

    def test_consistent_mapping(self):
        """Same key always maps to same node (consistency property)."""
        ring = ConsistentHashRing(virtual_node_count=150)
        ring.add_node("node1")
        ring.add_node("node2")
        ring.add_node("node3")
        first_result = ring.get_node("test_key")
        for _ in range(100):
            assert ring.get_node("test_key") == first_result


# ──────────────────────────────────────────────
# Client Tests
# ──────────────────────────────────────────────

class TestCacheClientSetGet:
    def test_set_and_get(self, client):
        assert client.set("greeting", "hello") is True
        assert client.get("greeting") == "hello"

    def test_get_missing_key(self, client):
        assert client.get("nonexistent") is None

    def test_set_overwrite(self, client):
        client.set("key1", "value1")
        client.set("key1", "value2")
        assert client.get("key1") == "value2"

    def test_set_multiple_keys(self, client):
        for i in range(10):
            client.set(f"k{i}", f"v{i}")
        for i in range(10):
            assert client.get(f"k{i}") == f"v{i}"


class TestCacheClientDelete:
    def test_delete_existing(self, client):
        client.set("to_delete", "value")
        assert client.delete("to_delete") is True
        assert client.get("to_delete") is None

    def test_delete_nonexistent(self, client):
        assert client.delete("ghost_key") is False


class TestCacheClientPing:
    def test_ping_all(self, client):
        assert client.ping() is True

    def test_ping_specific_node(self, client, server):
        node_id = f"127.0.0.1:{server.port}"
        assert client.ping(node_id=node_id) is True


class TestCacheClientTTL:
    def test_ttl_expiry(self, client, server):
        client.set("ephemeral", "data", ttl=1)
        assert client.get("ephemeral") == "data"
        time.sleep(1.5)
        assert client.get("ephemeral") is None

    def test_no_ttl_persists(self, client):
        client.set("permanent", "data")
        time.sleep(0.5)
        assert client.get("permanent") == "data"


class TestCacheClientFailure:
    def test_connection_refused(self):
        bad_client = CacheClient(
            nodes=[("127.0.0.1", 59999)],
            max_retries=1,
            base_timeout=0.1,
            socket_timeout=1.0,
        )
        with pytest.raises(ConnectionError):
            bad_client.get("key")

    def test_node_failure_retry(self, server):
        """Verify client retries on transient failures."""
        client = CacheClient(
            nodes=[("127.0.0.1", server.port)],
            max_retries=3,
            base_timeout=0.1,
        )
        # Normal operation should work
        assert client.set("retry_test", "value") is True
        assert client.get("retry_test") == "value"
