#!/usr/bin/env python3
"""From data/manifest.csv (every candidate we actually attempted, with ground-truth
has_manual_fr), pick a final set of up to MAX_VIDEOS for the corpus: all successes,
deduped, capped, printing a rough political-balance tally (by guest/party keywords in
the title) for a manual sanity check. Deletes the .fr.vtt files of videos NOT selected
from data/raw_subs/ so build_corpus.py only sees the curated set."""
import csv
import glob
import os
import re
import sys

MANIFEST = "data/manifest.csv"
RAW_DIR = "data/raw_subs"
MAX_VIDEOS = 45  # only BFMTV and LCP yielded manual French subs among the 9 target
# channels searched (283 candidates checked); keeping all of them (41) plus the 1
# pre-existing spike source not re-fetched stays close to the "about 25-40" videos
# guidance while maximizing sentence count towards the >=3000 labelling target.

PARTY_KEYWORDS = {
    "RN / droite radicale": r"\b(rn|rassemblement national|le pen|zemmour|reconquete|bardella|allisio)\b",
    "LFI / gauche radicale": r"\b(lfi|melenchon|m[ée]lenchon|delogu|chikirou|bompard|nupes)\b",
    "PS / gauche": r"\b(ps|parti socialiste|faure|hidalgo|gauche)\b",
    "EELV / écologistes": r"\b(eelv|ecologiste|verts|jadot)\b",
    "Renaissance / macronie": r"\b(renaissance|macron|attal|berge|penicaud|majorite presidentielle)\b",
    "LR / droite": r"\b(lr|les republicains|ciotti|wauquiez|retailleau)\b",
}


def tag_party(title):
    t = title.lower()
    hits = [name for name, pat in PARTY_KEYWORDS.items() if re.search(pat, t)]
    return hits or ["non identifié"]


def main():
    rows = list(csv.DictReader(open(MANIFEST, encoding="utf-8")))
    ok_rows = [r for r in rows if r["has_manual_fr"] == "True"]
    # dedupe by id (candidates.csv could have duplicated across queries)
    seen = set()
    dedup = []
    for r in ok_rows:
        if r["id"] not in seen:
            seen.add(r["id"])
            dedup.append(r)

    selected = dedup[:MAX_VIDEOS]
    dropped = dedup[MAX_VIDEOS:]

    print(f"{len(dedup)} unique videos with manual French subs, selecting {len(selected)}", file=sys.stderr)
    from collections import Counter

    chan_counts = Counter(r["channel"] for r in selected)
    print(f"By channel: {dict(chan_counts)}", file=sys.stderr)
    party_counts = Counter()
    for r in selected:
        for p in tag_party(r["title"]):
            party_counts[p] += 1
    print(f"By party/guest keyword in title (rough, can overlap): {dict(party_counts)}", file=sys.stderr)

    keep_ids = {r["id"] for r in selected}
    removed = 0
    for path in glob.glob(os.path.join(RAW_DIR, "*.fr.vtt")):
        base = os.path.basename(path).replace(".fr.vtt", "")
        # keep the 4 pre-existing spikes/stt sources (non-video-id names) untouched
        if re.fullmatch(r"[A-Za-z0-9_-]{11}", base) and base not in keep_ids:
            os.remove(path)
            removed += 1
    print(f"Removed {removed} unselected .fr.vtt files from {RAW_DIR}", file=sys.stderr)

    with open("data/selected_videos.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["id", "channel", "title", "duration"], extrasaction="ignore")
        w.writeheader()
        w.writerows(selected)


if __name__ == "__main__":
    main()
