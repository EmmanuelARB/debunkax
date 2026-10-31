#!/usr/bin/env python3
"""Speaker-identification eval harness for the Debunkax speaker-ID spike.

See README.md for what this measures and why. Short version: for each
sherpa-onnx speaker embedding model, enroll ~20 French political leaders
from two clips each, then score a third, held-out clip cut into windows
of a few lengths against every enrolled print, plus impostor windows from
people who are not enrolled.

Usage:
    spikes/stt/.venv/bin/python spikes/speakers/eval.py
    spikes/stt/.venv/bin/python spikes/speakers/eval.py --noise
    spikes/stt/.venv/bin/python spikes/speakers/eval.py --smoke-test

Run `--help` for all options.
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
import time
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import soundfile as sf

try:
    import sherpa_onnx
except ImportError:  # pragma: no cover
    sys.exit("sherpa_onnx not importable — run with spikes/stt/.venv/bin/python")

SR = 16000
HERE = Path(__file__).resolve().parent

MODELS: dict[str, str] = {
    "campplus-voxceleb": "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
    "eres2netv2-3dspeaker": "3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx",
    "wespeaker-resnet34-lm": "wespeaker_en_voxceleb_resnet34_LM.onnx",
    "titanet-small": "nemo_en_titanet_small.onnx",
}

IMPOSTOR_DIRNAME = "impostors"


# --------------------------------------------------------------------------
# Audio I/O and windowing
# --------------------------------------------------------------------------

def load_wav(path: Path) -> np.ndarray:
    """Load a wav file as mono float32 at SR Hz."""
    wave, sr = sf.read(str(path), dtype="float32", always_2d=True)
    wave = wave.mean(axis=1)  # downmix to mono
    if sr != SR:
        # Simplification adequate for this spike: input clips are already
        # 16 kHz mono; this is only a safety net for stray files.
        duration = len(wave) / sr
        n_out = int(round(duration * SR))
        x_old = np.linspace(0, duration, num=len(wave), endpoint=False)
        x_new = np.linspace(0, duration, num=n_out, endpoint=False)
        wave = np.interp(x_new, x_old, wave).astype(np.float32)
    return wave.astype(np.float32)


def cut_windows(wave: np.ndarray, win_s: float) -> list[np.ndarray]:
    """Non-overlapping windows of win_s seconds; drops a shorter remainder.

    If the whole clip is shorter than win_s, returns the whole clip as a
    single window (so short smoke-test clips still produce something).
    """
    n = int(win_s * SR)
    if n <= 0:
        return []
    if len(wave) < n:
        return [wave] if len(wave) > SR * 0.5 else []
    return [wave[i : i + n] for i in range(0, len(wave) - n + 1, n)]


# --------------------------------------------------------------------------
# Noise / room simulation (--noise)
# --------------------------------------------------------------------------

def make_impulse_response(rt60: float, sr: int, rng: np.random.Generator) -> np.ndarray:
    """Synthetic exponential-decay room impulse response."""
    tau = rt60 / np.log(1000.0)  # -60 dB = amplitude x0.001
    length = max(1, int(rt60 * 2 * sr))
    t = np.arange(length) / sr
    ir = rng.standard_normal(length).astype(np.float32) * np.exp(-t / tau).astype(np.float32)
    ir[0] = 1.0  # dominant direct-path tap
    peak = np.max(np.abs(ir)) + 1e-9
    return (ir / peak).astype(np.float32)


def apply_reverb(wave: np.ndarray, ir: np.ndarray) -> np.ndarray:
    out = np.convolve(wave, ir, mode="full")[: len(wave)]
    in_rms = np.sqrt(np.mean(wave**2)) + 1e-12
    out_rms = np.sqrt(np.mean(out**2)) + 1e-12
    return (out * (in_rms / out_rms)).astype(np.float32)


def band_limit(wave: np.ndarray, sr: int, low_hz: float = 150, high_hz: float = 7000) -> np.ndarray:
    n = len(wave)
    spec = np.fft.rfft(wave)
    freqs = np.fft.rfftfreq(n, d=1.0 / sr)
    mask = (freqs >= low_hz) & (freqs <= high_hz)
    spec = spec * mask
    return np.fft.irfft(spec, n=n).astype(np.float32)


def add_noise(wave: np.ndarray, rng: np.random.Generator, snr_db: float, babble_pool: list[np.ndarray]) -> np.ndarray:
    """Add white noise mixed with babble (other speech, tiled/cropped) at snr_db."""
    n = len(wave)
    white = rng.standard_normal(n).astype(np.float32)
    if babble_pool:
        src = babble_pool[rng.integers(0, len(babble_pool))]
        if len(src) >= n:
            start = rng.integers(0, len(src) - n + 1)
            babble = src[start : start + n]
        else:
            reps = int(np.ceil(n / max(1, len(src))))
            babble = np.tile(src, reps)[:n]
        noise = 0.5 * white + 0.5 * babble
    else:
        noise = white
    signal_rms = np.sqrt(np.mean(wave**2)) + 1e-12
    noise_rms = np.sqrt(np.mean(noise**2)) + 1e-12
    target_noise_rms = signal_rms / (10 ** (snr_db / 20.0))
    noise = noise * (target_noise_rms / noise_rms)
    return (wave + noise).astype(np.float32)


def degrade(wave: np.ndarray, rng: np.random.Generator, babble_pool: list[np.ndarray]) -> np.ndarray:
    ir = make_impulse_response(rt60=0.4, sr=SR, rng=rng)
    out = apply_reverb(wave, ir)
    out = band_limit(out, SR)
    out = add_noise(out, rng, snr_db=15.0, babble_pool=babble_pool)
    return out


# --------------------------------------------------------------------------
# Embedding
# --------------------------------------------------------------------------

class SpeakerEmbedder:
    def __init__(self, model_path: Path, num_threads: int = 1):
        config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=str(model_path), num_threads=num_threads, debug=False, provider="cpu"
        )
        self.extractor = sherpa_onnx.SpeakerEmbeddingExtractor(config)
        self.dim = self.extractor.dim

    def embed(self, wave: np.ndarray) -> np.ndarray:
        stream = self.extractor.create_stream()
        stream.accept_waveform(SR, wave.tolist())
        stream.input_finished()
        if not self.extractor.is_ready(stream):
            return None
        vec = np.asarray(self.extractor.compute(stream), dtype=np.float32)
        norm = np.linalg.norm(vec) + 1e-12
        return vec / norm

    def timing_ms(self, wave_5s: np.ndarray, reps: int = 20) -> float:
        self.embed(wave_5s)  # warmup
        t0 = time.perf_counter()
        for _ in range(reps):
            self.embed(wave_5s)
        t1 = time.perf_counter()
        return (t1 - t0) / reps * 1000.0


# --------------------------------------------------------------------------
# Speaker discovery
# --------------------------------------------------------------------------

@dataclass
class Speaker:
    slug: str
    clips: dict[str, Path]  # "1" -> path, "2" -> path, "3" -> path


def discover_speakers(audio_dir: Path) -> list[Speaker]:
    speakers: list[Speaker] = []
    if not audio_dir.is_dir():
        return speakers
    for sub in sorted(audio_dir.iterdir()):
        if not sub.is_dir() or sub.name == IMPOSTOR_DIRNAME:
            continue
        clips: dict[str, Path] = {}
        for wav in sub.glob("*.wav"):
            clips[wav.stem] = wav
        if all(k in clips for k in ("1", "2", "3")):
            speakers.append(Speaker(slug=sub.name, clips=clips))
        elif clips:
            print(f"  skipping {sub.name}: only {sorted(clips)} (need 1,2,3)")
    return speakers


def discover_impostor_clips(audio_dir: Path) -> list[Path]:
    impostor_dir = audio_dir / IMPOSTOR_DIRNAME
    if not impostor_dir.is_dir():
        return []
    return sorted(impostor_dir.glob("*.wav"))


# --------------------------------------------------------------------------
# Metrics
# --------------------------------------------------------------------------

def eer_from_scores(genuine: np.ndarray, impostor: np.ndarray) -> float:
    """Equal error rate between genuine-trial and impostor-trial scores."""
    if len(genuine) == 0 or len(impostor) == 0:
        return float("nan")
    thresholds = np.unique(np.concatenate([genuine, impostor]))
    best_gap = None
    eer = float("nan")
    for thr in thresholds:
        far = np.mean(impostor >= thr)  # false accept rate
        frr = np.mean(genuine < thr)  # false reject rate
        gap = abs(far - frr)
        if best_gap is None or gap < best_gap:
            best_gap = gap
            eer = (far + frr) / 2.0
    return float(eer)


def threshold_for_far(impostor_scores: np.ndarray, max_far: float) -> float:
    """Highest threshold keeping impostor accept rate <= max_far."""
    if len(impostor_scores) == 0:
        return 1.0  # no impostor data: can't calibrate, be conservative
    sorted_scores = np.sort(impostor_scores)
    idx = int(np.floor((1.0 - max_far) * len(sorted_scores)))
    idx = min(idx, len(sorted_scores) - 1)
    return float(sorted_scores[idx])


# --------------------------------------------------------------------------
# Core eval
# --------------------------------------------------------------------------

def enroll(embedder: SpeakerEmbedder, speaker: Speaker, enroll_window_s: float) -> np.ndarray | None:
    vecs = []
    for key in ("1", "2"):
        wave = load_wav(speaker.clips[key])
        windows = cut_windows(wave, enroll_window_s) or [wave]
        for w in windows:
            v = embedder.embed(w)
            if v is not None:
                vecs.append(v)
    if not vecs:
        return None
    mean = np.mean(vecs, axis=0)
    return mean / (np.linalg.norm(mean) + 1e-12)


def run_model(
    model_key: str,
    model_path: Path,
    speakers: list[Speaker],
    impostor_clips: list[Path],
    window_lengths: list[float],
    enroll_window_s: float,
    max_far: float,
    noise: bool,
    seed: int,
) -> dict:
    print(f"\n=== {model_key} ({model_path.name}, {model_path.stat().st_size/1e6:.1f} MB) ===")
    embedder = SpeakerEmbedder(model_path)
    rng = np.random.default_rng(seed)

    # Enrollment
    enrolled: dict[str, np.ndarray] = {}
    babble_pool: list[np.ndarray] = []
    for sp in speakers:
        wave = load_wav(sp.clips["1"])
        babble_pool.append(wave)
        v = enroll(embedder, sp, enroll_window_s)
        if v is not None:
            enrolled[sp.slug] = v
    if len(enrolled) < 2:
        print("  fewer than 2 usable enrolled speakers, skipping model.")
        return {"model": model_key, "file": model_path.name, "error": "not enough enrolled speakers"}
    labels = list(enrolled)
    matrix = np.stack([enrolled[s] for s in labels])  # (S, dim)

    # Speed
    five_s_window = None
    probe_wave = load_wav(speakers[0].clips["3"])
    probe_windows = cut_windows(probe_wave, 5.0)
    if probe_windows:
        five_s_window = probe_windows[0]
    embed_ms_5s = embedder.timing_ms(five_s_window) if five_s_window is not None else float("nan")

    results = {}
    for win_s in window_lengths:
        key = f"{win_s:g}s"
        genuine_true_scores = []
        top1_correct = 0
        top1_total = 0
        confusion = Counter()
        top_scores_per_window = []  # (true_label, top_score, predicted_label)

        for sp in speakers:
            if sp.slug not in enrolled:
                continue
            wave = load_wav(sp.clips["3"])
            windows = cut_windows(wave, win_s)
            for w in windows:
                if noise:
                    w = degrade(w, rng, babble_pool)
                v = embedder.embed(w)
                if v is None:
                    continue
                scores = matrix @ v
                top_idx = int(np.argmax(scores))
                pred = labels[top_idx]
                top1_total += 1
                if pred == sp.slug:
                    top1_correct += 1
                else:
                    confusion[(sp.slug, pred)] += 1
                true_idx = labels.index(sp.slug)
                genuine_true_scores.append(scores[true_idx])
                top_scores_per_window.append((sp.slug, float(scores[top_idx]), pred))

        impostor_top_scores = []
        for clip in impostor_clips:
            wave = load_wav(clip)
            windows = cut_windows(wave, win_s)
            for w in windows:
                if noise:
                    w = degrade(w, rng, babble_pool)
                v = embedder.embed(w)
                if v is None:
                    continue
                scores = matrix @ v
                impostor_top_scores.append(float(np.max(scores)))

        closed_acc = top1_correct / top1_total if top1_total else float("nan")
        impostor_arr = np.array(impostor_top_scores, dtype=np.float32)
        genuine_top_arr = np.array([s for _, s, _ in top_scores_per_window], dtype=np.float32)
        genuine_true_arr = np.array(genuine_true_scores, dtype=np.float32)

        thr = threshold_for_far(impostor_arr, max_far)
        accepted_and_correct = sum(
            1 for true_l, s, pred_l in top_scores_per_window if pred_l == true_l and s >= thr
        )
        open_set_acc = accepted_and_correct / top1_total if top1_total else float("nan")
        far_at_thr = float(np.mean(impostor_arr >= thr)) if len(impostor_arr) else float("nan")
        eer = eer_from_scores(genuine_true_arr, impostor_arr)

        top_confusions = confusion.most_common(5)
        results[key] = {
            "n_test_windows": top1_total,
            "n_impostor_windows": len(impostor_arr),
            "closed_set_top1_acc": closed_acc,
            "open_set_threshold": thr,
            "open_set_far_at_threshold": far_at_thr,
            "open_set_enrolled_acc_at_threshold": open_set_acc,
            "eer": eer,
            "top_confusions": [
                {"true": t, "predicted": p, "count": c} for (t, p), c in top_confusions
            ],
        }
        print(
            f"  {key:>5}: closed={closed_acc:.1%}  open@thr({thr:.2f})={open_set_acc:.1%} "
            f"(FAR={far_at_thr:.1%})  EER={eer:.1%}  n={top1_total}/{len(impostor_arr)}imp"
        )

    return {
        "model": model_key,
        "file": model_path.name,
        "size_mb": round(model_path.stat().st_size / 1e6, 1),
        "embedding_dim": embedder.dim,
        "num_speakers_enrolled": len(enrolled),
        "num_impostor_clips": len(impostor_clips),
        "embed_ms_5s_window": round(embed_ms_5s, 2),
        "noise": noise,
        "windows": results,
    }


# --------------------------------------------------------------------------
# Smoke test: fake speakers out of spikes/stt/audio so the pipeline can be
# exercised before real enrollment audio exists. Results are meaningless.
# --------------------------------------------------------------------------

def build_smoke_audio(stt_audio_dir: Path, out_dir: Path) -> None:
    wavs = sorted(stt_audio_dir.glob("*.wav"))
    if len(wavs) < 2:
        sys.exit(f"need at least 2 wav files under {stt_audio_dir} for --smoke-test")
    *speaker_sources, impostor_source = wavs  # last file plays "impostor"

    for i, src in enumerate(speaker_sources, start=1):
        wave = load_wav(src)
        third = len(wave) // 3
        slug = f"pseudo-{i:02d}"
        sdir = out_dir / slug
        sdir.mkdir(parents=True, exist_ok=True)
        for j in range(3):
            part = wave[j * third : (j + 1) * third if j < 2 else len(wave)]
            sf.write(str(sdir / f"{j+1}.wav"), part, SR)

    imp_dir = out_dir / IMPOSTOR_DIRNAME
    imp_dir.mkdir(parents=True, exist_ok=True)
    wave = load_wav(impostor_source)
    chunk = 20 * SR
    n_chunks = min(4, len(wave) // chunk)
    for i in range(n_chunks):
        part = wave[i * chunk : (i + 1) * chunk]
        sf.write(str(imp_dir / f"impostor-{i+1}.wav"), part, SR)

    print(
        f"smoke audio: {len(speaker_sources)} pseudo-speakers from "
        f"{[p.name for p in speaker_sources]}, impostors from {impostor_source.name}"
    )


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--audio-dir", default=str(HERE / "audio"))
    parser.add_argument("--models-dir", default=str(HERE / "models"))
    parser.add_argument("--results-dir", default=str(HERE / "results"))
    parser.add_argument("--models", default=",".join(MODELS), help="comma-separated subset of model keys")
    parser.add_argument("--windows", default="3,5,8", help="comma-separated window lengths in seconds")
    parser.add_argument("--enroll-window", type=float, default=10.0)
    parser.add_argument("--max-far", type=float, default=0.02, help="max impostor accept rate for threshold pick")
    parser.add_argument("--noise", action="store_true", help="simulate TV-through-room-mic degradation")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--smoke-test", action="store_true", help="fake speakers from spikes/stt/audio, prove the pipeline runs")
    parser.add_argument("--stt-audio-dir", default=str(HERE.parent / "stt" / "audio"))
    args = parser.parse_args()

    window_lengths = [float(x) for x in args.windows.split(",")]
    model_keys = [k.strip() for k in args.models.split(",") if k.strip()]

    smoke_tmp = None
    if args.smoke_test:
        smoke_tmp = tempfile.TemporaryDirectory(prefix="debunkax-speakers-smoke-")
        audio_dir = Path(smoke_tmp.name)
        build_smoke_audio(Path(args.stt_audio_dir), audio_dir)
        print("*** SMOKE TEST: pseudo-speakers, results are meaningless ***")
    else:
        audio_dir = Path(args.audio_dir)

    speakers = discover_speakers(audio_dir)
    impostor_clips = discover_impostor_clips(audio_dir)
    print(f"found {len(speakers)} usable speakers, {len(impostor_clips)} impostor clips")
    if len(speakers) < 2:
        sys.exit("need at least 2 speakers with clips 1,2,3 to run an eval")

    results_dir = Path(args.results_dir)
    if not args.smoke_test:
        results_dir.mkdir(parents=True, exist_ok=True)

    models_dir = Path(args.models_dir)
    summary_rows = []
    embed_ms_by_model: dict[str, float] = {}
    for key in model_keys:
        fname = MODELS.get(key)
        if fname is None:
            print(f"unknown model key {key!r}, skipping")
            continue
        model_path = models_dir / fname
        if not model_path.is_file():
            print(f"missing model file {model_path}, skipping (run download_models.sh)")
            continue
        result = run_model(
            model_key=key,
            model_path=model_path,
            speakers=speakers,
            impostor_clips=impostor_clips,
            window_lengths=window_lengths,
            enroll_window_s=args.enroll_window,
            max_far=args.max_far,
            noise=args.noise,
            seed=args.seed,
        )
        if not args.smoke_test:
            suffix = "-noise" if args.noise else ""
            out_path = results_dir / f"{key}{suffix}.json"
            out_path.write_text(json.dumps(result, indent=2, default=str))
        embed_ms_by_model[key] = result.get("embed_ms_5s_window", float("nan"))
        for win_key, win_res in result.get("windows", {}).items():
            summary_rows.append((key, win_key, win_res))

    if summary_rows:
        print("\nmodel                   win  closed   open@thr  EER    ms/emb(5s)")
        for key, win_key, r in summary_rows:
            print(
                f"{key:<24}{win_key:>4} {r['closed_set_top1_acc']:>7.1%} "
                f"{r['open_set_enrolled_acc_at_threshold']:>9.1%} {r['eer']:>5.1%}  "
                f"{embed_ms_by_model[key]:>6.1f}"
            )

    if smoke_tmp is not None:
        smoke_tmp.cleanup()


if __name__ == "__main__":
    main()
