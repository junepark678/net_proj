#!/usr/bin/env python3
"""Aggregate per-second call metrics CSVs into scenario tables and figures.

MOS is an indicative wideband estimate, not a listening-test result. It uses
ITU-T G.107's simplified R-to-MOS mapping and delay impairment, with Opus
wideband Ie/Bpl values (Ie=5, Bpl=10.1; Opus published codec parameters,
consistent with ITU-T G.113 Amendment guidance). Delay is RTT/2 + buffer_ms;
packet loss is supplied as percent. The simplified loss impairment assumes
random independent loss and no burst compensation; R is clamped to [0,100].
"""
import argparse, csv, json, math
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

SCENARIOS = ('E1_lan_loopback', 'E2_same_nat_tor', 'E3_cgnat_tor')
REQUIRED = ('t_s','rtt_ms','jitter_ms','loss_pct','late','plc','buffer_ms','tx_kbps','rx_kbps','circuit_id','hops')

def mos(rtt, buffer_ms, loss_pct):
    delay = rtt / 2 + buffer_ms
    id_ = 0.024 * delay + 0.11 * max(0, delay - 177.3)
    p = max(0, min(100, loss_pct)) / 100
    ie = 5 + (95 - 5) * p / (p + 10.1)
    r = max(0, min(100, 93.2 - id_ - ie))
    return 1 + 0.035*r + 7e-6*r*(r-60)*(100-r)

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('csvs', nargs='+'); ap.add_argument('--out', required=True); a=ap.parse_args()
    groups={s:[] for s in SCENARIOS}
    for fn in a.csvs:
        p=Path(fn); scenario=p.parent.name
        if scenario not in groups: continue
        with p.open(newline='') as f:
            reader=csv.DictReader(f)
            if reader.fieldnames != list(REQUIRED): raise ValueError(f'{p}: unexpected CSV header')
            rows=list(reader)
        if rows: groups[scenario].append((p.stem, rows))
    out=Path(a.out); out.mkdir(parents=True,exist_ok=True)
    summary={}; lines=['| Scenario | n runs | p50 RTT (ms) | p95 RTT (ms) | Mean jitter (ms) | p95 jitter (ms) | Loss (%) | Mean buffer delay (ms) | Mean tx (kbps) | Mean rx (kbps) | Estimated MOS |','|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    plt.style.use('grayscale'); fig,ax=plt.subplots(figsize=(8/2.54,5/2.54))
    cdf, ax2=plt.subplots(figsize=(8/2.54,5/2.54))
    styles=['-','--',':']; markers=['o','s','^']
    for i,s in enumerate(SCENARIOS):
        runs=groups[s]; vals={k:[] for k in ('rtt_ms','jitter_ms','loss_pct','buffer_ms','tx_kbps','rx_kbps')}
        for name,rows in runs:
            for k in vals: vals[k].extend(float(row[k]) for row in rows)
            t=np.array([float(r['t_s']) for r in rows]); rtt=np.array([float(r['rtt_ms']) for r in rows]); ax.plot(t,rtt,styles[i],label=f'{s} {name}',linewidth=1,marker=markers[i],markevery=max(1,len(t)//12),markersize=2)
        allv={k:np.array(v) for k,v in vals.items()}; rtt=allv['rtt_ms']; jit=allv['jitter_ms']
        if len(rtt):
            row=[len(runs),np.percentile(rtt,50),np.percentile(rtt,95),np.mean(jit),np.percentile(jit,95),np.mean(allv['loss_pct']),np.mean(allv['buffer_ms']),np.mean(allv['tx_kbps']),np.mean(allv['rx_kbps']),mos(float(np.mean(rtt)),float(np.mean(allv['buffer_ms'])),float(np.mean(allv['loss_pct'])))]
            lines.append('| '+s+' | '+' | '.join(f'{x:.2f}' if isinstance(x,(float,np.floating)) else str(x) for x in row)+' |')
            summary[s]={'n_runs':len(runs),'metrics':dict(zip(('p50_rtt_ms','p95_rtt_ms','mean_jitter_ms','p95_jitter_ms','loss_pct','mean_buffer_ms','mean_tx_kbps','mean_rx_kbps','estimated_mos'),[float(x) for x in row[1:]]))}
            xs=np.sort(jit); cdf.plot(xs,np.arange(1,len(xs)+1)/len(xs),styles[i],marker=markers[i],markevery=max(1,len(xs)//12),markersize=2,label=s)
        else: summary[s]={'n_runs':0,'metrics':None}; lines.append(f'| {s} | 0 | — | — | — | — | — | — | — | — | — |')
    for axis,title,x,y in ((ax,'RTT over time','Time (s)','RTT (ms)'),(cdf,'Jitter CDF','Jitter (ms)','Cumulative probability')):
        axis.set_title(title); axis.set_xlabel(x); axis.set_ylabel(y); axis.grid(True,linewidth=.35); axis.legend(fontsize=5)
    fig.tight_layout(); fig.savefig(out/'rtt_timeseries.png',dpi=300); plt.close(fig)
    cdf.tight_layout(); cdf.savefig(out/'jitter_cdf.png',dpi=300); plt.close(cdf)
    (out/'table.md').write_text('\n'.join(lines)+'\n'); (out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
if __name__=='__main__': main()
