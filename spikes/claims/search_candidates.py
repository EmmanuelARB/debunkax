#!/usr/bin/env python3
"""Search YouTube via yt-dlp for candidate videos and dump id/channel/title/duration to CSV."""
import csv
import json
import subprocess
import sys

QUERIES_FILE = "search_queries.txt"
OUT_FILE = "data/candidates.csv"
N = 20  # results per query


def search(query, n=N):
    cmd = [
        "yt-dlp",
        f"ytsearch{n}:{query}",
        "--flat-playlist",
        "--dump-single-json",
        "--no-warnings",
    ]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired:
        print(f"TIMEOUT: {query}", file=sys.stderr)
        return []
    if out.returncode != 0:
        print(f"ERROR searching '{query}': {out.stderr[:300]}", file=sys.stderr)
        return []
    try:
        data = json.loads(out.stdout)
    except json.JSONDecodeError:
        print(f"BAD JSON for '{query}'", file=sys.stderr)
        return []
    entries = data.get("entries", []) or []
    rows = []
    for e in entries:
        rows.append(
            {
                "id": e.get("id"),
                "channel": e.get("channel") or e.get("uploader") or "",
                "title": e.get("title") or "",
                "duration": e.get("duration") or "",
                "query": query,
            }
        )
    return rows


def main():
    with open(QUERIES_FILE, encoding="utf-8") as f:
        queries = [l.strip() for l in f if l.strip()]
    seen_ids = set()
    all_rows = []
    for q in queries:
        rows = search(q)
        print(f"{q}: {len(rows)} results", file=sys.stderr)
        for r in rows:
            if r["id"] and r["id"] not in seen_ids:
                seen_ids.add(r["id"])
                all_rows.append(r)
    with open(OUT_FILE, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["id", "channel", "title", "duration", "query"])
        w.writeheader()
        w.writerows(all_rows)
    print(f"Total unique candidates: {len(all_rows)}", file=sys.stderr)


if __name__ == "__main__":
    main()
