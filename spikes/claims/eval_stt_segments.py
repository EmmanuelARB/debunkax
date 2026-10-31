#!/usr/bin/env python3
"""Evaluates a TF-IDF+LR export (same JSON format as exports/tfidf_lr_export.json) on
the labelled STT segments (data/stt_segments_labeled.csv, produced by stt_segments.py
and hand-labelled per LABELING.md), instead of the clean subtitle sentences used in
RESULTS.md's main table.

Reports precision/recall/F1 at the export's own shipped threshold, plus two numbers
that matter for a user's wallet: Checks per real Claim (1/precision) and Checks per
hour of debate audio (total predicted-positive segments / total audio hours, computed
from the segments' own start/end timestamps).

Usage: .venv/bin/python eval_stt_segments.py exports/tfidf_lr_export.json \
           --data data/stt_segments_labeled.csv
"""
import argparse
import csv
import json
from collections import defaultdict

from make_parity_fixture import build_vectorizer_helpers, score
from metrics_util import precision_recall_f1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("export", help="Path to a tfidf_lr_export.json-shaped file")
    parser.add_argument("--data", default="data/stt_segments_labeled.csv")
    parser.add_argument("--exclude-garbled", action="store_true",
                         help="Drop segments flagged too_garbled=1 before scoring")
    args = parser.parse_args()

    with open(args.export, encoding="utf-8") as f:
        export = json.load(f)
    word_ngrams, char_ngrams = build_vectorizer_helpers()

    rows = list(csv.DictReader(open(args.data, encoding="utf-8")))
    if args.exclude_garbled:
        rows = [r for r in rows if r.get("too_garbled") != "1"]

    y_true, y_pred = [], []
    duration_by_source = defaultdict(float)
    seen_source_end = defaultdict(float)
    n_checks = 0
    for r in rows:
        label = int(r["label"])
        prob = score(r["text"], export, word_ngrams, char_ngrams)
        pred = 1 if prob >= export["threshold"] else 0
        y_true.append(label)
        y_pred.append(pred)
        n_checks += pred
        seen_source_end[r["source"]] = max(seen_source_end[r["source"]], float(r["end"]))

    total_audio_hours = sum(seen_source_end.values()) / 3600.0

    m = precision_recall_f1(y_true, y_pred)
    n_real_claims = sum(y_true)
    checks_per_claim = n_checks / n_real_claims if n_real_claims else float("nan")
    checks_per_hour = n_checks / total_audio_hours if total_audio_hours else float("nan")

    print(f"n segments        : {len(rows)}")
    print(f"n real Claims      : {n_real_claims} ({n_real_claims/len(rows):.1%})")
    print(f"threshold          : {export['threshold']:.4f}")
    print(f"precision          : {m['precision']:.3f}")
    print(f"recall             : {m['recall']:.3f}")
    print(f"f1                 : {m['f1']:.3f}")
    print(f"tp={m['tp']} fp={m['fp']} fn={m['fn']}")
    print(f"n Checks triggered : {n_checks}")
    print(f"Checks per real Claim : {checks_per_claim:.2f}")
    print(f"total audio        : {total_audio_hours*60:.1f} min ({total_audio_hours:.3f} h)")
    print(f"Checks per hour of audio : {checks_per_hour:.1f}")


if __name__ == "__main__":
    main()
