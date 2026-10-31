#!/usr/bin/env python3
"""Says who is speaking, every couple of seconds, among the enrolled leaders, or "inconnu".

    spikes/stt/.venv/bin/python spikes/speakers/live.py --mic                 # listens to the computer's mic
    spikes/stt/.venv/bin/python spikes/speakers/live.py FILE_OR_YOUTUBE_URL   # timeline of a file or video
        [--start 40:00] [--threshold 0.75] [--model titanet|campplus]

Voice prints come from each clip in audio/<slug>/ that passes check_clips.py's rules, cached in
results/prints-<model>.npz. Nothing is recorded: mic audio is only kept in memory for the current window.
"""
import argparse
import queue
import subprocess
import sys
from pathlib import Path

import numpy as np
import sherpa_onnx
import soundfile as sf

HERE = Path(__file__).resolve().parent
AUDIO = HERE / "audio"
MODELS = {"campplus": "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx", "titanet": "nemo_en_titanet_small.onnx"}
MODEL = "titanet"  # best at telling the 20 apart in eval.py; --model changes it
SR = 16000
WINDOW_S, HOP_S = 5.0, 2.5
MARGIN = 0.05  # the best match must beat the second best by this much

def extractor(name):
    cfg = sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(HERE / "models" / MODELS[name]), num_threads=4)
    return sherpa_onnx.SpeakerEmbeddingExtractor(cfg)


check_ex = extractor("campplus")  # check_clips.py's thresholds are CAM++ scores
ex = None


def emb(w, ex=None):
    ex = ex or globals()["ex"]
    st = ex.create_stream()
    st.accept_waveform(SR, w)
    st.input_finished()
    v = np.array(ex.compute(st))
    return v / np.linalg.norm(v)


def load(path):
    w, _ = sf.read(str(path), dtype="float32", always_2d=True)
    return w.mean(1)


def good_clips(clips):
    """The clips passing check_clips.py's rules: one voice inside, and the same voice as another clip."""
    ex3 = {}
    for c in clips:
        w = load(c)
        n = len(w) // SR
        ex3[c] = [emb(w[int(n * f) * SR:(int(n * f) + 30) * SR], check_ex) for f in (0.05, 0.37, 0.69)]
    good = []
    for c, e in ex3.items():
        within = np.mean([e[a] @ e[b] for a, b in [(0, 1), (0, 2), (1, 2)]])
        best = max((np.mean([x @ y for x in e for y in ex3[o]]) for o in ex3 if o != c), default=0.0)
        if within >= 0.8 and best >= 0.6:
            good.append(c)
    return good


def voice_prints():
    cache = HERE / "results" / f"prints-{MODEL}.npz"
    dirs = sorted(p for p in AUDIO.iterdir() if p.is_dir() and p.name != "impostors")
    stamp = {str(c): c.stat().st_mtime for d in dirs for c in d.glob("*.wav")}
    if cache.exists():
        cached = np.load(cache, allow_pickle=True)
        if cached["stamp"].item() == stamp:
            return list(cached["names"]), cached["prints"]
    print("Calcul des empreintes vocales…", file=sys.stderr)
    # One print per good clip, not one per person: a studio voice and a rally voice average into neither.
    names, prints = [], []
    for d in dirs:
        good = good_clips(sorted(d.glob("*.wav")))
        for c in good:
            w = load(c)
            m = np.mean([emb(w[i:i + 10 * SR]) for i in range(0, len(w) - 10 * SR + 1, 10 * SR)], axis=0)
            names.append(d.name)
            prints.append(m / np.linalg.norm(m))
        print(f"  {d.name}: {len(good)} clip(s) retenu(s) ({', '.join(c.stem for c in good)})", file=sys.stderr)
    prints = np.stack(prints)
    cache.parent.mkdir(exist_ok=True)
    np.savez(cache, names=np.array(names), prints=prints, stamp=np.array(stamp, dtype=object))
    return names, prints


def who(window, names, prints, threshold):
    if np.sqrt(np.mean(window ** 2)) < 0.005:
        return "(silence)", 0.0
    per_clip = prints @ emb(window)
    people = sorted(set(names))
    scores = np.array([max(s for n, s in zip(names, per_clip) if n == p) for p in people])
    order = np.argsort(scores)[::-1]
    top, second = scores[order[0]], scores[order[1]]
    if top < threshold or top - second < MARGIN:
        return f"inconnu  (plus proche : {people[order[0]]} {top:.2f})", top
    return f"{people[order[0]]}  ({top:.2f})", top


def windows_of(stream_chunks):
    """Yields 5 s windows every 2.5 s from an iterator of float32 chunks."""
    buf = np.zeros(0, dtype=np.float32)
    t = 0.0
    for chunk in stream_chunks:
        buf = np.concatenate([buf, chunk])
        while len(buf) >= WINDOW_S * SR:
            yield t, buf[: int(WINDOW_S * SR)]
            buf = buf[int(HOP_S * SR):]
            t += HOP_S


def mic_chunks():
    import sounddevice as sd

    q = queue.Queue()
    with sd.InputStream(samplerate=SR, channels=1, dtype="float32", callback=lambda d, *_: q.put(d[:, 0].copy())):
        print("Écoute du micro… (Ctrl+C pour arrêter)", file=sys.stderr)
        while True:
            yield q.get()


def source_chunks(src, start):
    """Decodes a file, or a YouTube URL through yt-dlp, to 16 kHz mono floats with ffmpeg."""
    if src.startswith("http"):
        ytdlp = subprocess.Popen(["yt-dlp", "-q", "--no-warnings", "-f", "bestaudio", "-o", "-", src], stdout=subprocess.PIPE)
        inp, stdin = "pipe:0", ytdlp.stdout
    else:
        inp, stdin = src, None
    cmd = ["ffmpeg", "-loglevel", "error"] + (["-ss", start] if start else []) + ["-i", inp, "-f", "f32le", "-ar", str(SR), "-ac", "1", "pipe:1"]
    ff = subprocess.Popen(cmd, stdin=stdin, stdout=subprocess.PIPE)
    while data := ff.stdout.read(SR * 4):
        yield np.frombuffer(data, dtype=np.float32)


def seconds(text):
    parts = [float(p) for p in text.split(":")]
    return sum(p * 60 ** i for i, p in enumerate(reversed(parts)))


def main():
    global MODEL, ex
    ap = argparse.ArgumentParser()
    ap.add_argument("source", nargs="?", help="audio/video file or YouTube URL")
    ap.add_argument("--mic", action="store_true")
    ap.add_argument("--start", help="start time for a file or URL, e.g. 40:00")
    ap.add_argument("--threshold", type=float, default=0.75)
    ap.add_argument("--model", choices=MODELS, default="titanet")
    args = ap.parse_args()
    MODEL, ex = args.model, extractor(args.model)
    if not args.mic and not args.source:
        ap.error("give --mic or a file/URL")

    names, prints = voice_prints()
    print(f"{len(set(names))} personnes connues : {', '.join(sorted(set(names)))}\n", file=sys.stderr)
    offset = seconds(args.start) if args.start else 0.0
    chunks = mic_chunks() if args.mic else source_chunks(args.source, args.start)
    last = None
    try:
        for t, w in windows_of(chunks):
            name, _ = who(w, names, prints, args.threshold)
            person = name.split()[0]
            if args.mic or person != last:
                ts = int(t + offset)
                print(f"{ts // 3600:d}:{ts // 60 % 60:02d}:{ts % 60:02d}  {name}", flush=True)
            last = person
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
