#!/usr/bin/env python3
"""Minifies exports/tfidf_lr_export.json into the APK asset TfidfClaimDetector loads.

Same content as the export (word/char vocab, IDF, logistic-regression coefficients,
intercept, threshold, plus the human-readable "preprocessing"/"scoring" fields kept for
anyone reading the shipped file) with whitespace stripped, since nothing about the
number is APK-size-sensitive enough to warrant dropping fields.
"""
import json

SRC = "exports/tfidf_lr_export.json"
DEST = "../../app/src/main/assets/claim_model.json"


def main():
    with open(SRC, encoding="utf-8") as f:
        export = json.load(f)
    with open(DEST, "w", encoding="utf-8") as f:
        json.dump(export, f, separators=(",", ":"), ensure_ascii=False)
    print(f"Wrote {DEST}")


if __name__ == "__main__":
    main()
