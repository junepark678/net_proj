# Development setup

For the headless two-engine call simulator, presets, and metrics output, see
[Call simulator](SIMULATOR.md).

The Tor integration gate uses the Tor Project Debian/Ubuntu repository. Its CI setup verifies the
signing key fingerprint before installing the keyring package, following the [official Tor
installation instructions](https://support.torproject.org/little-t-tor/getting-started/installing/).

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
pinned to archive `16111833` (tools revision `23.0`); Google publishes SHA-1 checksums in its repository
metadata, which the script verifies alongside a pinned SHA-256 computed from
the Linux and macOS archives. Re-running after installation skips license and
package setup when all required packages are present.

Desktop packaging uses `JAVA_HOME_17_X64` when it is available, while Gradle
and compilation continue on JDK 21. If a separate packaging JDK is needed, pass
`-PonionDialJpackageJavaHome=/path/to/jdk` to `:desktopApp:createDistributable`.

To create an emulator later, first install the optional system image:

```bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
sdkmanager emulator 'system-images;android-35;default;x86_64'
avdmanager create avd -n android-35 -k 'system-images;android-35;default;x86_64'
emulator -avd android-35 -no-window -no-audio -no-boot-anim
```

Emulator images are intentionally not installed by the setup script.
