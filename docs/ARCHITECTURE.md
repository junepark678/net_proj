# Architecture

## Modules

```
net_proj/
├── shared/            KMP library: protocol, codec, call engine, Tor control, metrics
│   ├── commonMain/    pure Kotlin; no platform APIs
│   ├── jvmSharedMain/ Concentus codec shared by Android and desktop
│   ├── androidMain/   AudioRecord/AudioTrack, tor-android process, foreground service glue
│   ├── desktopMain/   javax.sound.sampled, Tor Expert Bundle process
│   ├── commonTest/    protocol/jitter/state-machine tests (run on JVM)
│   └── jvmSharedTest/ codec tests and micro-benchmark (both JVM targets)
├── ui/                Compose Multiplatform screens + view models (shared)
├── androidApp/        Android entry point, foreground service, QR scan
├── desktopApp/        JVM desktop entry point, QR display/import
├── tools/             loopback simulator, Tor latency probe, report data scripts
└── report/            the 심화연구보고서 sources (Markdown → PDF), figures, data
```

## Identity and pairing

- On first launch the app generates an ed25519 onion-service key (v3) and a
  x25519 client-authorization keypair.
- Pairing payload (QR): `onion-talkie:v1;<onion>.onion;<x25519 pub base32>;<display name>`.
- Each device adds the peer's x25519 public key to its own onion service's
  `authorized_clients`, so only paired peers can even reach the rendezvous.

The `shared` identity package accepts key material from the Tor key owner (#7);
`IdentityStore.loadOrCreate` persists it without generating or rotating keys.
`Identity.pairingPayload()` exports only public material. `PairingPayload.parse`
returns a sealed success/failure result and validates the [v3 onion checksum](https://spec.torproject.org/rend-spec/encoding-onion-addresses.html),
unpadded 32-byte base32 client-auth keys, framing, and a 200-character/UTF-8-byte
budget. Display names may contain Unicode, spaces, and semicolons, but not
control characters. Parsing normalizes onion addresses to lowercase and
ASCII base32 keys to uppercase; non-ASCII key characters are rejected before case conversion.

`directoryStorage(dataDirectory = appDataDir())` is the platform storage boundary.
Android must call `AndroidAppData.init(context)` from `Application.onCreate` first;
the default then uses the application's private `filesDir`. Desktop defaults to
`$XDG_DATA_HOME/onion-talkie`, falling back to `~/.local/share/onion-talkie`.
Its JVM implementation is shared through `jvmSharedMain`, keeping `commonMain`
free of platform APIs. JSON files are written to a temporary file in the same
directory and atomically moved into place; a filesystem that cannot do an atomic
replace makes the write fail with the previous file intact rather than risk a torn
file. Identity files use owner-only permissions on POSIX filesystems. Use one `IdentityStore` and one
`ContactStore` per directory. Corrupt or invalid files raise an error rather
than silently replacing an identity or discarding peers. Unknown JSON fields are
ignored for forward compatibility.

`ContactStore.open(storage, isOnline, republish)` exposes `contacts` as a
`StateFlow` and provides `add`, `remove`, and `list`. Re-pairing the same onion
updates that entry. The Tor owner supplies `isOnline` and a suspend `republish`
callback that receives the complete set of public authorization keys after a
persisted key-set change while online. On going online, #7 must read `list()`
for offline changes. The callback also handles removals; it must not call back
into the store. Persistence and the state update finish together despite coroutine
cancellation. Publication-hook errors are logged without failing the saved operation;
coroutine cancellation still propagates. Tor must retry from the current list when
going online. `OnionService` supplies that publication hook; see
`docs/TOR_CONTROL.md` for wiring.

## Presence ("Go online") without push

- Going online = start Tor (if not running), publish the onion service
  descriptor, start listening on the local service port. Going offline tears
  this down.
- Calling = open a SOCKS5 connection through local Tor to `<peer>.onion:PORT`.
  If the peer is offline the descriptor fetch or rendezvous fails fast and the
  UI reports "peer not online". No server, no polling service, no FCM.
- Both sides may call simultaneously; the glare rule is: the side with the
  lexicographically smaller onion address keeps its outbound connection, the
  other side drops its own and accepts.

## Wire protocol (single TCP stream per call, Nagle disabled)

```
HELLO     u8 type=1 | u8 version | u16 len | utf8 display name | u32 opusRateHint
ACCEPT    u8 type=2 | u8 version
AUDIO     u8 type=3 | u16 seq | u32 tsMs | u16 len | opus frame (20 ms, ~60 B @ 24 kbps)
PING      u8 type=4 | u32 tsMs
PONG      u8 type=5 | u32 echoedTsMs | u32 peerTsMs
BYE       u8 type=6 | u8 reason
```

All multi-byte integers big-endian. The AUDIO `seq`/`tsMs` feed the jitter
buffer and the metrics logger (loss by gaps in `seq`, jitter per RFC 3550
§6.4.1, RTT from PING/PONG, one-way delay estimated as RTT/2).

Protocol version is `1`. HELLO names use strict UTF-8, with `len` counting bytes
(maximum 256 bytes); AUDIO payloads are at most 1275 bytes. BYE reason codes are
`0 = NORMAL`, `1 = VERSION`, `2 = BUSY`, `3 = GLARE`, and `4 = PROTOCOL_ERROR`;
other reasons and unknown frame types are invalid. HELLO and ACCEPT versions are
checked as soon as the version byte is read, before the remaining body is decoded.
An unsupported HELLO version receives BYE(VERSION), immediately flushed, then the
stream closes. Malformed or truncated frames also close the stream with a typed
`ProtocolException`. Read failures are rethrown on every subsequent read; only
EOF between frames is a clean end of stream.

The pure Kotlin codec is in `shared`'s `protocol` package. `FrameChannel` is the
minimal suspendable duplex interface for the Tor transport adapter: partial
reads are allowed, writes consume a whole buffer, and flush/close are explicit.
`FrameReader` reads only the current frame; `FrameWriter` writes one buffer and
flushes per frame. Callers serialize reads and writes independently; the transport
adapter is responsible for disabling TCP Nagle.

## Audio path

```
mic → 48 kHz mono PCM (20 ms = 960 samples) → Opus encode (24 kbps, VoIP mode)
    → framer → TCP via SOCKS5 → Tor
Tor → TCP → deframer → adaptive jitter buffer (target = p95 inter-arrival, min 60 ms,
    max 1500 ms, PLC on gaps via Opus decode(null)) → Opus decode → speaker
```

The common `dev.junepark.oniontalkie.shared.audio.AudioConstants` object owns the
shared audio format and Opus settings for the codec and platform audio I/O (#13).
`OpusCodec.decode` and `decodeFec` return the sample count written. When the next
packet arrives before a lost frame's playout deadline, `decodeFec(nextPacket, out)`
recovers that frame from LBRR data (or falls back to PLC when absent); decode the
same next packet normally afterward. Use `decode(null, out)` when it is unavailable.
One encode thread and one decode thread may share a call's codec; each direction
is independently single-threaded. Malformed payloads rejected by Concentus throw
`OpusException`.

## Tor lifecycle

- Android: `tor-android` AAR binary, started by a foreground service
  (`FOREGROUND_SERVICE_MICROPHONE` + `…_DATA_SYNC`), controlled via the
  control port with cookie auth. The service holds the call and Tor alive
  while online.
- Desktop: Tor Expert Bundle binary bundled per OS, same control-port driver.
- Common code owns bootstrap progress parsing, `ADD_ONION` (with
  `ClientAuthV3`), `GETINFO status/bootstrap-phase`, and circuit event
  logging.

`OnionService.open` uses `IdentityStore` to atomically persist Tor's expanded
ED25519-V3 private key alongside the onion address and our x25519 client-auth
keypair. First launch briefly creates a self-authorized service to obtain the
address and key, then deletes it before returning an offline owner. Reopening
does not contact Tor or regenerate keys. An older identity without a service key
fails explicitly; it cannot recover an unavailable service key by rotating keys.
The x25519 implementation uses Bouncy Castle's lightweight API in `jvmSharedMain`
for both desktop and Android API 26+, without installing a global JCA provider.

Publication maps virtual port 9999 to the caller's loopback listener. The service
uses `Flags=V3Auth` and stays attached to the dedicated control connection, so
disconnecting Tor control also removes it. An empty peer list authorizes only
our own key, keeping an unpaired device private. Changing peers deletes and
re-adds the same service with the complete new authorization list. Listener and
existing call-connection lifetimes remain the caller's responsibility.

`ClientOnionAuth` writes our private x25519 half to one `.auth_private` file per
peer we dial. The process owner configures `ClientOnionAuthDir` to that storage
directory and loads/reloads Tor before dialing. Files use the existing atomic
storage boundary and 0600 on POSIX. Android uses the application's private data
directory; Android Keystore wrapping of the secret material remains a follow-up.

## Metrics and the report

Every call writes a UTF-8 CSV line per second with this stable header:

```csv
t_s,rtt_ms,jitter_ms,loss_pct,late,plc,buffer_ms,tx_kbps,rx_kbps,circuit_id,hops
```

`shared`'s `metrics.MetricsLogger` exposes a read-only `StateFlow<CallStats>`
for the live screen. The call owner creates one logger with a `FileMetricsSink`
and a coroutine scope on an I/O dispatcher, then feeds it transport and playout
events. The transport, jitter buffer and Tor control integrations are owned by
their respective issues; no call engine exists in the scaffold yet.

```kotlin
val files = FileMetricsSink(appData, startedAtIso, peerShort)
val metrics = MetricsLogger(files, callIoScope)
try {
    // Feed AUDIO sequence/timestamp to onAudioReceived, and count all application
    // stream bytes once with onTransportBytes (including framing/control bytes).
    // Feed sent PING tokens and PONG echoes to onPingSent/onPong, playout events
    // to onPlayout, and the call's selected circuit + GETINFO circuit-status
    // response to onCircuitStatus. Collect metrics.stats for the live screen.
} finally {
    metrics.close()
}
```

RTT uses matched local PING send/receive times, so peer clock offset is irrelevant.
Jitter follows [RFC 3550 §6.4.1](https://www.rfc-editor.org/rfc/rfc3550#section-6.4.1)
in milliseconds, including u32 timestamp wrap. AUDIO arrival, PING send and PONG
arrival times are captured before acquiring the event mutex; callers may supply
those monotonic timestamps explicitly. Snapshots are taken under that mutex,
but all sink writes happen outside it so slow storage does not block events.

The CSV retains the exact 11-column header above. `t_s` is elapsed whole seconds
(rounded down); floating-point fields use two decimal places, a decimal point,
and no scientific notation. `loss_pct`, `late`, `plc`, `tx_kbps` and `rx_kbps`
describe the interval since the preceding row. Interval loss is the increase in
missing sequence numbers divided by the increase in expected sequence numbers,
clamped to zero when gap repairs exceed new losses; no new expected frames means
zero interval loss. `late` and `plc` are interval event counts. RTT, jitter, buffer
delay and circuit fields are the current measurements at the sample boundary.

Live `CallStats.lossPct` is the cumulative percentage of missing u16 sequence
numbers from the first frame observed to the highest sequence received;
`CallStats.late` and `plc` are also cumulative. Its `intervalLossPct`,
`intervalLate` and `intervalPlc` fields provide the CSV interval values.
Duplicates do not count as extra receptions; reordered frames
within the last 32,768 sequence numbers can repair gaps. Sequence jumps of half
the sequence space or more are ambiguous; a sender restart requires a new logger.
Late and PLC are jitter-buffer events, separate from network sequence loss.
Bitrates are decimal kilobits per second over the actual sampling
interval, excluding Tor relay overhead. `hops` counts the path entries in the
selected BUILT circuit's [GETINFO circuit-status](https://spec.torproject.org/control-spec/commands.html)
reply, never an arbitrary unrelated circuit. Unavailable RTT/circuit fields are
blank; a known circuit ID may have an unknown hop count.

Files are `<appData>/calls/<iso-timestamp>-<peer-short>.csv`. The supplied UTC
timestamp has its colons removed for portable filenames; `peer-short` is a safe
1–32 character identifier. Each call's summary lives beside the CSV in
`<appData>/calls/<iso-timestamp>-<peer-short>/summary.json`, preserving every
call's results. Use a fractional-second timestamp to distinguish rapid calls to
the same peer; existing call paths are rejected rather than truncated.

Hangup must call `close()`, even after cancellation of the sampling scope. It
flushes any partial second using its actual duration, writes the summary, and
closes the file. A hangup at the last tick's timestamp emits another row only if
events were accepted after that tick, preserving their metrics. The same rule
preserves events on an immediate hangup before the first millisecond. Such rows
use a minimum 1 ms bitrate denominator, and can share an integer `t_s` with the
preceding row; ordinary partial rows use their actual duration. The summary
contains `samples`, `duration_s`, cumulative `late_total` and `plc_total`, and
`{p50,p95}` objects for every numeric CSV metric
except elapsed time. Percentiles use **nearest rank** over CSV snapshots
(`sorted[ceil(p * n) - 1]`); unavailable measurements are omitted and empty
percentiles are JSON `null`. Each row, including a final partial row, has equal
weight. Summaries describe sampled values, not all individual packet RTTs. Loss,
late and PLC percentiles use interval values, matching the CSV.

The `tools/` scripts can aggregate these files into the tables and figures used
in the report's comparison section (Tor relay traversal vs. STUN hole punching
vs. TURN relay vs. direct LAN). No upload or synchronization is performed.
