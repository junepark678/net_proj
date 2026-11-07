# Metrics data layout

The app writes one CSV per call at `report/data/<scenario>/<run>.csv`, with a header and one row per second: `t_s,rtt_ms,jitter_ms,loss_pct,late,plc,buffer_ms,tx_kbps,rx_kbps,circuit_id,hops`. Supported scenario directories are `E1_lan_loopback`, `E2_same_nat_tor`, and `E3_cgnat_tor`. Keep generated reports in ignored `report/out/`.
