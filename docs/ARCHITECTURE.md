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
├── androidApp/        Android entry point, foreground service, camera QR scan
├── desktopApp/        JVM window, tray controls, QR display/image import
├── tools/             loopback simulator, Tor latency probe, report data scripts
└── report/            the 심화연구보고서 sources (Markdown → PDF), figures, data
```

## App shells and pairing

The shared Compose screen displays the local pairing QR, lets users paste or
enter a pairing code, and adds validated contacts through the runtime
controller. Android adds QR scanning through ZXing Embedded after an explicit
camera-permission explanation; the manual-entry path does not require camera
access. Desktop decodes a selected image with ZXing core and keeps import text
available through paste. Its system tray shows offline, bootstrap, online, and
in-call state, with controls to go online/offline or quit. Closing the window
hides it in the tray when the platform supports a tray.

Native desktop distribution builds fetch the Tor Expert Bundle for the build
host, verify the pinned SHA-256, and include it under the application resources.
At runtime `TorProcess` checks that packaged resource path before searching the
development `build/tor` directory or `PATH`.

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
going online. `onion.OnionService.publish` (below) is that publisher.

### Onion service publication (`onion` package)

`OnionService(control, identityStore, keyStore, displayName, localPort)` publishes
one v3 service with `ADD_ONION`, mapping virtual port 9999 to
`127.0.0.1:<localPort>` and adding one `ClientAuthV3=<x25519 pub base32>` per
`PeerAuthKey` (`Contact.authKey()`). First run sends `NEW:ED25519-V3`, saves the
returned `PrivateKey` blob to `onion-service-key.json` through `Storage`
before `publish` returns, and creates the `Identity` (address plus a fresh x25519
pair, generated by the pure-Kotlin RFC 7748 `X25519`). Later runs send
`ED25519-V3:<blob> Flags=V3Auth,DiscardPK`, so the address is stable and Tor does not
echo the secret. On disk the key is owner-only (0600) via `directoryStorage`;
Android additionally wraps it with an AES-GCM key held by Android Keystore. The service is
not `Detach`ed: it ends with the control connection. `unpublish()` sends
`DEL_ONION`; calling `publish` again while published replaces the service,
which is how `ContactStore`'s `republish` hook should be wired. With no peers a
random throwaway authorization key is used (never an unauthenticated service), so the
address can still be shown for pairing while nobody can connect.
`ClientAuthInstaller` writes `<peer id>.auth_private`
(`<id>:descriptor:x25519:<base32 private>`) via a `ClientAuthDir`
(`directoryClientAuthDir(path)` on JVM/Android, 0600 files in a 0700 directory). Tor reads
`ClientOnionAuthDir` at startup and on reload. `JvmOnionCallPresence` installs the
local private key for each saved peer and signals Tor to reload after online startup
and contact changes.

## Presence ("Go online") without push

- Going online = start Tor (if not running), publish the onion service
  descriptor, start listening on the local service port. Going offline tears
  this down.
- Calling = open a SOCKS5 connection through local Tor to `<peer>.onion:PORT`.
  If the peer is offline the descriptor fetch or rendezvous fails fast and the
  UI reports "peer not online". No server, no polling service, no FCM.
- `Socks5TorDialer(socksHost = "127.0.0.1", socksPort = 9050, timeoutMillis = 30_000).connect(onion, port)`
  (`jvmSharedMain`) implements `TorDialer`. It offers only SOCKS5 method 0x00 and sends the
  `.onion` name as address type 0x03, so it is never resolved locally. `socksHost` must be a
  numeric IPv4 or IPv6 address, such as `127.0.0.1`, `::1`, or `[::1]`; hostnames (including
  `localhost`), scoped IPv6 addresses, and malformed literals throw `IllegalArgumentException`
  at construction without DNS resolution. IPv4 uses four decimal octets without leading zeros.
  This keeps local DNS outside the dial path. Connect plus handshake
  must finish within the timeout (`DialResult.Timeout`). Results: `Connected(FrameChannel)`
  (Nagle disabled; use with `FrameReader`/`FrameWriter`), `PeerOffline` (Tor replies 0xF0, 0xF2,
  0xF3, 0xF7), `AuthRequired` (0xF4, 0xF5), `Timeout` (also reply 0x06), else
  `Error(code, message)` where `code` is the reply byte or a negative local code
  (`TorDialer.ERROR_*`). The managed Tor process enables the `ExtendedErrors` SOCKS flag,
  which makes onion descriptor/rendezvous failures distinguishable from local errors.
  Retries are the caller's concern (#16).
  The established channel uses direct `SocketChannel` reads and complete-buffer writes so a
  blocked read does not prevent concurrent writes on Android. Cancelling either direction
  closes the channel.
- `JvmOnionCallPresence` binds an OS-assigned loopback TCP port, publishes that port
  as virtual port 9999, and owns both until `goOffline`. The Tor control connection
  publishes only the saved contacts' `ClientAuthV3` keys; an empty contact list uses
  an unreachable decoy key.
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

Before a channel reaches this protocol, `AuthenticatedCallHandshake` performs a
mutual challenge-response using the paired X25519 client-auth keys. The caller
announces its public key and a fresh nonce; the service maps that key to exactly
one saved contact, proves its own key with a second nonce, and verifies the
caller's response before returning the channel. HMAC-SHA256 proofs bind both
onion addresses and both public keys. This identifies the contact because Tor's
ClientAuthV3 admission result does not identify which authorized key connected.
The handshake authenticates peers; it does not add separate application-layer
encryption to the call frames.

## Call lifecycle

`call.CallEngine` owns the single call and exposes `StateFlow<CallState>` for UI
collection. `goOnline()` waits for publication; `call(peerOnion)` admits an
outbound attempt and returns immediately. `accept()` / `decline()` decide an
incoming `Ringing` call. `hangUp()` waits for call teardown. `goOffline()` releases
the call and listener, and permits going online again; `close()` is terminal.
Cancelling the owning coroutine scope also releases everything. Stale commands
return `false`. A `PeerOffline` snapshot is distinct from other call failures and
permits retry or accepting a new incoming call. `Online.lastEnd` retains the
last call's outcome until the next attempt or presence restart.

The JVM/Android runtime composes `JvmOnionCallPresence`,
`JvmOnionCallDialer`, `AuthenticatedCallHandshake`, `CallEngine`, and
`OpusCallSessionFactory`. Opening presence owns publication/listening as one
resource; listener close withdraws it. Each accepted/dialed
`protocol.FrameChannel` transfers ownership to the engine. Cancellation before
delivery closes the socket. Incoming connections carry the contact identity
verified before the v1 HELLO is read; HELLO's display name is not used as an
identity claim.
Both local and peer onions use the existing v3 checksum validator and normalize
ASCII case and the optional `.onion` suffix before glare comparison.

Admission reserves one session before reading its HELLO. Other arrivals get a
flushed `BYE(BUSY)`, except simultaneous dialing to the same peer: the smaller
onion rejects the inbound leg with `BYE(GLARE)`; the larger reserves the inbound
slot and tears down its outbound leg before negotiating the incoming call.
Glare applies only while dialing, never to an established call. A silent inbound
An inbound HELLO has a 10-second deadline. Outbound callers wait 60 seconds for
ACCEPT: the receiver's 30-second ringing window plus a 30-second Tor transport
allowance. Ringing may first appear without a display name while HELLO is being
read. Early acceptance is retained until that HELLO validates; decline tears
down immediately.

InCall sends PING every 2 seconds; only received PONG or AUDIO resets the
15-second monotonic inactivity deadline. Timeout is a local `TIMEOUT` outcome;
v1 has no TIMEOUT BYE code, so graceful timeout/decline uses `BYE(NORMAL)`.
BYE delivery (also for rejected calls) is bounded to one second so a stalled
writer cannot indefinitely delay teardown. EOF, protocol errors, media failures,
hangup, and owner cancellation all share cleanup. A once-only transport wrapper
also covers FrameReader's own close-on-error path. State returns idle only after
the transport, reader, sender, and media owner have stopped. StateFlow can
conflate rapid intermediate snapshots as usual.

`CallSessionFactory` creates the media owner at acceptance. `run` sends encoded
AUDIO through an engine callback; `receive(Frame.Audio)` promptly enqueues encoded
audio for playout. The engine owns all frame reads and serializes frame writes.
Frame observation hooks feed transport bytes, audio sequence/timestamps, PING/PONG,
and playout deltas into the per-call metrics logger without making a metrics sink
failure interrupt media.

## Audio path

```
mic → 48 kHz mono PCM (20 ms = 960 samples) → Opus encode (24 kbps, VoIP mode)
    → framer → TCP via SOCKS5 → Tor
Tor → TCP → deframer → adaptive jitter buffer (target = p95 inter-arrival, min 60 ms,
    max 1500 ms, PLC on gaps via Opus decode(null)) → Opus decode → speaker
```

After three consecutive playout underruns, the jitter buffer re-primes at the
current adaptive target. This caps cursor advance during a sustained outage and
lets delayed frames establish a new sequence cursor after a Tor latency increase.

The common `dev.junepark.oniontalkie.shared.audio.AudioConstants` object owns the
shared audio format and Opus settings for the codec and platform audio I/O (#13).
`OpusCodec.decode` and `decodeFec` return the sample count written. When the next
packet arrives before a lost frame's playout deadline, `decodeFec(nextPacket, out)`
recovers that frame from LBRR data (or falls back to PLC when absent); decode the
same next packet normally afterward. Use `decode(null, out)` when it is unavailable.
One encode thread and one decode thread may share a call's codec; each direction
is independently single-threaded. Malformed payloads rejected by Concentus throw
`OpusException`.

`OpusCallSessionFactory` is the concrete `CallSessionFactory` for this path. It
owns one codec and device pair per accepted call, captures and encodes 960-sample
frames, and sends them through the engine's serialized writer. Received AUDIO
frames enter a bounded queue before the sequence-aware jitter buffer; decoded
frames and PLC output are written to the selected device. The ingress queue holds
up to 1.5 seconds and the egress queue holds two frames; both drop the oldest
queued frame on overflow to keep live speech current. `CallMediaMetrics` exposes
jitter-buffer state, hardware underruns, and queue-drop counts to the caller.
Android composition must pass `AndroidAudioRouteController` to select and restore
the communication route; the default route is a no-op for desktop.

## Tor lifecycle

- Android: `tor-android` AAR binary, started by a foreground service with the
  `specialUse` presence type and microphone type, controlled via the control port
  with cookie auth. The service holds calls and Tor alive while online; notification
  and microphone permissions are requested in the foreground before startup.
- Desktop: Tor Expert Bundle binary bundled per OS, same control-port driver.
- Common code owns bootstrap progress parsing, `ADD_ONION` (with
  `ClientAuthV3`), `GETINFO status/bootstrap-phase`, and circuit event
  logging.

## Metrics and the report

Every call writes a UTF-8 CSV line per second with this stable header:

```csv
t_s,rtt_ms,jitter_ms,loss_pct,late,plc,buffer_ms,tx_kbps,rx_kbps,circuit_id,hops
```

`shared`'s `metrics.MetricsLogger` exposes a read-only `StateFlow<CallStats>`
for the live screen. The JVM call session creates one logger and a `FileMetricsSink`
on its I/O dispatcher. The call engine forwards sent/received frames; the media
session records application-stream bytes, audio sequence/timestamps, PING/PONG,
and jitter-buffer playout deltas, then persists the CSV and summary on teardown.
The UI shows live RTT, jitter, interval loss, bitrates, buffer delay, and the CSV path.
Tor circuit ID and hop-count collection remain a follow-up integration.

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
