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

MOS is labeled **simplified E-model (assumed Ie/Bpl)**. Ie=5 and Bpl=10.1 are
assumptions for a wideband codec with PLC, not published Opus constants.
The application configuration is Opus 24 kbps mono, 20 ms frames, no FEC.
The simplified loss formula uses percentage points and assumes independent
loss; one-way delay is RTT/2 plus buffer delay. See
[ITU-T G.107](https://www.itu.int/rec/T-REC-G.107). These values are indicative,
not a validated wideband or listening-test MOS measurement.
