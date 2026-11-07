#!/usr/bin/env python3
"""Generate Figure 1 as SVG and 300 dpi PNG; requires librsvg's rsvg-convert."""
from pathlib import Path
import subprocess

OUT = Path(__file__).resolve().parent
W,H=1772,900
s=['''<svg xmlns="http://www.w3.org/2000/svg" width="15cm" height="7.62cm" viewBox="0 0 1772 900"><rect width="100%" height="100%" fill="white"/><style>text{font-family:'Noto Sans CJK KR','Noto Sans KR',sans-serif;fill:#111} .title{font-size:36px;font-weight:bold}.label{font-size:27px;font-weight:bold}.small{font-size:21px}.tiny{font-size:18px}.box{fill:#f1f1f1;stroke:#222;stroke-width:3}.nat{fill:#ddd;stroke:#222;stroke-width:3}.internet{fill:#fff;stroke:#444;stroke-width:3;stroke-dasharray:8 6}.solid{fill:none;stroke:#111;stroke-width:5;marker-end:url(#a)}.dash{fill:none;stroke:#555;stroke-width:4;stroke-dasharray:12 8;marker-end:url(#a)}</style><defs><marker id="a" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#111"/></marker></defs>''']
def t(x,y,txt,cl='small',anchor='middle'): s.append(f'<text x="{x}" y="{y}" text-anchor="{anchor}" class="{cl}">{txt}</text>')
def rect(x,y,w,h,cl='box'): s.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" class="{cl}"/>')
def line(x1,y1,x2,y2,cl): s.append(f'<path d="M{x1},{y1} L{x2},{y2}" class="{cl}"/>')
for i,(x,title) in enumerate([(24,'(a) STUN 홀 펀칭'),(602,'(b) TURN 릴레이'),(1180,'(c) Tor 어니언 서비스 랑데부')]):
 rect(x,18,568,690,'internet'); t(x+284,65,title,'title')
 # devices, NATs and cloud/server
 rect(x+25,330,110,78);t(x+80,362,'A: 스마트폰','small');t(x+80,390,'기기','tiny')
 rect(x+150,310,100,120,'nat');t(x+200,355,'CGNAT','small');t(x+200,387,'NAT','tiny')
 rect(x+300,310,100,120,'nat');t(x+350,355,'가정용','small');t(x+350,387,'라우터 NAT','tiny')
 rect(x+425,330,110,78);t(x+480,362,'B: PC','small');t(x+480,390,'기기','tiny')
 # rearrange visual network order A-> nat -> internet server -> nat -> B
 if i==0:
  rect(x+238,120,100,70);t(x+288,162,'STUN','small');line(x+80,330,x+275,190,'dash');line(x+480,330,x+300,190,'dash');line(x+110,370,x+455,370,'solid');t(x+284,475,'UDP 直連・穴開け後にメディア','tiny')
 elif i==1:
  rect(x+238,120,100,70);t(x+288,162,'TURN','small');line(x+80,330,x+275,190,'solid');line(x+480,330,x+300,190,'solid');line(x+110,370,x+455,370,'solid');t(x+284,475,'双方が外向き接続、TURN がメディア中継','tiny')
 else:
  rect(x+225,115,120,54);t(x+285,150,'紹介点','tiny');rect(x+225,190,120,54);t(x+285,225,'ランデブー点','tiny');line(x+80,330,x+250,160,'dash');line(x+480,330,x+320,220,'dash');line(x+110,370,x+455,370,'solid');t(x+284,475,'Tor 回路は各 3 ホップ：全通信が外向き TCP','tiny');rect(x+92,515,384,64);t(x+284,555,'NAT에 포트 개방 없음','label')
 t(x+284,637,['STUN으로 매핑 주소를 확인한 뒤 UDP 직접 연결','두 피어의 미디어를 TURN 서버가 릴레이','소개점에 게시하고 랑데부 지점에서 회선 연결'][i],'tiny')
# legend
line(130,780,230,780,'solid');t(244,787,'실선 = 미디어','tiny','start');line(490,780,590,780,'dash');t(605,787,'점선 = 시그널링/제어','tiny','start');line(965,780,1065,780,'solid');t(1080,787,'화살표 = 연결 시작 방향','tiny','start')
s.append('</svg>')
svg=''.join(s); (OUT/'fig1_traversal.svg').write_text(svg,encoding='utf-8')
subprocess.run(['rsvg-convert','-d','300','-p','300','-o',str(OUT/'fig1_traversal.png'),str(OUT/'fig1_traversal.svg')],check=True)
