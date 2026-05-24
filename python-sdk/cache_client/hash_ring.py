"""
Consistent Hash Ring implementation matching the Java implementation.
Uses MD5 hashing with 150 virtual nodes per physical node.
"""

import hashlib
import struct
from bisect import bisect_right, insort


class ConsistentHashRing:
    """
    Consistent hash ring with virtual nodes.
    Mirrors the Java ConsistentHashRing exactly so keys map to the same nodes.
    """

    def __init__(self, virtual_node_count: int = 150):
        self.virtual_node_count = virtual_node_count
        self._ring_keys: list[int] = []       # sorted list of hash values
        self._ring_map: dict[int, str] = {}   # hash -> node_id
        self._physical_nodes: list[str] = []

    def add_node(self, node_id: str) -> None:
        """Add a physical node with virtual_node_count virtual nodes."""
        if node_id in self._physical_nodes:
            return
        self._physical_nodes.append(node_id)
        for i in range(self.virtual_node_count):
            virtual_key = f"{node_id}#VN{i}"
            h = self._hash(virtual_key)
            self._ring_map[h] = node_id
            insort(self._ring_keys, h)

    def remove_node(self, node_id: str) -> None:
        """Remove a physical node and all its virtual nodes."""
        if node_id not in self._physical_nodes:
            return
        self._physical_nodes.remove(node_id)
        for i in range(self.virtual_node_count):
            virtual_key = f"{node_id}#VN{i}"
            h = self._hash(virtual_key)
            self._ring_map.pop(h, None)
            try:
                self._ring_keys.remove(h)
            except ValueError:
                pass

    def get_node(self, key: str) -> str | None:
        """Get the node responsible for the given key (clockwise walk)."""
        if not self._ring_keys:
            return None
        h = self._hash(key)
        idx = bisect_right(self._ring_keys, h)
        if idx >= len(self._ring_keys):
            idx = 0
        return self._ring_map[self._ring_keys[idx]]

    def get_physical_nodes(self) -> list[str]:
        """Return list of physical node IDs."""
        return list(self._physical_nodes)

    @staticmethod
    def _hash(key: str) -> int:
        """
        MD5 hash matching the Java implementation.
        Uses first 8 bytes of MD5 digest as a signed 64-bit integer.
        """
        digest = hashlib.md5(key.encode("utf-8")).digest()
        value = int.from_bytes(digest[:8], byteorder="big", signed=False)
        # Convert to signed 64-bit to match Java's long
        if value >= (1 << 63):
            value -= (1 << 64)
        return value
