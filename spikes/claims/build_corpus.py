#!/usr/bin/env python3
"""Turn every .fr.vtt file in data/raw_subs/ into a deduped, sentence-split corpus,
written to data/unlabeled.csv (text, normalized, source). No labels yet — labelling
happens separately per LABELING.md.

`normalized` approximates what an on-device speech recognizer would actually hand the
claim filter: uppercase, no punctuation (commas/periods/quotes stripped, hyphens turned
to spaces), apostrophes kept, with a trailing "?" kept only if the sentence is a
question — matching HeuristicClaimDetectorTest's documented STT-output shape.
"""
import csv
import glob
import os
import re
import sys

import pysbd
import webvtt

RAW_DIR = "data/raw_subs"
OUT_FILE = "data/unlabeled.csv"

_PUNCT_TO_SPACE = re.compile(r"[\-‐-―_/]")
_STRIP_CHARS = re.compile(r"[^\w\s'’?]", re.UNICODE)
_WS = re.compile(r"\s+")


def normalize_stt(sentence: str) -> str:
    s = sentence.strip()
    is_question = s.endswith("?")
    s = _PUNCT_TO_SPACE.sub(" ", s)
    s = _STRIP_CHARS.sub("", s)
    s = _WS.sub(" ", s).strip()
    s = s.upper()
    if is_question and not s.endswith("?"):
        s = s + " ?"
    return s


def cues_to_text(path: str) -> str:
    """Concatenate all cue texts of a vtt file into one running transcript,
    collapsing consecutive duplicate lines (rolling-caption artifact)."""
    lines = []
    prev = None
    for cue in webvtt.read(path):
        text = " ".join(cue.text.strip().splitlines())
        text = _WS.sub(" ", text).strip()
        if not text or text == prev:
            continue
        lines.append(text)
        prev = text
    return " ".join(lines)


def main():
    seg = pysbd.Segmenter(language="fr", clean=True)
    seen = set()
    rows = []
    vtt_files = sorted(glob.glob(os.path.join(RAW_DIR, "*.fr.vtt")))
    print(f"{len(vtt_files)} vtt files", file=sys.stderr)
    for path in vtt_files:
        source = os.path.basename(path).replace(".fr.vtt", "")
        text = cues_to_text(path)
        sentences = seg.segment(text)
        kept = 0
        for sent in sentences:
            sent = _WS.sub(" ", sent).strip()
            if len(sent.split()) < 4:
                continue
            key = sent.lower()
            if key in seen:
                continue
            seen.add(key)
            rows.append({"text": sent, "normalized": normalize_stt(sent), "source": source})
            kept += 1
        print(f"{source}: {len(sentences)} sentences -> {kept} kept", file=sys.stderr)
    with open(OUT_FILE, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["text", "normalized", "source"])
        w.writeheader()
        w.writerows(rows)
    print(f"Total unique sentences: {len(rows)}", file=sys.stderr)


if __name__ == "__main__":
    main()
