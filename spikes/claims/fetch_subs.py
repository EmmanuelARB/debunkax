#!/usr/bin/env python3
"""Attempt to download ONLY manual French subtitles (no audio/video) for each candidate.
Ground truth: if yt-dlp actually writes a .fr.vtt file, manual subs exist. Writes a
manifest of successes to data/manifest.csv, incrementally (safe to resume/kill)."""
import csv
import glob
import os
import subprocess
import sys
import time

CANDIDATES = "data/candidates.csv"
OUT_DIR = "data/raw_subs"
MANIFEST = "data/manifest.csv"

OFFICIAL_CHANNELS = {
    "bfmtv", "franceinfo", "france 24", "lcp - assemblée nationale",
    "public sénat", "france inter", "rtl", "cnews", "lci",
    "24 heures pujadas - lci",
}


def norm_channel(c):
    return c.strip().lower()


def already_done():
    done = set()
    if os.path.exists(MANIFEST):
        for r in csv.DictReader(open(MANIFEST, encoding="utf-8")):
            done.add(r["id"])
    return done


def try_download(video_id):
    out_tmpl = os.path.join(OUT_DIR, f"{video_id}.%(ext)s")
    cmd = [
        "yt-dlp",
        f"https://www.youtube.com/watch?v={video_id}",
        "--skip-download",
        "--write-subs",
        "--sub-langs",
        "fr,fr-FR,fr-orig",  # manual-only (--write-subs, no --write-auto-subs); code varies
        "--extractor-args",
        "youtube:player_client=android",  # dodges the "sign in to confirm you're not a bot" wall
        "-o",
        out_tmpl,
        "--no-warnings",
    ]
    try:
        subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    except subprocess.TimeoutExpired:
        return False
    time.sleep(0.4)
    # yt-dlp names the file after whichever manual code it found (fr, fr-FR, fr-orig);
    # normalize to <id>.fr.vtt so build_corpus.py can glob a single pattern.
    matches = glob.glob(os.path.join(OUT_DIR, f"{video_id}.fr*.vtt"))
    if not matches:
        return False
    target = os.path.join(OUT_DIR, f"{video_id}.fr.vtt")
    if matches[0] != target:
        os.replace(matches[0], target)
    for extra in matches[1:]:
        os.remove(extra)
    return True


def main():
    rows = list(csv.DictReader(open(CANDIDATES, encoding="utf-8")))
    rows = [r for r in rows if norm_channel(r["channel"]) in OFFICIAL_CHANNELS]
    seen_ids = set()
    dedup = []
    for r in rows:
        if r["id"] not in seen_ids:
            seen_ids.add(r["id"])
            dedup.append(r)
    rows = dedup
    done = already_done()
    print(f"{len(rows)} official-channel candidates, {len(done)} already probed", file=sys.stderr)
    write_header = not os.path.exists(MANIFEST)
    with open(MANIFEST, "a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["id", "channel", "title", "duration", "has_manual_fr"])
        if write_header:
            w.writeheader()
        for i, r in enumerate(rows):
            if r["id"] in done:
                continue
            ok = try_download(r["id"])
            w.writerow({
                "id": r["id"], "channel": r["channel"], "title": r["title"],
                "duration": r.get("duration", ""), "has_manual_fr": ok,
            })
            f.flush()
            print(f"[{i+1}/{len(rows)}] {'OK' if ok else '--'} {r['channel']:12s} {r['title'][:70]}",
                  file=sys.stderr, flush=True)


if __name__ == "__main__":
    main()
