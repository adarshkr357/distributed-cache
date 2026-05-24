"""
CacheClient — Python client for the distributed in-memory cache.

Uses consistent hashing to route keys to the correct cache node.
Connects via TCP socket with retry logic and exponential backoff.
"""

import socket
import time
import logging
from typing import Optional
from .hash_ring import ConsistentHashRing

logger = logging.getLogger(__name__)


class CacheClient:
    """
    Client for the distributed cache system.

    Usage:
        nodes = [("localhost", 6001), ("localhost", 6002), ("localhost", 6003)]
        client = CacheClient(nodes)
        client.set("user:1", "Alice", ttl=3600)
        value = client.get("user:1")
        client.delete("user:1")
    """

    def __init__(
        self,
        nodes: list[tuple[str, int]],
        virtual_nodes: int = 150,
        max_retries: int = 3,
        base_timeout: float = 1.0,
        socket_timeout: float = 5.0,
    ):
        """
        Initialize the cache client.

        Args:
            nodes: List of (host, port) tuples for cache nodes.
            virtual_nodes: Number of virtual nodes per physical node on the hash ring.
            max_retries: Maximum number of retry attempts on connection failure.
            base_timeout: Base timeout in seconds for exponential backoff.
            socket_timeout: Socket timeout in seconds for TCP connections.
        """
        self.nodes = {f"{host}:{port}": (host, port) for host, port in nodes}
        self.max_retries = max_retries
        self.base_timeout = base_timeout
        self.socket_timeout = socket_timeout

        self.hash_ring = ConsistentHashRing(virtual_nodes)
        for node_id in self.nodes:
            self.hash_ring.add_node(node_id)

    def _get_node_for_key(self, key: str) -> tuple[str, int]:
        """Find the node responsible for a given key using consistent hashing."""
        node_id = self.hash_ring.get_node(key)
        if node_id is None:
            raise ConnectionError("No nodes available in the hash ring")
        return self.nodes[node_id]

    def _send_command(self, host: str, port: int, command: str) -> str:
        """
        Send a command to a cache node via TCP with retry logic.

        Uses exponential backoff: base_timeout * 2^attempt seconds between retries.
        """
        last_error = None

        for attempt in range(self.max_retries):
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
                    sock.settimeout(self.socket_timeout)
                    sock.connect((host, port))

                    # Send command
                    sock.sendall((command + "\n").encode("utf-8"))

                    # Read response
                    response = b""
                    while True:
                        chunk = sock.recv(4096)
                        if not chunk:
                            break
                        response += chunk
                        if b"\n" in response:
                            break

                    # Send QUIT
                    try:
                        sock.sendall(b"QUIT\n")
                    except OSError:
                        pass

                    return response.decode("utf-8").strip()

            except (socket.error, socket.timeout, ConnectionRefusedError, OSError) as e:
                last_error = e
                if attempt < self.max_retries - 1:
                    wait_time = self.base_timeout * (2 ** attempt)
                    logger.warning(
                        "Connection to %s:%d failed (attempt %d/%d): %s. Retrying in %.1fs",
                        host, port, attempt + 1, self.max_retries, e, wait_time,
                    )
                    time.sleep(wait_time)

        raise ConnectionError(
            f"Failed to connect to {host}:{port} after {self.max_retries} attempts: {last_error}"
        )

    def get(self, key: str) -> Optional[str]:
        """
        Get a value from the cache.

        Args:
            key: The cache key to look up.

        Returns:
            The cached value, or None if the key does not exist or has expired.
        """
        host, port = self._get_node_for_key(key)
        response = self._send_command(host, port, f"GET {key}")

        if response == "$NULL":
            return None
        if response.startswith("$"):
            return response[1:]
        if response.startswith("-ERR"):
            raise RuntimeError(f"Cache error: {response}")
        return response

    def set(self, key: str, value: str, ttl: Optional[int] = None) -> bool:
        """
        Set a key-value pair in the cache.

        Args:
            key: The cache key.
            value: The value to store.
            ttl: Time to live in seconds. None means no expiry.

        Returns:
            True if the operation was successful.
        """
        host, port = self._get_node_for_key(key)
        command = f"SET {key} {value}"
        if ttl is not None:
            command += f" {ttl}"

        response = self._send_command(host, port, command)

        if response == "+OK":
            return True
        if response.startswith("-ERR"):
            raise RuntimeError(f"Cache error: {response}")
        return False

    def delete(self, key: str) -> bool:
        """
        Delete a key from the cache.

        Args:
            key: The cache key to delete.

        Returns:
            True if the key was deleted, False if it didn't exist.
        """
        host, port = self._get_node_for_key(key)
        response = self._send_command(host, port, f"DELETE {key}")

        if response == "+OK":
            return True
        if response.startswith("-ERR"):
            return False
        return False

    def ping(self, node_id: Optional[str] = None) -> bool:
        """
        Ping a cache node to check if it's alive.

        Args:
            node_id: Specific node to ping (e.g., "localhost:6001").
                     If None, pings all nodes and returns True if any respond.

        Returns:
            True if the node(s) responded with PONG.
        """
        if node_id:
            if node_id not in self.nodes:
                raise ValueError(f"Unknown node: {node_id}")
            host, port = self.nodes[node_id]
            try:
                response = self._send_command(host, port, "PING")
                return response == "+PONG"
            except ConnectionError:
                return False
        else:
            for nid, (host, port) in self.nodes.items():
                try:
                    response = self._send_command(host, port, "PING")
                    if response == "+PONG":
                        return True
                except ConnectionError:
                    continue
            return False

    def stats(self, node_id: str) -> Optional[dict]:
        """
        Get statistics from a specific cache node.

        Args:
            node_id: Node identifier (e.g., "localhost:6001").

        Returns:
            Dictionary with node stats or None if unreachable.
        """
        if node_id not in self.nodes:
            raise ValueError(f"Unknown node: {node_id}")
        host, port = self.nodes[node_id]
        try:
            import json
            response = self._send_command(host, port, "STATS")
            return json.loads(response)
        except (ConnectionError, Exception):
            return None

    def add_node(self, host: str, port: int) -> None:
        """Add a new node to the client's hash ring."""
        node_id = f"{host}:{port}"
        self.nodes[node_id] = (host, port)
        self.hash_ring.add_node(node_id)

    def remove_node(self, host: str, port: int) -> None:
        """Remove a node from the client's hash ring."""
        node_id = f"{host}:{port}"
        self.nodes.pop(node_id, None)
        self.hash_ring.remove_node(node_id)
