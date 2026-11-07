# Architecture

## Modules

```
net_proj/
├── shared/            KMP library: protocol, codec, call engine, Tor control, metrics
│   ├── commonMain/    pure Kotlin; no platform APIs
│   ├── androidMain/   AudioRecord/AudioTrack, tor-android process, foreground service glue
│   ├── desktopMain/   javax.sound.sampled, Tor Expert Bundle process
│   └── commonTest/    protocol/jitter/codec/state-machine tests (run on JVM)
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

## Audio path

```
mic → 48 kHz mono PCM (20 ms = 960 samples) → Opus encode (24 kbps, VoIP mode)
    → framer → TCP via SOCKS5 → Tor
Tor → TCP → deframer → adaptive jitter buffer (target = p95 inter-arrival, min 60 ms,
    max 1500 ms, PLC on gaps via Opus decode(null)) → Opus decode → speaker
```

## Tor lifecycle

- Android: `tor-android` AAR binary, started by a foreground service
  (`FOREGROUND_SERVICE_MICROPHONE` + `…_DATA_SYNC`), controlled via the
  control port with cookie auth. The service holds the call and Tor alive
  while online.
- Desktop: Tor Expert Bundle binary bundled per OS, same control-port driver.
- Common code owns bootstrap progress parsing, `ADD_ONION` (with
  `ClientAuthV3`), `GETINFO status/bootstrap-phase`, and circuit event
  logging.

## Metrics and the report

Every call writes a CSV line per second (rtt_ms, jitter_ms, loss_pct,
buffer_ms, bitrate_kbps, circuit_hops) to the app's data directory. The
`tools/` scripts aggregate these into the tables and figures used in the
report's comparison section (Tor relay traversal vs. STUN hole punching vs.
TURN relay vs. direct LAN).
