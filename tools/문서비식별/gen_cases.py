# 3단계 시험 문서 생성기 — 표 유형 × 장수 × 포맷
#
# ──────────────────────────────────────────────────────────────────────────
# 설계에서 두 번 데이고 고친 것 (첫 판은 핵심 케이스를 아예 못 쟀다)
#
#   ① **본문용 PII 와 표용 PII 를 다른 값으로 쓴다.**
#      같은 값을 두 자리에 심으면 전체 텍스트 검색으로 자리를 구분할 수 없다.
#      "본문은 되는데 표에서 안 된다" 를 말하려면 값이 달라야 한다.
#
#   ② **쪼갠 케이스는 기대값을 따로 적는다.**
#      값분할·셀내줄바꿈은 일부러 값을 쪼개 넣으므로 문서에 완성된 값이 없다.
#      문서를 뒤져 정답을 만들면 정답이 0건이 되고, 가장 중요한 케이스를
#      측정에서 놓친다. 그래서 '문서에 넣은 꼴'(as_placed)과
#      '합쳐지면 이래야 하는 값'(expected)을 둘 다 기록한다.
#
#   ③ 주민번호는 **체크섬을 맞춘다**. 안 맞추면 영원히 미탐으로 보인다(1단계 §2.1).
# ──────────────────────────────────────────────────────────────────────────
import io, json, os, sys


def rrn_checksum(d12):
    w = [2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5]
    return str((11 - sum(int(c) * k for c, k in zip(d12, w)) % 11) % 10)


def rrn(front, mid):
    v = front + mid + rrn_checksum(front + mid)
    return v[:6] + "-" + v[6:]


# 본문(기준선)용 — 표와 겹치지 않는 값
BODY = {
    "name":  "홍길동",
    "phone": "010-1234-5678",
    "rrn":   rrn("900101", "123456"),
    "acct":  "110-234-567890",
    "addr":  "서울특별시 강남구 테헤란로 123",
    "org":   "서울남부교정시설",
}

# 표용 — 본문과 한 글자도 겹치지 않게 다른 값을 쓴다
TBL = {
    "name":  "최민수",
    "name2": "정수진",
    "phone": "010-5555-6666",
    "rrn":   rrn("880315", "246802"),
    "acct":  "302-9876-543210",
    "addr":  "부산광역시 해운대구 센텀중앙로 77",
    "org":   "부산교정시설",
}

BASELINE = ("수용자 {name} (전화 {phone}) 은 {org} 에 수용 중이며 주소는 {addr} 이다. "
            "주민등록번호는 {rrn} 이고 환불 계좌는 {acct} 이다.").format(**BODY)

BR = "⟦br⟧"      # 셀 안 줄바꿈
MG = "⟦mg→⟧"     # 오른쪽 칸과 가로 병합


def _split3(v, a, b):
    """값을 세 토막으로. (앞, 중간, 뒤) — 구분자는 버린다."""
    s = v.replace("-", "")
    return s[:a], s[a:a + b], s[a + b:]


# ---------------------------------------------------------------- 표 유형
#
# 각 유형 = (이름, 노리는 깨짐, 행렬, 기대 PII 목록)
#   기대 PII: {"kind","expected","as_placed","count"}
#     expected  = 합쳐지면 이래야 하는 값(채점 기준)
#     as_placed = 문서에 실제 넣은 꼴(사람이 읽기 위한 기록)

def case_simple():
    m = [["성명", TBL["name"]],
         ["연락처", TBL["phone"]],
         ["주민등록번호", TBL["rrn"]],
         ["계좌", TBL["acct"]],
         ["주소", TBL["addr"]],
         ["기관", TBL["org"]]]
    exp = [{"kind": k, "expected": TBL[k], "as_placed": TBL[k], "count": 1}
           for k in ("name", "phone", "rrn", "acct", "addr", "org")]
    return m, exp


def case_split():
    p = _split3(TBL["phone"], 3, 4)          # 010 / 5555 / 6666
    r = TBL["rrn"].split("-")                # 880315 / 246802x
    a = TBL["addr"].split(" ")               # 부산광역시 / 해운대구 / 센텀중앙로 77
    m = [["지역", "국번", "번호"],
         [p[0], p[1], p[2]],
         ["생년월일", "뒷자리", ""],
         [r[0], r[1], ""],
         ["시", "구", "상세"],
         [a[0], a[1], " ".join(a[2:])]]
    exp = [
        {"kind": "phone", "expected": TBL["phone"], "as_placed": " · ".join(p), "count": 1},
        {"kind": "rrn",   "expected": TBL["rrn"],   "as_placed": " · ".join(r), "count": 1},
        {"kind": "addr",  "expected": TBL["addr"],  "as_placed": " · ".join(a[:2] + [" ".join(a[2:])]), "count": 1},
    ]
    return m, exp


def case_wrap():
    ph = TBL["phone"]
    rr = TBL["rrn"]
    ad = TBL["addr"]
    ac = TBL["acct"]
    m = [["연락처", ph[:4] + BR + ph[4:]],
         ["주민등록번호", rr[:7] + BR + rr[7:]],
         ["주소", " ".join(ad.split(" ")[:2]) + BR + " ".join(ad.split(" ")[2:])],
         ["계좌", ac[:8] + BR + ac[8:]]]
    exp = [
        {"kind": "phone", "expected": ph, "as_placed": ph[:4] + "⏎" + ph[4:], "count": 1},
        {"kind": "rrn",   "expected": rr, "as_placed": rr[:7] + "⏎" + rr[7:], "count": 1},
        {"kind": "addr",  "expected": ad, "as_placed": "시·구 ⏎ 상세", "count": 1},
        {"kind": "acct",  "expected": ac, "as_placed": ac[:8] + "⏎" + ac[8:], "count": 1},
    ]
    return m, exp


def case_merge():
    m = [["면회 신청자", MG, "관계"],
         [TBL["name"], TBL["name2"], "지인"],
         ["연락처", MG, TBL["phone"]],
         ["주소", MG, TBL["addr"]]]
    exp = [{"kind": "name", "expected": TBL["name"], "as_placed": TBL["name"], "count": 1},
           {"kind": "name2", "expected": TBL["name2"], "as_placed": TBL["name2"], "count": 1},
           {"kind": "phone", "expected": TBL["phone"], "as_placed": TBL["phone"], "count": 1},
           {"kind": "addr", "expected": TBL["addr"], "as_placed": TBL["addr"], "count": 1}]
    return m, exp


def case_nameonly():
    names = [TBL["name"], TBL["name2"], "강현우", "윤지호", "임세라", "조한결"]
    m = [["성명", "성명", "성명"], names[:3], names[3:]]
    exp = [{"kind": "name_only_%d" % i, "expected": n, "as_placed": n, "count": 1}
           for i, n in enumerate(names)]
    return m, exp


def case_nested():
    m = [["구분", "내용"],
         ["인적사항", "성명 %s / 주민 %s" % (TBL["name"], TBL["rrn"])],
         ["연락", "휴대 %s" % TBL["phone"]],
         ["주소", TBL["addr"]],
         ["기관", TBL["org"]]]
    exp = [{"kind": k, "expected": TBL[k], "as_placed": TBL[k], "count": 1}
           for k in ("name", "rrn", "phone", "addr", "org")]
    return m, exp


def case_empty():
    m = [["성명", "", "연락처", ""],
         [TBL["name"], "", TBL["phone"], ""],
         ["", "주소", "", TBL["addr"]],
         ["주민", "", TBL["rrn"], ""]]
    exp = [{"kind": k, "expected": TBL[k], "as_placed": TBL[k], "count": 1}
           for k in ("name", "phone", "addr", "rrn")]
    return m, exp


def case_long(rows=60):
    m = [["번호", "성명", "연락처", "주민등록번호"]]
    for i in range(rows):
        nm = TBL["name"] if i % 2 == 0 else TBL["name2"]
        m.append(["DMY-%04d" % (i + 1), nm, TBL["phone"], TBL["rrn"]])
    exp = [
        {"kind": "name",  "expected": TBL["name"],  "as_placed": "짝수 행", "count": (rows + 1) // 2},
        {"kind": "name2", "expected": TBL["name2"], "as_placed": "홀수 행", "count": rows // 2},
        {"kind": "phone", "expected": TBL["phone"], "as_placed": "모든 행", "count": rows},
        {"kind": "rrn",   "expected": TBL["rrn"],   "as_placed": "모든 행", "count": rows},
    ]
    return m, exp


CASES = {
    "단순2열":    ("없음(기준선) — 이건 돼야 한다", case_simple),
    "값분할":     ("한 값을 여러 칸에 쪼갰다 → 정규식이 안 맞는다", case_split),
    "셀내줄바꿈": ("한 값을 셀 안에서 두 줄로 끊었다", case_wrap),
    "병합셀":     ("가로 병합이 섞여 셀 경계가 무너진다", case_merge),
    "이름만":     ("문장 없이 이름만 — 문맥이 없다", case_nameonly),
    "중첩표":     ("한 칸에 여러 PII", case_nested),
    "빈셀섞임":   ("빈 칸이 섞여 열이 밀린다", case_empty),
    "긴표":       ("쪽을 넘기는 표 — 쪽 경계에서 행이 끊긴다", case_long),
}


# ---------------------------------------------------------------- 생성

def _plain(matrix):
    return [[c.replace(BR, "\n") for c in row] for row in matrix]


def make_docx(path, title, tables, pages):
    import docx
    from docx.enum.text import WD_BREAK
    d = docx.Document()
    d.add_heading(title, level=1)
    for pg in range(pages):
        d.add_paragraph(BASELINE)
        for name, desc, matrix in tables:
            d.add_paragraph("표 — %s (%s)" % (name, desc))
            cols = max(len(r) for r in matrix)
            t = d.add_table(rows=len(matrix), cols=cols)
            t.style = "Table Grid"
            for i, row in enumerate(matrix):
                for j in range(cols):
                    val = row[j] if j < len(row) else ""
                    if val == MG:
                        continue
                    cell = t.cell(i, j)
                    if j + 1 < len(row) and row[j + 1] == MG:
                        cell = cell.merge(t.cell(i, j + 1))
                    if BR in val:
                        p = cell.paragraphs[0]
                        bits = val.split(BR)
                        p.add_run(bits[0])
                        for b in bits[1:]:
                            p.add_run().add_break()
                            p.add_run(b)
                    else:
                        cell.text = val
            d.add_paragraph("표 바로 뒤 문단 — 담당 %s" % BODY["name"])
        if pg + 1 < pages:
            d.add_paragraph().add_run().add_break(WD_BREAK.PAGE)
    sec = d.sections[0]
    sec.header.paragraphs[0].text = "머리말 — 작성자 %s" % BODY["name"]
    sec.footer.paragraphs[0].text = "꼬리말 — 문의 %s" % BODY["phone"]
    d.core_properties.author = BODY["name"]
    d.save(path)


def make_xlsx(path, title, tables, pages):
    import openpyxl
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "시험"
    r = 1
    ws.cell(row=r, column=1, value=title); r += 2
    for _ in range(pages):
        ws.cell(row=r, column=1, value=BASELINE); r += 2
        for name, desc, matrix in tables:
            ws.cell(row=r, column=1, value="표 — %s (%s)" % (name, desc)); r += 1
            for row in _plain(matrix):
                for j, val in enumerate(row):
                    if val == MG:
                        continue
                    ws.cell(row=r, column=1 + j, value=val)
                r += 1
            ws.cell(row=r, column=1, value="표 바로 뒤 문단 — 담당 %s" % BODY["name"]); r += 2
    wb.properties.creator = BODY["name"]
    wb.save(path)


def make_pptx(path, title, tables, pages):
    from pptx import Presentation
    from pptx.util import Inches
    p = Presentation()
    for _ in range(pages):
        s = p.slides.add_slide(p.slide_layouts[1])
        s.shapes.title.text = title
        s.placeholders[1].text_frame.text = BASELINE
        for name, desc, matrix in tables:
            m = _plain(matrix)
            # 슬라이드 한 장에 들어갈 행만 올리고 넘치면 장을 쪼갠다(긴 표).
            chunk = 14
            for off in range(0, len(m), chunk):
                part = m[off:off + chunk]
                if off and m[0] not in part:
                    part = [m[0]] + part          # 머리행 반복
                s2 = p.slides.add_slide(p.slide_layouts[5])
                s2.shapes.title.text = "표 — %s" % name
                cols = max(len(r) for r in part)
                t = s2.shapes.add_table(len(part), cols, Inches(0.3), Inches(1.4),
                                        Inches(9.4), Inches(0.28 * len(part))).table
                for i, row in enumerate(part):
                    for j in range(cols):
                        val = row[j] if j < len(row) else ""
                        t.cell(i, j).text = "" if val == MG else val
            tb = s.shapes.add_textbox(Inches(0.3), Inches(6.6), Inches(9.4), Inches(0.5))
            tb.text_frame.text = "표 바로 뒤 문단 — 담당 %s" % BODY["name"]
    p.core_properties.author = BODY["name"]
    p.save(path)


BUILDERS = {"docx": make_docx, "xlsx": make_xlsx, "pptx": make_pptx}


def body_answers(pages):
    """본문 기준선에 들어간 PII. 쪽마다 한 번씩 반복된다."""
    out = []
    for k, v in BODY.items():
        n = BASELINE.count(v)
        if n:
            out.append({"kind": "body_" + k, "expected": v, "as_placed": v, "count": n * pages})
    return out


def build(outdir, formats, cases, pages):
    os.makedirs(outdir, exist_ok=True)
    made = []
    for case in cases:
        desc, fn = CASES[case]
        matrix, exp = fn()
        # 표가 쪽마다 반복되므로 기대 횟수도 쪽수만큼 늘어난다
        exp = [dict(e, count=e["count"] * pages) for e in exp]
        for f in formats:
            name = "case_%s_%dp.%s" % (case, pages, f)
            path = os.path.join(outdir, name)
            BUILDERS[f](path, "표 구조 시험 — %s" % case, [(case, desc, matrix)], pages)
            made.append({"file": name, "format": f, "case": case, "pages": pages,
                         "breaks": desc, "table": exp, "body": body_answers(pages)})
            print("%-36s %8d bytes" % (name, os.path.getsize(path)))
    io.open(os.path.join(outdir, "cases.json"), "w", encoding="utf-8").write(
        json.dumps({"body_pii": BODY, "table_pii": TBL, "baseline": BASELINE, "docs": made},
                   ensure_ascii=False, indent=2))
    return made


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "cases"
    which = sys.argv[2].split(",") if len(sys.argv) > 2 and sys.argv[2] else list(CASES)
    pages = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    fmts = sys.argv[4].split(",") if len(sys.argv) > 4 else ["docx", "xlsx", "pptx"]
    build(out, fmts, which, pages)
