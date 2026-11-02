# iOS port plan

Native Swift/SwiftUI port of the Android app, living in `ios/` next to `app/`. Same product, same rules (AGENTS.md), same glossary (CONTEXT.md), same assets. Written on a Linux machine without Xcode or a Swift toolchain: **nothing in `ios/` has been compiled or run** until someone builds it on a Mac. Every status line says so.

## Decisions taken with the user

| Topic | Decision |
|---|---|
| Approach | Native Swift 6 + SwiftUI rewrite. The Android project is not touched. |
| Minimum OS | iOS 26 (required by SpeechAnalyzer). iPhone and iPad. |
| Speech-to-text | Primary: Apple `SpeechAnalyzer` + `SpeechTranscriber` (fr-FR, on device). Fallback: sherpa-onnx streaming zipformer fr (same model, same checksums, same one-time download as Android). |
| App-audio source (ADR 0004) | Not on iOS: iOS can't capture another app's audio without a ReplayKit screen broadcast. Microphone only; the source picker is hidden. New ADR. |
| Speaker recognition (ADR 0003) | Same `titanet_small.onnx` and `prints.json`, same thresholds (0.45, margin 0.05), run through sherpa-onnx's `SpeakerEmbeddingExtractor` (C API; it embeds onnxruntime). That is what Android and `spikes/speakers/` use, so the embeddings match the stored prints; calling onnxruntime directly would mean re-implementing the fbank front end and re-validating the prints. |
| Distribution | TestFlight / App Store. No launch-time update check on iOS (one network exit fewer). |
| Project file | XcodeGen `project.yml`; no hand-written `.pbxproj`. |
| Tests | XCTest ports of the JVM unit tests that cover pure logic. |
| Docs | New ADR(s), AGENTS.md and docs/STATUS.md updated, `ios/README.md`. |

## Layout

```
ios/
  README.md                  build on a Mac: xcodegen, sherpa build script, tests, TestFlight
  project.yml                XcodeGen: app target Debunkax, test target, package dependency on ./DebunkaxKit
  .gitignore                 Vendor/, *.xcodeproj, DerivedData
  scripts/build-sherpa.sh    builds sherpa-onnx v1.13.8 as sherpa-onnx.xcframework (+ onnxruntime) into Vendor/
  DebunkaxKit/               Swift package, no UIKit/AVFoundation: testable with `swift test` on a Mac
    Package.swift            products DebunkaxCore, DebunkaxData; platforms iOS 26, macOS 26
    Sources/DebunkaxCore/    port of domain/, session/ logic, check/, speaker/ matching, keys/Pkce
    Sources/DebunkaxData/    SQLite History repository (system SQLite3), file-backed settings
    Tests/DebunkaxCoreTests/ Tests/DebunkaxDataTests/
  Debunkax/                  app target (Apple frameworks, sherpa, UI)
    App/                     DebunkaxApp, AppContainer (composition root, debug demo modes), Info.plist, entitlements
    Speech/                  MicAudioSource (AVAudioEngine), FileAudioSource, SpeechAnalyzerEngine, SherpaSpeechEngine,
                             SherpaModelManager, TarBz2 extraction (libbz2), SherpaOnnx.swift wrapper + bridging header
    Speaker/                 SpeakerIdentifier (sherpa C API), SpeakerTracker glue
    Keys/                    KeychainKeyVault, OpenRouterAuth (ASWebAuthenticationSession)
    UI/                      SwiftUI screens (Listen, AlertOverlay, History, ClaimDetail, Settings, TrustedSources, Privacy, SpeakerTest)
```

Bundled resources are referenced from their Android location in `project.yml` (`../app/src/main/assets/claim_model.json`, `.../speakers/prints.json`, `.../speakers/titanet_small.onnx`), so `spikes/speakers/export_prints.py` stays the single source and nothing is duplicated. Tests that need `claim_model.json` find it from `#filePath`.

## Kotlin to Swift mapping

| Kotlin | Swift |
|---|---|
| `Instant`, `Duration` | `Date`, `TimeInterval` (seconds); clocks injected as `@Sendable () -> Date` |
| `Flow<T>` | `AsyncThrowingStream<T, Error>` (speech, audio), `AsyncStream<T>` (history observation) |
| `StateFlow` + ViewModel | `@MainActor @Observable` classes |
| coroutines, `Semaphore` | structured concurrency, `Task`, an actor-based limiter |
| `@Synchronized` classes | `final class` + `Mutex` (Synchronization) or actors, all `Sendable` |
| sealed classes | enums with associated values; `CheckFailure`, `SpeechFailure` conform to `Error` |
| kotlinx.serialization | `Codable` / `JSONSerialization` |
| OkHttp | `URLSession` behind a small `HTTPTransport` protocol so tests can stub it |
| Room | SQLite3 C API in an actor; same schema (`sessions`, `claims`, cascade); change notifications fan out to `AsyncStream`s |
| DataStore settings | JSON file in Application Support (excluded from backup), not UserDefaults |
| Tink + Android Keystore | Keychain, `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` (never synced or migrated) |
| Custom Tabs + intent filter | `ASWebAuthenticationSession`, callback scheme `debunkax` |
| `FLAG_KEEP_SCREEN_ON` | `UIApplication.shared.isIdleTimerDisabled` during a session |

Public Swift names mirror the Kotlin ones (Verdict, HeardClaim, CheckResult, CheckOutcome, CheckFailure, CheckClient, HistoryRepository, ClaimRecord, SessionHistory, HistoryFilter, SpeechEngine, TranscriptSegment, SpeakerRecognition, AlertPolicy, CheckBudget, RepeatedClaimMatcher, RollingTranscript, Sentences, SentenceTiming, SecondPerson, CheckPrompts, TrustedSources, TfidfClaimDetector, ListeningSession, SessionState, ActiveAlert, ChecksPaused, PendingClaimChecker, ExplanationLoader, OpenRouterCheckClient, Models, SpeakerMatcher, SpeakerTimeline, SpeakerTracker, Pkce). Prompt strings, French UI copy, regexes, thresholds and JSON shapes are copied verbatim.

## Rules that carry over unchanged

- No audio or transcript on disk: audio buffers are converted and dropped chunk by chunk; the rolling transcript is in memory; the SQLite schema has no transcript or audio column.
- Mic sessions run only in the foreground: no `audio` background mode in Info.plist; the session stops on `scenePhase != .active`. No background tasks.
- Network exits: Checks to OpenRouter (ZDR requested), the OpenRouter OAuth exchange, the one-time sherpa model GET. New on iOS: Apple's own on-device speech asset download via `AssetInventory` (system-managed, sends no user data) — recorded in the ADR and in AGENTS.md. No analytics, no crash reporting (no third-party SDK at all).
- Restricted search only, single provider and model (ADR 0001, 0005), Speaker only when recognised (ADR 0003), Addressee rules (ADR 0006), French only.
- History, settings and the sherpa model live in Application Support with `isExcludedFromBackup = true` (mirrors `data_extraction_rules.xml`).
- Debug-only switches ("Mode démo", "Démo audio réelle" reading `Documents/demo.wav`) compiled under `#if DEBUG` only; the developer OpenRouter key comes from an untracked `ios/Debunkax/App/Debug.local.xcconfig` in debug builds only, empty in release.

## Work packages

Delegated to sub-agents one after another (each reads the Kotlin sources it ports; none commits — commits are made by the maintainer identity after review).

1. **Scaffold + DebunkaxCore domain.** `ios/` tree, `.gitignore`, `Package.swift`, `project.yml` skeleton. Port `domain/*` and `check/Models.kt`, `check/CheckErrors.kt`, `check/SpendCounter.kt`, `keys/Pkce.kt` (CryptoKit). Tests: CheckPrompts, RollingTranscript, SecondPerson, SessionRules, TfidfClaimDetector, Pkce.
2. **Session, Check client, speaker logic (DebunkaxCore).** `ListeningSession`, `PendingClaimChecker`, `ExplanationLoader`, `FallbackSpeechEngine`, `ScriptedSpeechEngine`, `OpenRouterCheckClient` + `CheckClientFactory`, `SpeakerMatcher`, `SpeakerTimeline`, the pure part of `SpeakerTracker`. Tests: ListeningSession, PendingClaimChecker, ExplanationLoader, OpenRouterCheckClient (stubbed transport), SpeakerMatcher, SpeakerTimeline, FallbackSpeechEngine, ScriptedSpeechEngine.
3. **DebunkaxData + keys.** `SQLiteHistoryRepository` (filter, ordering, cascade, `closeUnfinishedSessions`, pending claims), `FileSettingsRepository`; app-side `KeychainKeyVault`, `OpenRouterAuth`. Tests: ports of RoomHistoryRepositoryTest and DataStoreSettingsRepositoryTest.
4. **Audio, speech, speaker platform layer.** `AVAudioSession` (`.record`, `.measurement`), `MicAudioSource` (AVAudioEngine tap, AVAudioConverter to 16 kHz mono Int16, 100 ms chunks, interruption handling), `FileAudioSource`, `TappedAudioSource`, `SpeechAnalyzerEngine` (fr-FR, volatile results as partials, finalized as finals, asset install mapped to `SpeechFailure.modelDownloading`, unsupported locale/device mapped to `.unsupported` so `FallbackSpeechEngine` switches), `SherpaModelManager` (URLSession, resume, SHA-256, tar.bz2 extraction), `SherpaSpeechEngine`, `SpeakerIdentifier`, `scripts/build-sherpa.sh`, wrapper and bridging header.
5. **SwiftUI app.** `AppContainer`, `DebunkaxApp`, all screens and view models ported from `ui/`, alert overlay behaviour (newest replaces, easy dismiss), deep-link handling, idle timer, mic permission flow, `Info.plist` (`NSMicrophoneUsageDescription`, `NSSpeechRecognitionUsageDescription` in French, URL scheme), entitlements, `project.yml` completed.
6. **Docs.** ADR 0007 (iOS port: native Swift, SpeechAnalyzer + sherpa, mic only, TestFlight without update check, Apple speech asset download as a new network exit), AGENTS.md (iOS stack, code map, build commands, rule amendments), docs/STATUS.md (iOS section: nothing compiled or verified), `ios/README.md`.

A final review pass (by the maintainer session) checks cross-package API consistency, the privacy rules above, French copy, and that no file mentions an assistant or carries co-author lines.

## Not verified, by construction

Everything. Known risks for the first Mac build: Swift 6 strict-concurrency errors, the exact SpeechAnalyzer/AssetInventory API shapes (iOS 26 SDK), the sherpa-onnx xcframework build and its Swift wrapper version, AVAudioConverter format edge cases, XcodeGen resource paths outside `ios/`.

## Outcome

The six work packages are written and committed. A CI workflow on a macOS runner (`.github/workflows/ios.yml`) then built them: after two test fixes and four compile fixes, `swift test` passes and the app builds for the simulator. It has not been run. The decision is recorded in `docs/adr/0007-ios-port.md`, the current state and known first-build risks in the "iOS port" section of `docs/STATUS.md`, and the build instructions in `ios/README.md`. Where this plan and the code differ (for example the developer key file is `Debug.local.xcconfig`, not `Debug.local.xcconfig`), the code and those documents win.
