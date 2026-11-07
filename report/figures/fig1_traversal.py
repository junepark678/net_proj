#!/usr/bin/env python3
"""Generate SVG + 300 dpi PNG. Requires rsvg-convert (librsvg), fontconfig,
 and Noto Sans CJK KR. SVG text remains editable; see README.md for font setup.
"""
from html import escape
from pathlib import Path
import shutil
import struct
import subprocess
import zlib

OUT = Path(__file__).resolve().parent
W, H = 1000, 1660
FONT = 'Noto Sans CJK KR'


def set_png_resolution(png):
    """Write deterministic 300 dpi metadata, replacing any renderer value."""
    data = png.read_bytes()
    payload = struct.pack('>IIB', 11811, 11811, 1)
    chunk = b'pHYs' + payload
    resolution = struct.pack('>I', len(payload)) + chunk + struct.pack('>I', zlib.crc32(chunk))
    chunks = [data[:8]]
    offset = 8
    while offset < len(data):
        length = struct.unpack_from('>I', data, offset)[0]
        kind = data[offset+4:offset+8]
        end = offset + length + 12
        if kind != b'pHYs':
            chunks.append(data[offset:end])
        if kind == b'IHDR':
            chunks.append(resolution)
        offset = end
    png.write_bytes(b''.join(chunks))


def check_dependencies():
    for command in ('rsvg-convert', 'fc-list'):
        if not shutil.which(command):
            raise SystemExit(f'Required command missing: {command}. See report/figures/README.md.')
    families = subprocess.check_output(['fc-list', '--format', '%{family}\n'], text=True)
    if FONT not in families:
        raise SystemExit(f'Required font missing: {FONT}. See report/figures/README.md.')


def generate():
    s = [f'''<svg xmlns="http://www.w3.org/2000/svg" width="15cm" height="24.9cm" viewBox="0 0 {W} {H}">
<rect width="100%" height="100%" fill="white"/>
<style>text{{font-family:'{FONT}';fill:#111;font-size:21px}}.title{{font-size:27px;font-weight:bold}}.bold{{font-weight:bold}}.panel{{fill:white;stroke:#aaa;stroke-width:1.5}}.box{{fill:#f5f5f5;stroke:#333;stroke-width:2}}.nat{{fill:#e4e4e4;stroke:#333;stroke-width:2}}.media,.control{{fill:none;stroke:#222;stroke-width:3;marker-end:url(#arrow)}}.media{{marker-start:url(#arrow)}}.control{{stroke-dasharray:8 6}}</style>
<defs><marker id="arrow" markerWidth="7" markerHeight="7" refX="6" refY="3" orient="auto-start-reverse"><path d="M0,0 L6,3 L0,6" fill="none" stroke="#222" stroke-width="1.3"/></marker></defs>''']

    def text(x, y, value, cls='', anchor='middle'):
        s.append(f'<text x="{x}" y="{y}" class="{cls}" text-anchor="{anchor}">{escape(value)}</text>')

    def box(x, y, w, h, labels, cls='box'):
        s.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" class="{cls}"/>')
        for j, label in enumerate(labels):
            text(x+w/2, y+29+j*28, label)

    def path(d, cls='control'):
        s.append(f'<path d="{d}" class="{cls}"/>')

    def panel(y, title, height=490):
        box(15, y, 970, height, [], 'panel')
        text(40, y+38, title, 'title', 'start')

    def peers(y):
        # Each endpoint is visibly inside its own NAT boundary.
        box(40, y, 190, 115, ['NAT A'], 'nat')
        box(55, y+40, 160, 65, ['A · 사설 IP'])
        box(770, y, 190, 115, ['NAT B'], 'nat')
        box(785, y+40, 160, 65, ['B · 사설 IP'])

    panel(15, '(a) STUN 홀 펀칭 → UDP 직접 연결')
    box(390, 80, 220, 55, ['STUN 서버'])
    peers(200)
    path('M55,275 H25 V105 H380')
    path('M420,135 V195 H240 V275 H225')
    path('M945,275 H975 V105 H620')
    path('M580,135 V195 H760 V275 H775')
    text(180, 88, '① 주소 요청')
    text(820, 88, '① 주소 요청')
    text(300, 170, '② 공인 IP:포트 응답')
    text(700, 170, '② 공인 IP:포트 응답')
    path('M235,260 H760')
    path('M765,290 H240')
    text(500, 241, '③ 후보 주소 교환 (별도 시그널링)')
    path('M135,315 V350 H850')
    path('M865,315 V385 H150')
    text(500, 335, '④ 양쪽에서 외향 UDP 전송')
    path('M100,315 V440 H900 V315', 'media')
    text(500, 422, '⑤ 홀 펀칭 성공 후 직접 미디어')
    text(500, 480, 'STUN 서버는 주소 확인에만 사용')

    panel(520, '(b) TURN 중계 · 양쪽 모두 외향 연결')
    box(380, 600, 240, 85, ['TURN 중계 서버', '할당된 중계 주소'])
    peers(735)
    path('M55,810 H25 V625 H370')
    path('M945,810 H975 V625 H630')
    text(205, 605, '① 할당 요청 →')
    text(805, 605, '① 연결 시작 ←')
    path('M135,850 V900 H430 V695', 'media')
    path('M865,850 V900 H570 V695', 'media')
    text(500, 935, '② 미디어는 TURN 서버를 통해 양방향 전달')
    text(500, 980, '각 단말이 NAT 안에서 외부로 연결 시작')

    panel(1025, '(c) Tor 어니언 서비스 · 양쪽 모두 외향 연결', 590)
    box(260, 1095, 200, 55, ['디렉터리'])
    box(540, 1095, 200, 55, ['소개 지점'])
    peers(1210)
    path('M945,1280 H975 V1175 H360 V1160')
    text(750, 1200, '① B: 디스크립터 게시')
    path('M55,1280 H25 V1122 H250')
    text(135, 1090, '② A: 조회')
    path('M230,1280 H500 V1122 H530')
    text(365, 1255, '③ A: 소개 요청')
    box(270, 1345, 170, 55, ['중계 노드 2개'])
    box(560, 1345, 170, 55, ['중계 노드 3개'])
    box(395, 1435, 210, 55, ['랑데부 지점'])
    path('M135,1325 V1372 H260')
    path('M865,1325 V1372 H740')
    path('M355,1405 V1462 H385')
    path('M645,1405 V1462 H615')
    path('M135,1325 V1390 H260', 'media')
    path('M355,1405 V1480 H385', 'media')
    path('M865,1325 V1390 H740', 'media')
    path('M645,1405 V1480 H615', 'media')
    text(500, 1540, '④ 두 회로가 랑데부에서 만남 · 미디어 약 6홉')
    text(500, 1580, 'NAT에 포트 개방 없음', 'bold')
    text(500, 1610, '외향 TCP 회로로 중계 · 홀 펀칭 사용 안 함')
    # Legend outside the panels; arrowheads on solid lines mean media direction.
    path('M40,1640 H100', 'media')
    text(115, 1647, '미디어 (양방향)', anchor='start')
    path('M420,1640 H480')
    text(495, 1647, '설정·연결 시작 (화살표 방향)', anchor='start')
    s.append('</svg>')
    svg = OUT / 'fig1_traversal.svg'
    svg.write_text('\n'.join(s)+'\n', encoding='utf-8')
    png = OUT / 'fig1_traversal.png'
    subprocess.run(['rsvg-convert', '-d', '300', '-p', '300', '-o',
                    str(png), str(svg)], check=True)
    set_png_resolution(png)


if __name__ == '__main__':
    check_dependencies()
    generate()
