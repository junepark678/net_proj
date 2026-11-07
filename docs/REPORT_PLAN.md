# 심화연구보고서 plan (due 2026-11-20 Fri 23:55, 리로스쿨 > 활동보고서 > 교과활동 > [컴네](2차수행)심화연구보고서)

Body must be 2 to under 3 pages; hwp/word/pdf. Rubric: motivation 10, concept
understanding 10, depth (comparison/analysis/application) 10, readability and
figures 5, citations 5, length and format 5.

## Outline

1. 탐구 동기 (motivation): NAT/PAT means two phones behind home routers cannot
   call each other directly. How do apps solve this, and can it be done with
   no server at all?
2. 개념 (concepts): NAT/PAT (RFC 3022), why inbound fails; the three families
   of traversal: hole punching (STUN RFC 8489 / ICE), relays (TURN RFC 8656),
   and rendezvous via Tor onion services (both ends outbound). Figure 1: the
   three topologies side by side.
3. 심화 (depth): the Onion Dial app. Design (no server, opt-in presence, no
   push), Opus 24 kbps over a TCP stream through Tor. Measurement table:
   same-LAN direct vs. Tor-to-Tor (RTT, jitter, loss, MOS estimate), plus
   bandwidth per call. When it makes sense (no infrastructure, privacy,
   symmetric NAT/CGNAT where hole punching fails) vs. when it does not
   (latency-sensitive, large groups, battery).
4. 결론 및 시사점: onion services as a general NAT traversal tool, legitimate
   uses (SecureDrop, OnionShare, Ricochet/Cwtch, Briar), limits.
5. 참고문헌: RFC 3022, RFC 8489, RFC 8656, RFC 6716, Tor rendezvous spec v3,
   Ford/Srisuresh/Kegel "Peer-to-Peer Communication Across NATs" (2005).

## Experiments the app must support

- E1: LAN loopback (no Tor) baseline.
- E2: Android ↔ desktop over Tor, both on the same NAT.
- E3: Android (mobile data, CGNAT) ↔ desktop (home NAT) over Tor.
- Each: 3 × 2-minute calls, CSV metrics, summarized by `tools/report_stats.py`.
