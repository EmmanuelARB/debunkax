#!/usr/bin/env bash
# Builds sherpa-onnx v1.13.8 for iOS (device + simulator) and vendors the results into ios/Vendor/:
#   Vendor/sherpa-onnx.xcframework     static framework "SherpaOnnxC" (the C API)
#   Vendor/onnxruntime.xcframework     static onnxruntime, downloaded by sherpa-onnx's own build-ios.sh
#   Vendor/SherpaOnnx.swift            the official Swift wrapper of that tag
# Vendor/ is gitignored. Needs a Mac with Xcode, cmake, wget and git. Takes a while.
set -euo pipefail

TAG="v1.13.8"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENDOR="$SCRIPT_DIR/../Vendor"
WORK="${SHERPA_WORK_DIR:-$SCRIPT_DIR/../.build-sherpa}"

for tool in git cmake wget unzip xcodebuild libtool lipo; do
  command -v "$tool" >/dev/null || { echo "Outil manquant : $tool" >&2; exit 1; }
done

mkdir -p "$WORK" "$VENDOR"
if [ ! -d "$WORK/sherpa-onnx/.git" ]; then
  git clone --depth 1 --branch "$TAG" https://github.com/k2-fsa/sherpa-onnx.git "$WORK/sherpa-onnx"
fi
cd "$WORK/sherpa-onnx"
git checkout --quiet "$TAG"

# build-ios.sh works in ./build-ios relative to the repository root and produces
# build-ios/sherpa-onnx.xcframework; it downloads onnxruntime into build-ios/ios-onnxruntime/.
./build-ios.sh

OUT="$WORK/sherpa-onnx/build-ios"
ORT="$OUT/ios-onnxruntime/onnxruntime.xcframework"
[ -d "$OUT/sherpa-onnx.xcframework" ] || { echo "sherpa-onnx.xcframework introuvable" >&2; exit 1; }
[ -e "$ORT" ] || { echo "onnxruntime.xcframework introuvable" >&2; exit 1; }

rm -rf "$VENDOR/sherpa-onnx.xcframework" "$VENDOR/onnxruntime.xcframework"
cp -R "$OUT/sherpa-onnx.xcframework" "$VENDOR/sherpa-onnx.xcframework"
cp -RL "$ORT" "$VENDOR/onnxruntime.xcframework"   # -L: the build leaves a symlink to the versioned directory

# The wrapper only imports the C module under SWIFT_PACKAGE; in the app target the bridging header provides the symbols.
cp swift-api-examples/SherpaOnnx.swift "$VENDOR/SherpaOnnx.swift"

echo "Prêt : $VENDOR"
ls "$VENDOR"
