#!/usr/bin/env python3
"""Merge every data/batches/labels_XX.csv (id,label[,note]) back onto
data/unlabeled.csv, producing the final data/sentences.csv (text, normalized, label,
source). Fails loudly if any row is missing a label or a label isn't 0/1."""
import csv
import glob
import sys

UNLABELED = "data/unlabeled.csv"
LABELS_GLOB = "data/batches/labels_*.csv"
OUT_FILE = "data/sentences.csv"


def main():
    rows = list(csv.DictReader(open(UNLABELED, encoding="utf-8")))
    labels = {}
    for path in sorted(glob.glob(LABELS_GLOB)):
        for r in csv.DictReader(open(path, encoding="utf-8")):
            idx = int(r["id"])
            label = r["label"].strip()
            if label not in ("0", "1"):
                raise ValueError(f"{path}: row {idx} has invalid label {label!r}")
            if idx in labels:
                raise ValueError(f"duplicate label for id {idx} (in {path})")
            labels[idx] = label

    missing = [i for i in range(len(rows)) if i not in labels]
    if missing:
        raise ValueError(f"{len(missing)} rows missing a label, e.g. ids {missing[:10]}")

    with open(OUT_FILE, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["text", "normalized", "label", "source"])
        w.writeheader()
        for i, r in enumerate(rows):
            w.writerow({"text": r["text"], "normalized": r["normalized"], "label": labels[i], "source": r["source"]})

    n = len(rows)
    pos = sum(1 for v in labels.values() if v == "1")
    print(f"Wrote {OUT_FILE}: {n} rows, {pos} positive ({pos/n:.1%})", file=sys.stderr)


if __name__ == "__main__":
    main()
