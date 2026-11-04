import sqlite3
import os
import json
from datetime import datetime

DB_PATH = os.path.join(os.path.dirname(__file__), "..", "data", "sports_betting.db")

def get_db():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    return conn

def init_db():
    conn = get_db()
    schema_path = os.path.join(os.path.dirname(__file__), "schema.sql")
    with open(schema_path, "r", encoding="utf-8") as f:
        conn.executescript(f.read())
    
    # Verify critical tables exist
    cursor = conn.cursor()
    tables = ['sports', 'leagues', 'teams', 'matches', 'odds', 'predictions', 
              'coupons', 'coupon_legs', 'bankroll', 'bankroll_transactions',
              'settings', 'model_registry', 'match_results', 'audit_log',
              'data_sources', 'collection_runs', 'feature_store', 'drift_log',
              'notifications', 'notification_rules', 'rg_limits', 'bankroll_snapshots']
    
    for table in tables:
        cursor.execute(f"SELECT COUNT(*) FROM {table}")
        count = cursor.fetchone()[0]
        print(f"  ✅ Table '{table}': {count} rows")
    
    # Verify indexes
    cursor.execute("SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'idx_%'")
    indexes = cursor.fetchall()
    print(f"  ✅ Indexes created: {len(indexes)}")
    
    conn.commit()
    conn.close()
    print("Database initialized successfully with full schema v2.0.")

def reset_db():
    """Dangerous: drops all tables and recreates"""
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("SELECT name FROM sqlite_master WHERE type='table'")
    tables = cursor.fetchall()
    for (table,) in tables:
        if not table.startswith('sqlite_'):
            cursor.execute(f"DROP TABLE IF EXISTS {table}")
    conn.commit()
    conn.close()
    init_db()

if __name__ == "__main__":
    init_db()