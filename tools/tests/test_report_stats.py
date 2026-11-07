import csv
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/report_stats.py'
spec = importlib.util.spec_from_file_location('report_stats', SCRIPT)
stats = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stats)


class ReportStatsTest(unittest.TestCase):
    def invoke(self, paths, out, no_packages=False):
        return subprocess.run([sys.executable, '-I'] + (['-S'] if no_packages else []) +
                              [str(SCRIPT), *map(str, paths), '--out', str(out)],
                              capture_output=True, text=True)

    def test_reference_mos(self):
        # Fixed reference values calculated from G.107's percentage-point loss
        # formula with assumed Ie=5/Bpl=10.1; high loss catches /100 mistakes.
        for rtt, loss, expected in ((0, 0, 4.292446024),
                                    (0, 30, 1.27805209168289),
                                    (354.4, 0, 4.164048650461426),
                                    (354.8, 0, 4.163526269221562)):
            with self.subTest(rtt=rtt, loss=loss):
                self.assertAlmostEqual(stats.mos(rtt, 0, loss), expected, places=10)
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
                expected = dict(mean_rtt_ms=100, p50_rtt_ms=60, p95_rtt_ms=186,
                                mean_jitter_ms=6, p50_jitter_ms=4, p95_jitter_ms=11.2,
                                loss_pct=10, plc_rate=0.1, mean_buffer_ms=20,
                                mean_tx_kbps=25, mean_rx_kbps=21,
                                estimated_mos=2.1502645392785196)
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

    def test_help_without_optional_imports(self):
        result = subprocess.run([sys.executable, '-I', '-S', str(SCRIPT), '--help'],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertNotIn('matplotlib unavailable', result.stderr)


if __name__ == '__main__':
    unittest.main()
