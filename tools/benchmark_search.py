#!/usr/bin/env python3
"""Synthetic SQLite search benchmark (not an Android device benchmark).

Current Room DAO uses LIKE '%term%' and cannot benefit from a normal B-tree
prefix index. Compare that to an indexed prefix search to guide a future FTS
migration; do not change substring search semantics without a product decision.
"""
import sqlite3
import statistics
import time

db = sqlite3.connect(":memory:")
db.execute("CREATE TABLE videos (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
db.executemany("INSERT INTO videos(name) VALUES (?)", ((f"Video {i:06d} Sample {i % 53}.mkv",) for i in range(30000)))
db.execute("CREATE INDEX idx_video_name_nocase ON videos(name COLLATE NOCASE)")
queries = [
    ("substring", "SELECT id FROM videos WHERE name LIKE ? COLLATE NOCASE", ("%Sample 7%",)),
    ("prefix", "SELECT id FROM videos WHERE name LIKE ? COLLATE NOCASE", ("Video 001%",)),
]
for label, sql, args in queries:
    plan = db.execute("EXPLAIN QUERY PLAN " + sql, args).fetchall()
    timings = []
    for _ in range(10):
        t = time.perf_counter()
        db.execute(sql, args).fetchall()
        timings.append((time.perf_counter() - t) * 1000)
    print(f"{label}: median={statistics.median(timings):.2f}ms, plan={plan}")
print("Synthetic host-only results. Real tablet benchmarks and an FTS migration are still needed.")
