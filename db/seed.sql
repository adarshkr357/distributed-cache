-- Seed script: Initialize database and run migrations
-- This script sets up the distributed cache database

CREATE DATABASE IF NOT EXISTS distributed_cache;
USE distributed_cache;

-- Run migrations in order
SOURCE /docker-entrypoint-initdb.d/migrations/001_create_wal_log.sql;
SOURCE /docker-entrypoint-initdb.d/migrations/002_create_snapshots.sql;

