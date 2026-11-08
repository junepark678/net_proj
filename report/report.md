---
title: "NAT/PAT 뒤의 통화: 직접 연결과 중계"
subtitle: "STUN 홀 펀칭과 Tor onion service의 비교"
date: "컴퓨터네트워크 심화연구보고서 · 2026년 11월"
lang: ko-KR
mainfont: "NanumBarunGothic"
fontsize: 10pt
geometry:
  - a4paper
  - top=10mm
  - bottom=10mm
  - left=12mm
  - right=12mm
header-includes:
  - \usepackage{fontspec}
  - \usepackage{graphicx}
  - \newcommand{\latin}[1]{{\fontspec{DejaVu Sans}#1}}
  - \XeTeXlinebreaklocale "ko"
  - \XeTeXlinebreakskip = 0pt plus 1pt
  - \setlength{\parskip}{0.35em}
---

<!--
Rebuild with:
TEXMFCNF=/usr/share/texmf-dist/web2c pandoc report/report.md --resource-path=report --pdf-engine=xelatex -o report/out/report.pdf
-->

\newpage

그림 1은 PAT 뒤의 직접 연결, TURN 중계, Tor rendezvous를 비교한다.

\begin{center}
\includegraphics[width=13cm]{report/figures/fig1_traversal.png}

\textbf{그림 1.} PAT 환경의 직접 연결, TURN 중계, Tor onion-service rendezvous 경로 비교
\end{center}

그림의 NAT A/B 표시는 PAT 매핑을 수행하는 게이트웨이를 일반적으로 나타낸다.

## 1. 탐구 동기

집과 학교에서는 여러 기기가 한 공인 IPv4 주소를 공유한다. 웹은 되는데 외부에서 먼저 전화를 걸기 어려운 이유는 무엇일까? 이 탐구는 PAT 동작과 STUN/ICE 직접 연결, TURN·Tor 중계를 비교하고 Tor 통화의 한계를 앱 측정으로 살펴본다.

## 2. 개념 정리: PAT와 세 가지 경로

PAT(Port Address Translation)는 여러 사설 주소와 포트를 하나의 공인 주소와 여러 포트로 변환한다. RFC 3022는 여러 내부 주소를 하나의 외부 주소와 포트로 변환하는 방식을 Network Address Port Translation (NAPT)이라고 부른다.

내부 기기가 외부 서버에 패킷을 보내면 공유기는 출발지 주소·포트의 대응 상태를 기록하고, 응답을 원래 기기로 되돌린다. 외부에서 먼저 도착한 연결 요청은 이에 대응하는 상태가 없어서 목적지를 정하기 어렵다. 이것은 “NAT 자체가 방화벽”이라는 뜻은 아니다.

PAT의 주소·포트 매핑이 사전 연결 없이 들어오는 통신을 막는 핵심 이유다 [1].

STUN은 외부에서 관찰되는 주소와 포트를 알아내고 연결성을 검사하는 도구이지, 단독 NAT 통과 해법은 아니다 [2]. ICE는 양쪽의 주소 후보를 교환하고 연결성을 검사한다. 직접 후보가 성공하면 NAT가 허용하는 범위에서 두 기기가 서로 먼저 패킷을 보내 매핑을 만들고 통신할 수 있는데, 이를 홀 펀칭이라고 한다 [3, 7]. NAT 동작이나 방화벽 정책에 따라 직접 경로가 만들어지지 않을 수 있다.

직접 경로를 찾지 못하면 ICE는 TURN 후보를 선택할 수 있다. TURN은 서버에 릴레이 주소를 할당하고 양쪽 데이터를 그 서버를 통해 주고받는다 [4]. Tor onion service도 릴레이형이지만 rendezvous는 다르다. 클라이언트와 서비스가 각자 Tor 회로를 만들고 Tor rendezvous relay가 두 회로를 이어 준다 [5]. 따라서 Tor는 NAT의 포트 구멍을 뚫는 방식이 아니다. 앱 운영자가 통화 서버를 직접 둘 필요는 없지만, 통신은 Tor의 분산 릴레이와 디렉터리 인프라에 의존한다. STUN/ICE와 TURN은 표준에 따른 개념 비교이며, 이 앱에서 구현하거나 실측한 경로는 아니다. TURN과 Tor rendezvous 모두 중계 대역폭과 지연을 치르는 대신 어려운 NAT에서도 연결할 수 있다.

## 3. 심화 탐구: Onion Dial과 측정

Onion Dial은 Android와 데스크톱에서 각 기기가 직접 Tor onion service를 열고, QR 코드로 상대를 등록한다. 사용자가 “온라인”을 켜야 수신 가능하고, 양쪽이 켜져 있어야 통화가 연결된다. 별도 푸시 서버는 두지 않는다. 음성은 mono Opus 24 kbit/s, 20 ms 프레임으로 인코딩되어 Tor를 통과하는 TCP 스트림으로 전달된다 [6]. 코덱 비트레이트보다 왕복 지연과 늦게 도착하는 프레임이 대화성을 더 크게 좌우한다.

표 1. 완전한 1초 구간을 합친 앱 측정 요약 (E1은 지연 프로파일 모사, E2는 같은 호스트의 실제 Tor 회로)

| 시나리오 | RTT p50/p95* (ms) | 지터 p50/p95 (ms) | PLC 비율 |
|---|---:|---:|---:|
| E1 simulated LAN profile | 3 / 4.4 | 1.3 / 2.5 | 0.14% |
| E2 same-host Tor | 1,086.5 / 20,152.8 | 27.2 / 61.3 | 48.47% |

PLC(Packet Loss Concealment, 패킷 손실 은폐)는 재생 시각까지 도착하지 않은 음성 프레임을 보완해 재생하는 처리다. 비율은 은폐한 프레임 수를 전체 재생 프레임 수로 나눈 값이며, 완전한 1초 구간마다 20 ms 프레임 50개를 분모에 포함한다.

두 시나리오의 앱 TX 평균은 모두 27.65 kbps였다. 단순화 MOS proxy는 E1 4.09, E2 1.00이다.

문헌 비교: 2005년 자발적 NAT Check 응답에서는 UDP 홀 펀칭이 310/380 (82%), TCP가 184/286 (64%) NAT 보고에서 호환됐다[7]. 이는 오래된 자기선택 표본의 호환성이지 오늘날 CGNAT 성공률이 아니다. 2022년 공개 5G V2N 측정에서는 mmWave 구간의 STUN 연결 앱 계층 지연이 TURN보다 15% 낮았고, 다른 무선 구간은 대체로 비슷했다[10]. 각 결과는 해당 표본·망에 한정되어 이번 Tor 측정값과 직접 비교할 수 없다.

표의 E1·E2는 각각 3회 통화, 6개 끝점 CSV의 완전한 1초 구간을 합친 값이다. E1은 한 JVM의 두 CallEngine을 메모리 채널로 연결하고 약 4 ms RTT의 LAN 프로파일을 모사했으며, 실제 LAN 측정은 아니다. E2는 한 Linux 호스트의 한 JVM 프로세스 안에서 독립 앱 런타임 2개를 실행하고, 별도 Tor 프로세스 2개가 서로 다른 onion service와 실제 Tor 회로를 사용해 수행했다. Android와 데스크톱 사이의 E2는 수행하지 못해 이 same-host 시험으로 대체했다. 두 실험 모두 440 Hz 시험음을 썼다. *RTT는 각 완전한 1초 구간에 기록된 가장 최근의 일치 PONG 값이다. 새 PONG이 오기 전까지 같은 값이 반복될 수 있으므로 p50/p95는 독립 probe 횟수가 아니다.

별도의 headless Android emulator-데스크톱 시험도 연결됐지만 대화 품질은 낮았다. 첫 시험의 양 끝점 RTT p50은 약 13초, 두 번째 시험은 Android 측정이 18초에서 끝났다. 데스크톱의 여섯 연속 1초 구간에는 각 구간에서 가장 최근에 일치한 PONG 값인 52.295초가 반복 기록됐다. 이는 여섯 번의 독립 RTT probe를 뜻하지 않는다. 두 시험은 같은 호스트·NAT에서 했고 사람의 청취는 평가하지 않았다.

E2의 앱 AUDIO sequence-gap 지표는 0%였지만 PLC 비율은 48.47%였다. 이 지표는 앱에서 관찰한 AUDIO 순번의 공백을 세며 IP 패킷 손실이나 재전송을 측정하지 않는다. 재생 시각까지 프레임이 도착하지 않으면 PLC가 발생할 수 있다. 이 로그만으로 TCP 재전송, 릴레이 지연, 버퍼 언더런의 원인을 분리할 수 없다. p95 RTT 약 20초는 자연스러운 대화에 부적합했다. 이는 동일 호스트 3회 결과이며 일반 Tor 성능을 대표하지 않는다. TX는 앱 프레임·프로토콜 데이터를 포함하고 Tor 암호화 오버헤드는 제외한다.

참고: MOS는 G.107 단순화 식에 RTT의 절반과 버퍼 지연을 넣어 계산한 참고값이다. 코덱 손상 계수는 비슷한 비트레이트의 G.722.2 자료를 빌린 것이므로 Opus의 검증된 MOS나 청취 평가가 아니다 [9].

## 4. 결론 및 시사점

PAT 뒤에서 직접 도달성이 필요한 앱은 먼저 STUN/ICE 홀 펀칭을 시도할 수 있다. 낮은 지연이 중요하면 이 경로가 유리하지만, 모든 NAT에서 성공하지는 않는다. 실패한 연결을 안정적으로 잇는 TURN은 운영자의 릴레이 서버와 대역폭이 필요하다. Tor는 사용자가 별도 통화 서버를 운영하지 않으면서 양 끝의 위치를 숨기는 목적에 맞지만, 릴레이 회로의 지연이 크고 변동할 수 있다. 이번 실험에서는 24 kbit/s 음성의 대역폭보다 지연이 결정적인 한계였다. 사전에 서로 온라인 상태를 맞추고 프라이버시를 우선하는 제한적 실험에는 의미가 있지만, 일상 통화·긴급 통화·빠른 대화에는 맞지 않는다.

Tor가 범죄 전용 기술이라는 인상도 정확하지 않다. SecureDrop은 언론 제보자가 Tor onion service로 자료와 메시지를 보내도록 실제 운영되는 공개 시스템이다 [8]. 이 사례가 모든 Tor 사용을 정당화하는 것은 아니지만, 익명성과 위치 보호가 언론 제보처럼 합법적인 목적에 쓰인다는 점을 보여 준다.

한계: 실제 모바일 CGNAT과 사람의 청취 평가는 하지 않았다. 이 실험들은 제품 품질이나 일반 Tor 성능을 입증하지 않는다.

\newpage

## 참고문헌

1. P. Srisuresh, K. Egevang, “Traditional IP Network Address Translator (Traditional NAT),” RFC 3022, 2001. [IETF RFC 3022](https://datatracker.ietf.org/doc/html/rfc3022)
2. M. Petit-Huguenin et al., “Session Traversal Utilities for NAT (STUN),” RFC 8489, 2020. [IETF RFC 8489](https://datatracker.ietf.org/doc/html/rfc8489)
3. \latin{A. Keränen, C. Holmberg, J. Rosenberg}, “Interactive Connectivity Establishment (ICE),” RFC 8445, 2018. [IETF RFC 8445](https://datatracker.ietf.org/doc/html/rfc8445)
4. T. Reddy et al., “Traversal Using Relays around NAT (TURN),” RFC 8656, 2020. [IETF RFC 8656](https://datatracker.ietf.org/doc/html/rfc8656)
5. Tor Project, “The rendezvous protocol,” Tor Rendezvous Specification v3. [Tor Specifications](https://spec.torproject.org/rend-spec/rendezvous-protocol.html)
6. J.-M. Valin et al., “Definition of the Opus Audio Codec,” RFC 6716, 2012. [IETF RFC 6716](https://datatracker.ietf.org/doc/html/rfc6716)
7. B. Ford, P. Srisuresh, D. Kegel, “Peer-to-Peer Communication Across Network Address Translators,” USENIX ATC, 2005. [USENIX paper](https://www.usenix.org/conference/2005-usenix-annual-technical-conference/peer-peer-communication-across-network-address)
8. Freedom of the Press Foundation, “Onion Service.” SecureDrop Documentation. [SecureDrop docs](https://docs.securedrop.org/en/stable/glossary.html)
9. ITU-T, Recommendation G.113, “Transmission impairments due to speech processing,” 09/2024, Appendix IV; Recommendation G.107, “The E-model.” [ITU-T G.113](https://www.itu.int/rec/T-REC-G.113-202409-I/en) · [ITU-T G.107](https://www.itu.int/rec/T-REC-G.107/en)
10. B. Kihei, T. Davison, M. Okpok, J. Song, “Comparison of V2N STUN/TURN Round Trip Time Performance on a Public 5G Network,” IEEE VTC2022-Fall, 2022. [IEEE Xplore](https://doi.org/10.1109/VTC2022-Fall57202.2022.10012872)
