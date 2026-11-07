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

The `qualify-emulator` and `qualify-tor` jobs are opt-in placeholders activated
by the corresponding pull request labels; adding a label triggers a run. Their qualification bodies are
tracked by issues #24 and #25. Branch protection on `main` requires the
`unit` and `android` checks to pass on an up-to-date head before a merge; no
review approvals are required and force pushes are disabled.
