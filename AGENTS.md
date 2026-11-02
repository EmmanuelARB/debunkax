# AGENTS.md

Debunkax is a French-only app for Android and iOS (the iOS port, in `ios/`, compiles and passes its unit tests in CI but has never been run: ADR 0007, `docs/STATUS.md`). While a Listening Session is running, it transcribes the French speech it hears (mostly TV political debates, shows and journalism), detects Claims on the device, checks them with the user's own AI provider, and raises Alerts for false, misleading or lacks-context Verdicts.

## Read first

- `docs/STATUS.md`: current state, what's verified and what isn't, and the next step. Update it whenever that changes.
- `CONTEXT.md`: the glossary. Use its terms exactly (Listening Session, Claim, Check, Verdict, Pending Claim, Alert, Explanation, History, Trusted Source) in code, UI copy and docs. Don't use the words listed under `_Avoid_`.
- `docs/adr/`: architectural decisions. Don't go against one without writing a new ADR that supersedes it.
- `docs/possible-evolutions.md`: features deliberately left out of the first version. Don't build them unasked.

## Non-negotiable rules

- **Never persist audio or transcripts.** Raw audio is discarded as soon as it's transcribed. The rolling transcript window (about 30 s) lives in memory only. Only Claims, their Verdicts, context, Explanations, sources and timestamps go to disk (the History).
- **Mic sessions listen only in the foreground**, started by the user with the big button. On Android, app-audio sessions (ADR 0004) are the exception (iOS has no app-audio source and no background listening at all: mic sessions stop when the scene goes to the background): started under the platform's per-session consent, with a mandatory notification and a red recording icon the user can revoke at any time, they keep running while the app is in the background, because the audio plays in another app. No hidden background service, no listening on boot, screen kept awake during a foreground session.
- **Nothing leaves the phone except Checks** (Claim + transcript window) sent to the user's chosen provider, plus the launch-time update check against the public release page (Android only; iOS is distributed through TestFlight/App Store) and the one-time download of the sherpa-onnx speech model from its official GitHub release (a plain GET for a public file, sending no user data). On iOS, also Apple's own on-device speech asset download (`AssetInventory`, system-managed, sending no user data; ADR 0007). There is no Debunkax backend, analytics or crash reporting that sends data.
- **Bring your own key**: OpenRouter (OAuth PKCE). The key lives encrypted in Android Keystore, or in the iOS Keychain (this device only, never synced). Every request asks for zero data retention where the provider supports it. (Claude BYOK was removed — ADR 0005.)
- **Checks must be grounded in Trusted Sources** through provider-side web search restricted to the allowlisted domains. Only offer models or engines where that restriction actually works (see ADR 0001). Never quietly fall back to an unrestricted search.
- **A Claim names its Speaker only when recognised by voice with confidence, else says unknown** (ADR 0003). Never guess from what was said.
- **French only**: audio, UI and generated text.

## Stack

- Android: Kotlin, Jetpack Compose, native. minSdk 31 (Android 12), target devices with about 6 GB of RAM or more.
- iOS (ADR 0007): Swift 6, SwiftUI, native, in `ios/`. iOS 26 minimum. Project built from an XcodeGen spec. Built and unit-tested in CI (`.github/workflows/ios.yml`, macOS runner), never run on a simulator or device yet.
- Speech-to-text on the phone: ML Kit GenAI Speech Recognition (fr-FR) on Android, Apple SpeechAnalyzer/SpeechTranscriber (fr-FR) on iOS. Fallback on both: sherpa-onnx streaming zipformer (Moonshine has no French model).
- Claim filter on the phone: TF-IDF + logistic regression in pure Kotlin, and in pure Swift on iOS (`TfidfClaimDetector`, model in `assets/claim_model.json`, shared, trained by `spikes/claims/`).
- Distribution: Android: signed APK, direct download (no Play Store yet). iOS: TestFlight / App Store.

## Code map

Android, `app/src/main/java/app/debunkax/`:
- `domain/`: pure Kotlin model and rules: Claims, Verdicts, AlertPolicy, CheckPrompts, TrustedSources, TfidfClaimDetector, repository and engine interfaces
- `session/`: ListeningSession, the loop from speech to Check to Alert and History
- `speech/`: ML Kit, sherpa-onnx, FallbackSpeechEngine, scripted and file demo engines
- `check/`: the OpenRouter client, and the model list (`Models.kt`)
- `history/`: Room
- `keys/`: Tink key vault and OpenRouter PKCE
- `settings/`: DataStore
- `speaker/`: speaker recognition (voice prints, matching, the per-session timeline)
- `ui/`: Compose screens
- `update/`: release check
- `AppContainer.kt`: manual dependency wiring, debug demo modes and test overrides

iOS, `ios/` (names mirror the Kotlin ones):
- `DebunkaxKit/` (Swift package, no UIKit, testable with `swift test`): `Sources/DebunkaxCore` (port of `domain/`, `session/`, `check/`, speaker matching, PKCE) and `Sources/DebunkaxData` (SQLite History, file-backed settings)
- `Debunkax/` (app target): `App/` (DebunkaxApp, AppContainer, Info.plist, xcconfigs), `Speech/` (mic and file audio, SpeechAnalyzer, sherpa-onnx), `Speaker/`, `Keys/` (Keychain, OpenRouter sign-in), `UI/` (SwiftUI)
- `scripts/build-sherpa.sh` builds the sherpa-onnx xcframeworks into the gitignored `ios/Vendor/`; `project.yml` is the XcodeGen spec; `README.md` has the build steps

`spikes/`: Python experiments behind the ADRs: `stt/` for speech-to-text accuracy, `claims/` for the claim-filter dataset and training, `verdicts/` for the Verdict golden set, `speakers/` for speaker recognition (voice clips, eval, and `export_prints.py`, which copies the model and voice prints into the app's assets: run it before building).

## Build and test

- Export `JAVA_HOME=$(dirname $(dirname $(readlink -f /usr/bin/java)))` (JDK 17) before Gradle. The shell is zsh: unquoted `$VAR` doesn't word-split.
- `./gradlew testDebugUnitTest assembleDebug`: unit tests (JVM, Robolectric for Room) and the debug APK.
- `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`: instrumented Compose tests, using the test overrides in `AppContainer`.
- `./gradlew assembleRelease`: arm64-only. Add `-PemulatorAbi` to include x86_64 for smoke tests. Signing reads `keystore.properties` (see `docs/release.md`).
- `./gradlew testDebugUnitTest --tests '*VerdictBench*' -Pbench`: the paid Verdict bench (`spikes/verdicts/README.md`).
- iOS, on a Mac with Xcode 26 (see `ios/README.md`; CI runs the same steps, without the speaker export): run `spikes/speakers/export_prints.py` first, then `ios/scripts/build-sherpa.sh`, `xcodegen` in `ios/`, `swift test` in `ios/DebunkaxKit`, and `xcodebuild -project ios/Debunkax.xcodeproj -scheme Debunkax ...` for the app.
- Debug-only switches in Réglages: "Mode démo" (scripted transcript and fake Verdicts) and "Démo audio réelle" (replays `files/demo.wav` through ML Kit, or sherpa-onnx where ML Kit is unsupported, e.g. emulators).

## Working conventions

- Developer API keys go only in the gitignored `local.properties` (`debunkax.debug.openRouterKey`). On iOS the debug key goes only in the untracked `ios/Debunkax/App/Debug.local.xcconfig` (`OPENROUTER_DEBUG_KEY`). It reaches debug builds and the Verdict bench only; the release build type hard-codes it empty. Never write a key anywhere else.
- Keep `CONTEXT.md` a glossary only: no implementation details. When a new domain term settles, add it there.
- Write an ADR only for decisions that are hard to reverse, surprising and the result of a real trade-off (format: `.claude/skills/domain-modeling/ADR-FORMAT.md`).
- Any UI text shown to users must be in French.
- No overfitting: never chain paid Verdict-bench tuning rounds — with 46 entries, gains of 1–2 are noise and the bench has no held-out set. Judge changes on real sessions; say plainly when a measurement can't tell.
- Never ask for an API key in chat.
- If a gitignored `AGENTS.local.md` exists, read it too: it holds the maintainer's local setup and preferences.
