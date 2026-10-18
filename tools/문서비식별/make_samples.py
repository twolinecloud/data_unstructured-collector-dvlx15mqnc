# 9번 탭이 내려줄 시험 문서 묶음을 고르고 매니페스트를 만든다.
#
# 왜 레포에 담는가 — **hwp · hwpx 는 클러스터에서 만들 수 없다**(한글 COM 필요).
# docx · xlsx · pptx 는 만들 수 있지만, 같은 묶음을 쓰지 않으면 채팅 실화면 시험과
# 숫자를 맞출 수 없다. 그래서 **한 벌을 고정 자산으로** 둔다.
#
# 사용: make_samples.py <탐침폴더> <표케이스폴더> <장수폴더> <출력폴더>
import io, json, os, shutil, sys

probe, cases, pages, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
os.makedirs(out, exist_ok=True)

items = []


def take(src, name, group, desc, extra=None):
    if not os.path.exists(src):
        print("없음", src)
        return
    shutil.copy2(src, os.path.join(out, name))
    it = {"name": name, "group": group, "desc": desc,
          "format": name.rsplit(".", 1)[-1], "bytes": os.path.getsize(src)}
    if extra:
        it.update(extra)
    items.append(it)


# ① 1쪽 6포맷 탐침 세트 — 포맷 지원 여부를 보는 기본 묶음
for f in ("docx", "xlsx", "pptx", "hwpx", "pdf", "hwp"):
    take(os.path.join(probe, "deid_probe." + f), "probe_1p." + f,
         "포맷지원", "1쪽 · PII 12종 · 자리 9곳(본문·표 4종·표뒤문단·머리말·꼬리말·문서속성)",
         {"answers": "probe_answers.json"})
take(os.path.join(probe, "answers.json"), "probe_answers.json", "정답표", "탐침 세트 정답")

# ② 표 구조 8유형 — docx 전체 + 깨지는 두 유형은 다른 포맷까지
TBL = ["단순2열", "값분할", "셀내줄바꿈", "병합셀", "이름만", "중첩표", "빈셀섞임", "긴표"]
BREAKS = {"값분할": "한 값을 여러 칸에 — 모든 포맷에서 깨진다",
          "셀내줄바꿈": "셀 안에서 두 줄로 — docx 가 가장 심하다"}
for c in TBL:
    take(os.path.join(cases, "case_%s_1p.docx" % c), "table_%s.docx" % c,
         "표구조", BREAKS.get(c, "표 유형 — " + c), {"answers": "table_cases.json", "case": c})
for c in ("값분할", "셀내줄바꿈"):
    for f in ("hwpx", "pdf", "hwp"):
        take(os.path.join(cases, "case_%s_1p.%s" % (c, f)), "table_%s.%s" % (c, f),
             "표구조", BREAKS[c] + " (포맷 비교)", {"answers": "table_cases.json", "case": c})
take(os.path.join(cases, "cases.json"), "table_cases.json", "정답표", "표 구조 케이스 정답")

# ③ 장수 — 성능 축. 1·10·50·100 만 담는다(중간값은 도구로 만들 수 있다)
for n in (1, 10, 50, 100):
    take(os.path.join(pages, "p%d" % n, "case_단순2열_%dp.docx" % n), "pages_%dp.docx" % n,
         "장수", "%d장 · 장당 PII 고정" % n, {"pages": n})
    take(os.path.join(pages, "p%d" % n, "case_단순2열_%dp.pptx" % n), "pages_%dp.pptx" % n,
         "장수", "%d장 · pptx 는 슬라이드 수에 비례해 느리다" % n, {"pages": n})

man = {"generated": "2026-10-18",
       "note": ("hwp · hwpx 는 한글 COM 이 필요해 클러스터에서 만들 수 없다 — 고정 자산이다. "
                "pdf 는 한글 내보내기로 만들었다(이 PC 에 Word 는 없다). "
                "hwpx · pdf · hwp 는 docx 변환본이므로 포맷 간 PII 출현 횟수가 다를 수 있다."),
       "items": items}
io.open(os.path.join(out, "samples.json"), "w", encoding="utf-8").write(
    json.dumps(man, ensure_ascii=False, indent=2))

total = sum(i["bytes"] for i in items)
print("%d개 · %.1f KB" % (len(items), total / 1024.0))
for i in items:
    print("  %-26s %-8s %7d  %s" % (i["name"], i["group"], i["bytes"], i["desc"][:40]))
