# Metrics data layout

Archived experiments are grouped under `report/data/E1/`, `E2/`, and `E3/`, then by scenario (`E1_lan_loopback`, `E2_same_nat_tor`, or `E3_cgnat_tor`). E1 currently uses paired in-memory channels with a simulated LAN latency profile; it is not a physical LAN measurement. Store each endpoint's per-call CSV beside its `summary.json` in a per-call subdirectory; record setup details in the experiment's `conditions.md`. The app and simulator write one CSV per call with a header and one row per sample interval: `t_s,rtt_ms,jitter_ms,loss_pct,late,plc,buffer_ms,tx_kbps,rx_kbps,circuit_id,hops`. The report tool finds the scenario name in any parent directory. Commit `table.md` and `summary.json` under `report/out/`; the PDF and generated plots remain ignored.

Run from the repository root (the shell expands the CSV paths):

```sh
python3 -I tools/report_stats.py $(find report/data -type f -name '*.csv') --out report/out/ --no-plots
python3 -I tools/tests/test_report_stats.py
```

No third-party packages are needed for `table.md` and `summary.json`. Commit those
two reproducible analysis outputs. PDFs and generated plot files remain local;
the report includes the required traversal diagram and measured results table.
With matplotlib installed in the selected Python environment, the tool also
writes `rtt_timeseries.png` and `jitter_cdf.png`. Without it, a warning explains
that figures were skipped. Unsupported scenario paths warn and are skipped;
zero usable rows is an error. Runs are pooled by scenario, with each interval
weighted equally, including for percentiles (linear interpolation).

The `--no-plots` option writes only `table.md` and `summary.json`. When a CSV
has its companion `summary.json`, the tool checks the recorded sample count and
excludes a final interval shorter than 950 ms; blank RTT rows are omitted from
RTT percentiles and the indicative MOS estimate while their other metrics are
retained. Archive each call's CSV beside its own `summary.json` so this filter
can distinguish a short closing interval from a full sample.

Each analyzed row represents one approximately complete second. `t_s` is
elapsed time within that run; `rtt_ms`, `jitter_ms`, `buffer_ms`, `tx_kbps`, and
`rx_kbps` are interval measurements. `rtt_ms` is the most recent matched PONG
observed in that interval; it can repeat across rows when no newer PONG arrives,
so pooled percentiles are snapshots, not counts of independent probes. It may
be blank before the first PONG; blank values are omitted from RTT statistics.
When the sidecar summary is present, the report tool drops a final interval
shorter than 950 ms. Without a sidecar, every supplied row is assumed complete.
`loss_pct` is the per-interval percentage of missing application AUDIO sequence
numbers; it does not measure IP packet loss or TCP retransmissions. The report
averages these per-interval percentages, not packet counts. `late` and `plc` are
counts within that interval, not cumulative counters. PLC rate is a fraction:
`sum(plc frames) / total received+concealed frames`. With 20 ms frames and
continuous playout, that denominator is 50 times the number of analyzed rows.
The CSV has no received-frame counter; do not include paused playout or partial
intervals without their summary sidecar. Late frames are not added to this
denominator. `circuit_id` and `hops` describe the circuit and are not averaged.

The summary's `n_endpoint_csvs` counts one file per endpoint; it is not a count
of paired calls. Three two-ended trials therefore contribute six endpoint CSVs.

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
python3 -I tools/report_stats.py $(find report/data -type f -name '*.csv') --out report/out/ --ie 10 --bpl 4.9
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
E1 (simulated LAN profile), E2 (same NAT via Tor), E3 (CGNAT via Tor). Lighter RTT
lines show individual calls using the experiment's shared grayscale, line
style, and marker; jitter CDFs pool calls within each experiment.
