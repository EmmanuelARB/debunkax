#!/usr/bin/env bash
# Downloads sherpa-onnx speaker embedding models used by eval.py.
#
# Source: the sherpa-onnx "speaker-recongition-models" GitHub release (yes,
# that's the upstream tag's own typo):
#   https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models
#
# We picked 4 candidates spanning architectures/training sets:
#   - CAM++ (3D-Speaker), trained on VoxCeleb
#   - ERes2NetV2 (3D-Speaker), trained on a large merged zh-cn corpus
#   - WeSpeaker ResNet34, VoxCeleb, large-margin fine-tuned (LM)
#   - NeMo TitaNet-small
# All four are language-agnostic speaker embedding models (not ASR), so
# training language doesn't have to match French; what matters is how well
# the embedding separates speakers under TV/room-mic conditions.
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p models
cd models

BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models"

declare -a MODELS=(
  "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
  "3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx"
  "wespeaker_en_voxceleb_resnet34_LM.onnx"
  "nemo_en_titanet_small.onnx"
)

for name in "${MODELS[@]}"; do
  if [ -f "$name" ]; then
    echo "$name already present, skipping."
    continue
  fi
  echo "Downloading $name"
  curl -L -o "$name" "${BASE}/${name}"
done

echo "Models ready in $(pwd)"
ls -lh .
