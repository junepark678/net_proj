# Development setup

Install a JDK 21 distribution and ensure `java -version` reports 21. Install
`curl`, `unzip`, and `shasum` (or `sha256sum`), then from the repository root run:

```bash
bash tools/setup-android-sdk.sh
```

The script installs Android command-line tools and the exact SDK packages used
by CI: `platform-tools`, `platforms;android-35`, and `build-tools;35.0.0`. It
accepts SDK licenses non-interactively and writes the local SDK path to the
git-ignored `local.properties`. Set `ANDROID_HOME` to choose another SDK
location; the default is `~/Android/Sdk`. The command-line tools download is
pinned to version `16111833`; Google publishes SHA-1 checksums in its repository
metadata, which the script verifies alongside a pinned SHA-256 computed from
the Linux and macOS archives. Re-running after installation skips license and
package setup when all required packages are present.

To create an emulator later, first install the optional system image:

```bash
sdkmanager 'system-images;android-35;default;x86_64'
avdmanager create avd -n android-35 -k 'system-images;android-35;default;x86_64'
emulator -avd android-35 -no-window -no-audio -no-boot-anim
```

Emulator images are intentionally not installed by the setup script.
