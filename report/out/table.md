| Scenario | endpoint CSVs | Mean RTT (ms) | p50 RTT (ms) | p95 RTT (ms) | Mean jitter (ms) | p50 jitter (ms) | p95 jitter (ms) | Mean interval loss (%) | PLC rate (fraction) | Mean buffer delay (ms) | Mean tx (kbps) | Mean rx (kbps) | MOS: simplified E-model (proxy/custom Ie/Bpl) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| E1_lan_loopback | 6 | 2.6064 | 3.0000 | 4.3500 | 1.4019 | 1.2500 | 2.5115 | 0.0000 | 0.0014 | 52.7500 | 27.6533 | 27.6533 | 4.0938 |
| E2_same_nat_tor | 6 | 6217.4770 | 1086.5000 | 20152.7500 | 31.1944 | 27.1850 | 61.2970 | 0.0000 | 0.4847 | 52.0833 | 27.6537 | 26.8164 | 1.0000 |
| E3_cgnat_tor | 0 | — | — | — | — | — | — | — | — | — | — | — | — |

Loss is the mean of per-interval percentages. PLC rate is concealed frames /
total received+concealed frames (50 frames per complete one-second interval).

MOS parameters: Ie=10 (proxy: G.722.2 23.85 kbps, diotic, uniform loss; ITU-T G.113 (2024), Appendix IV Table IV.4);
Bpl=4.9 (proxy: G.722.2 23.85 kbps, diotic, uniform loss; ITU-T G.113 (2024), Appendix IV Table IV.4).
Indicative only; not a validated Opus or wideband MOS measurement.
Figure legends: E1 = simulated LAN profile; E2 = same NAT via Tor; E3 = CGNAT via Tor.
RTT lines show individual calls; jitter CDFs pool calls by experiment.
