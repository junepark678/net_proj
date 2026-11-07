# Supplemental Android-to-desktop call evidence

This directory contains one metrics-only Android emulator to desktop call. It is supplemental evidence, not a physical-network qualification.

## Trial 1 conditions

- Date: 2026-11-07, approximately 19:56 UTC.
- Android endpoint: API 35 x86_64 AVD (`sdk_gphone64_x86_64`, device `emu64xa`).
- Desktop endpoint: Linux x86_64 host, kernel `7.2.12-gentoo-dist-hardened`, Intel Core i5-14600K.
- Tor: Android Guardian Project AAR `0.4.8.17.2`; desktop Tor `0.4.9.15`.
- Network: emulator and desktop shared the same host and outbound NAT. No mobile data, CGNAT, or separate access network was used.
- Direction: Android initiated and desktop auto-accepted. Pairing was entered manually from the payload; camera QR scanning, the reverse call direction, and simultaneous-dial glare were not exercised.
- Media: the desktop sent a deterministic 440 Hz test tone. The emulator used its app audio-input path. No human listening evaluation was performed.
- Android endpoint CSV: 125.996 seconds, 126 samples. Median/p95 RTT 13,197/15,620 ms; median transmit rate 27.64 kbps; median interval loss 0%; PLC total 6,277 frames.
- Desktop endpoint CSV: 119.996 seconds, 120 samples. Median/p95 RTT 12,937/15,970 ms; median transmit rate 27.67 kbps; median interval loss 0%; PLC total 5,753 frames.
- Desktop Tor bootstrap: log timestamps show 0% at 04:54:16 and 100% at 04:54:25 KST, approximately 9 seconds. Android bootstrap reached 100% before the call, but its elapsed time was not retained.
- Call setup latency: an exact tap-to-InCall duration was not retained for this trial.

The high RTT and PLC counts make this a connected-call result, not evidence of conversationally usable audio. These measurements describe this emulator, host, and Tor route only.

No screenshots, screen recording, or audio recording are included, following the user's instruction not to create visible capture artifacts. Issue #26 remains incomplete: physical devices/networks, both call directions, glare, camera-based QR pairing, exact Android bootstrap and call-setup timings, and the requested visual captures remain unverified or omitted.
