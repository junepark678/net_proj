# Tor binary sources

The desktop bundle is fetched into `build/tor/` and SHA-256 verified by
`tools/fetch_tor.sh`. Native distribution tasks stage the host bundle in
application resources at `lib/app/resources/tor/<platform>/`; `TorProcess`
checks `compose.application.resources.dir` before the development bundle
directory or `PATH`. It writes a private torrc, launches the bundled executable,
authenticates to its automatically assigned control port, and queries
`GETINFO net/listeners/socks` for the SOCKS port.

The Android helper uses the extracted `libtor.so` from the pinned AAR and stores
Tor state under `filesDir/tor`. The Android runtime path was verified on an API
35 x86_64 emulator by starting the packaged daemon, authenticating to the
generated control port, and discovering the SOCKS listener. This startup check
does not wait for network bootstrap or qualify a full app-to-app call.

Verified 2026-11-07 against [Tor's download page](https://www.torproject.org/download/tor/) and [dist.torproject.org](https://dist.torproject.org/torbrowser/16.0/). Current listed stable Expert Bundle: **16.0**. Distribution archive name is `tor-expert-bundle-<platform>-16.0.tar.gz`; extraction includes `tor/tor`, `tor/libevent...`, and data files including `data/geoip` and `data/geoip6`.

| Platform | Source/version | Download and signature | SHA-256 (archive) | License | Runtime location |
|---|---|---|---|---|---|
| Android | Guardian Project Maven `info.guardianproject:tor-android:0.4.8.17.2` (AAR). | [AAR](https://raw.githubusercontent.com/guardianproject/gpmaven/master/info/guardianproject/tor-android/0.4.8.17.2/tor-android-0.4.8.17.2.aar) · [POM](https://raw.githubusercontent.com/guardianproject/gpmaven/master/info/guardianproject/tor-android/0.4.8.17.2/tor-android-0.4.8.17.2.pom) | Pin the version in the version catalog; repository uses HTTPS. | Preserve bundled license notices from the AAR. | Execute `<nativeLibraryDir>/libtor.so`; use `<filesDir>/tor` as `DataDirectory`. AAR contains `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libtor.so`. |
| Linux x86_64 | Tor Expert Bundle 16.0 | [tar.gz](https://dist.torproject.org/torbrowser/16.0/tor-expert-bundle-linux-x86_64-16.0.tar.gz) · [`.asc`](https://dist.torproject.org/torbrowser/16.0/tor-expert-bundle-linux-x86_64-16.0.tar.gz.asc) | `cc950ebe9acb80188325c3a2cd8817def7dd7237d6fdcd861bb87b234a0cb2fd` | Tor Project open source; individual licenses in archive | Bundle at `build/tor/linux-x86_64/tor/tor`; packaged app can locate relative to app resources. |
| Linux aarch64 | Tor Expert Bundle 16.0 | [tar.gz](https://dist.torproject.org/torbrowser/16.0/tor-expert-bundle-linux-aarch64-16.0.tar.gz) · matching `.asc` | `bed98744e093a0dc3a22be0eecfdce7a33cb36866d3f15fcf011a35bf3513d0d` | same | `build/tor/linux-aarch64/tor/tor` |
| macOS x86_64 | Tor Expert Bundle 16.0 | [tar.gz](https://dist.torproject.org/torbrowser/16.0/tor-expert-bundle-macos-x86_64-16.0.tar.gz) · matching `.asc` | `5b1860215c26f2d408392d1d79e798a2f81d65eb939510bb94c11f237bd0bfc3` | same | `build/tor/macos-x86_64/tor/tor` |
| macOS aarch64 | Tor Expert Bundle 16.0 | [tar.gz](https://dist.torproject.org/torbrowser/16.0/tor-expert-bundle-macos-aarch64-16.0.tar.gz) · matching `.asc` | `b8da251eccbe9e82f3681ee3af3c97fb4291c4a4e153e5b824bde48a1117cd6a` | same | `build/tor/macos-aarch64/tor/tor` |
| Windows x86_64 | Tor Expert Bundle 16.0 | [tar.gz](https://dist.torproject.org/torbrowser/16.0/tor-expert-bundle-windows-x86_64-16.0.tar.gz) · matching `.asc` | `2ee123e9167318a3742778ff704e75f01d4780f05a14522d451fe011011077c4` | same | `build/tor/windows-x86_64/tor/tor.exe` (Windows launcher needs `.exe`) |

For authenticity, verify `.asc` using GPG and the Tor Browser Developers signing key obtained through Tor Project's official key distribution; SHA-256 detects corruption and is pinned above. The script verifies SHA-256 before extracting. Run `tools/fetch_tor.sh` on a supported host.

The project adds Guardian Project's Maven repository and packages the pinned AAR in the Android app. The [tor-android README](https://github.com/guardianproject/tor-android#using-tor-android-in-your-project) documents that its `libtor.so` can be used directly as a Tor daemon. The app configures legacy JNI packaging so the library is extracted under `nativeLibraryDir`, then launches it with the same generated torrc used on desktop.

The script preserves `tor/`, `data/` (including `geoip` and `geoip6`), and `docs/` (licenses) under `build/tor/<platform>/`. A successful install writes `.completed-sha256` containing the expected archive digest; reruns compare it directly and reuse the installation offline. On Linux, set the child process's `LD_LIBRARY_PATH` to the installed `build/tor/<platform>/tor/` directory so bundled libevent/libssl libraries resolve.

## Tor configuration notes

Use `SocksPort 127.0.0.1:auto`, `ControlPort 127.0.0.1:auto`, `CookieAuthentication 1`, `ControlPortWriteToFile <private-path>`, `DataDirectory <private-path>`, `Log notice stdout`, and `ClientOnionAuthDir <private-path>`. Keep data and onion authorization files private. `ControlPortWriteToFile` writes `PORT=<address>:<port>`, for example `PORT=127.0.0.1:9051`; see [Tor src/feature/control/control.c](https://github.com/guardianproject/tor/blob/86771da/src/feature/control/control.c). It never writes a `COOKIE=` entry. Read the 32-byte binary authentication cookie from `<DataDirectory>/control_auth_cookie` (or the configured `CookieAuthFile`), separately from the port file. The SOCKS port is queried after cookie authentication; neither port is hard-coded.

Android needs a foreground service while Tor remains available. Onion Dial declares `specialUse` for the explicitly user-enabled, persistent Tor onion-service listener and `microphone` for voice calls; the manifest's special-use subtype explains the peer-to-peer use case. It does not use `dataSync` for presence: for apps targeting API 35, Android 15 limits `dataSync` foreground services to six hours per app in a 24-hour period. Long-lived services are also affected by Doze and vendor battery restrictions; explain the persistent notification and allow users to go offline. `android:process` changes app/service process ownership and lifecycle; keep Tor and its control client in a deliberately coordinated process. `extractNativeLibs` controls whether packaged native libraries are extracted; if false, do not assume a filesystem path exists. The direct daemon consumer must enable extraction (`extractNativeLibs=true`) and use the native library path described above.
