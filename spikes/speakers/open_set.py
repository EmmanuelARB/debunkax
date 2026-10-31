#!/usr/bin/env python3
"""How often the app would name the wrong person: live.py's prints and rule (threshold, 0.05 margin), 5 s windows
brought to 0.05 RMS as SpeakerIdentifier does. Clean clips, so a best case for a TV heard across the room.

- enrolled: each clip's windows against every print but that clip's own (leave one clip out)
- not enrolled: each person's windows with all their prints removed, plus audio/impostors/ voices nobody enrolled

    spikes/stt/.venv/bin/python spikes/speakers/open_set.py
"""
import sys
import numpy as np

import live
LEADERS = {"attal", "bardella", "bayrou", "bompard", "darmanin", "faure", "glucksmann", "lecornu", "lepen", "macron",
           "marechal", "melenchon", "panot", "philippe", "retailleau", "roussel", "ruffin", "tondelier", "wauquiez", "zemmour"}
WIN = 5 * live.SR
PER_CLIP = 8  # windows spread over each clip


def windows(w):
    starts = np.linspace(0, len(w) - WIN, PER_CLIP).astype(int) if len(w) > WIN else []
    out = []
    for s in starts:
        x = w[s:s + WIN]
        level = np.sqrt(np.mean(x ** 2))
        if level >= 0.0005:
            out.append(live.emb(np.clip(x * 0.05 / level, -1, 1)))
    return out


def outcome(e, names, prints, keep, threshold):
    """The name given, or None: best print per person among the prints kept, then threshold and margin."""
    per_clip = prints[keep] @ e
    kept = [n for n, k in zip(names, keep) if k]
    best = {}
    for n, s in zip(kept, per_clip):
        best[n] = max(best.get(n, -1.0), s)
    ranked = sorted(best.items(), key=lambda kv: -kv[1])
    (top, score), second = ranked[0], ranked[1][1]
    return top if score >= threshold and score - second >= live.MARGIN else None


def main():
    model = sys.argv[1] if len(sys.argv) > 1 else "titanet"
    live.MODEL, live.ex = model, live.extractor(model)
    names, prints = [], []
    clips = []  # (person, index of its print, window embeddings)
    for d in sorted(p for p in live.AUDIO.iterdir() if p.is_dir() and p.name != "impostors"):
        for c in live.good_clips(sorted(d.glob("*.wav"))):
            w = live.load(c)
            m = np.mean([live.emb(w[i:i + 10 * live.SR]) for i in range(0, len(w) - 10 * live.SR + 1, 10 * live.SR)], axis=0)
            names.append(d.name)
            prints.append(m / np.linalg.norm(m))
            clips.append((d.name, len(names) - 1, windows(w)))
    prints = np.stack(prints)
    people = set(names)
    outsiders = [(p.stem, windows(live.load(p))) for p in sorted((live.AUDIO / "impostors").glob("*.wav")) if p.stem not in people]
    print(f"{len(people)} personnes, {len(names)} empreintes ; voix jamais inscrites : {', '.join(o for o, _ in outsiders)}")

    for threshold in (0.40, 0.45, 0.50, 0.55, 0.60):
        stats = {"leader": [0, 0, 0], "other": [0, 0, 0]}  # right, wrong, unknown
        wrong_leader = []
        for person, own, embs in clips:
            keep = np.ones(len(names), bool)
            keep[own] = False
            if not any(n == person for n, k in zip(names, keep) if k):
                continue  # a single clip: nothing left to recognise it by
            for e in embs:
                got = outcome(e, names, prints, keep, threshold)
                stats["leader" if person in LEADERS else "other"][0 if got == person else 2 if got is None else 1] += 1
        absent = [0, 0]  # named, windows
        for person in sorted(people):
            keep = np.array([n != person for n in names])
            for _, own, embs in (c for c in clips if c[0] == person):
                for e in embs:
                    got = outcome(e, names, prints, keep, threshold)
                    absent[1] += 1
                    if got is not None:
                        absent[0] += 1
                        wrong_leader.append(f"{person}→{got}")
        keep = np.ones(len(names), bool)
        for person, embs in outsiders:
            for e in embs:
                got = outcome(e, names, prints, keep, threshold)
                absent[1] += 1
                if got is not None:
                    absent[0] += 1
                    wrong_leader.append(f"{person}→{got}")
        print(f"\nseuil {threshold:.2f}")
        for group, (r, wr, u) in stats.items():
            n = r + wr + u
            print(f"  inscrits ({'leaders' if group == 'leader' else 'autres'}) : nommés juste {r / n:.0%}, mauvais nom {wr / n:.1%}, inconnu {u / n:.0%}  ({n} fenêtres)")
        print(f"  non inscrits : un nom donné {absent[0] / absent[1]:.1%}  ({absent[1]} fenêtres)")
        if threshold == 0.45:
            from collections import Counter
            print("  confusions :", ", ".join(f"{k} ×{v}" for k, v in Counter(wrong_leader).most_common(15)))


if __name__ == "__main__":
    main()
