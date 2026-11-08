# E2 supplemental conditions: same-host Tor simulator

- Date: 2026-11-07 UTC.
- Trials: three calls of approximately 120 seconds; both endpoints are archived separately with their CSV and `summary.json` sidecar.
- Endpoints: two production `CallEngine` instances in independent app runtimes within one JVM process on a Linux x86_64 host. Each runtime launched a separate Tor process and published its own onion service; calls used those services over real Tor circuits.
- Network: both clients shared the same host and outbound internet connection, so this approximates two clients behind one NAT. The experiment did not use separate devices, access networks, or a controlled NAT testbed. No synthetic delay or loss profile was applied.
- Media: production mono Opus at 24 kbps, 20 ms frames, deterministic 440 Hz test tone, no FEC.
- Host: Linux x86_64, kernel `7.2.12-gentoo-dist-hardened`, Intel Core i5-14600K.
- Telemetry: the CSV records endpoint-side measurements. Circuit ID and hop fields were unavailable in the archived metrics.

The reported per-endpoint median RTTs ranged from 990 ms to 18,440 ms; the largest per-endpoint p95 was 21,953 ms. This route was highly variable and did not provide conversational latency in these runs. Treat these values as measurements of this simulator run and route only; they do not establish general Tor performance.

This is supplemental simulator evidence, not a physical Android-to-desktop call. It does not satisfy issue #26's device evidence or the physical E2/E3 scenarios in the report plan.
