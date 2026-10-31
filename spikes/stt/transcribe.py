#!/usr/bin/env python3
"""Streaming French STT test harness (sherpa-onnx / moonshine-voice).

Usage:
  python transcribe.py --engine {sherpa,moonshine} AUDIO [--ref REF.txt] [--out DIR]
"""
import argparse
import re
import resource
import subprocess
import sys
import tempfile
import time
import wave
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
MODELS_DIR = HERE / "models"
CHUNK_MS = 100
SAMPLE_RATE = 16000


def convert_to_wav(audio_path: Path, tmp_dir: Path) -> Path:
    out_path = tmp_dir / "audio_16k_mono.wav"
    subprocess.run(
        [
            "ffmpeg", "-y", "-loglevel", "error", "-i", str(audio_path),
            "-ac", "1", "-ar", str(SAMPLE_RATE), "-f", "wav", str(out_path),
        ],
        check=True,
    )
    return out_path


def read_wav_samples(wav_path: Path):
    with wave.open(str(wav_path), "rb") as wf:
        assert wf.getnchannels() == 1
        assert wf.getsampwidth() == 2
        assert wf.getframerate() == SAMPLE_RATE
        n = wf.getnframes()
        raw = wf.readframes(n)
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    duration = n / SAMPLE_RATE
    return samples, duration


def chunked(samples, chunk_size):
    for i in range(0, len(samples), chunk_size):
        yield samples[i:i + chunk_size]


def run_sherpa(samples):
    import sherpa_onnx

    model_dir = MODELS_DIR / "sherpa-onnx-streaming-zipformer-fr-2023-04-14"
    if not model_dir.exists():
        sys.exit(f"Model not found at {model_dir}. Run download_models.sh first.")

    recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(model_dir / "tokens.txt"),
        encoder=str(model_dir / "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx"),
        decoder=str(model_dir / "decoder-epoch-29-avg-9-with-averaged-model.onnx"),
        joiner=str(model_dir / "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx"),
        num_threads=2,
        sample_rate=SAMPLE_RATE,
        feature_dim=80,
        decoding_method="greedy_search",
    )
    stream = recognizer.create_stream()
    chunk_size = int(SAMPLE_RATE * CHUNK_MS / 1000)

    for chunk in chunked(samples, chunk_size):
        stream.accept_waveform(SAMPLE_RATE, chunk)
        time.sleep(0)  # yield, keeps the loop honest about being a stream
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)

    tail = np.zeros(int(SAMPLE_RATE * 0.5), dtype=np.float32)
    stream.accept_waveform(SAMPLE_RATE, tail)
    stream.input_finished()
    while recognizer.is_ready(stream):
        recognizer.decode_stream(stream)

    return recognizer.get_result(stream).strip()


def run_moonshine(samples, duration):
    try:
        import moonshine_voice as mv
    except ImportError:
        sys.exit("moonshine-voice is not installed.")

    try:
        mv.get_model_for_language("fr")
    except ValueError as e:
        sys.exit(
            "Moonshine has no official French model or streaming support: "
            f"{e}\nSee README.md for details. Not substituting another engine."
        )
    raise RuntimeError("unreachable: French became supported, update this script")


def model_size_bytes(engine):
    if engine == "sherpa":
        model_dir = MODELS_DIR / "sherpa-onnx-streaming-zipformer-fr-2023-04-14"
        files = [
            "tokens.txt",
            "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
            "decoder-epoch-29-avg-9-with-averaged-model.onnx",
            "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx",
        ]
        return sum((model_dir / f).stat().st_size for f in files if (model_dir / f).exists())
    return 0


# --- reference normalization / metrics -------------------------------------

def strip_subtitle_markup(text: str) -> str:
    lines = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.isdigit():
            continue
        if re.match(r"^\d\d:\d\d:\d\d[,.]\d\d\d\s*-->", line):
            continue
        if line.startswith("WEBVTT"):
            continue
        line = re.sub(r"<[^>]+>", "", line)
        lines.append(line)
    return " ".join(lines)


def load_ref(ref_path: Path) -> str:
    text = ref_path.read_text(encoding="utf-8")
    if ref_path.suffix.lower() in (".srt", ".vtt"):
        return strip_subtitle_markup(text)
    return text.strip()


def normalize_fr(text: str) -> str:
    text = text.lower()
    text = text.replace("\u2019", "'")
    text = re.sub(r"[^\w\s']", " ", text, flags=re.UNICODE)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def compute_wer_cer(ref: str, hyp: str):
    import jiwer

    ref_n, hyp_n = normalize_fr(ref), normalize_fr(hyp)
    wer = jiwer.wer(ref_n, hyp_n)
    cer = jiwer.cer(ref_n, hyp_n)
    return wer, cer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine", choices=["sherpa", "moonshine"], required=True)
    parser.add_argument("audio", type=Path)
    parser.add_argument("--ref", type=Path, default=None)
    parser.add_argument("--out", type=Path, default=HERE / "results")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as td:
        wav_path = convert_to_wav(args.audio, Path(td))
        samples, duration = read_wav_samples(wav_path)

        start = time.monotonic()
        if args.engine == "sherpa":
            transcript = run_sherpa(samples)
        else:
            transcript = run_moonshine(samples, duration)
        elapsed = time.monotonic() - start

    peak_rss_kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    peak_rss_mb = peak_rss_kb / 1024

    out_file = args.out / f"{args.audio.stem}.{args.engine}.txt"
    out_file.write_text(transcript + "\n", encoding="utf-8")

    rtf = elapsed / duration if duration > 0 else float("nan")
    print(f"audio duration : {duration:.2f} s")
    print(f"processing time: {elapsed:.2f} s")
    print(f"real-time factor: {rtf:.3f}")
    print(f"peak RSS       : {peak_rss_mb:.1f} MB")
    print(f"transcript     : {transcript}")
    print(f"written to     : {out_file}")

    if args.ref:
        ref_text = load_ref(args.ref)
        wer, cer = compute_wer_cer(ref_text, transcript)
        print(f"WER            : {wer:.3f}")
        print(f"CER            : {cer:.3f}")


if __name__ == "__main__":
    main()
