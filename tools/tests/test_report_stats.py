import csv
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/report_stats.py'
spec = importlib.util.spec_from_file_location('report_stats', SCRIPT)
stats = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stats)


class ReportStatsTest(unittest.TestCase):
    def invoke(self, paths, out, no_packages=False, extra=()):
        return subprocess.run([sys.executable, '-I'] + (['-S'] if no_packages else []) +
                              [str(SCRIPT), *map(str, paths), '--out', str(out), *extra],
                              capture_output=True, text=True)

    def test_reference_mos(self):
        # Fixed reference values calculated from G.107's percentage-point loss
        # formula with the G.113 G.722.2 proxy Ie=10/Bpl=4.9;
        # Decimal reference calculations; high loss catches /100 mistakes.
        for rtt, loss, expected in ((0, 0, 4.138996224),
                                    (0, 30, 1.0367998425502356),
                                    (354.4, 0, 3.9835916311846257),
                                    (354.8, 0, 3.982975994347362)):
            with self.subTest(rtt=rtt, loss=loss):
                self.assertAlmostEqual(stats.mos(rtt, 0, loss), expected, places=10)
        self.assertAlmostEqual(stats.mos(100, 20, 10, ie=5, bpl=10.1),
                               2.1502645392785196, places=10)
        self.assertAlmostEqual(stats.percentile([2, 10, 4], 95), 9.4)

    def test_outputs_from_pooled_csvs(self):
        with tempfile.TemporaryDirectory() as d:
            data = Path(d) / 'E1_lan_loopback'
            data.mkdir()
            paths = []
            for name, rows in [('a', [[0,40,2,0,0,2,10,24,20,'x',1],
                                      [1,60,4,10,0,3,20,25,21,'x',1]]),
                               ('b', [[0,200,12,20,0,10,30,26,22,'y',2]])]:
                path = data / (name + '.csv')
                paths.append(path)
                with path.open('w', newline='') as f:
                    writer = csv.writer(f)
                    writer.writerow(stats.REQUIRED)
                    writer.writerows(rows)
            for no_packages in (False, True):
                out = Path(d) / str(no_packages)
                result = self.invoke(paths, out, no_packages)
                self.assertEqual(result.returncode, 0, result.stderr)
                summary = json.loads((out/'summary.json').read_text())
                group = summary['E1_lan_loopback']
                self.assertEqual(group['n_runs'], 2)
                self.assertEqual(group['mos_model'], stats.MOS_LABEL)
                self.assertEqual(group['mos_parameters'], {
                    'ie': 10, 'bpl': 4.9,
                    'ie_source': stats.PARAMETER_SOURCE, 'bpl_source': stats.PARAMETER_SOURCE})
                expected = dict(mean_rtt_ms=100, p50_rtt_ms=60, p95_rtt_ms=186,
                                mean_jitter_ms=6, p50_jitter_ms=4, p95_jitter_ms=11.2,
                                loss_pct=10, plc_rate=0.1, mean_buffer_ms=20,
                                mean_tx_kbps=25, mean_rx_kbps=21,
                                estimated_mos=1.3968858578687554)
                for key, value in expected.items():
                    self.assertIsInstance(group['metrics'][key], (int, float))
                    self.assertAlmostEqual(group['metrics'][key], value, places=8, msg=key)
                self.assertIsNone(summary['E2_same_nat_tor']['metrics'])
                table = (out/'table.md').read_text()
                for label in ('Mean RTT', 'p50 jitter', 'PLC rate', stats.MOS_LABEL, '0.1000'):
                    self.assertIn(label, table)
                if 'matplotlib unavailable' in result.stderr:
                    self.assertFalse((out/'jitter_cdf.png').exists())
                else:
                    for name in ('rtt_timeseries.png', 'jitter_cdf.png'):
                        self.assertGreater((out/name).stat().st_size, 100)
                if no_packages:
                    self.assertIn('Install matplotlib', result.stderr)

            for extra, ie, bpl in ((('--ie', '5', '--bpl', '10.1'), 5, 10.1),
                                   (('--ie', '5'), 5, stats.DEFAULT_BPL),
                                   (('--bpl', '10.1'), stats.DEFAULT_IE, 10.1)):
                result = self.invoke(paths, out, no_packages=True, extra=extra)
                self.assertEqual(result.returncode, 0, result.stderr)
                group = json.loads((out/'summary.json').read_text())['E1_lan_loopback']
                parameters = group['mos_parameters']
                self.assertEqual((parameters['ie'], parameters['bpl']), (ie, bpl))
                self.assertAlmostEqual(group['metrics']['estimated_mos'],
                                       stats.mos(100, 20, 10, ie=ie, bpl=bpl))
                for flag in ('--ie', '--bpl'):
                    key = flag[2:]
                    expected_source = f'CLI {flag}' if flag in extra else stats.PARAMETER_SOURCE
                    self.assertEqual(parameters[f'{key}_source'], expected_source)
                    self.assertIn(expected_source, (out/'table.md').read_text())
                self.assertIn(f'Ie={ie:g}', (out/'table.md').read_text())
                self.assertIn(f'Bpl={bpl:g}', (out/'table.md').read_text())

    def test_invalid_model_parameters(self):
        with tempfile.TemporaryDirectory() as d:
            for flag, values in (('--ie', ('-1', '96', 'nan', 'inf')),
                                 ('--bpl', ('0', '-1', 'nan', 'inf'))):
                for value in values:
                    with self.subTest(flag=flag, value=value):
                        result = self.invoke(['missing.csv'], Path(d)/'out',
                                             no_packages=True, extra=(flag, value))
                        self.assertEqual(result.returncode, 2)
                        self.assertIn(f'{flag} must be finite', result.stderr)
                        self.assertFalse((Path(d)/'out').exists())

    def test_nine_call_plot_legends(self):
        try:
            import matplotlib
            matplotlib.use('Agg')
            import matplotlib.pyplot as plt
        except ImportError:
            self.skipTest('optional matplotlib is unavailable')
        with tempfile.TemporaryDirectory() as d:
            paths = []
            for scenario in stats.SCENARIOS:
                folder = Path(d)/scenario
                folder.mkdir()
                for run in range(3):
                    path = folder/f'{run}.csv'
                    with path.open('w', newline='') as f:
                        writer = csv.writer(f)
                        writer.writerow(stats.REQUIRED)
                        writer.writerows([[t, 40 + 10*run + t, t + run, 0, 0, 0,
                                           10, 24, 24, 'x', 1] for t in range(3)])
                    paths.append(str(path))
            out = Path(d)/'out'
            try:
                with patch.object(sys, 'argv', [str(SCRIPT), *paths, '--out', str(out)]), \
                        patch.object(plt, 'close') as close:
                    stats.main()
                figures = [call.args[0] for call in close.call_args_list
                           if isinstance(call.args[0], matplotlib.figure.Figure)]
                self.assertEqual(len(figures), 2)
                self.assertEqual(len(figures[0].axes[0].lines), 9)
                self.assertEqual(len(figures[1].axes[0].lines), 3)
                for figure in figures:
                    self.assertAlmostEqual(figure.get_figwidth()*2.54, 8)
                    legend = figure.axes[0].get_legend()
                    self.assertEqual([text.get_text() for text in legend.get_texts()],
                                     ['E1', 'E2', 'E3'])
                    self.assertTrue(all(text.get_fontsize() >= 7 for text in legend.get_texts()))
                    figure.canvas.draw()
                    bounds = legend.get_window_extent(figure.canvas.get_renderer())
                    self.assertTrue(figure.bbox.contains(bounds.x0, bounds.y0))
                    self.assertTrue(figure.bbox.contains(bounds.x1, bounds.y1))
                for name in ('rtt_timeseries.png', 'jitter_cdf.png'):
                    self.assertGreater((out/name).stat().st_size, 100)
            finally:
                plt.close('all')

    def test_empty_and_unsupported(self):
        with tempfile.TemporaryDirectory() as d:
            unsupported = Path(d)/'unknown'/'run.csv'
            result = self.invoke([unsupported], Path(d)/'out')
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('unsupported scenario path', result.stderr)
            self.assertIn('zero usable rows', result.stderr)
            folder = Path(d)/'E1_lan_loopback'
            folder.mkdir()
            empty = folder/'empty.csv'
            empty.write_text(','.join(stats.REQUIRED)+'\n')
            result = self.invoke([empty], Path(d)/'out')
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('zero usable rows', result.stderr)

    def test_stale_figures_removed_without_matplotlib(self):
        with tempfile.TemporaryDirectory() as d:
            data = Path(d) / 'E1_lan_loopback'
            data.mkdir()
            path = data / 'a.csv'
            with path.open('w', newline='') as f:
                writer = csv.writer(f)
                writer.writerow(stats.REQUIRED)
                writer.writerow([0, 40, 2, 0, 0, 2, 10, 24, 20, 'x', 1])
            out = Path(d) / 'out'
            out.mkdir()
            for name in stats.FIGURES:
                (out / name).write_bytes(b'stale')
            result = self.invoke([path], out, no_packages=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('matplotlib unavailable', result.stderr)
            for name in stats.FIGURES:
                self.assertFalse((out / name).exists(), name)
            self.assertTrue((out / 'summary.json').exists())

    def test_help_without_optional_imports(self):
        result = subprocess.run([sys.executable, '-I', '-S', str(SCRIPT), '--help'],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertNotIn('matplotlib unavailable', result.stderr)


if __name__ == '__main__':
    unittest.main()
