#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p report/out
TEXMFCNF=/usr/share/texmf-dist/web2c pandoc report/report.md --resource-path=report --pdf-engine=xelatex -o report/out/report.pdf
