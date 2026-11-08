#!/usr/bin/env python3
"""Pool per-second metrics. MOS is indicative, not a listening-test result.

Simplified ITU-T G.107 loss impairment (Ppl in percentage points) and R-to-MOS
mapping: https://www.itu.int/rec/T-REC-G.107 . Defaults Ie=10, Bpl=4.9 are a
proxy: G.722.2 23.85 kbps, diotic listening, uniform loss, from ITU-T G.113,
Transmission impairments due to speech processing (2024), Appendix IV,
Table IV.4: https://www.itu.int/rec/T-REC-G.113-202409-I/en . Override with
--ie/--bpl. These are not measured Opus parameters. The application uses Opus
24 kbps mono, 20 ms frames, no FEC. Independent random loss is assumed;
burst loss and wideband R-scale calibration are not modeled. Borrowing WB
planning factors does not make this simplified model a validated Opus MOS.
"""
import argparse
import csv
import json
import math
import statistics
import sys
from pathlib import Path

SCENARIOS = ('E1_lan_loopback', 'E2_same_nat_tor', 'E3_cgnat_tor')
REQUIRED = ('t_s', 'rtt_ms', 'jitter_ms', 'loss_pct', 'late', 'plc', 'buffer_ms',
            'tx_kbps', 'rx_kbps', 'circuit_id', 'hops')
MOS_LABEL = 'simplified E-model (proxy/custom Ie/Bpl)'
FIGURES = ('rtt_timeseries.png', 'jitter_cdf.png')
# ITU-T G.113 (2024), Transmission impairments due to speech processing,
# Appendix IV Table IV.4: G.722.2 23.85 kbps, diotic, uniform loss.
DEFAULT_IE = 10.0
DEFAULT_BPL = 4.9
PARAMETER_SOURCE = ('proxy: G.722.2 23.85 kbps, diotic, uniform loss; '
                    'ITU-T G.113 (2024), Appendix IV Table IV.4')


def percentile(values, percent):
    """Linear interpolation at index (n - 1) * percent / 100."""
    xs = sorted(values)
    position = (len(xs) - 1) * percent / 100
    lower = int(position)
    upper = min(lower + 1, len(xs) - 1)
    return xs[lower] + (xs[upper] - xs[lower]) * (position - lower)


def mos(rtt, buffer_ms, loss_pct, ie=DEFAULT_IE, bpl=DEFAULT_BPL):
    delay = rtt / 2 + buffer_ms
    id_ = 0.024 * delay + 0.11 * max(0, delay - 177.3)
    p = max(0, min(100, loss_pct))
    ie_eff = ie + (95 - ie) * p / (p + bpl)
    r = max(0, min(100, 93.2 - id_ - ie_eff))
    return 1 + 0.035*r + 7e-6*r*(r-60)*(100-r)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('csvs', nargs='+')
    ap.add_argument('--out', required=True)
    ap.add_argument('--ie', type=float, help=f'codec impairment, 0..95 (default: {DEFAULT_IE}, proxy)')
    ap.add_argument('--bpl', type=float, help=f'loss robustness, >0 (default: {DEFAULT_BPL}, proxy)')
    ap.add_argument('--no-plots', action='store_true',
                    help='write table and summary without creating figure files')
    args = ap.parse_args()
    parameters = {
        'ie': DEFAULT_IE if args.ie is None else args.ie,
        'bpl': DEFAULT_BPL if args.bpl is None else args.bpl,
        'ie_source': PARAMETER_SOURCE if args.ie is None else 'CLI --ie',
        'bpl_source': PARAMETER_SOURCE if args.bpl is None else 'CLI --bpl',
    }
    if not math.isfinite(parameters['ie']) or not 0 <= parameters['ie'] <= 95:
        ap.error('--ie must be finite and between 0 and 95')
    if not math.isfinite(parameters['bpl']) or parameters['bpl'] <= 0:
        ap.error('--bpl must be finite and greater than 0')
    # Argument help and statistics work without any third-party dependencies.
    plt = None
    if not args.no_plots:
        try:
            import matplotlib
            matplotlib.use('Agg')
            import matplotlib.pyplot as plt
        except ImportError:
            print('Warning: matplotlib unavailable; plots skipped. Install matplotlib '
                  'in your Python environment to enable figures.', file=sys.stderr)

    groups = {s: [] for s in SCENARIOS}
    for fn in args.csvs:
        path = Path(fn)
        scenario = next((parent.name for parent in path.parents if parent.name in groups), None)
        if scenario is None:
            print(f'Warning: unsupported scenario path {path}; skipped', file=sys.stderr)
            continue
        with path.open(newline='') as f:
            reader = csv.DictReader(f)
            if reader.fieldnames != list(REQUIRED):
                ap.error(f'{path}: unexpected CSV header')
            rows = list(reader)
        summary_path = path.parent / 'summary.json'
        if rows and summary_path.is_file():
            try:
                run_summary = json.loads(summary_path.read_text())
                duration = float(run_summary['duration_s'])
                sample_count = int(run_summary['samples'])
            except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
                ap.error(f'{summary_path}: invalid metrics summary ({error})')
            if sample_count != len(rows):
                ap.error(f'{path}: {len(rows)} CSV rows but summary reports {sample_count} samples')
            elapsed_in_final_interval = duration - math.floor(duration)
            if sample_count > math.floor(duration) and elapsed_in_final_interval < 0.95:
                rows.pop()
                print(
                    f'Excluded final partial interval ({elapsed_in_final_interval:.3f}s): {path}',
                    file=sys.stderr,
                )
        if rows:
            groups[scenario].append((path.stem, rows))
    if not any(groups.values()):
        ap.error('zero usable rows in supported scenario paths')

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    if not args.no_plots:
        # Never leave figures from an earlier run beside a freshly written summary.
        for name in FIGURES:
            (out/name).unlink(missing_ok=True)
    keys = ('mean_rtt_ms', 'p50_rtt_ms', 'p95_rtt_ms', 'mean_jitter_ms',
            'p50_jitter_ms', 'p95_jitter_ms', 'loss_pct', 'plc_rate',
            'mean_buffer_ms', 'mean_tx_kbps', 'mean_rx_kbps', 'estimated_mos')
    labels = ('Mean RTT (ms)', 'p50 RTT (ms)', 'p95 RTT (ms)', 'Mean jitter (ms)',
              'p50 jitter (ms)', 'p95 jitter (ms)', 'Mean interval loss (%)',
              'PLC rate (fraction)', 'Mean buffer delay (ms)', 'Mean tx (kbps)',
              'Mean rx (kbps)', f'MOS: {MOS_LABEL}')
    lines = ['| Scenario | endpoint CSVs | ' + ' | '.join(labels) + ' |',
             '|---|---:|' + '---:|' * len(keys)]
    summary = {}
    styles = ['-', '--', ':']
    markers = ['o', 's', '^']
    if plt:
        plt.style.use('grayscale')
        plt.rcParams.update({'font.size': 7, 'axes.titlesize': 8})
        fig, ax = plt.subplots(figsize=(8/2.54, 5/2.54))
        cdf, ax2 = plt.subplots(figsize=(8/2.54, 5/2.54))
    for i, scenario in enumerate(SCENARIOS):
        runs = groups[scenario]
        rows = [row for _, run in runs for row in run]
        if not rows:
            summary[scenario] = {'n_endpoint_csvs': 0, 'metrics': None}
            lines.append(f'| {scenario} | 0 | ' + ' | '.join(['—'] * len(keys)) + ' |')
            continue
        rtt_values = [float(row['rtt_ms']) for row in rows if row['rtt_ms'].strip()]
        vals = {k: [float(row[k]) for row in rows]
                for k in ('jitter_ms', 'loss_pct', 'plc', 'buffer_ms', 'tx_kbps', 'rx_kbps')}
        mean = {k: statistics.mean(v) for k, v in vals.items()}
        mean_rtt = statistics.mean(rtt_values) if rtt_values else None
        # Each complete one-second interval contains 50 received+concealed
        # playout frames at 20 ms/frame. CSV plc is an interval count.
        plc_rate = sum(vals['plc']) / (50 * len(rows))
        values = [mean_rtt,
                  percentile(rtt_values, 50) if rtt_values else None,
                  percentile(rtt_values, 95) if rtt_values else None,
                  mean['jitter_ms'],
                  percentile(vals['jitter_ms'], 50), percentile(vals['jitter_ms'], 95),
                  mean['loss_pct'], plc_rate, mean['buffer_ms'], mean['tx_kbps'],
                  mean['rx_kbps'],
                  mos(mean_rtt, mean['buffer_ms'], mean['loss_pct'],
                      parameters['ie'], parameters['bpl']) if mean_rtt is not None else None]
        summary[scenario] = {'n_endpoint_csvs': len(runs), 'mos_model': MOS_LABEL,
                             'mos_parameters': parameters,
                             'metrics': dict(zip(keys, values))}
        lines.append(f'| {scenario} | {len(runs)} | ' +
                     ' | '.join('—' if v is None else f'{v:.4f}' for v in values) + ' |')
        if plt:
            experiment = scenario.split('_', 1)[0]
            plotted_run = False
            for run_index, (_, run) in enumerate(runs):
                rtt_rows = [row for row in run if row['rtt_ms'].strip()]
                if rtt_rows:
                    ax.plot([float(r['t_s']) for r in rtt_rows], [float(r['rtt_ms']) for r in rtt_rows],
                            styles[i], label=experiment if not plotted_run else '_nolegend_',
                            color=str(.15 + .2*i), alpha=.65, linewidth=1,
                            marker=markers[i], markevery=max(1, len(rtt_rows)//12), markersize=2)
                    plotted_run = True
            xs = sorted(vals['jitter_ms'])
            ax2.plot(xs, [(j + 1)/len(xs) for j in range(len(xs))], styles[i],
                     marker=markers[i], markevery=max(1, len(xs)//12), markersize=2,
                     color=str(.15 + .2*i), label=experiment)
    if plt:
        for axis, title, x, y in ((ax, 'RTT over time', 'Time (s)', 'RTT (ms)'),
                                  (ax2, 'Jitter CDF', 'Jitter (ms)', 'Cumulative probability')):
            axis.set_title(title)
            axis.set_xlabel(x)
            axis.set_ylabel(y)
            axis.grid(True, linewidth=.35)
            axis.legend(fontsize=7, ncol=3, handlelength=2, columnspacing=.8)
        for figure, name in zip((fig, cdf), FIGURES):
            figure.tight_layout()
            figure.savefig(out/name, dpi=300)
            plt.close(figure)
    lines.extend(['', 'Loss is the mean of per-interval percentages. PLC rate is concealed frames /',
                  'total received+concealed frames (50 frames per complete one-second interval).',
                  '', f'MOS parameters: Ie={parameters["ie"]:g} ({parameters["ie_source"]});',
                  f'Bpl={parameters["bpl"]:g} ({parameters["bpl_source"]}).',
                  'Indicative only; not a validated Opus or wideband MOS measurement.',
                  'Figure legends: E1 = simulated LAN profile; E2 = same NAT via Tor; E3 = CGNAT via Tor.',
                  'RTT lines show individual calls; jitter CDFs pool calls by experiment.'])
    (out/'table.md').write_text('\n'.join(lines) + '\n')
    (out/'summary.json').write_text(json.dumps(summary, indent=2) + '\n')


if __name__ == '__main__':
    main()
