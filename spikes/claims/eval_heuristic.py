#!/usr/bin/env python3
"""Evaluate model (a): the current Kotlin heuristic, ported to heuristic.py, on the
held-out test set. The heuristic is a hard rule (no score to threshold), so it gets one
fixed operating point, reported alongside the tuned models for comparison."""
import csv
import sys

from heuristic import is_claim
from metrics_util import precision_recall_f1, save_json


def main():
    rows = list(csv.DictReader(open("data/test.csv", encoding="utf-8")))
    y_true = [int(r["label"]) for r in rows]
    y_pred = [1 if is_claim(r["normalized"]) else 0 for r in rows]
    m = precision_recall_f1(y_true, y_pred)
    print(f"Heuristic on test set (n={len(rows)}): {m}", file=sys.stderr)
    save_json({"model": "heuristic", "n_test": len(rows), **m}, "out/metrics_heuristic.json")


if __name__ == "__main__":
    main()
