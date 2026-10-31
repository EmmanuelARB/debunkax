#!/usr/bin/env python3
"""Split data/unlabeled.csv into fixed-size batches for labelling, each written to
data/batches/batch_XX.csv (id, text, normalized, source). `id` is a stable row number
into data/unlabeled.csv so labels can be merged back later with merge_labels.py."""
import csv
import math
import os
import sys

IN_FILE = "data/unlabeled.csv"
BATCH_DIR = "data/batches"
BATCH_SIZE = int(sys.argv[1]) if len(sys.argv) > 1 else 600


def main():
    rows = list(csv.DictReader(open(IN_FILE, encoding="utf-8")))
    os.makedirs(BATCH_DIR, exist_ok=True)
    n_batches = math.ceil(len(rows) / BATCH_SIZE)
    for b in range(n_batches):
        chunk = rows[b * BATCH_SIZE : (b + 1) * BATCH_SIZE]
        path = os.path.join(BATCH_DIR, f"batch_{b:02d}.csv")
        with open(path, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=["id", "text", "normalized", "source"])
            w.writeheader()
            for i, r in enumerate(chunk):
                idx = b * BATCH_SIZE + i
                w.writerow({"id": idx, "text": r["text"], "normalized": r["normalized"], "source": r["source"]})
        print(f"{path}: {len(chunk)} rows", file=sys.stderr)
    print(f"Total: {len(rows)} rows in {n_batches} batches", file=sys.stderr)


if __name__ == "__main__":
    main()
