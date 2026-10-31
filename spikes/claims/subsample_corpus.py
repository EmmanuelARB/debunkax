#!/usr/bin/env python3
"""build_corpus.py found 16,247 unique sentences across 42 videos -- far more than the
>=3,000 target and more than can reasonably be hand-labelled for a spike. Subsample per
source (video) with a per-source cap, preserving in-video order (so a labeller reading a
batch still gets short runs of real conversational context, not shuffled fragments),
capped total close to ~3,300 sentences. Overwrites data/unlabeled.csv; the untruncated
version is kept at data/unlabeled_full.csv for reference (gitignored either way)."""
import csv
import random
import shutil
import sys
from collections import defaultdict

IN_FILE = "data/unlabeled.csv"
FULL_BACKUP = "data/unlabeled_full.csv"
CAP_PER_SOURCE = 80
SEED = 42


def main():
    shutil.copyfile(IN_FILE, FULL_BACKUP)
    rows = list(csv.DictReader(open(IN_FILE, encoding="utf-8")))
    by_source = defaultdict(list)
    for i, r in enumerate(rows):
        by_source[r["source"]].append((i, r))

    rng = random.Random(SEED)
    kept_indices = set()
    for source, items in by_source.items():
        if len(items) <= CAP_PER_SOURCE:
            kept_indices.update(i for i, _ in items)
        else:
            chosen = rng.sample(items, CAP_PER_SOURCE)
            kept_indices.update(i for i, _ in chosen)

    kept_rows = [r for i, r in enumerate(rows) if i in kept_indices]
    with open(IN_FILE, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["text", "normalized", "source"])
        w.writeheader()
        w.writerows(kept_rows)

    print(f"{len(rows)} -> {len(kept_rows)} sentences (cap={CAP_PER_SOURCE}/source), "
          f"across {len(by_source)} sources", file=sys.stderr)


if __name__ == "__main__":
    main()
