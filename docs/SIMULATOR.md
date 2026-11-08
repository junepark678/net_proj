# Call simulator

The `lan`, `tor-typical`, and `tor-bad` profiles run two production `CallEngine` instances in one
JVM. They connect over paired in-memory `FrameChannel`s and use the same 24 kbps mono Opus
session, jitter buffer, and metrics logger as the app. These profiles do not start Tor or open an
audio device. The separate `tor-real` profile starts Tor clients, as described below.

From the repository root:

```sh
./gradlew :tools:simulate --args="--preset lan --seconds 60"
./gradlew :tools:simulate --args="--preset tor-typical --seconds 60 --wav path/to/voice.wav"
./gradlew :tools:simulate --args="--delay 400 --jitter 120 --loss 2 --seconds 60"
./gradlew :tools:simulate --args="--preset tor-real --seconds 60"
./gradlew :tools:simulate --args="--preset tor-peer --seconds 120"
./gradlew torLoopbackTest
```

`--wav` accepts WAV formats supported by Java Sound that it can convert to signed 16-bit, mono,
48 kHz PCM. It plays the source once and fills any remainder of the requested duration with
silence. Without `--wav`, the in-memory profiles send a deterministic 440 Hz tone. `tor-real`
writes that tone to `source.wav` and then reads it through the WAV input adapter. Capture is paced
at the app's 20 ms audio frame interval.

The in-memory and `tor-real` runs write two decoded receive WAVs and two metrics CSVs under a
unique run directory. `tor-peer` writes the desktop endpoint's decoded WAV and metrics CSV.
LAN output is placed below `build/simulator/E1_lan_loopback` by default and can be passed to
`tools/report_stats.py`; for example, `find build/simulator/E1_lan_loopback -name '*.csv'` lists
its CSV inputs. Set `--out DIRECTORY` to choose another base directory. Empty circuit id and hop
fields mean the in-memory simulator has no Tor circuit to report.

## Presets and impairment semantics

| Preset | One-way delay | Uniform jitter | Target audio loss | Mean loss burst | Bandwidth cap per direction |
|---|---:|---:|---:|---:|---:|
| `lan` | 2 ms | ±1 ms | 0% | 0 ms | unlimited |
| `tor-typical` | 400 ms | ±150 ms | 1% | 100 ms | 96 kbps |
| `tor-bad` | 900 ms | ±400 ms | 5% | 200 ms | 64 kbps |

`tor-real` runs two actual Tor clients and does not use the in-memory impairment model.
Command-line impairment values override the selected preset. Loss applies to complete AUDIO
frames using a seeded burst model; HELLO, ACCEPT, PING, PONG and BYE remain reliable. Jitter is
sampled independently per frame, and delivered frames remain in stream order. The seed is fixed
per preset so repeated runs with the same profile are reproducible. This models the impact of
media loss on the jitter buffer; it does not model packet loss and retransmission inside TCP or
Tor, Tor circuit construction, or real network throughput.

The simulator is useful for repeatable pipeline and report data. It does not replace the Android
to desktop and mobile data Tor calls described in [the report plan](REPORT_PLAN.md).

`tor-real` starts two independent local Tor clients, waits for both to bootstrap, publishes and
mutually pairs their onion services, fetches each paired descriptor, then completes a short audio
call through Tor. Transient descriptor `NOT_FOUND` results are retried before dialing. A bootstrap
timeout is retried once; attempt logs remain beside the output. Temporary Tor data directories,
including service private keys, are removed after the run. Only the call CSV/summary JSON and Tor
logs are copied to the output directory. The CI-only `torLoopbackTest` runs the two-client Tor
control and call qualification. The CI gate is manual or enabled with the `qualify:tor` pull-request
label.

`tor-peer` starts one desktop Tor client and publishes a pairing payload. Paste it into the
Android app, then paste the Android app's pairing payload into the simulator's stdin. The
simulator authorizes the Android peer, accepts its incoming call, sends the selected WAV (or a
deterministic 440 Hz tone), and writes the desktop endpoint's decoded WAV and metrics under the
run directory. This headless path exercises the Android app and desktop JVM runtime over Tor; it
does not establish distinct NATs or verify that a human listener heard the audio. Stop the Android
app's Tor service and remove the temporary desktop contact after the run.
