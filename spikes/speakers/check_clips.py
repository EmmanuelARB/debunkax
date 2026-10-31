#!/usr/bin/env python3
"""Checks that each leader's clips hold one voice, and the same voice across clips.

Embeds three 30 s excerpts per clip with CAM++ (VoxCeleb). "within" is the mean similarity of a clip's excerpts
(low: other voices, music or crowd inside the clip); "across" compares two clips (low: not the same person, or
one clip is not speech). A clip passes when within >= 0.8 and it reaches >= 0.6 with at least one other clip.

    spikes/stt/.venv/bin/python spikes/speakers/check_clips.py [slug ...]
"""
import sys
from pathlib import Path

import numpy as np
import sherpa_onnx
import soundfile as sf

HERE = Path(__file__).resolve().parent
AUDIO = HERE / "audio"
MODEL = HERE / "models" / "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
SR = 16000
WITHIN, ACROSS = 0.8, 0.6

ex = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(MODEL), num_threads=8))


def emb(w):
    st = ex.create_stream()
    st.accept_waveform(SR, w)
    st.input_finished()
    v = np.array(ex.compute(st))
    return v / np.linalg.norm(v)


def excerpts(path):
    w, _ = sf.read(str(path), dtype="float32", always_2d=True)
    w = w.mean(1)
    n = len(w) // SR
    starts = [int(n * f) for f in (0.05, 0.37, 0.69)]
    return [emb(w[s * SR:(s + 30) * SR]) for s in starts]


def main(slugs):
    dirs = [AUDIO / s for s in slugs] if slugs else sorted(p for p in AUDIO.iterdir() if p.is_dir() and p.name != "impostors")
    failed = []
    for d in dirs:
        clips = sorted(d.glob("*.wav"))
        E = {c.stem: excerpts(c) for c in clips}
        verdicts = []
        for k, e in E.items():
            within = np.mean([e[a] @ e[b] for a, b in [(0, 1), (0, 2), (1, 2)]])
            best = max((np.mean([x @ y for x in e for y in E[o]]) for o in E if o != k), default=0.0)
            ok = within >= WITHIN and best >= ACROSS
            verdicts.append(f"{k}:{'ok' if ok else 'BAD'}(w{within:.2f},a{best:.2f})")
            if not ok:
                failed.append(f"{d.name}/{k}")
        print(f"{d.name:11s} " + "  ".join(verdicts))
    print("failed:", " ".join(failed) or "none")


if __name__ == "__main__":
    main(sys.argv[1:])
