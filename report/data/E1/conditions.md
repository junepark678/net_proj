# E1 conditions: simulated LAN profile over in-memory channels

- Date: 2026-11-07 UTC
- Trials: three calls, each approximately 120 seconds; both endpoints are archived as separate CSVs with their `summary.json` sidecars.
- Endpoints: two production `CallEngine` instances in one JVM connected by paired in-memory `FrameChannel`s. No phone, desktop audio device, or physical network link was used.
- Network profile: `lan` (2 ms one-way delay, ±1 ms jitter, 0% target audio-frame loss, no bandwidth cap).
- Media: production mono Opus at 24 kbps, 20 ms frames, deterministic 440 Hz test tone, no FEC.
- Tor: not used; circuit and hop fields are empty by design.
- Host: Linux x86_64 (`7.2.12-gentoo-dist-hardened`).

These files establish a repeatable media-pipeline baseline. They do not represent an Android-to-desktop call and do not satisfy the physical-call evidence in issue #26 or the Tor scenarios E2 and E3.

The report tool uses each summary sidecar to exclude a final sample interval shorter than 950 ms and omits blank RTT samples from RTT percentiles and the indicative MOS estimate.
