# Metrics data layout

The app writes one CSV per call at `report/data/<scenario>/<run>.csv`, with a header and one row per second: `t_s,rtt_ms,jitter_ms,loss_pct,late,plc,buffer_ms,tx_kbps,rx_kbps,circuit_id,hops`. Supported scenario directories are `E1_lan_loopback`, `E2_same_nat_tor`, and `E3_cgnat_tor`. Keep generated reports in ignored `report/out/`.

Run from the repository root (the shell expands the CSV paths):

```sh
python3 -I tools/report_stats.py report/data/*/*.csv --out report/out/
python3 -I tools/tests/test_report_stats.py
```

No third-party packages are needed for `table.md` and `summary.json`.
With matplotlib installed in the selected Python environment, the tool also
writes `rtt_timeseries.png` and `jitter_cdf.png`. Without it, a warning explains
that figures were skipped. Unsupported scenario paths warn and are skipped;
zero usable rows is an error. Runs are pooled by scenario, with each interval
weighted equally, including for percentiles (linear interpolation).

Each row represents one complete second. `t_s` is elapsed time within that run;
`rtt_ms`, `jitter_ms`, `buffer_ms`, `tx_kbps`, and `rx_kbps` are interval
measurements. `loss_pct` is already in percentage points: the report averages
these per-interval percentages, not packet counts. `late` and `plc` are counts
within that interval, not cumulative counters. PLC rate is a fraction:
`sum(plc frames) / total received+concealed frames`. With 20 ms frames and
continuous playout, that denominator is 50 times the number of rows. The CSV
has no received-frame counter; partial intervals or paused playout must not be
supplied under this assumption. Late frames are not added to this denominator.
`circuit_id` and `hops` describe the circuit and are not averaged.

MOS is labeled **simplified E-model (proxy/custom Ie/Bpl)**. Defaults are
**Ie=10, Bpl=4.9**, a **proxy: G.722.2 23.85 kbps**, from
[ITU-T G.113, *Transmission impairments due to speech processing* (2024),
Appendix IV Table IV.4](https://www.itu.int/rec/T-REC-G.113-202409-I/en).
That table gives paired provisional wideband planning values for diotic
listening and uniform loss; they are not measured Opus constants. This nearby
bitrate proxy is an explicit approximation for the app's Opus 24 kbps mono,
20 ms frames, no FEC configuration, not an Opus calibration.

Use `--ie` (finite, 0 to 95) and `--bpl` (finite, greater than zero) to override
either parameter, for example when reproducing a codec study or evaluating
sensitivity. Unspecified parameters retain their proxy defaults:

```sh
python3 -I tools/report_stats.py report/data/*/*.csv --out report/out/ --ie 10 --bpl 4.9
```

`table.md` records the selected values and whether each came from the proxy
or a CLI override; each populated scenario in `summary.json` contains the same
information under `mos_parameters` (`ie`, `bpl`, `ie_source`, `bpl_source`).
The simplified [ITU-T G.107](https://www.itu.int/rec/T-REC-G.107) formula still
uses loss in percentage points and RTT/2 plus buffer delay. It assumes
independent random loss and does not implement the wideband R-scale calibration
of G.107.1. Borrowing wideband planning factors does not validate that model:
the estimate is indicative, not a measured or validated Opus/wideband MOS.

Figures are 8 cm wide with 7 pt legend text and one entry per experiment:
E1 (LAN loopback), E2 (same NAT via Tor), E3 (CGNAT via Tor). Lighter RTT
lines show individual calls using the experiment's shared grayscale, line
style, and marker; jitter CDFs pool calls within each experiment.
