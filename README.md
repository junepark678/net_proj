# net_proj — Onion Talkie

A serverless, push-free, walkie-talkie-style VoIP app (Android + desktop) that
carries Opus 24 kbps mono audio over Tor onion services. It is the practical
half of a Computer Networks research report on **NAT/PAT traversal**: Tor
rendezvous is used as a relay-style traversal technique (both peers only ever
make *outbound* connections), contrasted against hole punching (STUN/ICE) and
classic relays (TURN).

## Why this exists

- Show, with measurements, when relay-style traversal through Tor is a
  reasonable engineering choice and when it is not (latency, head-of-line
  blocking over TCP, bandwidth).
- Show that onion services are an ordinary networking tool, not "a trick
  criminals use": no server to run, no public IP, no port forwarding, no push
  notification infrastructure, mutual client authorization built in.

## Operating model

1. Each device owns a v3 onion service (hidden-service key generated locally).
2. Peers exchange onion address + client-authorization key once via QR code.
3. To talk, **both** parties press "Go online". A call connects only if the
   peer is also online. There is no push/FCM; presence is opt-in.
4. Audio is full duplex: Opus, 20 ms frames, 24 kbps mono, length-prefixed
   over one TCP stream through Tor, with an adaptive jitter buffer and
   per-call metrics (RTT, jitter, loss, one-way delay) logged for the report.

## Stack

| Layer | Choice |
| --- | --- |
| Language / UI | Kotlin Multiplatform + Compose Multiplatform (Android, desktop JVM) |
| Codec | Opus via Concentus (pure JVM port) in `commonMain` |
| Transport | Tor: `tor-android` AAR (Android), Tor Expert Bundle (desktop); driven over the control port from common code |
| Audio I/O | `expect`/`actual`: AudioRecord/AudioTrack (Android), `javax.sound.sampled` (desktop) |
| CI | GitHub Actions: JVM unit tests, Android build, AOSP emulator instrumentation tests, desktop smoke test |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the module layout and
protocol, and [docs/REPORT_PLAN.md](docs/REPORT_PLAN.md) for the report
outline and the experiments the app must support.

## Development

Use JDK 21. The committed Gradle wrapper downloads Gradle 8.14.6 and verifies
its SHA-256 checksum; a system Gradle installation is unnecessary.

Install the Android SDK command-line tools into `~/Android/Sdk/cmdline-tools/latest`.
On Linux, the same tools version used by CI can be installed with:

```sh
curl -fLo /tmp/onion-talkie-cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  /tmp/onion-talkie-cmdline-tools.zip' | sha256sum -c -
mkdir -p "$HOME/Android/Sdk/cmdline-tools"
unzip -q /tmp/onion-talkie-cmdline-tools.zip -d /tmp/onion-talkie-sdk-tools
mv /tmp/onion-talkie-sdk-tools/cmdline-tools "$HOME/Android/Sdk/cmdline-tools/latest"
```

For an existing SDK, skip the download and use its `sdkmanager`. Accept the
licenses, install exactly the packages CI uses, and create the ignored local
SDK pointer (run from the repository root):

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
```

Android uses minSdk 26, targetSdk 35, compileSdk 35 and Build Tools 35.0.0.
All dependency versions live in `gradle/libs.versions.toml`. Kotlin 2.4.21 is
the current stable release. AGP 8.13.2 retains support for Build Tools 35.0.0;
Compose 1.11.1 and Activity Compose 1.10.1 retain compatibility with compileSdk
35. Newer Compose and Activity releases require a newer compile SDK. The
metrics logger uses coroutines for sampling/StateFlow and kotlinx-io for local
call files; serialization remains a catalog pin until a module needs it.
See the [Kotlin compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html),
[Compose version mapping](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)
and [AGP 8.13 requirements](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
when updating these pins together.

Work is tracked in GitHub milestones (`A0`, `B1`, … style) and issues. Every
PR goes through council review (Claude Fable 5.1, GPT-6 Astra, GPT-6.1 Sol)
before merge; the scores are recorded in the PR description.

```
./gradlew :shared:allTests          # common + JVM unit tests
./gradlew :desktopApp:run           # desktop client
./gradlew :androidApp:assembleDebug # Android APK
```

`shared/src/commonTest` runs through the `desktop` JVM target; it needs no
emulator. The Android and desktop entry points both render `ui`'s common
placeholder screen, which consumes `shared`. Android-specific shared code
lives in `shared/src/androidMain`, desktop code in `shared/src/desktopMain`.

The APK is written to `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Desktop `run` requires a graphical session. On a headless host, build a
distributable without opening a window:

```sh
./gradlew :desktopApp:createDistributable
# Same build and test command as CI:
./gradlew --no-daemon build :shared:allTests :androidApp:assembleDebug :desktopApp:createDistributable
```

The desktop application and bundled runtime are written to
`desktopApp/build/compose/binaries/main/app/OnionTalkie/`. Builds enable
configuration cache and parallel execution; rerunning a command reuses the
configuration cache. Build configuration performs no downloads or SDK setup
outside normal Gradle dependency resolution.

## License

MIT — see [LICENSE](LICENSE).
