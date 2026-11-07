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

Work is tracked in GitHub milestones (`A0`, `B1`, … style) and issues. Every
PR goes through council review (Claude Fable 5.1, GPT-6 Astra, GPT-6.1 Sol)
before merge; the scores are recorded in the PR description.

```
./gradlew :shared:allTests          # common + JVM unit tests
./gradlew :desktopApp:run           # desktop client
./gradlew :androidApp:assembleDebug # Android APK
```

Prerequisites: JDK 21 and the Android SDK (`platform-tools`, `platforms;android-37.0`,
`build-tools;36.0.0`; `compileSdk` 37 because AndroidX Compose 1.12 requires it, `targetSdk` 35,
`minSdk` 26). Point Gradle at the SDK with `ANDROID_HOME` or a git-ignored `local.properties`
containing `sdk.dir=/path/to/Android/Sdk`. Dependency and plugin versions live in
`gradle/libs.versions.toml`; the Gradle wrapper is committed.

## License

MIT — see [LICENSE](LICENSE).
