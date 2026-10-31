#!/usr/bin/env bash
# Downloads the sherpa-onnx French streaming zipformer model used by transcribe.py.
# Moonshine has no official French model, so there is nothing to download for it
# (see README.md).
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p models
cd models

MODEL=sherpa-onnx-streaming-zipformer-fr-2023-04-14
if [ -d "$MODEL" ]; then
  echo "$MODEL already present, skipping."
  exit 0
fi

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL}.tar.bz2"
echo "Downloading $URL"
curl -L -o "${MODEL}.tar.bz2" "$URL"
tar xjf "${MODEL}.tar.bz2"
rm "${MODEL}.tar.bz2"
echo "Model ready at models/${MODEL}"
