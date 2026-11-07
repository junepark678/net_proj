import csv, subprocess, sys, tempfile, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
class ReportStatsTest(unittest.TestCase):
 def test_outputs_from_synthetic_csv(self):
  with tempfile.TemporaryDirectory() as d:
   data=Path(d)/'E1_lan_loopback'; data.mkdir(); f=data/'run.csv'
   headers='t_s,rtt_ms,jitter_ms,loss_pct,late,plc,buffer_ms,tx_kbps,rx_kbps,circuit_id,hops'.split(',')
   with f.open('w',newline='') as h:
    w=csv.writer(h); w.writerow(headers); w.writerow([0,40,2,0,0,0,10,24,20,'x',1]); w.writerow([1,60,4,1,0,0,20,25,21,'x',1])
   out=Path(d)/'out'; subprocess.run([sys.executable,'-I',str(ROOT/'tools/report_stats.py'),str(f),'--out',str(out)],check=True)
   for name in ('table.md','summary.json','rtt_timeseries.png','jitter_cdf.png'): self.assertTrue((out/name).is_file())
   self.assertIn('E1_lan_loopback',(out/'table.md').read_text())
if __name__=='__main__': unittest.main()
