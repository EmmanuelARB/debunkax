#!/usr/bin/env python3
"""Split data/sentences.csv into train/test by SOURCE (whole videos held out), so the
test set measures generalization to unseen debates, not just unseen sentences from a
debate already partly seen in training. Writes data/train.csv and data/test.csv.

Target: ~80/20 by sentence count, stratified roughly by class balance per source group.
"""
import csv
import random
import sys
from collections import defaultdict

IN_FILE = "data/sentences.csv"
TRAIN_FILE = "data/train.csv"
TEST_FILE = "data/test.csv"
TEST_FRACTION = 0.2
SEED = 42


def main():
    rows = list(csv.DictReader(open(IN_FILE, encoding="utf-8")))
    by_source = defaultdict(list)
    for r in rows:
        by_source[r["source"]].append(r)

    sources = list(by_source.keys())
    rng = random.Random(SEED)
    rng.shuffle(sources)

    total = len(rows)
    target_test = total * TEST_FRACTION
    test_sources, train_sources = [], []
    running = 0
    for s in sources:
        if running < target_test:
            test_sources.append(s)
            running += len(by_source[s])
        else:
            train_sources.append(s)

    train_rows = [r for s in train_sources for r in by_source[s]]
    test_rows = [r for s in test_sources for r in by_source[s]]

    def stats(rs, name):
        pos = sum(1 for r in rs if r["label"] == "1")
        print(f"{name}: {len(rs)} rows, {pos} positive ({pos/len(rs):.1%})", file=sys.stderr)

    stats(train_rows, "train")
    stats(test_rows, "test")
    print(f"test sources ({len(test_sources)}): {test_sources}", file=sys.stderr)

    fieldnames = rows[0].keys()
    with open(TRAIN_FILE, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(train_rows)
    with open(TEST_FILE, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(test_rows)


if __name__ == "__main__":
    main()
