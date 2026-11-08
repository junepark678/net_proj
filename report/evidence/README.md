# Supplemental Android-to-desktop call evidence

This directory contains two Android emulator to desktop attempts with endpoint metrics. They are supplemental evidence, not physical-network qualification.

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

## Trial 2 conditions

- Date: 2026-11-07 UTC (2026-11-08 KST).
- Android endpoint: same API 35 x86_64 AVD (`sdk_gphone64_x86_64`, device `emu64xa`) as Trial 1.
- Desktop endpoint: same Linux x86_64 host as Trial 1.
- Tor: Android Guardian Project AAR `0.4.8.17.2`; desktop Tor `0.4.9.15`.
- Network: the emulator and desktop shared one host and outbound NAT; no physical or independent NAT paths were involved.
- Direction and pairing: Android initiated using a manually entered pairing payload; the desktop simulator auto-accepted. QR scanning, reverse direction, and glare were not exercised.
- Media: the desktop transmitted a deterministic 440 Hz test tone and the emulator used its app audio-input path. No human listening evaluation or audio recording was made.
- Desktop Tor bootstrap: 12.208 seconds. Desktop accepted the call and reached InCall 2.085 seconds later. Android bootstrap was complete before dialing; its elapsed time was not retained.
- Android metrics: 18 unique one-second samples through `t_s=18`; zero RTT samples and 771 PLC frames after excluding the duplicate final-flush row at `t_s=18` (23 PLC frames; raw CSV sum 794). Mean TX was 26.52 kbps and mean RX 3.68 kbps. Received bitrate was zero for most samples after the initial seconds.
- Desktop metrics: 60 unique one-second intervals; each interval from `t_s=55` through `t_s=60` repeated the latest matched PONG value (52,295 ms), not six independent RTT probes. The raw CSV also has a duplicate final-flush row at `t_s=60`, so the value appears in seven rows. PLC totaled 2,993 frames; mean TX was 27.62 kbps and mean RX 3.69 kbps. The desktop simulator ran for 60 seconds.

`loss_pct` is the interval percentage of missing application AUDIO sequence numbers, not IP packet loss or TCP retransmissions. `rtt_ms` records the latest matched PONG in each complete one-second interval, so a value may repeat across rows until a new PONG arrives.

Tor reported the Android SOCKS stream succeeded and the desktop entered the call, so this verifies connection setup. The Android metrics stop after 18 seconds and the measured RTT/PLC show unusable conversation quality on this route. Trial 2 does not satisfy the two-minute-call criterion; Trial 1 has two-minute endpoint CSVs, but both directions, glare, and human audio remain unverified.

## Remaining evidence gaps

No screenshots, screen recording, or audio recording are included; the archived evidence is limited to metrics CSVs and written conditions. Physical devices/networks, reverse-direction dialing, simultaneous-dial glare, camera-based QR pairing, exact Android bootstrap and tap-to-InCall durations, and human audio remain unverified. The desktop bootstrap and accept-to-InCall timing above are recorded, but they do not replace those missing measurements. Issue #26 remains open.
