# 한국어 PII 특화 트랜스포머 NER — 구축 · 3자 벤치마크 (AI-R · 제로샷 · 도메인 튜닝)
#
# 정규식 · 형태소 분석기(가름 등)를 섞지 않은 '순수 딥러닝 NER' 끼리 비교한다.
#   ① AI-R           — 파수 AI-R 결과 JSON 을 주입받아 채점(실제 응답 형식 그대로)
#   ② 제로샷          — 공개 한국어 NER 가중치를 그대로(기본 monologg/koelectra-base-v3-naver-ner)
#   ③ 도메인 튜닝      — 같은 몸통(koelectra-base-v3-discriminator)을 교정 도메인 합성셋으로 3~5 에포크
# ②와 ③은 몸통 · 토크나이저가 같다. 둘의 차이는 '도메인 튜닝을 했느냐' 하나뿐이다.
#
# ──────────────────────────────────────────────────────────────────────────
# 설계에서 지킨 것 (전부 커넥터 인수인계 · 비식별 실측에서 데인 자리다)
#
#   ① **시험셋은 봉인 세트다.** Track A · B 각 20문장은 아래에 손으로 적어 고정했다.
#      학습셋은 다른 템플릿 · 다른 값 풀에서 만들고, 시험셋 이름과 '이름 부분'(다정 · 소망 …)은
#      학습 생성에서 아예 뺀다. 그래서 시험의 모든 이름은 모델이 처음 보는 이름이다.
#      시험셋을 보고 학습셋 · 하이퍼파라미터를 고치면 그 숫자는 더 이상 미오염이 아니다
#      (가름 v1~v16 이 그렇게 오염됐다 — 첫 실행만 믿는다).
#
#   ② **시험셋은 엔진들이 실측에서 놓친 자리로 채웠다.** 문맥 없는 이름 · 낱말과 같은 이름
#      (다정 · 강산 · 소망 · 하늘) · 외자(임별 · 주진 · 국란) · 희성(평 · 판 · 제갈 · 서문 · 어) ·
#      STT 로 쪼개진 이름("김도 윤") · 말로 부른 번호("공일공 …") · 띄어 쓴 번호 ·
#      하이픈 없는 주민번호 · 접미사 없는 건물명. 반대로 이름처럼 보이는 낱말(정미소 · 강산 ·
#      하늘)과 PII 가 아닌 숫자(시각 · 인원 · 호실)도 넣어 과탐을 잰다.
#
#   ③ **라벨은 어절(공백) 단위 BIO 다.** 제로샷 모델(Naver NER)이 학습한 단위와 같아
#      어느 한쪽에 유리하지 않다. 조사가 붙은 어절("김다정의")은 그 어절 전체가 개체다.
#
#   ④ **AI-R 은 실제 응답 형식으로 받는다.** labelResult(딥러닝) · patternResult(정규식) 의
#      entity|pattern · word · start · end · except. 순수 딥러닝 비교는 labelResult 만,
#      참고로 정규식까지 합친 '제품 전체' 줄을 따로 낸다.
#      AI-R 은 주소 하나를 6~7조각으로 쪼갠다 — 붙어 있는 주소 조각을 합쳐 채점한다
#      (커넥터 AirProbe 와 같은 보정. 안 하면 AI-R 이 부당하게 불리하다).
#      word 가 원문과 어긋난 개체는 버리고 센다(좌표 어긋남을 조용히 받지 않는다).
#
#   ⑤ **점수는 두 눈으로 본다.** seqeval strict 개체 F1(유형까지 맞혀야 정답)과,
#      비식별 관점의 '남은 개인정보'(유형 무관 — 개체의 한 어절이라도 O 면 샌 것).
#      AI-R 은 사람 이름을 ORG_BIZ 로 단 적이 있다 — 유형은 틀려도 가렸으면 막은 것이다.
#      20문장은 작다. 부트스트랩 95% 신뢰구간과 대응 부트스트랩 개선 폭 CI 를 함께 낸다.
#
#   ⑥ **주민번호는 체크섬을 맞춘다.** 체크섬 안 맞는 가상 번호는 실제 엔진이 영원히
#      미탐으로 보인다(문서비식별 1단계 함정).
# ──────────────────────────────────────────────────────────────────────────
#
# 설치(Python 3.10+ · CPU 로 충분):
#   pip install "torch>=2.6" "transformers>=4.46" "datasets>=3.0" seqeval accelerate
#   (검증한 조합: torch 2.14.0 · transformers 5.17.0 · datasets 5.0.1 · seqeval 1.2.2 · accelerate 1.15.0)
#
# 쓰는 법:
#   python ner_benchmark.py run                       # 생성 → 제로샷 → 튜닝 → 평가 → out/REPORT.md
#   python ner_benchmark.py run --quick               # CPU 빠른 검증(학습 200문장/트랙 · 3 에포크)
#   python ner_benchmark.py run --train-tracks AB     # 일반 문맥(A)까지 같이 학습(운영 권장안)
#   python ner_benchmark.py run --air-json air.json   # AI-R 결과 주입 → 3자 비교
#   python ner_benchmark.py gen                       # 데이터셋만 out/data/*.jsonl
#   python ner_benchmark.py export-air                # AI-R 에 넣을 문장(out/air_input.jsonl)
#   python ner_benchmark.py air-fetch --endpoint URL  # 커넥터로 AI-R 결과 받기(포트포워드 필요)
#   python ner_benchmark.py air-oracle                # AI-R 형식 자가검증 파일(정답을 AI-R 모양으로)
import argparse
import hashlib
import inspect
import json
import os
import random
import re
import sys
import time
import urllib.request
from datetime import datetime

os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
LABELS = ["O", "B-PER", "I-PER", "B-NUM", "I-NUM", "B-LOC", "I-LOC"]
L2I = {l: i for i, l in enumerate(LABELS)}
TYPES = ("PER", "NUM", "LOC")
TYPE_KO = {"PER": "이름", "NUM": "번호", "LOC": "주소"}


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


# ---------------------------------------------------------------- 주민번호

def rrn_checksum(d12):
    w = [2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5]
    return str((11 - sum(int(c) * k for c, k in zip(d12, w)) % 11) % 10)


def rrn(front, mid, hyphen=True):
    v = front + mid + rrn_checksum(front + mid)
    return v[:6] + "-" + v[6:] if hyphen else v


# ---------------------------------------------------------------- 봉인 시험셋
#
# [유형:표면형] 으로 정답을 적는다. 문장 · 값은 학습 생성기와 겹치지 않게 썼다.
# 이 목록을 고치면 그 뒤 숫자는 이전 숫자와 견줄 수 없다 — 고칠 거면 새 id 로 새 세트를 만든다.

TRACK_A_TEST = [  # 일반 대화 · 업무
    ("A01", "안녕하세요, [PER:김다정] 고객님 맞으시죠? 등록된 번호가 [NUM:010-4821-7735]로 되어 있습니다."),
    ("A02", "배송지는 [LOC:서울특별시 마포구 월드컵북로 396]이고 수령인은 [PER:최강산] 님입니다."),
    ("A03", "회의록 작성: [PER:남궁윤서] / 참석: [PER:오세린], [PER:탁준]"),
    ("A04", "[PER:정미소] 씨가 내일 오전에 동네 정미소에 쌀을 맡기러 간대요."),
    ("A05", "본인 확인용 주민등록번호 [NUM:%s] 맞는지 확인 부탁드립니다." % rrn("930214", "204781")),
    ("A06", "환불 계좌는 국민은행 [NUM:604202-01-387415]이고 예금주는 [PER:한소망]입니다."),
    ("A07", "[PER:평광호] 과장님, 견적서 보내드렸습니다. 확인 후 [NUM:02-3412-8890]으로 연락 주세요."),
    ("A08", "올해는 강산이 변할 만큼 많은 일이 있었지만 다정한 동료들 덕분에 버틸 수 있었다."),
    ("A09", "택배는 [LOC:경기도 성남시 분당구 판교역로 166 에이치스퀘어 N동] 1층 안내데스크에 맡겨 주세요."),
    ("A10", "[PER:이슬] 대리가 [PER:박하늘] 팀장에게 주간 보고서를 넘겼다."),
    ("A11", "연락처: [NUM:010 5520 1847] (문자만 가능)"),
    ("A12", "신청인 [PER:제갈민규]의 주소는 [LOC:부산광역시 해운대구 센텀중앙로 79], 연락처는 [NUM:051-744-2310]입니다."),
    ("A13", "[오후 2:14] [PER:서지안]: 3시에 2층 회의실에서 봬요."),
    ("A14", "상담원 [PER:모나래]입니다. 본인 확인을 위해 휴대폰 번호 [NUM:010-9036-4412] 끝까지 말씀해 주세요."),
    ("A15", "[PER:고은서] 님 앞으로 온 등기우편이 [LOC:대전 유성구 대학로 99] 관리실에 보관 중입니다."),
    ("A16", "교육 이수자 명단: [PER:변하람], [PER:심겨레], [PER:주아람], [PER:태도현]"),
    ("A17", "다음 주 월요일 오전 10시, 3번 출구 앞에서 만나요. 총 5명이고 회비는 2만 원입니다."),
    ("A18", "[PER:하예린] 님, 주문하신 상품이 [LOC:인천광역시 연수구 송도과학로 32]로 출발했습니다."),
    ("A19", "보내는 분 [PER:채다움] / 받는 분 [PER:임별] / 연락처 [NUM:010-3378-2051]"),
    ("A20", "어제 [PER:도누리]랑 [PER:황보세진]이 같이 사무실에 들렀어."),
]

TRACK_B_TEST = [  # 교정 — 접견 녹취(STT) · 상담일지 · 공문
    ("B01", "접견일지 / 수용자 [PER:강하나] (수용번호 [NUM:2024-0187]) / 접견인 모 [PER:윤정숙] / 접견번호 [NUM:20261014-0032]"),
    ("B02", "수용자: 어 엄마 나야 나 [PER:민수]. 접견인: 응 [PER:민수]야 밥은 잘 먹고 있어?"),
    ("B03", "접견인: 변호사님이 [PER:김도 윤] 씨 사건 [NUM:2025고단1234] 기록 다 보셨대요."),
    ("B04", "수용자: 누나 번호 [NUM:공일공 칠삼사이 팔팔일오] 맞지? 거기로 전화 좀 해 줘."),
    ("B05", "상담일지 / 수용자명: [PER:평나봄] / 수번: [NUM:3271] / 상담자: 교위 [PER:서문혁] / 내용: 가족 관계 회복 상담"),
    ("B06", "접견인: [PER:소망]이가 다음 주에 면회 온대요. 접견 예약번호는 [NUM:V-2026-004512]예요."),
    ("B07", "피고인 [PER:주진]은 [NUM:2024노1876] 사건 당시 [LOC:광주광역시 북구 첨단과기로 123]에 주소를 두고 있었다."),
    ("B08", "수용동 3동 12실 점검 결과 이상 없음. 오후 2시 운동 후 14시 30분 귀소 완료."),
    ("B09", "접견인: 형님 그 [PER:판석규] 사장 있잖아 출소하면 연락하래 번호는 [NUM:010-2284-9013]이래."),
    ("B10", "보호관찰 대상자 [PER:양다온]의 주민등록번호는 [NUM:%s]이며 주소지는 "
            "[LOC:경상남도 창원시 성산구 중앙대로 151]이다." % rrn("970825", "108342", hyphen=False)),
    ("B11", "수용자: 저기 교도관님 [PER:어겨울] 씨 접견 신청이요 수번 [NUM:1542]번이요."),
    ("B12", "사건번호 [NUM:2023고합412] 관련하여 [PER:석분이] 수용자의 이송 요청서를 첨부합니다."),
    ("B13", "접견인: 어 그래 아빠가 [PER:정세우] 변호사 만나고 왔다 [NUM:2025고단5678] 그거 다음 달에 선고래"),
    ("B14", "수용번호 [NUM:2025-1103] [PER:고은별], [NUM:2025-1104] [PER:나태윤] 두 명 오전 10시 의무과 진료 동행."),
    ("B15", "상담 메모: 처 [PER:국란]과 통화함. 자녀 문제로 상심이 큼."),
    ("B16", "접견인: 우리 집 주소 바뀌었어 [LOC:대구 수성구 달구벌대로 2450]으로 편지 보내."),
    ("B17", "수용자: 그 [PER:진혁]이 형한테 전화번호 [NUM:010 6624 7739] 이거 꼭 전해 줘."),
    ("B18", "수용자: 어 엄마 나 괜찮아 밥도 잘 먹고 운동도 하고 여기 하늘도 맑더라."),
    ("B19", "[PER:임하은] 수용자 출정 일정: [NUM:2024고단3390] 공판, 서울남부지방법원 3층 305호 법정."),
    ("B20", "접견번호 [NUM:20261015-0117] / 접견 대상: [PER:왕도하] (수번 [NUM:2087]) / "
            "접견인: 배우자 [PER:선우미르] / 연락처: [NUM:공일공 오삼삼일 이사구칠]"),
]

MARK = re.compile(r"\[(PER|NUM|LOC):([^\]]+)\]")


def parse_markup(s):
    """'[PER:김다정] 고객님' → ('김다정 고객님', [(0, 3, 'PER')])."""
    out, spans, last = "", [], 0
    for m in MARK.finditer(s):
        out += s[last:m.start()]
        st = len(out)
        out += m.group(2)
        spans.append((st, len(out), m.group(1)))
        last = m.end()
    return out + s[last:], spans


def word_offsets(text):
    return [(m.group(), m.start(), m.end()) for m in re.finditer(r"\S+", text)]


def spans_to_tags(text, spans, strict=True):
    """문자 구간 → 어절 BIO. 어절이 구간과 한 글자라도 겹치면 그 개체다."""
    words = word_offsets(text)
    tags = ["O"] * len(words)
    for s, e, t in sorted(spans):
        first = True
        for i, (_, ws, we) in enumerate(words):
            if ws < e and we > s:
                if tags[i] != "O":
                    if strict:
                        raise ValueError("한 어절에 개체 둘: %r · %r" % (text, text[s:e]))
                    continue
                tags[i] = ("B-" if first else "I-") + t
                first = False
    return [w for w, _, _ in words], tags


def make_example(eid, track, split, text, spans):
    tokens, tags = spans_to_tags(text, spans)
    return {"id": eid, "track": track, "split": split, "text": text, "tokens": tokens,
            "ner_tags": [L2I[t] for t in tags], "tags": tags,
            "spans": [[s, e, t] for s, e, t in spans]}


def test_set(track):
    rows = TRACK_A_TEST if track == "A" else TRACK_B_TEST
    out = []
    for eid, mk in rows:
        text, spans = parse_markup(mk)
        out.append(make_example(eid, track, "test", text, spans))
    return out


# ---------------------------------------------------------------- 학습셋 생성기

SURNAMES = ("김 이 박 최 정 강 조 윤 장 임 한 오 서 신 권 황 안 송 류 전 홍 고 문 양 손 배 백 허 유 남 "
            "심 노 하 곽 성 차 주 우 구 민 진 나 지 엄 변 채 원 천 방 공 현 함 염 여 추 도 소 석 선 "
            "설 마 길 연 위 표 명 기 반 왕 금 옥 육 인 맹 제 모 탁 국 어 은 편 용 예 경 봉 사 부 가 "
            "복 태 목 형 계 피 두 감 음 빈 동 온 호 범 좌 팽 승 간 상 갈 시 견 당 화 창 옹 순 근 단 "
            "평 판 라").split()
COMPOUND_SURNAMES = ["남궁", "황보", "제갈", "선우", "독고", "사공", "서문", "동방"]
GIVEN_FIRST = list("민서지현준우연윤수하은도예진영호성혜정유재승시아태경동원희주다나채소한세가건규라미보상인용종창철형효선미")
GIVEN_LAST = list("민서준우연윤수하은진영호성혜정유재아경원희주린율온담빈결별솔람훈혁철웅석규건현환찬범섭순숙자미란옥선")
WORDLIKE_GIVEN = ["다정", "하늘", "소망", "미소", "강산", "하나", "구슬", "바다", "새날", "한빛", "보람", "슬기",
                  "나래", "가람", "단비", "누리", "마루", "아름", "사랑", "기쁨", "겨레", "이슬", "샛별", "봄비",
                  "여름", "가을", "노을", "햇살", "은하", "한결", "다온", "해솔", "새봄", "초롱", "하랑", "푸름",
                  "이든", "그루", "온새", "찬솔", "마음", "아라", "벼리", "다솜", "라온", "늘봄"]
SINGLE_GIVEN = list("별솔결진혁란훈철웅준빈율담봄샘환석찬건현호산")
TITLES = ["과장", "대리", "부장", "팀장", "차장", "실장", "주임", "사원", "이사", "대표"]
STAFF = ["교위", "교사", "교감", "교정관", "주임", "계장", "교도"]
RELATIONS = ["처", "모친", "부친", "형", "누나", "동생", "배우자", "아들", "딸", "모", "부", "삼촌", "이모"]
FACILITIES = ["서울구치소", "안양교도소", "대구교도소", "청주여자교도소", "원주교도소", "수원구치소", "여주교도소"]
FILLERS = ["어 ", "음 ", "그 ", "저기 ", "아니 ", "어어 ", "", "", ""]
CASE_CODES = ["고단", "고합", "고정", "노", "도", "초기", "가단", "가합", "로", "구단"]
KO_DIGIT = {"0": "공", "1": "일", "2": "이", "3": "삼", "4": "사", "5": "오", "6": "육", "7": "칠", "8": "팔", "9": "구"}
SIDO = [("서울특별시", "서울"), ("부산광역시", "부산"), ("대구광역시", "대구"), ("인천광역시", "인천"),
        ("광주광역시", "광주"), ("대전광역시", "대전"), ("울산광역시", "울산"), ("세종특별자치시", "세종"),
        ("경기도", "경기"), ("강원특별자치도", "강원"), ("충청북도", "충북"), ("충청남도", "충남"),
        ("전북특별자치도", "전북"), ("전라남도", "전남"), ("경상북도", "경북"), ("경상남도", "경남"),
        ("제주특별자치도", "제주")]
SIGUNGU = {"서울": ["종로구", "중구", "용산구", "성동구", "강서구", "노원구", "은평구"],
           "부산": ["동래구", "남구", "부산진구", "사하구"], "대구": ["달서구", "북구", "동구"],
           "인천": ["부평구", "계양구", "남동구"], "광주": ["광산구", "서구", "남구"], "대전": ["서구", "중구", "대덕구"],
           "울산": ["울주군", "남구"], "세종": ["한솔동", "조치원읍"],
           "경기": ["수원시 영통구", "고양시 일산동구", "용인시 수지구", "화성시", "평택시", "파주시"],
           "강원": ["춘천시", "원주시", "강릉시"], "충북": ["청주시 흥덕구", "충주시"], "충남": ["천안시 서북구", "아산시"],
           "전북": ["전주시 완산구", "군산시"], "전남": ["목포시", "여수시"], "경북": ["포항시 남구", "구미시"],
           "경남": ["김해시", "진주시"], "제주": ["제주시", "서귀포시"]}
ROAD_STEM = ["시청", "역전", "공원", "문화", "산업", "평화", "통일", "희망", "새말", "행복", "동산", "솔밭",
             "강변", "장미", "은행", "백제", "가야", "대흥", "신촌", "중부", "남부", "한누리", "예술"]
DONG = ["역삼동", "신림동", "봉천동", "우동", "부전동", "월평동", "둔산동", "상무동", "삼산동", "영통동", "정자동"]
BUILDING = ["아파트", "빌라", "오피스텔", "타워", "빌딩"]
BUILDING_NAME = ["한울", "푸른숲", "새빛", "청솔", "동문", "은하수", "그린", "현대", "대림", "센트럴", "리버뷰"]


def _final(s):
    """마지막 글자의 받침 — (받침 있음, ㄹ 받침). 숫자는 읽는 소리로(영 · 일 · 삼 · 육 · 칠 · 팔)."""
    ch = s.rstrip()[-1:]
    if "가" <= ch <= "힣":
        jong = (ord(ch) - 0xAC00) % 28
        return jong != 0, jong == 8
    if ch.isdigit():
        return ch in "013678", ch in "178"
    return False, False


def josa(surface, pair):
    a, b = pair.split("/")
    has, rieul = _final(surface)
    if a == "으로":
        return "로" if (not has or rieul) else "으로"
    return a if has else b


class Gen:
    """슬롯 값 생성기. 시험셋 값 · 이름 부분과 겹치는 것은 다시 뽑는다(봉인)."""

    def __init__(self, rng, sealed):
        self.rng = rng
        self.sealed = sealed          # {"PER_FULL": set, "GIVEN": set, "VALUES": set, "ROADS": set}

    def d(self, n):
        return "".join(self.rng.choice("0123456789") for _ in range(n))

    def surname(self):
        r = self.rng
        if r.random() < 0.06:
            return r.choice(COMPOUND_SURNAMES)
        head = SURNAMES[:20]
        return r.choice(head) if r.random() < 0.6 else r.choice(SURNAMES)

    def given(self):
        r = self.rng
        for _ in range(200):
            p = r.random()
            if p < 0.70:
                g = r.choice(GIVEN_FIRST) + r.choice(GIVEN_LAST)
            elif p < 0.88:
                g = r.choice(WORDLIKE_GIVEN)
            else:
                g = r.choice(SINGLE_GIVEN)
            if g not in self.sealed["GIVEN"] and not (len(g) == 2 and g[0] == g[1]):
                return g
        raise RuntimeError("이름 풀이 너무 좁다")

    def person(self):
        for _ in range(200):
            n = self.surname() + self.given()
            if n not in self.sealed["PER_FULL"]:
                return n
        raise RuntimeError("이름 풀이 너무 좁다")

    def phone(self):
        r = self.rng
        f = r.choice(["m", "m", "m", "m", "seoul", "local", "070"])
        if f == "m":
            return "010-%s-%s" % (self.d(4), self.d(4))
        if f == "seoul":
            return "02-%s-%s" % (self.d(r.choice([3, 4])), self.d(4))
        if f == "070":
            return "070-%s-%s" % (self.d(4), self.d(4))
        return "%s-%s-%s" % (r.choice(["031", "032", "033", "041", "042", "043", "051", "052", "053", "054",
                                       "055", "061", "062", "063", "064"]), self.d(3), self.d(4))

    def phone_sp(self):
        return "010 %s %s" % (self.d(4), self.d(4))

    def phone_ko(self):
        groups = ["010", self.d(4), self.d(4)]
        zero = "영" if self.rng.random() < 0.25 else "공"
        return " ".join("".join(zero if c == "0" else KO_DIGIT[c] for c in g) for g in groups)

    def rrn(self, hyphen=True):
        r = self.rng
        yy = r.randint(55, 99) if r.random() < 0.8 else r.randint(0, 6)
        gender = r.choice("12") if yy >= 55 else r.choice("34")
        front = "%02d%02d%02d" % (yy, r.randint(1, 12), r.randint(1, 28))
        return rrn(front, gender + self.d(5), hyphen)

    def acct(self):
        r = self.rng
        f = r.choice([(3, 3, 6), (6, 2, 6), (4, 3, 6), (3, 6, 5), (3, 2, 6)])
        return "-".join(self.d(n) for n in f)

    def address(self):
        r = self.rng
        for _ in range(200):
            full, short = r.choice(SIDO)
            sido = full if r.random() < 0.6 else short
            parts = [sido, r.choice(SIGUNGU[short])]
            if r.random() < 0.75:
                road = r.choice(ROAD_STEM) + r.choice(["로", "길", "대로", "로%d번길" % r.randint(1, 60)])
                if road in self.sealed["ROADS"]:
                    continue
                num = str(r.randint(1, 999)) + ("-%d" % r.randint(1, 30) if r.random() < 0.15 else "")
                parts += [road, num]
            else:
                parts += [r.choice(DONG), "%d-%d" % (r.randint(1, 999), r.randint(1, 99))]
            x = r.random()
            if x < 0.25:
                parts += ["%d동" % r.randint(101, 125), "%d호" % r.randint(101, 2504)]
            elif x < 0.40:
                parts += [r.choice(BUILDING_NAME) + r.choice(BUILDING), "%d층" % r.randint(1, 20)]
            elif x < 0.47:
                parts += [r.choice(BUILDING_NAME) + r.choice(["플레이스", "스퀘어", "파크"])]
            return " ".join(parts)
        raise RuntimeError("주소 풀이 너무 좁다")

    def inmate(self):
        r = self.rng
        f = r.random()
        if f < 0.45:
            return "%d-%s" % (r.randint(2018, 2026), self.d(4))
        return str(r.randint(100, 9999))

    def visit(self):
        r = self.rng
        f = r.random()
        if f < 0.45:
            return "%d%02d%02d-%s" % (r.randint(2024, 2026), r.randint(1, 12), r.randint(1, 28), self.d(4))
        if f < 0.75:
            return "V-%d-%s" % (r.randint(2024, 2026), self.d(6))
        return "M%d%s" % (r.randint(2024, 2026), self.d(5))

    def case(self):
        r = self.rng
        return "%d%s%d" % (r.randint(2018, 2026), r.choice(CASE_CODES), r.randint(1, 19999))

    def value(self, kind):
        """→ (표면형, 유형 | None)."""
        r = self.rng
        for _ in range(100):
            if kind == "PER":
                v, t = self.person(), "PER"
            elif kind == "GIVEN":
                v, t = self.given(), "PER"
            elif kind == "PER_SPLIT":                       # STT 가 이름을 띄어 적은 꼴
                n = self.person()
                cut = r.randint(1, len(n) - 1) if len(n) > 2 else 1
                v, t = n[:cut] + " " + n[cut:], "PER"
            elif kind == "PHONE":
                v, t = self.phone(), "NUM"
            elif kind == "PHONE_SP":
                v, t = self.phone_sp(), "NUM"
            elif kind == "PHONE_KO":
                v, t = self.phone_ko(), "NUM"
            elif kind == "RRN":
                v, t = self.rrn(True), "NUM"
            elif kind == "RRN_NH":
                v, t = self.rrn(False), "NUM"
            elif kind == "ACCT":
                v, t = self.acct(), "NUM"
            elif kind == "ADDR":
                v, t = self.address(), "LOC"
            elif kind == "INMATE":
                v, t = self.inmate(), "NUM"
            elif kind == "VISIT":
                v, t = self.visit(), "NUM"
            elif kind == "CASE":
                v, t = self.case(), "NUM"
            elif kind == "F":
                return r.choice(FILLERS), None
            elif kind == "TITLE":
                return r.choice(TITLES), None
            elif kind == "STAFF":
                return r.choice(STAFF), None
            elif kind == "REL":
                return r.choice(RELATIONS), None
            elif kind == "FAC":
                return r.choice(FACILITIES), None
            elif kind == "M":
                return str(r.randint(1, 12)), None
            elif kind == "D":
                return str(r.randint(1, 28)), None
            elif kind == "H":
                return str(r.randint(1, 12)), None
            elif kind == "N":
                return str(r.randint(1, 9)), None
            else:
                raise KeyError(kind)
            if v.replace(" ", "") not in self.sealed["VALUES"]:
                return v, t
        raise RuntimeError("봉인 값과 계속 겹친다: " + kind)


# 학습 템플릿 — {슬롯} · {슬롯|조사A/조사B}(받침으로 고름). 시험셋 문장과 다른 틀로 썼다.
TRAIN_A = [
    "{PER|은/는} 오늘 오후 반차입니다.",
    "고객님 성함이 {PER} 님 맞으신가요?",
    "담당자 {PER} ({PHONE})에게 문의해 주세요.",
    "주문자 {PER} / 배송지 {ADDR} / 연락처 {PHONE}",
    "{PER} 님, 예약이 확정되었습니다. 변경은 {PHONE|으로/로} 연락 바랍니다.",
    "본인 확인을 위해 주민등록번호 {RRN|을/를} 입력해 주세요.",
    "급여 계좌가 {ACCT|으로/로} 변경되었습니다. 예금주 {PER}",
    "{PER} {TITLE}님이 {PER2} 사원에게 업무를 인계했습니다.",
    "참석자: {PER}, {PER2}, {PER3}",
    "[알림] {PER} 님의 택배가 {ADDR|으로/로} 배송 출발했습니다.",
    "이사 간 집 주소는 {ADDR|이고/고} 전화는 {PHONE_SP|이에요/예요}.",
    "{PER|이/가} 그러는데 {PER2|이/가} 다음 달에 결혼한대.",
    "회원 정보 수정 요청: 이름 {PER}, 주소 {ADDR}",
    "{GIVEN|아/야} 내일 몇 시에 만나?",
    "상담 요청인 {PER} (연락처 {PHONE})",
    "{PER} {TITLE} 휴대폰 번호 바뀌었대요 {PHONE|이에요/예요}.",
    "계약자 {PER}, 주민번호 {RRN_NH}, 주소 {ADDR}",
    "오늘 회의는 {PER} {TITLE} 주재로 진행됩니다.",
    "{PER|과/와} {PER2|이/가} 공동 발표자로 선정됐다.",
    "{PER} 선생님께서 상담 일정을 문자로 알려 주셨습니다.",
    "청구서 수령지: {ADDR}",
    "작성 {PER} · 검토 {PER2} · 승인 {PER3}",
    "{PER} 고객 환불 계좌 {ACCT}",
    "@{PER} 자료 공유 부탁드려요.",
    "택시 기사님께 {ADDR} 앞에서 내려 달라고 했다.",
    "이번 주 당직은 {PER}, 다음 주는 {PER2}입니다.",
    "{GIVEN} 씨 번호 불러 드릴게요 {PHONE_KO}.",
    "고객: {PER} / 문의: 배송 지연 / 회신: {PHONE}",
    "우편물은 {ADDR} 경비실에 맡겨 주세요.",
    "{PER} 님께서 {M}월 {D}일 {H}시에 방문 예약하셨습니다.",
    "제 이름은 {PER|이고/고} 번호는 {PHONE|이에요/예요}.",
    "{PER_SPLIT} 님 맞으세요?",
    "신규 입사자 {PER} 님을 환영합니다!",
    "거래처 {PER} {TITLE} 연락처 {PHONE_SP}",
    "{ADDR}에 사는 {PER} 씨가 분실물을 찾아갔다.",
]
NEG_A = [
    "오늘 회의는 오후 {H}시에 {N}층 대회의실에서 열립니다.",
    "이번 분기 매출이 {N}% 늘었고 직원 수는 {N}0명이다.",
    "바다가 보이는 카페에서 보람찬 하루를 보냈다.",
    "사랑과 기쁨이 가득한 연말 보내세요.",
    "택배는 문 앞에 두시고 벨은 누르지 마세요.",
    "다음 주 화요일까지 보고서 초안을 제출해 주세요.",
    "고객님 감사합니다. 좋은 하루 되세요.",
    "회의실 예약은 내선 {N}번으로 해 주세요.",
    "슬기로운 생활을 위한 다섯 가지 습관",
    "노을이 지는 강변을 따라 한참 걸었다.",
    "{M}월 {D}일부터 사흘간 시스템 점검이 있습니다.",
]
TRAIN_B = [
    "수용자: {F}엄마 나 {GIVEN|이야/야}. 잘 지내지?",
    "접견인: {F}{GIVEN|아/야} 밥은 잘 챙겨 먹니?",
    "수용자: {F}그 {PER} 변호사한테 전화 좀 해 줘 번호는 {PHONE|이야/야}.",
    "수용자: 번호 불러 줄게 {PHONE_KO} {F}여기로 연락해.",
    "접견인: 아빠가 {PER_SPLIT} 씨 만나고 왔어.",
    "수용자: 영치금은 {ACCT|으로/로} 넣어 주면 돼.",
    "접견인: {F}집 주소는 {ADDR} 그대로야.",
    "수용자: 형 {PER|이/가} 면회 온다고 했는데 언제 와?",
    "접견인: 다음 달에 {CASE} 재판 날짜 잡혔대.",
    "수용자: {F}그러니까 {PER} 사장님 번호가 {PHONE_SP} 맞지?",
    "수용자명: {PER} / 수용번호: {INMATE} / 상담일: {M}월 {D}일",
    "{INMATE} {PER} 수용자 의무과 진료 요청.",
    "접견번호 {VISIT} — 수용자 {PER}, 접견인 {PER2} ({REL})",
    "사건번호 {CASE} 피고인 {PER}에 대한 출정 일정 통보",
    "수번 {INMATE}번 {PER|은/는} 오늘 작업장에 출역함.",
    "상담자: {STAFF} {PER} / 내담자: {PER2} (수번 {INMATE})",
    "{PER} 수용자의 주소지는 {ADDR|이며/며} 보호자는 {PER2}이다.",
    "주민등록번호 {RRN} 수용자 {PER} 신상 변경 신청",
    "접견 예약 {VISIT} / 대상 {PER} / 연락처 {PHONE}",
    "{PER} ({INMATE}) 가석방 심사 대상",
    "수용자: 교도관님 {PER} 접견 신청했는데요 수번 {INMATE|이에요/예요}.",
    "{CASE} 사건 관련 {PER} 수용자 {FAC} 이송 결정",
    "출정: {PER} / 사건 {CASE} / {N}층 {N}0{N}호 법정",
    "수용자: {F}그게 {PER_SPLIT|이/가} 아니고 다른 사람이야.",
    "접견인: 엄마 새 번호야 {PHONE} 저장해 둬.",
    "{REL} {PER|과/와} 전화 접견함. 특이사항 없음.",
    "{REL} {GIVEN|이/가} 오늘 다녀감.",
    "수용자 {PER}의 접견번호는 {VISIT|이다/다}.",
    "이송 대상자 명단: {PER} {INMATE}, {PER2} {INMATE2}",
    "수용자: {F}거기 {ADDR} 맞아 거기로 보내.",
    "접견인: 변호사가 그러는데 {CASE} 항소한대.",
    "{PER} 수용자 심리 상담 연장 요청.",
    "{STAFF} {PER} 확인. 수번 {INMATE} 귀소 완료.",
    "보호자 {PER} 연락처 {PHONE} 주소 {ADDR}",
    "수용자: {F}{GIVEN|이/}한테 {RRN_NH} 이거 내 주민번호라고 전해 줘.",
    "가족 접견 신청서 — 신청인 {PER} ({REL}) / 수용자 {PER2} / 수번 {INMATE}",
    "{PER} 수용자 {CASE} 판결 확정으로 기결 전환.",
    "접견인: {F}{PER} 형사님이 집으로 찾아왔었어 {PHONE_KO} 이 번호 남기고 갔어.",
]
NEG_B = [
    "수용자: {F}응 알았어 엄마 건강 잘 챙기고.",
    "접견인: 날씨가 추워졌으니까 옷 따뜻하게 입어.",
    "생활관 순찰 결과 특이사항 없음. 21시 취침 점호 완료.",
    "오전 {H}시 의무과 진료 후 오후 {H}시 귀소.",
    "영치품 {N}점 반입 허가.",
    "수용자: 여기 밥은 먹을 만해 걱정하지 마.",
    "다음 주 월요일 교화 프로그램 참석 예정.",
    "접견인: 변호사님이 다음 주에 오신대.",
    "수용자: 어머님 건강은 좀 어떠세요?",
    "교도관님 감사합니다.",
    "작업장 출역 인원 {N}2명, 결원 {N}명.",
    "접견인: 바다 보러 가자고 했던 거 기억나? 나오면 꼭 가자.",
    "수용자: 보람 있게 지내려고 책 많이 읽고 있어.",
]
SLOT = re.compile(r"\{([A-Z_]+?)(\d?)(?:\|([^}]*))?\}")


def fill(template, gen):
    out, spans, last = "", [], 0
    for m in SLOT.finditer(template):
        out += template[last:m.start()]
        surface, typ = gen.value(m.group(1))
        st = len(out)
        out += surface
        if typ:
            spans.append((st, len(out), typ))
        if m.group(3):
            out += josa(surface, m.group(3))
        last = m.end()
    out += template[last:]
    if "  " in out:
        raise ValueError("템플릿에 공백 둘: %r" % template)
    return out, spans


def sealed_sets():
    """시험셋에서 학습에 쓰면 안 되는 값을 모은다 — 이름 전체 · 이름 부분 · 번호 · 도로명."""
    full, given, values, roads = set(), set(), set(), set()
    for track in "AB":
        for ex in test_set(track):
            for s, e, t in ex["spans"]:
                v = ex["text"][s:e].replace(" ", "")
                values.add(v)
                if t == "PER":
                    full.add(v)
                    given.add(v)
                    if len(v) >= 2:
                        given.add(v[1:])
                    for cs in COMPOUND_SURNAMES:
                        if v.startswith(cs) and len(v) > 2:
                            given.add(v[2:])
                if t == "LOC":
                    roads.update(w for w in ex["text"][s:e].split() if w.endswith(("로", "길")))
    return {"PER_FULL": full, "GIVEN": given, "VALUES": values, "ROADS": roads}


def train_set(tracks, per_track, seed):
    rng = random.Random(seed)
    gen = Gen(rng, sealed_sets())
    out = []
    for track in tracks:
        pos, neg = (TRAIN_A, NEG_A) if track == "A" else (TRAIN_B, NEG_B)
        for i in range(per_track):
            tpl = rng.choice(neg) if rng.random() < 0.15 else rng.choice(pos)
            text, spans = fill(tpl, gen)
            out.append(make_example("T%s%04d" % (track, i + 1), track, "train", text, spans))
    rng.shuffle(out)
    return out


def leakage_report(train, tests):
    """봉인 확인 — 시험 개체 표면형이 학습셋에 나오면 안 된다(이름은 0 이어야 정상)."""
    train_text = {ex["text"] for ex in train}
    train_vals = {}
    for ex in train:
        for s, e, t in ex["spans"]:
            train_vals.setdefault(t, set()).add(ex["text"][s:e].replace(" ", ""))
    res = {"same_sentence": 0, "PER": [], "NUM": [], "LOC": []}
    for ex in tests:
        if ex["text"] in train_text:
            res["same_sentence"] += 1
        for s, e, t in ex["spans"]:
            v = ex["text"][s:e].replace(" ", "")
            if v in train_vals.get(t, ()):
                res[t].append(v)
    return res


def to_hf(examples):
    """Hugging Face Token Classification 표준 형식(tokens · ner_tags + ClassLabel)."""
    from datasets import ClassLabel, Dataset, Features, Sequence, Value
    feats = Features({"id": Value("string"), "track": Value("string"),
                      "tokens": Sequence(Value("string")), "ner_tags": Sequence(ClassLabel(names=LABELS))})
    return Dataset.from_list([{k: ex[k] for k in ("id", "track", "tokens", "ner_tags")} for ex in examples],
                             features=feats)


def write_jsonl(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


# ---------------------------------------------------------------- 라벨 맞춤

EXT_TYPE = {"PER": "PER", "PS": "PER", "PERSON": "PER", "LOC": "LOC", "LC": "LOC", "LOCATION": "LOC",
            "NUM": "NUM", "QT": "NUM"}


def normalize_external(label):
    """외부 모델 라벨 → 우리 BIO. 'PER-B'(Naver) · 'B-PS'(KLUE) 모두. 범위 밖 유형은 O."""
    if not label or label == "O":
        return "O"
    parts = re.split(r"[-_]", label)
    if parts[0] in ("B", "I"):
        pre, typ = parts[0], parts[-1]
    elif parts[-1] in ("B", "I"):
        pre, typ = parts[-1], parts[0]
    else:
        return "O"
    t = EXT_TYPE.get(typ.upper())
    return "%s-%s" % (pre, t) if t else "O"


def fix_bio(tags):
    """예측 정리 — 모든 모델(AI-R 포함)에 똑같이 적용한다.
    ① I-X 가 O 나 다른 유형 뒤에 오면 B-X 로.
    ② 붙어 있는 주소 조각(LOC 바로 뒤의 B-LOC)은 한 주소로. AI-R 은 주소를 6~7조각으로,
       Naver NER 은 지명마다 따로 단다 — 관례 차이지 못 찾은 게 아니다. 건물번호를 NUM 으로 다는
       관례는 보정하지 않는다(그건 strict 에서 틀리고, 남은 개인정보에서는 가린 것으로 센다)."""
    out, prev = [], "O"
    for t in tags:
        if t.startswith("I-") and (prev == "O" or prev[2:] != t[2:]):
            t = "B-" + t[2:]
        if t == "B-LOC" and prev.endswith("LOC"):
            t = "I-LOC"
        out.append(t)
        prev = t
    return out


# ---------------------------------------------------------------- 트랜스포머 추론 · 학습

def load_token_model(name_or_path):
    from transformers import AutoModelForTokenClassification, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(name_or_path)
    model = AutoModelForTokenClassification.from_pretrained(name_or_path)
    model.eval()
    return tok, model


def predict(tok, model, examples, normalize, max_len, batch_size):
    """어절별 첫 서브토큰의 예측을 그 어절의 라벨로 쓴다(학습과 같은 규칙)."""
    import torch
    preds, truncated = [], 0
    t0 = time.time()
    for i in range(0, len(examples), batch_size):
        batch = examples[i:i + batch_size]
        enc = tok([ex["tokens"] for ex in batch], is_split_into_words=True, truncation=True,
                  max_length=max_len, padding=True, return_tensors="pt")
        inputs = {k: v for k, v in enc.items() if k in ("input_ids", "attention_mask", "token_type_ids")}
        with torch.no_grad():
            ids = model(**inputs).logits.argmax(-1).tolist()
        for b, ex in enumerate(batch):
            tags, seen = ["O"] * len(ex["tokens"]), set()
            for j, w in enumerate(enc.word_ids(b)):
                if w is None or w in seen:
                    continue
                seen.add(w)
                tags[w] = normalize(model.config.id2label[ids[b][j]])
            if len(seen) < len(ex["tokens"]):
                truncated += 1
            preds.append(fix_bio(tags))
    return preds, truncated, time.time() - t0


def _encode_train(tok, ds, max_len):
    """HF 표준 데이터셋(tokens · ner_tags) → 서브토큰 입력. 어절의 첫 서브토큰만 라벨, 나머지는 -100."""
    def enc_batch(b):
        enc = tok(b["tokens"], is_split_into_words=True, truncation=True, max_length=max_len)
        labels = []
        for i, tags in enumerate(b["ner_tags"]):
            lab, prev = [], None
            for w in enc.word_ids(i):
                lab.append(-100 if w is None or w == prev else tags[w])
                prev = w
            labels.append(lab)
        enc["labels"] = labels
        return enc
    return ds.map(enc_batch, batched=True, remove_columns=ds.column_names)


def _training_args(**kw):
    """버전마다 이름이 바뀐 인자가 있어 지금 설치된 TrainingArguments 가 받는 것만 넘긴다."""
    from transformers import TrainingArguments
    accepted = inspect.signature(TrainingArguments.__init__).parameters
    return TrainingArguments(**{k: v for k, v in kw.items() if k in accepted})


def finetune(init, train, out_dir, epochs, lr, batch, max_len, seed):
    import torch
    from transformers import (AutoModelForTokenClassification, AutoTokenizer, DataCollatorForTokenClassification,
                              Trainer, set_seed)
    set_seed(seed)
    tok = AutoTokenizer.from_pretrained(init)
    model = AutoModelForTokenClassification.from_pretrained(
        init, num_labels=len(LABELS), id2label=dict(enumerate(LABELS)), label2id=L2I,
        ignore_mismatched_sizes=True)          # 제로샷 NER 체크포인트에서 시작해도 머리만 새로
    ds = _encode_train(tok, to_hf(train), max_len)
    steps = max(1, -(-len(ds) // batch)) * epochs
    args = _training_args(
        output_dir=os.path.join(out_dir, "_trainer"), num_train_epochs=epochs, learning_rate=lr,
        per_device_train_batch_size=batch, weight_decay=0.01, warmup_steps=int(steps * 0.1),
        lr_scheduler_type="linear", logging_steps=max(1, steps // 10), save_strategy="no",
        report_to="none", seed=seed, data_seed=seed, dataloader_num_workers=0,
        use_cpu=not torch.cuda.is_available(), disable_tqdm=False)
    trainer = Trainer(model=model, args=args, train_dataset=ds, data_collator=DataCollatorForTokenClassification(tok))
    t0 = time.time()
    result = trainer.train()
    secs = time.time() - t0
    trainer.save_model(out_dir)
    tok.save_pretrained(out_dir)
    return {"seconds": round(secs, 1), "steps": steps, "train_loss": round(float(result.training_loss), 4)}


# ---------------------------------------------------------------- AI-R 주입

def load_air(path):
    """AI-R 결과 파일 → (_meta, {문장 id: 응답}). JSON 객체(id 키) 또는 JSONL(줄마다 id) 둘 다."""
    raw = open(path, encoding="utf-8").read().strip()
    meta, table = {}, {}
    try:
        whole = json.loads(raw)
    except json.JSONDecodeError:
        whole = None
    if isinstance(whole, dict):
        meta = whole.pop("_meta", {}) or {}
        table = whole
    else:
        for line in raw.splitlines():
            if not line.strip():
                continue
            o = json.loads(line)
            if "_meta" in o:
                meta = o["_meta"]
            elif len(o) == 1 and not {"id", "ner_tags", "tags"} & set(o):
                table.update(o)              # 한 줄짜리 {id: 응답} 객체
            else:
                table[o["id"]] = o
    return meta, table


def air_entities(payload):
    """응답 모양이 무엇이든 (종류, 라벨, start, end, word) 목록으로.
    labelResult/patternResult 가 맨 위 · detectResult 아래 · 중첩 배열 어디에 있어도 찾는다."""
    out = []

    def walk(v, kind):
        if isinstance(v, dict):
            if "start" in v and "end" in v and ({"entity", "pattern", "label"} & set(v)):
                if v.get("except"):
                    return                    # 예외 단어(마스킹 제외) — 개체 아님
                is_pattern = "pattern" in v and "entity" not in v and "label" not in v
                lab = v.get("entity") or v.get("label") or v.get("pattern")
                out.append(("PATTERN" if is_pattern else kind, str(lab), int(v["start"]), int(v["end"]),
                            v.get("word")))
                return
            for k, x in v.items():
                if k == "labelResult":
                    walk(x, "LABEL")
                elif k == "patternResult":
                    walk(x, "PATTERN")
                elif k in ("entities", "detectResult", "result", "data", "response"):
                    walk(x, kind)
        elif isinstance(v, list):
            for x in v:
                walk(x, kind)

    walk(payload, "LABEL")
    return out


def air_type(kind, label):
    if kind == "PATTERN":
        return "NUM"                          # 정규식 층(전화 · 주민 · 계좌 …)
    u = label.upper()
    if u in TYPES:
        return u
    if u.startswith("SS_NAME") or u == "NAME":
        return "PER"
    if u.startswith("AD_") and not u.endswith("BRAND"):
        return "LOC"
    return None                               # ORG_* · *_BRAND 등 — 이번 범위 밖(O)


def merge_loc(text, spans):
    """붙어 있는(사이가 공백뿐인) 주소 조각을 하나로 — AI-R 주소 쪼개기 보정."""
    spans = sorted(set(spans))
    out = []
    for s, e, t in spans:
        if out and t == "LOC" and out[-1][2] == "LOC" and (s <= out[-1][1] or not text[out[-1][1]:s].strip()):
            out[-1] = (out[-1][0], max(out[-1][1], e), "LOC")
        elif out and t == out[-1][2] and s < out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], e), t)   # 같은 자리를 패턴 id 별로 거듭 준 것
        else:
            out.append((s, e, t))
    return out


def air_predict(examples, table, mode):
    """mode: 'label'(딥러닝 라벨만 — 순수 DL 비교) · 'full'(정규식 포함 — 제품 전체)."""
    preds, covered, stats = [], [], {"mismatch": 0, "out_of_scope": 0, "entities": 0}
    for ex in examples:
        payload = table.get(ex["id"])
        if payload is None:
            continue
        covered.append(ex["id"])
        if isinstance(payload, dict) and ("ner_tags" in payload or "tags" in payload):
            tags = payload.get("tags") or payload.get("ner_tags")
            tags = [LABELS[t] if isinstance(t, int) else t for t in tags]
            if len(tags) != len(ex["tokens"]):
                raise ValueError("%s: 어절 수가 다르다(%d ≠ %d)" % (ex["id"], len(tags), len(ex["tokens"])))
            preds.append(fix_bio(tags))
            continue
        spans, pattern_spans = [], []
        for kind, label, s, e, word in air_entities(payload):
            if mode == "label" and kind == "PATTERN":
                continue
            t = air_type(kind, label)
            if t is None:
                stats["out_of_scope"] += 1
                continue
            if not (0 <= s < e <= len(ex["text"])) or (word is not None and ex["text"][s:e] != word):
                stats["mismatch"] += 1        # 좌표가 원문과 어긋남 — 조용히 받지 않는다
                continue
            stats["entities"] += 1
            (pattern_spans if kind == "PATTERN" else spans).append((s, e, t))
        # 정규식 층이 잡은 자리는 정규식 유형으로 — AI-R 이 전화를 정규식(NUM)과 딥러닝(AD_*)으로
        # 동시에 단 일이 있다(A12). 겹친 딥러닝 구간을 버린다. AI-R 에 유리한 쪽의 보정이다.
        spans = [x for x in spans if not any(x[0] < pe and x[1] > ps for ps, pe, _ in pattern_spans)]
        spans += pattern_spans
        _, tags = spans_to_tags(ex["text"], merge_loc(ex["text"], spans), strict=False)
        preds.append(fix_bio(tags))
    return preds, covered, stats


def air_has_patterns(table):
    return any(k == "PATTERN" for p in table.values() for k, *_ in air_entities(p))


# ---------------------------------------------------------------- 평가

def entity_report(gold, pred):
    from seqeval.metrics import classification_report
    from seqeval.scheme import IOB2
    rep = classification_report(gold, pred, mode="strict", scheme=IOB2, output_dict=True, zero_division=0)
    micro = rep.get("micro avg", {"precision": 0, "recall": 0, "f1-score": 0, "support": 0})
    per = {t: rep.get(t, {"precision": 0.0, "recall": 0.0, "f1-score": 0.0, "support": 0}) for t in TYPES}
    return micro, per


def f1_strict(gold, pred):
    from seqeval.metrics import f1_score
    from seqeval.scheme import IOB2
    return f1_score(gold, pred, mode="strict", scheme=IOB2, zero_division=0)


def masking_stats(gold, pred):
    """비식별 관점 — 유형은 따지지 않는다. 개체의 한 어절이라도 O 로 남으면 '샌 것'."""
    from seqeval.metrics.sequence_labeling import get_entities
    leaked, total, pii, hit, o_tok, over = 0, 0, 0, 0, 0, 0
    leaked_items = []
    for g, p in zip(gold, pred):
        for _, s, e in get_entities(g):
            total += 1
            if any(p[i] == "O" for i in range(s, e + 1)):
                leaked += 1
                leaked_items.append((s, e))
        for gt, pt in zip(g, p):
            if gt != "O":
                pii += 1
                hit += pt != "O"
            else:
                o_tok += 1
                over += pt != "O"
    return {"leaked": leaked, "total": total, "token_recall": hit / pii if pii else 0.0,
            "over_rate": over / o_tok if o_tok else 0.0, "over": over}


def bootstrap(gold, preds_by_model, n, seed):
    """문장을 복원 추출해 F1 95% 구간 · 모델 쌍의 차이 구간(같은 표본으로 — 대응 부트스트랩)."""
    rng = random.Random(seed)
    k = len(gold)
    samples = {m: [] for m in preds_by_model}
    for _ in range(n):
        idx = [rng.randrange(k) for _ in range(k)]
        g = [gold[i] for i in idx]
        for m, p in preds_by_model.items():
            samples[m].append(f1_strict(g, [p[i] for i in idx]))
    return samples


def ci(vals):
    v = sorted(vals)
    return v[int(0.025 * len(v))], v[max(0, int(0.975 * len(v)) - 1)]


def error_samples(examples, gold, pred, limit):
    from seqeval.metrics.sequence_labeling import get_entities
    miss, fp = [], []
    for ex, g, p in zip(examples, gold, pred):
        ge = {(t, s, e) for t, s, e in get_entities(g)}
        pe = {(t, s, e) for t, s, e in get_entities(p)}
        for t, s, e in sorted(ge - pe, key=lambda x: x[1]):
            got = sorted({p[i] for i in range(s, e + 1)})
            miss.append("%s `%s` %s → %s" % (ex["id"], " ".join(ex["tokens"][s:e + 1]), t, "/".join(got)))
        for t, s, e in sorted(pe - ge, key=lambda x: x[1]):
            fp.append("%s `%s` → %s" % (ex["id"], " ".join(ex["tokens"][s:e + 1]), t))
    return miss[:limit], fp[:limit], len(miss), len(fp)


NUM_KINDS = ("전화", "주민", "계좌", "교정 식별번호")
KO_SPOKEN = set("공영일이삼사오육칠팔구 ")


def num_kind(surface):
    """정답 번호의 갈래 — AI-R 프로파일(정규식)이 다루는 번호인가, 교정 식별번호인가."""
    s = surface.strip()
    if set(s) <= KO_SPOKEN:
        return "전화"                                    # 말로 부른 번호
    if re.fullmatch(r"0\d{1,2}[- ]\d{3,4}[- ]\d{4}", s):
        return "전화"
    if re.fullmatch(r"\d{6}-?[1-4]\d{6}", s):
        return "주민"
    if re.fullmatch(r"\d{2,6}-\d{2,6}-\d{2,6}", s):
        return "계좌"
    return "교정 식별번호"                                # 수용번호 · 접견번호 · 사건번호


def num_entities(ex):
    """정답 번호 → [(갈래, 시작 어절, 끝 어절)]."""
    words = word_offsets(ex["text"])
    out = []
    for s, e, t in ex["spans"]:
        if t == "NUM":
            idx = [i for i, (_, ws, we) in enumerate(words) if ws < e and we > s]
            out.append((num_kind(ex["text"][s:e]), idx[0], idx[-1]))
    return out


def without_domain_ids(tag_rows, examples):
    """교정 식별번호 어절을 정답 · 예측 모두에서 O 로 — AI-R 프로파일이 다루는 범위만 남긴다.
    프로파일에 없는 유형까지 세면 'AI-R 이 못 잡는다' 가 아니라 '설정에 없다' 를 재는 셈이다(3계층 비교 §2.1)."""
    out = []
    for tags, ex in zip(tag_rows, examples):
        drop = {i for k, s, e in num_entities(ex) if k == "교정 식별번호" for i in range(s, e + 1)}
        out.append(fix_bio(["O" if i in drop else t for i, t in enumerate(tags)]))
    return out


def num_breakdown(examples, pred):
    """갈래별 [정확히 찾음, 가림(유형 무관), 전체]."""
    from seqeval.metrics.sequence_labeling import get_entities
    res = {k: [0, 0, 0] for k in NUM_KINDS}
    for ex, p in zip(examples, pred):
        found = set(get_entities(p))
        for k, s, e in num_entities(ex):
            res[k][2] += 1
            res[k][0] += ("NUM", s, e) in found
            res[k][1] += all(p[i] != "O" for i in range(s, e + 1))
    return res


# ---------------------------------------------------------------- 리포트

def pct(x):
    return "%.1f" % (100 * x)


def build_report(ctx):
    L = []
    w = L.append
    w("# 한국어 PII NER 벤치마크 — AI-R · 제로샷 · 도메인 튜닝")
    w("")
    w("생성 %s · 시드 %d · `%s`" % (ctx["when"], ctx["seed"], ctx["cmd"]))
    w("")
    w("| 항목 | 값 |")
    w("|---|---|")
    for k, v in ctx["setup"]:
        w("| %s | %s |" % (k, v))
    w("")
    lk = ctx["leakage"]
    w("**봉인 확인** — 시험 문장이 학습셋에 그대로 나온 수 %d · 시험 개체가 학습셋에 나온 수 이름 %d · 번호 %d · 주소 %d"
      % (lk["same_sentence"], len(lk["PER"]), len(lk["NUM"]), len(lk["LOC"])))
    w("")
    models = ctx["models"]

    w("## 1. 3자 비교 매트릭스 — 개체 단위 strict F1 (seqeval)")
    w("")
    w("| 모델 | Track A 범용 F1 [95% CI] | Track B 교정 F1 [95% CI] | B − A |")
    w("|---|---|---|---|")
    for m in models:
        cells = []
        f = {}
        for tr in "AB":
            r = m["tracks"].get(tr)
            if r is None:
                cells.append("—")
                continue
            lo, hi = r["ci"]
            f[tr] = r["micro"]["f1-score"]
            note = " (%d/%d문장)" % (r["n"], r["n_all"]) if r["n"] < r["n_all"] else ""
            cells.append("**%s** [%s–%s]%s" % (pct(f[tr]), pct(lo), pct(hi), note))
        diff = "%+.1f" % (100 * (f["B"] - f["A"])) if len(f) == 2 else "—"
        w("| %s | %s | %s | %s |" % (m["name"], cells[0], cells[1], diff))
    w("")
    w("> 20문장 세트라 구간이 넓다. 두 모델의 구간이 겹치면 그 차이는 이 세트만으로는 단정하지 않는다 — 아래 2절의 대응 부트스트랩을 본다.")
    w("")

    w("## 2. 개선 폭 — 같은 표본으로 잰 F1 차이 (대응 부트스트랩 %d회)" % ctx["n_boot"])
    w("")
    w("| 비교 | Track A ΔF1 [95% CI] | Track B ΔF1 [95% CI] |")
    w("|---|---|---|")
    for label, a, b in ctx["deltas"]:
        w("| %s | %s | %s |" % (label, a, b))
    w("")
    w("> 구간이 0 을 넘지 않으면(양쪽 다 +) 이 세트에서 개선이 확인된 것이다. '도메인 적응(튜닝)에 따른 개선 폭' 은 **도메인 튜닝 − 제로샷** 줄이다.")
    w("")

    w("## 2-B. 공통 범위 — 교정 식별번호(수용 · 접견 · 사건번호) 제외")
    w("")
    w("AI-R 프로파일에는 교정 식별번호 패턴이 없다. 그 어절을 정답 · 예측 모두에서 빼고 이름 · 주소 · 전화 · 주민 · 계좌만 잰다 — "
      "'설정에 없다' 를 '못 잡는다' 로 세지 않기 위해서다. 위 1절은 요구사항 그대로(교정 식별번호 포함)의 숫자다.")
    w("")
    w("| 모델 | Track A F1 [95% CI] | Track B F1 [95% CI] |")
    w("|---|---|---|")
    for m in models:
        cells = []
        for tr in "AB":
            r = m["tracks"].get(tr)
            if r is None:
                cells.append("—")
            else:
                lo, hi = r["scope_ci"]
                cells.append("**%s** [%s–%s]" % (pct(r["scope_micro"]["f1-score"]), pct(lo), pct(hi)))
        w("| %s | %s | %s |" % (m["name"], cells[0], cells[1]))
    if ctx["scope_deltas"]:
        w("")
        w("| 비교(공통 범위) | Track A ΔF1 [95% CI] | Track B ΔF1 [95% CI] |")
        w("|---|---|---|")
        for label, a, b in ctx["scope_deltas"]:
            w("| %s | %s | %s |" % (label, a, b))
    w("")

    for tr, title in (("A", "Track A 범용"), ("B", "Track B 교정")):
        w("## 3-%s. %s — 정밀도 · 재현율 · 비식별 관점" % (tr, title))
        w("")
        w("| 모델 | P | R | F1 | 이름 F1 | 번호 F1 | 주소 F1 | 남은 개인정보 | 어절 가림률 | 과탐률 |")
        w("|---|---|---|---|---|---|---|---|---|---|")
        for m in models:
            r = m["tracks"].get(tr)
            if r is None:
                w("| %s | — | — | — | — | — | — | — | — | — |" % m["name"])
                continue
            mi, per, ms = r["micro"], r["per"], r["mask"]
            w("| %s | %s | %s | **%s** | %s | %s | %s | %d/%d | %s%% | %s%% |" % (
                m["name"], pct(mi["precision"]), pct(mi["recall"]), pct(mi["f1-score"]),
                pct(per["PER"]["f1-score"]), pct(per["NUM"]["f1-score"]), pct(per["LOC"]["f1-score"]),
                ms["leaked"], ms["total"], pct(ms["token_recall"]), pct(ms["over_rate"])))
        sup = ctx["support"][tr]
        w("")
        w("정답 개체 %d(이름 %d · 번호 %d · 주소 %d) · 문장 %d. **남은 개인정보** = 한 어절이라도 가려지지 않은 개체 수(유형 무관). "
          "**과탐률** = 정답이 O 인 어절 중 개체로 잡은 비율." % (sum(sup.values()), sup["PER"], sup["NUM"], sup["LOC"], ctx["n_test"][tr]))
        w("")

    w("## 3-C. 번호 세부 — 정확히 찾음 · 가림(유형 무관) / 전체")
    w("")
    w("| 모델 | 트랙 | 전화 | 주민 | 계좌 | 교정 식별번호 |")
    w("|---|---|---|---|---|---|")
    for m in models:
        for tr in "AB":
            r = m["tracks"].get(tr)
            if r is None:
                continue
            cells = ["%d · %d / %d" % tuple(r["num"][k]) if r["num"][k][2] else "—" for k in NUM_KINDS]
            w("| %s | %s | %s |" % (m["name"], tr, " | ".join(cells)))
    w("")
    w("전화에는 말로 부른 번호(`공일공 …`)와 띄어 쓴 번호가, 교정 식별번호에는 수용번호 · 접견번호 · 사건번호가 들어 있다.")
    w("")

    w("## 4. 속도 (CPU)")
    w("")
    n_all = sum(ctx["n_test"].values())
    w("| 모델 | 시험 %d문장 추론 | 문장/초 | 비고 |" % n_all)
    w("|---|---|---|---|")
    for m in models:
        if m.get("secs") is not None:
            w("| %s | %.2f초 | %.1f | %s |" % (m["name"], m["secs"], n_all / m["secs"] if m["secs"] else 0, m.get("speed_note", "")))
    w("")

    w("## 5. 틀린 자리 (모델별 · 트랙별 앞 %d개)" % ctx["err_limit"])
    w("")
    for m in models:
        for tr in "AB":
            r = m["tracks"].get(tr)
            if r is None:
                continue
            miss, fp, nm, nf = r["errors"]
            w("<details><summary>%s · Track %s — 놓침 %d · 과탐 %d</summary>" % (m["name"], tr, nm, nf))
            w("")
            for x in miss:
                w("- 놓침 " + x)
            for x in fp:
                w("- 과탐 " + x)
            w("")
            w("</details>")
            w("")

    w("## 6. 읽을 때 함께 볼 것")
    w("")
    for c in ctx["caveats"]:
        w("- " + c)
    w("")
    return "\n".join(L)


# ---------------------------------------------------------------- 명령

def cmd_gen(args):
    tests = test_set("A") + test_set("B")
    train = train_set(args.train_tracks, args.train_per_track, args.seed)
    d = os.path.join(args.out, "data")
    write_jsonl(os.path.join(d, "track_a_test.jsonl"), [x for x in tests if x["track"] == "A"])
    write_jsonl(os.path.join(d, "track_b_test.jsonl"), [x for x in tests if x["track"] == "B"])
    write_jsonl(os.path.join(d, "train_%s.jsonl" % args.train_tracks), train)
    from datasets import DatasetDict
    DatasetDict({"train": to_hf(train), "test_track_a": to_hf([x for x in tests if x["track"] == "A"]),
                 "test_track_b": to_hf([x for x in tests if x["track"] == "B"])}).save_to_disk(os.path.join(d, "hf"))
    lk = leakage_report(train, tests)
    log("데이터셋 — 시험 A %d · B %d · 학습(%s) %d → %s" % (
        sum(x["track"] == "A" for x in tests), sum(x["track"] == "B" for x in tests), args.train_tracks, len(train), d))
    log("봉인 확인 — 같은 문장 %d · 이름 %d · 번호 %d · 주소 %d" % (
        lk["same_sentence"], len(lk["PER"]), len(lk["NUM"]), len(lk["LOC"])))
    return tests, train, lk


def cmd_export_air(args):
    rows = [{"id": x["id"], "track": x["track"], "text": x["text"]} for x in test_set("A") + test_set("B")]
    p = os.path.join(args.out, "air_input.jsonl")
    write_jsonl(p, rows)
    log("AI-R 입력 %d문장 → %s (text 를 한 글자도 바꾸지 말고 보낼 것 — start/end 가 이 문자열 기준이다)" % (len(rows), p))


def cmd_air_oracle(args):
    """정답을 AI-R 응답 모양으로 — 이름은 SS_NAME, 주소는 어절마다 쪼갠 AD_* 조각, 번호는 patternResult.
    full 모드에서 100 이 나와야 어댑터 · 주소 조각 합치기가 맞다(자가검증). 실제 AI-R 결과가 아니다."""
    out = {"_meta": {"mock": True, "kind": "oracle", "note": "정답을 AI-R 형식으로 바꾼 자가검증 파일 — 실제 AI-R 결과 아님"}}
    ad = ["AD_METRO", "AD_CITY", "AD_ADDRESS", "AD_DETAIL", "AD_BUILDING"]
    for ex in test_set("A") + test_set("B"):
        lab, pat = [], []
        for s, e, t in ex["spans"]:
            word = ex["text"][s:e]
            if t == "PER":
                lab.append({"except": False, "entity": "SS_NAME", "word": word, "masked": "*" * len(word), "start": s, "end": e})
            elif t == "LOC":
                for i, m in enumerate(re.finditer(r"\S+", word)):
                    lab.append({"except": False, "entity": ad[min(i, len(ad) - 1)], "word": m.group(),
                                "masked": "*" * len(m.group()), "start": s + m.start(), "end": s + m.end()})
            else:
                pat.append({"except": False, "pattern": "oracle", "word": word, "masked": "*" * len(word), "start": s, "end": e})
        out[ex["id"]] = {"labelResult": [lab], "patternResult": [pat]}
    p = os.path.join(args.out, "air_oracle.json")
    os.makedirs(args.out, exist_ok=True)
    json.dump(out, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    log("AI-R 형식 자가검증 파일 → %s" % p)


def cmd_air_fetch(args):
    """커넥터 /api/v1/deid/air/detect 로 시험 40문장을 보내 응답을 그대로 모은다.
    kubectl -n data-pipeline port-forward deploy/agent-connector-dp8qbi7xqh 18098:8080 이 떠 있어야 한다."""
    out = {"_meta": {"mock": False, "source": args.endpoint, "label_option": args.label_option,
                     "pattern_option": args.pattern_option, "fetched": datetime.now().isoformat(timespec="seconds")}}
    for ex in test_set("A") + test_set("B"):
        body = {"text": ex["text"], "masking": True}
        if args.label_option:
            body["labelOptionCode"] = args.label_option
        if args.pattern_option:
            body["patternOptionCode"] = args.pattern_option
        req = urllib.request.Request(args.endpoint, data=json.dumps(body).encode("utf-8"),
                                     headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=180) as r:
            if r.status != 200:
                raise RuntimeError("%s: HTTP %d — 측정을 멈춘다(지난 결과를 새 숫자로 읽지 않게)" % (ex["id"], r.status))
            out[ex["id"]] = json.loads(r.read().decode("utf-8"))
        log("AI-R %s 받음" % ex["id"])
    p = os.path.join(args.out, "air_result%s.json" % ("_" + args.tag if args.tag else ""))
    os.makedirs(args.out, exist_ok=True)
    json.dump(out, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    log("AI-R 결과 → %s · 채점: python ner_benchmark.py run --air-json %s" % (p, p))


def cmd_run(args):
    import torch
    torch.set_num_threads(args.threads or os.cpu_count() or 1)
    tests, train, lk = cmd_gen(args)
    by_track = {tr: [x for x in tests if x["track"] == tr] for tr in "AB"}
    gold = {tr: [x["tags"] for x in by_track[tr]] for tr in "AB"}
    models = []

    # ② 제로샷
    log("제로샷 모델 적재 — %s" % args.zeroshot)
    tok, model = load_token_model(args.zeroshot)
    zs_labels = sorted({normalize_external(l) for l in model.config.id2label.values()} - {"O"})
    zs = {"name": "② 제로샷 `%s`" % args.zeroshot.split("/")[-1], "key": "zeroshot", "tracks": {}, "secs": 0.0}
    for tr in "AB":
        p, trunc, secs = predict(tok, model, by_track[tr], normalize_external, args.max_len, args.batch)
        zs["tracks"][tr] = {"pred": p, "trunc": trunc}
        zs["secs"] += secs
    log("제로샷 추론 끝 — %.2f초 · 맞춘 라벨 %s" % (zs["secs"], ", ".join(zs_labels)))
    del model

    # ③ 도메인 튜닝
    ft_dir = os.path.join(args.out, "ft_model_%s" % args.train_tracks)
    data_hash = hashlib.sha1(json.dumps([x["text"] for x in train], ensure_ascii=False).encode("utf-8")).hexdigest()[:12]
    conf = {"init": args.init, "epochs": args.epochs, "lr": args.lr, "batch": args.batch, "max_len": args.max_len,
            "seed": args.seed, "data": data_hash, "n_train": len(train)}
    meta_p = os.path.join(ft_dir, "bench_meta.json")
    old = json.load(open(meta_p, encoding="utf-8")) if os.path.exists(meta_p) else None
    if args.retrain or not old or old.get("conf") != conf:
        log("도메인 튜닝 시작 — 초기 %s · 학습 %d문장(%s) · %d 에포크 · lr %g · 배치 %d · 스레드 %d" % (
            args.init, len(train), args.train_tracks, args.epochs, args.lr, args.batch, torch.get_num_threads()))
        train_info = finetune(args.init, train, ft_dir, args.epochs, args.lr, args.batch, args.max_len, args.seed)
        json.dump({"conf": conf, "train": train_info}, open(meta_p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        log("도메인 튜닝 끝 — %.1f초 · %d 스텝 · 학습 손실 %.4f" % (train_info["seconds"], train_info["steps"], train_info["train_loss"]))
    else:
        train_info = old["train"]
        log("같은 설정으로 학습한 모델이 있어 다시 쓴다 — %s (새로 하려면 --retrain)" % ft_dir)
    tok, model = load_token_model(ft_dir)
    ft = {"name": "③ 도메인 튜닝 (%s · %s)" % (args.init.split("/")[-1], "B만" if args.train_tracks == "B" else args.train_tracks),
          "key": "finetuned", "tracks": {}, "secs": 0.0}
    for tr in "AB":
        p, trunc, secs = predict(tok, model, by_track[tr], lambda l: l, args.max_len, args.batch)
        ft["tracks"][tr] = {"pred": p, "trunc": trunc}
        ft["secs"] += secs
    log("도메인 튜닝 모델 추론 끝 — %.2f초" % ft["secs"])
    del model

    # ① AI-R
    air_rows, air_meta, air_stats = [], {}, {}
    if args.air_json:
        air_meta, table = load_air(args.air_json)
        mock = " · 모의" if air_meta.get("mock") else ""
        modes = [("label", "① AI-R 딥러닝 라벨만%s" % mock)]
        if air_has_patterns(table):
            modes.append(("full", "①′ AI-R 제품 전체(+정규식)%s" % mock))
        for mode, name in modes:
            row = {"name": name, "key": "air_" + mode, "tracks": {}, "secs": None}
            for tr in "AB":
                p, covered, st = air_predict(by_track[tr], table, mode)
                if covered:
                    row["tracks"][tr] = {"pred": p, "covered": covered}
                air_stats.setdefault(mode, {"mismatch": 0, "out_of_scope": 0, "entities": 0})
                for k in st:
                    air_stats[mode][k] += st[k]
            air_rows.append(row)
        log("AI-R 주입 — %s · 메타 %s · 개체 %s" % (args.air_json, air_meta or "없음", air_stats))
    else:
        air_rows.append({"name": "① AI-R (결과 주입 안 됨 — `--air-json`)", "key": "air_none", "tracks": {}, "secs": None})
    models = air_rows + [zs, ft]

    # 채점
    for m in models:
        for tr, r in list(m["tracks"].items()):
            exs, g = by_track[tr], gold[tr]
            if "covered" in r:
                keep = [i for i, x in enumerate(exs) if x["id"] in set(r["covered"])]
                exs, g = [exs[i] for i in keep], [g[i] for i in keep]
            r["n"], r["n_all"] = len(exs), len(by_track[tr])
            r["gold"], r["examples"] = g, exs
            r["micro"], r["per"] = entity_report(g, r["pred"])
            r["mask"] = masking_stats(g, r["pred"])
            r["errors"] = error_samples(exs, g, r["pred"], args.err_limit)
            r["num"] = num_breakdown(exs, r["pred"])
            r["scope_gold"], r["scope_pred"] = without_domain_ids(g, exs), without_domain_ids(r["pred"], exs)
            r["scope_micro"], _ = entity_report(r["scope_gold"], r["scope_pred"])

    # 부트스트랩 — 전체 문장을 덮는 모델끼리만 같은 표본으로
    deltas, samples_by_track = [], {}
    for tr in "AB":
        full = {m["key"]: m["tracks"][tr]["pred"] for m in models if tr in m["tracks"] and m["tracks"][tr]["n"] == m["tracks"][tr]["n_all"]}
        samples = samples_by_track[tr] = bootstrap(gold[tr], full, args.n_boot, args.seed)
        for m in models:
            if tr in m["tracks"]:
                r = m["tracks"][tr]
                r["ci"] = ci(samples[m["key"]]) if m["key"] in samples else ci(bootstrap(r["gold"], {"x": r["pred"]}, args.n_boot, args.seed)["x"])
    pairs = [("**도메인 튜닝 − 제로샷** (도메인 적응 개선 폭)", "finetuned", "zeroshot")]
    for m in air_rows:
        if m["tracks"]:
            pairs.append(("도메인 튜닝 − %s" % m["name"].split(" ", 1)[1], "finetuned", m["key"]))
            pairs.append(("제로샷 − %s" % m["name"].split(" ", 1)[1], "zeroshot", m["key"]))
    # 공통 범위(교정 식별번호 제외) — 같은 방식으로 구간 · 차이
    scope_samples = {}
    for tr in "AB":
        full = {m["key"]: m["tracks"][tr]["scope_pred"] for m in models
                if tr in m["tracks"] and m["tracks"][tr]["n"] == m["tracks"][tr]["n_all"]}
        scope_samples[tr] = bootstrap(without_domain_ids(gold[tr], by_track[tr]), full, args.n_boot, args.seed)
        for m in models:
            if tr in m["tracks"]:
                r = m["tracks"][tr]
                r["scope_ci"] = ci(scope_samples[tr][m["key"]]) if m["key"] in scope_samples[tr] else \
                    ci(bootstrap(r["scope_gold"], {"x": r["scope_pred"]}, args.n_boot, args.seed)["x"])

    def delta_cells(a, b, samples, metric):
        cells = []
        for tr in "AB":
            s = samples[tr]
            if a in s and b in s:
                d = [x - y for x, y in zip(s[a], s[b])]
                fa = next(m for m in models if m["key"] == a)["tracks"][tr][metric]["f1-score"]
                fb = next(m for m in models if m["key"] == b)["tracks"][tr][metric]["f1-score"]
                lo, hi = ci(d)
                cells.append("**%+.1f** [%+.1f, %+.1f]" % (100 * (fa - fb), 100 * lo, 100 * hi))
            else:
                cells.append("— (부분 커버리지)")
        return cells

    scope_deltas = []
    for label, a, b in pairs:
        deltas.append((label, *delta_cells(a, b, samples_by_track, "micro")))
        if b.startswith("air_"):
            scope_deltas.append((label, *delta_cells(a, b, scope_samples, "scope_micro")))

    sup = {}
    for tr in "AB":
        from seqeval.metrics.sequence_labeling import get_entities
        c = {t: 0 for t in TYPES}
        for g in gold[tr]:
            for t, _, _ in get_entities(g):
                c[t] += 1
        sup[tr] = c
    trunc = sum(r.get("trunc", 0) for m in (zs, ft) for r in m["tracks"].values())
    zs["speed_note"] = "몸통 koelectra-base(약 1.1억 파라미터)"
    ft["speed_note"] = "같은 몸통 · 같은 속도여야 정상"
    caveats = [
        "**합성 봉인 세트**다. 실제 접견 녹취 · 상담일지가 아니다. 실데이터는 오타 · 사투리 · 잡음이 더 많아 모든 모델의 숫자가 낮아질 수 있다.",
        "시험 이름 · 이름 부분은 학습에 한 번도 나오지 않았다(봉인 확인 표). 하지만 문장 **틀**은 같은 사람이 같은 도메인 감으로 썼다 — "
        "실데이터 첫 실행 전까지 도메인 튜닝 숫자는 '합성 분포 안의 일반화' 로 읽는다.",
        "제로샷 모델의 범위 밖 라벨(ORG · DAT · TIM 등)은 O 로 맞췄다. 제로샷 NUM 은 수량 전반(3명 · 10시)을 잡도록 학습돼 과탐이 생긴다 — 모델의 성질이다.",
        "AI-R 은 주입한 파일 그대로 채점한다. AI-R 숫자는 **프로파일에 따라 크게 달라진다** — 개발계 기본(스모크테스트용 정규식 3패턴)과 "
        "조직 전체 프로파일(정규식 15 · 딥러닝 전체)의 차이를 메타에 적어 둔다. `ORG_*` 로 단 사람 이름은 strict F1 에선 놓침, 남은 개인정보에선 막음이다.",
        "어절 단위 채점이다 — 조사가 붙은 어절 전체를 개체로 본다. 글자 단위 경계(조사 떼기)는 이 벤치마크가 재지 않는다.",
        "붙어 있는 주소 조각은 **모든 모델에서** 한 주소로 합쳐 채점한다(AI-R 의 주소 쪼개기 · Naver NER 의 지명별 라벨 보정). "
        "건물번호를 NUM 으로 다는 관례는 보정하지 않았다.",
        "시험셋을 본 뒤 학습셋 · 하이퍼파라미터를 고치면 이 숫자는 오염된다. 고치면 **새 봉인 세트**(새 id)를 만들어 첫 실행을 다시 남긴다.",
    ]
    if trunc:
        caveats.insert(0, "⚠ max_len %d 에서 잘린 문장 %d — 잘린 어절은 O 로 채점됐다. --max-len 을 올릴 것." % (args.max_len, trunc))
    if air_meta.get("mock"):
        caveats.insert(0, "⚠ **AI-R 줄은 모의 입력이다** (%s) — 실제 AI-R 성능이 아니다." % air_meta.get("note", air_meta.get("kind", "")))
    if air_stats:
        for mode, st in air_stats.items():
            caveats.append("AI-R %s — 받은 개체 %d · 범위 밖(ORG 등) %d · 좌표 어긋나 버림 %d." % (
                "라벨만" if mode == "label" else "제품 전체", st["entities"], st["out_of_scope"], st["mismatch"]))

    setup = [
        ("시험셋(봉인)", "Track A 범용 %d문장 · Track B 교정 %d문장 — 손으로 쓴 고정 세트" % (len(by_track["A"]), len(by_track["B"]))),
        ("학습셋(생성)", "%d문장 · 트랙 %s · 시험과 다른 템플릿 · 이름 풀 · 시드 %d" % (len(train), args.train_tracks, args.seed)),
        ("라벨", "어절 단위 BIO · " + " ".join(LABELS)),
        ("② 제로샷", "`%s` 그대로 — 라벨 맞춤 %s" % (args.zeroshot, " ".join(zs_labels))),
        ("③ 도메인 튜닝", "초기 `%s` · %d 에포크 · lr %g · 배치 %d · max_len %d · 학습 %.0f초(%d 스텝) · 손실 %.4f" % (
            args.init, args.epochs, args.lr, args.batch, args.max_len, train_info["seconds"], train_info["steps"], train_info["train_loss"])),
        ("① AI-R", ("`%s` · %s" % (args.air_json, json.dumps(air_meta, ensure_ascii=False))) if args.air_json else "주입 안 됨"),
        ("채점", "seqeval strict(IOB2) 개체 F1 · 부트스트랩 %d회 · 비식별 관점(남은 개인정보 · 과탐률)" % args.n_boot),
        ("환경", "Python %s · torch %s · transformers %s · CPU 스레드 %d" % (
            sys.version.split()[0], torch.__version__, __import__("transformers").__version__, torch.get_num_threads())),
    ]
    ctx = {"when": datetime.now().strftime("%Y-%m-%d %H:%M"), "seed": args.seed,
           "cmd": "python ner_benchmark.py " + " ".join(sys.argv[1:]), "setup": setup, "leakage": lk,
           "models": models, "deltas": deltas, "scope_deltas": scope_deltas, "n_boot": args.n_boot, "support": sup,
           "n_test": {tr: len(by_track[tr]) for tr in "AB"}, "err_limit": args.err_limit, "caveats": caveats}
    report = build_report(ctx)
    os.makedirs(args.out, exist_ok=True)
    sfx = "_" + args.tag if args.tag else ""
    rp = os.path.join(args.out, "REPORT%s.md" % sfx)
    open(rp, "w", encoding="utf-8").write(report)
    raw = {m["key"]: {tr: {"micro": r["micro"], "per": r["per"], "mask": r["mask"], "ci": r["ci"], "n": r["n"],
                           "pred": r["pred"]} for tr, r in m["tracks"].items()} for m in models}
    json.dump({"setup": dict(setup), "leakage": lk, "results": raw}, open(os.path.join(args.out, "results%s.json" % sfx), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1, default=lambda o: o.item() if hasattr(o, "item") else str(o))
    print()
    print(report.split("## 3-A.")[0])
    log("리포트 → %s" % rp)


def main():
    ap = argparse.ArgumentParser(description="한국어 PII NER 벤치마크 — AI-R · 제로샷 · 도메인 튜닝")
    ap.add_argument("command", nargs="?", default="run", choices=["run", "gen", "export-air", "air-fetch", "air-oracle"])
    ap.add_argument("--out", default=os.path.join(HERE, "out"))
    ap.add_argument("--zeroshot", default="monologg/koelectra-base-v3-naver-ner")
    ap.add_argument("--init", default="monologg/koelectra-base-v3-discriminator",
                    help="도메인 튜닝 시작 가중치. klue/roberta-base 도 된다(제로샷도 같은 계열로 맞출 것)")
    ap.add_argument("--train-tracks", default="B", choices=["B", "AB", "A"], help="요구사항 기본은 B(교정)만")
    ap.add_argument("--train-per-track", type=int, default=600)
    ap.add_argument("--epochs", type=int, default=4)
    ap.add_argument("--lr", type=float, default=5e-5)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--max-len", type=int, default=128)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--threads", type=int, default=0, help="CPU 스레드(0 = 전부)")
    ap.add_argument("--n-boot", type=int, default=1000)
    ap.add_argument("--err-limit", type=int, default=12)
    ap.add_argument("--air-json", default=None, help="AI-R 결과 파일(JSON · JSONL)")
    ap.add_argument("--quick", action="store_true", help="학습 200문장/트랙 · 3 에포크")
    ap.add_argument("--retrain", action="store_true")
    ap.add_argument("--tag", default="", help="리포트 이름 꼬리표 — out/REPORT_<tag>.md · results_<tag>.json")
    ap.add_argument("--endpoint", default="http://localhost:18098/api/v1/deid/air/detect?server=dev")
    ap.add_argument("--label-option", default="", help="air-fetch: AI-R 딥러닝 프로파일 id(비우면 서버 기본)")
    ap.add_argument("--pattern-option", default="", help="air-fetch: AI-R 정규식 프로파일 id(비우면 서버 기본)")
    args = ap.parse_args()
    if args.quick:
        args.train_per_track, args.epochs = 200, 3
    {"run": cmd_run, "gen": cmd_gen, "export-air": cmd_export_air, "air-fetch": cmd_air_fetch,
     "air-oracle": cmd_air_oracle}[args.command](args)


if __name__ == "__main__":
    main()
