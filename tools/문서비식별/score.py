# 1단계 탐침 채점 v2
#
# v1 의 잘못 — 값을 '전체 마스킹 텍스트' 에서 찾았다. 그래서 한 군데만 안 가려져도
# 그 값이 들어간 모든 자리가 '남음' 으로 찍혔다(홍길동 5자리가 한꺼번에 X 로 보였다).
# 자리별 판정은 전체 검색으로 만들 수 없다. 그래서 두 가지로 바꾼다.
#   ① 값별 출현 횟수 — 원문 n회 중 마스킹 결과에 m회 남았는가 (m>0 이면 미가림)
#   ② 파트별 — detectedData[i] 와 maskedData[i] 를 같은 i 로 맞춰 본다
import io, json, os, sys

SP = sys.argv[1]
OUT = os.path.join(SP, "out")
ans = json.load(io.open(os.path.join(SP, "probe", "answers.json"), encoding="utf-8"))
PII = ans["pii"]

FORMATS = ["docx", "xlsx", "pptx", "hwpx", "pdf", "hwp"]
L = []
SUM = []


def parts(node, key):
    out = []
    for e in node.get(key) or []:
        out.append(str(e.get("txt", "")) if isinstance(e, dict) else str(e))
    return out


def ents(node):
    out = []
    for key, kind in (("labelResult", "LABEL"), ("patternResult", "PATTERN")):
        for doc in node.get(key) or []:
            if not isinstance(doc, list):
                continue
            for e in doc:
                if not isinstance(e, dict) or e.get("except"):
                    continue
                out.append((kind, e.get("entity") or e.get("pattern") or "", str(e.get("word", ""))))
    return out


for f in FORMATS:
    p = os.path.join(OUT, f + ".json")
    if not os.path.exists(p):
        continue
    d = json.load(io.open(p, encoding="utf-8"))
    st, ms = d.get("airStatus"), d.get("elapsedMs")
    b = d.get("body") or {}
    if st != 200:
        L.append("## %s — **AI-R HTTP %s** (%sms) · 지원 불가\n" % (f, st, ms))
        SUM.append((f, st, ms, 0, 0, 0, 0))
        continue

    dp, mp = parts(b, "detectedData"), parts(b, "maskedData")
    E = ents(b)
    ex_all, mk_all = "\n".join(dp), "\n".join(mp)

    rows, tot_n, tot_left = [], 0, 0
    for k, v in PII.items():
        n = ex_all.count(v)          # 추출 텍스트에 몇 번 살아 왔나
        m = mk_all.count(v)          # 마스킹 결과에 몇 번 평문으로 남았나
        hit = [t for kd, t, w in E if w == v]            # 값 그대로 잡힌 개체
        near = [t for kd, t, w in E if w != v and (v in w or w in v)]  # 걸쳐 잡힌 것
        tot_n += n
        tot_left += m
        rows.append((k, v, n, m, hit, near))

    L.append("## %s — AI-R 200 · %sms" % (f, ms))
    L.append("")
    L.append("- 추출 파트 %d개 · %d자 / 마스킹 파트 %d개 · %d자" % (len(dp), len(ex_all), len(mp), len(mk_all)))
    L.append("- 검출 개체 %d건 (LABEL %d · PATTERN %d)"
             % (len(E), sum(1 for e in E if e[0] == "LABEL"), sum(1 for e in E if e[0] == "PATTERN")))
    L.append("- **심은 PII %d회 중 %d회가 평문으로 남았다**" % (tot_n, tot_left))
    L.append("")
    L.append("| 종류 | 값 | 추출 | 남음 | 개체로 잡힘 | 걸쳐 잡힘 |")
    L.append("|---|---|---|---|---|---|")
    for k, v, n, m, hit, near in rows:
        L.append("| %s | `%s` | %d회 | %s | %s | %s |"
                 % (k, v, n, ("**%d회**" % m) if m else "0",
                    ",".join(sorted(set(hit))) or "**없음**",
                    ",".join(sorted(set(near))) or "-"))
    L.append("")

    # 파트별 — 어느 파트에서 남는가
    L.append("**파트별 잔존**")
    for i, mt in enumerate(mp):
        left = [v for v in PII.values() if v in mt]
        head = (dp[i][:40].replace("\n", " ⏎ ") + "…") if i < len(dp) else "(추출 파트 없음)"
        L.append("- 파트 %d (%d자) `%s` → %s" % (i, len(mt), head, ", ".join(left) or "남은 평문 없음"))
    L.append("")

    # 분절 관찰
    frag = []
    for probe, label in (("010-\n9876-5432", "셀 안 줄바꿈이 줄바꿈으로 남았다"),
                         ("010-9876-5432", "한 값으로 붙었다"),
                         ("010 1234 5678", "세 칸이 공백으로 이어졌다"),
                         ("010\t1234\t5678", "세 칸이 탭으로 이어졌다")):
        if probe in ex_all:
            frag.append("- `%s` → %s" % (probe.replace("\n", "⏎").replace("\t", "⇥"), label))
    L.append("**값 분절**")
    L.extend(frag or ["- (관찰한 꼴 없음)"])
    L.append("- 분절 조각 `9876-5432` 가 마스킹 결과에 남았는가: %s"
             % ("**예**" if "9876-5432" in mk_all else "아니오"))
    L.append("")

    # 무슨 패턴이 돌았나 — 개발계 정규식 3패턴을 확인
    pat = sorted(set(t for kd, t, w in E if kd == "PATTERN"))
    lab = sorted(set(t for kd, t, w in E if kd == "LABEL"))
    L.append("**실제로 불린 규칙** — PATTERN: %s / LABEL: %s\n" % (", ".join(pat) or "없음", ", ".join(lab) or "없음"))

    SUM.append((f, st, ms, len(dp), len(E), tot_n, tot_left))

head = ["# 1단계 탐침 결과 — AI-R 문서 비식별", "",
        "개발계 · 2026-10-18 · `POST /air/api/detect/system/file` · masking=true · OCR 미사용",
        "검출 세트 = 커넥터 개발계 기본값(라벨 `fac987ce…` · 패턴 `eff674bb…`)", "",
        "| 포맷 | AI-R | 시간 | 파트 | 검출개체 | 심은 PII | **평문 잔존** |", "|---|---|---|---|---|---|---|"]
for f, st, ms, np_, ne, tn, tl in SUM:
    if st != 200:
        head.append("| %s | **HTTP %s** | %sms | — | — | — | — |" % (f, st, ms))
    else:
        head.append("| %s | 200 | %sms | %d | %d | %d회 | **%d회** |" % (f, ms, np_, ne, tn, tl))
head.append("")

io.open(os.path.join(OUT, "REPORT.md"), "w", encoding="utf-8").write("\n".join(head + L) + "\n")
print("ok")
