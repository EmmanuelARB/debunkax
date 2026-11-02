// Objective-C bridging header for the Debunkax app target.
//
// - bzlib.h: the system libbz2 used by Speech/TarBz2.swift (link libbz2.tbd).
// - c-api.h: the sherpa-onnx C API, from Vendor/sherpa-onnx.xcframework (built by scripts/build-sherpa.sh), used by
//   the vendored Vendor/SherpaOnnx.swift wrapper and by Speech/SherpaSpeechEngine.swift, Speaker/SpeakerIdentifier.swift.
#include <bzlib.h>
#include <SherpaOnnxC/sherpa-onnx/c-api/c-api.h>
