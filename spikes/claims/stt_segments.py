#!/usr/bin/env python3
"""Segments the STT spike's WAV excerpts exactly like the app does, to measure how the
Claim filter performs on real speech-to-text output instead of clean subtitle sentences.

Mirrors app/src/main/java/app/debunkax/speech/SherpaSpeechEngine.kt:
  - sherpa-onnx streaming zipformer, French (same model + version as spikes/stt),
    numThreads=2, featureDim=80, sample rate 16000.
  - Audio is streamed in 100 ms chunks (SHERPA_CHUNK_SAMPLES in AudioSource.kt).
  - enableEndpoint=true with EndpointConfig() at its *default* values: sherpa-onnx's
    own default EndpointConfig is rule1_min_trailing_silence=2.4s,
    rule2_min_trailing_silence=1.2s, rule3_min_utterance_length=20.0s (the same values
    OnlineRecognizer.from_transducer()'s own defaults use, and this Python package and
    the Android AAR are both sherpa-onnx 1.13.8, so the underlying C++ EndpointConfig
    default is shared).
  - On each chunk: accept_waveform, decode while ready, check is_endpoint. On an
    endpoint, reset() the stream (which also clears its text) and, if the text before
    reset was non-blank, that's one final segment -- exactly SherpaSpeechEngine's
    `if (isEndpoint) { recognizer.reset(stream); if (text.isNotBlank()) emit(...) }`.
  - Deliberately does NOT flush a trailing partial at end-of-file: the app doesn't
    either (no input_finished() call in SherpaSpeechEngine.runSession), so any speech
    after the last endpoint before the audio cuts off is genuinely lost, same as in
    production. This is intentional, not an oversight.

Writes data/stt_segments.csv (source, start, end, text) -- gitignored, never audio or a
full transcript, only short recognized segments (same policy as the on-device History:
this spike file exists only to label and evaluate the filter, never committed).

Usage:
  .venv/bin/python stt_segments.py \
      --audio-dir ../stt/audio \
      --model-dir ../stt/models/sherpa-onnx-streaming-zipformer-fr-2023-04-14 \
      --out data/stt_segments.csv

Run with the spikes/stt venv's Python (sherpa-onnx + numpy), e.g.:
  ../stt/.venv/bin/python3 stt_segments.py ...
"""
import argparse
import csv
import sys
import wave
from pathlib import Path

import numpy as np

CHUNK_MS = 100
SAMPLE_RATE = 16000
CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS // 1000


def read_wav_samples(wav_path: Path):
    with wave.open(str(wav_path), "rb") as wf:
        assert wf.getnchannels() == 1, f"{wav_path} is not mono"
        assert wf.getsampwidth() == 2, f"{wav_path} is not 16-bit PCM"
        assert wf.getframerate() == SAMPLE_RATE, f"{wav_path} is not {SAMPLE_RATE} Hz"
        n = wf.getnframes()
        raw = wf.readframes(n)
    # Same conversion as SherpaSpeechEngine.kt: chunk[it] / 32768.0f
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    return samples


def build_recognizer(model_dir: Path):
    import sherpa_onnx

    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(model_dir / "tokens.txt"),
        encoder=str(model_dir / "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx"),
        decoder=str(model_dir / "decoder-epoch-29-avg-9-with-averaged-model.onnx"),
        joiner=str(model_dir / "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx"),
        num_threads=2,
        sample_rate=SAMPLE_RATE,
        feature_dim=80,
        decoding_method="greedy_search",
        enable_endpoint_detection=True,
        # sherpa-onnx's own EndpointConfig defaults -- same as Kotlin's EndpointConfig()
        # with no arguments, since both bindings wrap the same 1.13.8 C++ library.
        rule1_min_trailing_silence=2.4,
        rule2_min_trailing_silence=1.2,
        rule3_min_utterance_length=20.0,
    )


def segment_file(recognizer, samples: np.ndarray, source: str):
    """Yields (source, start_s, end_s, text) tuples, one per detected endpoint."""
    stream = recognizer.create_stream()
    n = len(samples)
    segment_start = 0.0
    pos = 0
    while pos < n:
        chunk = samples[pos:pos + CHUNK_SAMPLES]
        pos += len(chunk)
        stream.accept_waveform(SAMPLE_RATE, chunk)
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)

        is_endpoint = recognizer.is_endpoint(stream)
        text = recognizer.get_result(stream).strip()
        now = pos / SAMPLE_RATE

        if is_endpoint:
            recognizer.reset(stream)
            if text:
                yield (source, round(segment_start, 2), round(now, 2), text)
            segment_start = now
    # No trailing flush: matches SherpaSpeechEngine, which never calls
    # input_finished() -- speech after the last endpoint is dropped, same as in
    # a real Listening Session ending mid-utterance.


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--audio-dir", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("data/stt_segments.csv"))
    args = parser.parse_args()

    wav_files = sorted(args.audio_dir.glob("*.wav"))
    if not wav_files:
        sys.exit(f"No .wav files found in {args.audio_dir}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    total_duration = 0.0
    for wav_path in wav_files:
        source = wav_path.stem
        print(f"Segmenting {source}...", file=sys.stderr)
        samples = read_wav_samples(wav_path)
        total_duration += len(samples) / SAMPLE_RATE
        recognizer = build_recognizer(args.model_dir)
        file_rows = list(segment_file(recognizer, samples, source))
        print(f"  {len(file_rows)} segments", file=sys.stderr)
        rows.extend(file_rows)

    with open(args.out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["source", "start", "end", "text"])
        w.writerows(rows)

    print(
        f"Wrote {len(rows)} segments from {len(wav_files)} files "
        f"({total_duration / 60:.1f} min total audio) to {args.out}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
