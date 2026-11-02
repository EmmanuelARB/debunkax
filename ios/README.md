# Debunkax for iOS

Native Swift 6 / SwiftUI port of the Android app (ADR 0007, plan in `../docs/ios-port-plan.md`). iOS 26 minimum, iPhone and iPad.

> **Not yet compiled.** This code was written on a Linux machine with no Mac, Xcode or Swift toolchain. Nothing here has been built, run or tested, including the unit tests and the sherpa-onnx build script. Expect compile errors on the first build (see "Known first-build risks" in `../docs/STATUS.md`).

## Prerequisites

- A Mac with Xcode 26 (iOS 26 SDK) and an iPhone or iPad on iOS 26 for real sessions. The simulator has no Apple speech model, so it falls back to sherpa-onnx.
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).
- CMake, wget and git, for building sherpa-onnx (`brew install cmake wget`).
- Python, to run the speaker export (see step 1).

## Build

From the repository root:

1. Export the speaker model and voice prints into the shared assets (the iOS project reads them from `app/src/main/assets/speakers/`):
   `cd spikes/speakers && python export_prints.py` (see `spikes/speakers/` for its venv and inputs). Without them the app still builds but cannot name Speakers.
2. Build sherpa-onnx v1.13.8 and its onnxruntime for iOS: `ios/scripts/build-sherpa.sh`. It fills `ios/Vendor/` (gitignored) with `sherpa-onnx.xcframework`, `onnxruntime.xcframework` and `SherpaOnnx.swift`. Slow.
3. Generate the Xcode project: `cd ios && xcodegen generate`. `Debunkax.xcodeproj` is gitignored.
4. Open `ios/Debunkax.xcodeproj`, set your team under Signing (`DEVELOPMENT_TEAM` is empty in `project.yml`), pick a device or simulator, run.
   From a terminal: `xcodebuild -project ios/Debunkax.xcodeproj -scheme Debunkax -destination 'generic/platform=iOS Simulator' build`.

If the build reports Swift concurrency errors in `Vendor/SherpaOnnx.swift`, the per-file Swift 5 flag was ignored: set `SWIFT_VERSION` to `5` for the app target.

## Tests

The pure logic lives in the Swift package and needs no simulator:

```
cd ios/DebunkaxKit && swift test
```

The scheme `DebunkaxKitTests` in the XcodeGen project runs the same package tests from Xcode. The tests read `claim_model.json` from `app/src/main/assets/` through `#filePath`. The app target itself has no UI tests.

## Debug key

Put a developer OpenRouter key only in `ios/Debunkax/App/Debug.local.xcconfig` (untracked):

```
OPENROUTER_DEBUG_KEY = sk-or-v1-...
```

It is read by Debug builds only (`Debug.xcconfig` includes it, via Info.plist); `Release.xcconfig` keeps it empty. Never put a key anywhere else, and never in chat.

## Demo modes (Debug builds only)

In Réglages: "Mode démo" (scripted transcript, fake Verdicts) and "Démo audio réelle", which replays `Documents/demo.wav` (16 kHz mono WAV, like the Android `files/demo.wav`) at real-time pace through SpeechAnalyzer, or sherpa-onnx where Apple's is unsupported.

`Info.plist` does **not** set `UIFileSharingEnabled`, so Finder file sharing will not show the app. Either add `UIFileSharingEnabled` = YES (and optionally `LSSupportsOpeningDocumentsInPlace`) to `Debunkax/App/Info.plist` for your local debug builds, or on a simulator copy the file into the app's data container:

```
xcrun simctl get_app_container booted app.debunkax data
cp demo.wav "<printed path>/Documents/demo.wav"
```

## TestFlight

Distribution is TestFlight / App Store; there is no launch-time update check on iOS. Set your team and bundle identifier, make sure an app icon exists (none yet), archive the Release configuration (`MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` in `project.yml`), and upload from Xcode Organizer. `ITSAppUsesNonExemptEncryption` is already false. The privacy answers must say that Claims and transcript windows go to the user's own OpenRouter account and nothing else leaves the device, apart from Apple's speech asset download and the one-time sherpa-onnx model download.

## Layout

See "Code map" in `../AGENTS.md`. `DebunkaxKit/` is the package (`DebunkaxCore`, `DebunkaxData`), `Debunkax/` the app target, `scripts/build-sherpa.sh` and `project.yml` the build tooling.
