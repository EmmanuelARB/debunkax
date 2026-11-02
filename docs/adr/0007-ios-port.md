# iOS is a native Swift/SwiftUI port, microphone only

The app now also targets iPhone and iPad, as a native Swift 6 / SwiftUI project in `ios/` next to the Android `app/`. Same product, same rules (AGENTS.md), same glossary, same bundled assets (`claim_model.json`, the speaker model and prints are read from `app/src/main/assets/`). The port was written without a Mac or Swift toolchain; it is now built and unit-tested in CI on a macOS runner but has not been run yet (see `docs/STATUS.md`).

## Considered options

- **Kotlin Multiplatform / Compose Multiplatform**: rejected. The pure logic is a minority of the code; the speech, audio, speaker, key storage, OAuth, persistence and UI layers are platform-specific on both sides and would stay so. A native port also lets the pure logic (`ios/DebunkaxKit`) be tested with plain `swift test`, with no Xcode project or simulator.

## Decisions

- **Minimum OS iOS 26**, required by `SpeechAnalyzer`.
- **Speech-to-text**: Apple `SpeechAnalyzer` / `SpeechTranscriber`, fr-FR, on device. Fallback: sherpa-onnx streaming zipformer, the same model, checksums and one-time download as on Android, used when Apple reports the device or locale unsupported (simulators, for instance).
- **Apple's on-device speech asset download is a new network exit** (`AssetInventory`). It is managed by the system, fetches a language model and sends no user data. It joins the sherpa model GET among the "nothing leaves the phone except..." exceptions in AGENTS.md.
- **Speaker recognition (ADR 0003)** runs through sherpa-onnx's `SpeakerEmbeddingExtractor` with the same titanet model, `prints.json` and thresholds as Android and `spikes/speakers/`, so the embeddings match the stored prints. Calling onnxruntime directly was rejected: it would mean re-implementing the fbank front end and re-validating the prints.
- **Microphone only.** iOS cannot capture another app's audio without a ReplayKit screen broadcast, the option ADR 0004 already rejected on Android. ADR 0004's app-audio source, and its background exception, are Android-only; the source picker does not exist on iOS.
- **Mic sessions stop when the scene goes to the background** (`scenePhase == .background`); there is no `audio` background mode and no background task. The screen stays on during a session (`isIdleTimerDisabled`).
- **Distribution through TestFlight / the App Store**, so there is no launch-time update check on iOS (one network exit fewer).
- **Storage mirrors Android's backup exclusion and Keystore**: the History (SQLite, same schema as Room), the settings file and the sherpa model live in Application Support with `isExcludedFromBackup`; the OpenRouter key is a Keychain item with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, never synced or migrated.

## Consequences

- Two code bases must be kept in step by hand: prompts, thresholds, French copy and JSON shapes are copied verbatim, and a change to one side needs the same change on the other. The Swift names mirror the Kotlin ones to make that practical.
- The port behaves differently from Android in a few places where Swift forced an explicit choice (listed in `docs/STATUS.md`).
- The privacy rules of AGENTS.md hold unchanged: no audio or transcript on disk, foreground-only mic sessions, and nothing leaves the phone except Checks and the downloads above.
