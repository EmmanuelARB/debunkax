# Releasing

1. Create a signing key once and keep it safe outside the repo. Whoever holds it is the only one who can publish updates that install over existing copies:
   `keytool -genkeypair -keystore ~/keys/debunkax-release.jks -alias debunkax -keyalg RSA -keysize 4096 -validity 10000`
2. Create `keystore.properties` at the repo root (gitignored):
   ```
   storeFile=/home/you/keys/debunkax-release.jks
   storePassword=…
   keyAlias=debunkax
   keyPassword=…
   ```
3. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, then run `./gradlew assembleRelease`. The APK is `app/build/outputs/apk/release/app-release.apk` (arm64-v8a only, about 37 MB).
4. Publish it with a `vX.Y.Z` tag, and set `RELEASES_API_URL` in `update/UpdateChecker.kt` to that repository's "latest release" API URL.

To smoke-test the minified build on an x86_64 emulator, add `-PemulatorAbi`.
