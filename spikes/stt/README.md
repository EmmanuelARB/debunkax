# STT spike: French streaming ASR on desktop

Test harness for on-device French STT, ahead of building the Android app.

## Setup

    python3 -m venv .venv   # system Python 3.14 has wheels for everything used here
    .venv/bin/pip install -r requirements.txt
    ./download_models.sh    # fetches the sherpa-onnx French model into models/

If your system Python lacks wheels for sherpa-onnx/onnxruntime, bootstrap `uv`
in a throwaway venv, `uv python install 3.12`, then `uv venv --python 3.12
.venv` before the pip install above.

## Usage

    .venv/bin/python transcribe.py --engine sherpa AUDIO [--ref REF.txt] [--out DIR]

AUDIO is any ffmpeg-readable format, converted to 16 kHz mono and streamed in
~100 ms chunks. `--ref` accepts .txt, .srt, or .vtt. Transcript goes to
`results/<stem>.<engine>.txt`; stdout prints duration, processing time, RTF,
peak RSS, and WER/CER (French-aware normalization) if `--ref` is given.

## Engines

- `sherpa`: sherpa-onnx streaming zipformer, French, int8
  (`sherpa-onnx-streaming-zipformer-fr-2023-04-14`).
- `moonshine`: not usable. The official `moonshine-voice` package (Feb 2026)
  supports only ar/es/de/en/ja/ko/vi/uk/zh/tl, no French. `--engine moonshine`
  fails fast with that explanation instead of substituting another model.
