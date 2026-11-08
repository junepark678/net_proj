# Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request. Runs for the
same branch cancel older in-progress runs, and Gradle dependency and build
caches are managed by `gradle/actions/setup-gradle`.

The CI jobs are:

- **unit** builds all modules and runs the shared and UI test suites. JUnit XML
  reports are attached as an artifact when this job fails.
- **android** assembles the debug APK and uploads it as `android-debug-apk`.
- **desktop** builds the unpackaged desktop distributable (an application
  image, no installer) for the Ubuntu runner and uploads it as
  `desktop-distributable`. The upload fails if the build produced no files.

The `qualify-emulator` and `qualify-tor` jobs are opt-in qualifications activated
by pull request labels #24 and #25; adding a label triggers a run.
`qualify-emulator` runs Android instrumentation on an API 35 AOSP emulator,
including checks for audio APIs, Keystore persistence, SOCKS duplex I/O, and
startup of the packaged Tor daemon. `qualify-tor` installs Tor from the Tor
Project repository after checking its signing-key fingerprint, runs the Tor
control and two-engine loopback qualification, and makes a 60-second simulated
audio call over real Tor. It uploads the raw qualification output as an artifact.
These jobs do not demonstrate a physical Android-to-desktop call or a call over
two independently routed networks. Branch protection on `main` requires the
`unit` and `android` checks to pass on an up-to-date head before a merge; no
review approvals are required and force pushes are disabled.
