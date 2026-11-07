# Figure 1

Run `python3 -I report/figures/fig1_traversal.py` from the repository root.
Requires Python 3, `rsvg-convert` from librsvg, fontconfig (`fc-list`), and
**Noto Sans CJK KR**. The generator checks the commands and font before writing.
Install these dependencies using your environment's supported provisioning method;
no Python packages are required.

The SVG is 15 × 24.9 cm; the PNG is rendered at 300 dpi. The smallest text
is 21 SVG units, equivalent to 8.93 pt at 15 cm width. Print at actual size.
SVG text is editable and requires Noto Sans CJK KR on the viewing system;
text is not converted to outlines. The PNG embeds the rendered glyphs and is
portable without fonts. Conversion to paths would require another rendering
 dependency, so it is deliberately omitted.

Dashed arrows show setup or outbound connection initiation; solid double-ended
arrows show bidirectional media. Tor's two three-hop circuits meet at the
rendezvous point (roughly six relay hops end to end); its service descriptor is
published to the directory, and introduction is a separate setup operation.
