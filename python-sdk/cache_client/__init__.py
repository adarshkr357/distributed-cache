"""
Distributed Cache Python Client SDK

A Python client for the distributed in-memory cache system.
Uses consistent hashing to route requests to the correct node.
"""

__version__ = "1.0.0"

from .client import CacheClient

__all__ = ["CacheClient"]
