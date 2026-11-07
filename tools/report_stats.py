#!/usr/bin/env python3
"""Pool per-second metrics. MOS is indicative, not a listening-test result.

Simplified ITU-T G.107 loss impairment (Ppl in percentage points) and R-to-MOS
mapping: https://www.itu.int/rec/T-REC-G.107 . Ie=5 and Bpl=10.1 below are
ASSUMED values for a wideband codec with PLC, not published Opus parameters.
The application uses Opus 24 kbps mono, 20 ms frames, no FEC. Independent
random loss is assumed; burst loss and wideband calibration are not modeled.
"""
import argparse
import csv
import json
import statistics
import sys
from pathlib import Path

SCENARIOS = ('E1_lan_loopback', 'E2_same_nat_tor', 'E3_cgnat_tor')
REQUIRED = ('t_s', 'rtt_ms', 'jitter_ms', 'loss_pct', 'late', 'plc', 'buffer_ms',
            'tx_kbps', 'rx_kbps', 'circuit_id', 'hops')
MOS_LABEL = 'simplified E-model (assumed Ie/Bpl)'


def percentile(values, percent):
    """Linear interpolation at index (n - 1) * percent / 100."""
    xs = sorted(values)
    position = (len(xs) - 1) * percent / 100
    lower = int(position)
    upper = min(lower + 1, len(xs) - 1)
    return xs[lower] + (xs[upper] - xs[lower]) * (position - lower)


def mos(rtt, buffer_ms, loss_pct):
    delay = rtt / 2 + buffer_ms
    id_ = 0.024 * delay + 0.11 * max(0, delay - 177.3)
    p = max(0, min(100, loss_pct))
    ie = 5 + (95 - 5) * p / (p + 10.1)
    r = max(0, min(100, 93.2 - id_ - ie))
    return 1 + 0.035*r + 7e-6*r*(r-60)*(100-r)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('csvs', nargs='+')
    ap.add_argument('--out', required=True)
    args = ap.parse_args()
    # Argument help and statistics work without any third-party dependencies.
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
    except ImportError:
        plt = None
        print('Warning: matplotlib unavailable; plots skipped. Install matplotlib '
              'in your Python environment to enable figures.', file=sys.stderr)

    groups = {s: [] for s in SCENARIOS}
    for fn in args.csvs:
        path = Path(fn)
        if path.parent.name not in groups:
            print(f'Warning: unsupported scenario path {path}; skipped', file=sys.stderr)
            continue
        with path.open(newline='') as f:
            reader = csv.DictReader(f)
            if reader.fieldnames != list(REQUIRED):
                ap.error(f'{path}: unexpected CSV header')
            rows = list(reader)
        if rows:
            groups[path.parent.name].append((path.stem, rows))
    if not any(groups.values()):
        ap.error('zero usable rows in supported scenario paths')

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    keys = ('mean_rtt_ms', 'p50_rtt_ms', 'p95_rtt_ms', 'mean_jitter_ms',
            'p50_jitter_ms', 'p95_jitter_ms', 'loss_pct', 'plc_rate',
            'mean_buffer_ms', 'mean_tx_kbps', 'mean_rx_kbps', 'estimated_mos')
    labels = ('Mean RTT (ms)', 'p50 RTT (ms)', 'p95 RTT (ms)', 'Mean jitter (ms)',
              'p50 jitter (ms)', 'p95 jitter (ms)', 'Mean interval loss (%)',
              'PLC rate (fraction)', 'Mean buffer delay (ms)', 'Mean tx (kbps)',
              'Mean rx (kbps)', f'MOS: {MOS_LABEL}')
    lines = ['| Scenario | n runs | ' + ' | '.join(labels) + ' |',
             '|---|---:|' + '---:|' * len(keys)]
    summary = {}
    styles = ['-', '--', ':']
    markers = ['o', 's', '^']
    if plt:
        plt.style.use('grayscale')
        fig, ax = plt.subplots(figsize=(8/2.54, 5/2.54))
        cdf, ax2 = plt.subplots(figsize=(8/2.54, 5/2.54))
    for i, scenario in enumerate(SCENARIOS):
        runs = groups[scenario]
        rows = [row for _, run in runs for row in run]
        if not rows:
            summary[scenario] = {'n_runs': 0, 'metrics': None}
            lines.append(f'| {scenario} | 0 | ' + ' | '.join(['—'] * len(keys)) + ' |')
            continue
        vals = {k: [float(row[k]) for row in rows]
                for k in ('rtt_ms', 'jitter_ms', 'loss_pct', 'plc', 'buffer_ms', 'tx_kbps', 'rx_kbps')}
        mean = {k: statistics.mean(v) for k, v in vals.items()}
        # Each complete one-second interval contains 50 received+concealed
        # playout frames at 20 ms/frame. CSV plc is an interval count.
        plc_rate = sum(vals['plc']) / (50 * len(rows))
        values = [mean['rtt_ms'], percentile(vals['rtt_ms'], 50),
                  percentile(vals['rtt_ms'], 95), mean['jitter_ms'],
                  percentile(vals['jitter_ms'], 50), percentile(vals['jitter_ms'], 95),
                  mean['loss_pct'], plc_rate, mean['buffer_ms'], mean['tx_kbps'],
                  mean['rx_kbps'], mos(mean['rtt_ms'], mean['buffer_ms'], mean['loss_pct'])]
        summary[scenario] = {'n_runs': len(runs), 'mos_model': MOS_LABEL,
                             'metrics': dict(zip(keys, values))}
        lines.append(f'| {scenario} | {len(runs)} | ' +
                     ' | '.join(f'{v:.4f}' for v in values) + ' |')
        if plt:
            for name, run in runs:
                ax.plot([float(r['t_s']) for r in run], [float(r['rtt_ms']) for r in run],
                        styles[i], label=f'{scenario} {name}', linewidth=1,
                        marker=markers[i], markevery=max(1, len(run)//12), markersize=2)
            xs = sorted(vals['jitter_ms'])
            ax2.plot(xs, [(j + 1)/len(xs) for j in range(len(xs))], styles[i],
                     marker=markers[i], markevery=max(1, len(xs)//12), markersize=2,
                     label=scenario)
    if plt:
        for axis, title, x, y in ((ax, 'RTT over time', 'Time (s)', 'RTT (ms)'),
                                  (ax2, 'Jitter CDF', 'Jitter (ms)', 'Cumulative probability')):
            axis.set_title(title)
            axis.set_xlabel(x)
            axis.set_ylabel(y)
            axis.grid(True, linewidth=.35)
            axis.legend(fontsize=5)
        for figure, name in ((fig, 'rtt_timeseries.png'), (cdf, 'jitter_cdf.png')):
            figure.tight_layout()
            figure.savefig(out/name, dpi=300)
            plt.close(figure)
    lines.extend(['', 'Loss is the mean of per-interval percentages. PLC rate is concealed frames /',
                  'total received+concealed frames (50 frames per complete one-second interval).'])
    (out/'table.md').write_text('\n'.join(lines) + '\n')
    (out/'summary.json').write_text(json.dumps(summary, indent=2) + '\n')


if __name__ == '__main__':
    main()
