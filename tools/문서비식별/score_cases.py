# 3단계 채점 — 표 유형별로 '무엇이 얼마나 떨어지는가' 를 세 갈래로 가른다.
#
#   ① 추출 : 기대값이 추출 텍스트에 **온전히** 왔는가        → 도달
#   ② 구조 : 온전히는 안 왔는데 **조각으로** 왔는가          → 분절
#   ③ 탐지 : 왔는데(온전·조각 무관) 평문으로 **남았는가**     → 잔존
#
# 자리 구분은 **값으로** 한다 — 본문 PII 와 표 PII 를 다른 값으로 심었다.
# 전체 텍스트 검색으로 자리별 O/X 를 만들려 하면 안 된다(1단계에서 그렇게 틀렸다).
import io, json, os, subprocess, sys


def post(url, path, out):
    # 호출 전에 결과 파일을 지운다 — curl 은 실패 시 기존 파일을 덮지 않아
    #   지난 실행 결과가 새 측정처럼 읽힌다(2026-10-18 에 그렇게 읽었다).
    if os.path.exists(out):
        os.remove(out)
    r = subprocess.run(["curl", "-s", "--max-time", "900", "-F", "file=@" + path,
                        "-o", out, "-w", "%{http_code}", url],
                       capture_output=True, text=True)
    code = r.stdout.strip()
    if code != "200" or not os.path.exists(out):
        raise SystemExit("호출 실패 HTTP %s — %s (측정 중단)" % (code, url))
    return json.load(io.open(out, encoding="utf-8"))


def texts(node, key):
    out = []
    for e in node.get(key) or []:
        out.append(str(e.get("txt", "")) if isinstance(e, dict) else str(e))
    return out


def gather(j, kind):
    if kind == "A":
        b = j.get("body") or {}
        return "\n".join(texts(b, "detectedData")), "\n".join(texts(b, "maskedData"))
    ex, mk = [], []
    for pt in j.get("air") or []:
        body = pt.get("body") or {}
        ex += texts(body, "detectedData")
        mk += texts(body, "maskedData")
    return "\n".join(ex), "\n".join(mk)


def pieces(v):
    """값을 조각으로. 구분자·공백을 뺀 숫자/글자 덩어리들."""
    import re
    return [p for p in re.split(r"[\s\-·|]+", v) if len(p) >= 3]


def squash(s):
    return "".join(s.split())


def measure(answers, ex, mk):
    """기대값 목록에 대해 도달·분절·잔존을 센다."""
    want = whole = frag = left = 0
    detail = []
    exs, mks = squash(ex), squash(mk)
    for a in answers:
        v, n = a["expected"], a["count"]
        want += n
        w = min(ex.count(v), n)                      # 온전히 도달
        whole += w
        f = 0
        if w < n:
            # 온전히 안 왔다 — 조각으로 왔는지 본다(공백·줄바꿈을 지우고 비교)
            ps = pieces(v)
            if ps and all(p in exs for p in ps):
                f = n - w
        frag += f
        # 잔존: 평문 그대로 남았거나, 조각이 남아 있다
        lw = mk.count(v)
        lf = 0
        if lw == 0:
            ps = pieces(v)
            if ps and all(p in mks for p in ps):
                lf = 1
        left += lw + lf
        detail.append({"kind": a["kind"], "expected": v, "as_placed": a.get("as_placed"),
                       "want": n, "whole": w, "frag": f, "left": lw + lf})
    return {"want": want, "whole": whole, "frag": frag, "left": left, "detail": detail}


def main():
    sp = sys.argv[1]
    port = sys.argv[2] if len(sys.argv) > 2 else "18098"
    paths = (sys.argv[3].split(",") if len(sys.argv) > 3 else ["A", "B"])
    cases_dir = os.path.join(sp, "cases")
    out_dir = os.path.join(sp, "cases_out")
    os.makedirs(out_dir, exist_ok=True)
    base = "http://127.0.0.1:" + port
    URLS = {"A": base + "/api/v1/deid/air/detect-file?server=dev&masking=true",
            "B": base + "/api/v1/deid/doc/detect?server=dev&masking=true"}

    meta = json.load(io.open(os.path.join(cases_dir, "cases.json"), encoding="utf-8"))
    rows = []
    for d in meta["docs"]:
        p = os.path.join(cases_dir, d["file"])
        if not os.path.exists(p):
            continue
        for kind in paths:
            o = os.path.join(out_dir, "%s.%s.json" % (d["file"], kind))
            j = post(URLS[kind], p, o)
            st = j.get("airStatus") if kind == "A" else max(
                [x.get("status") for x in j.get("air") or []] or [0])
            ms = j.get("elapsedMs") if kind == "A" else (
                (j.get("extract", {}).get("elapsedMs") or 0) + (j.get("airElapsedMs") or 0))
            ex, mk = gather(j, kind)
            t = measure(d["table"], ex, mk)
            b = measure(d["body"], ex, mk)
            rows.append({"case": d["case"], "format": d["format"], "path": kind,
                         "status": st, "ms": ms, "breaks": d["breaks"],
                         "pages": d["pages"], "tbl": t, "body": b})
            io.open(os.path.join(out_dir, "%s.%s.추출.txt" % (d["file"], kind)),
                    "w", encoding="utf-8").write(ex)
            io.open(os.path.join(out_dir, "%s.%s.마스킹.txt" % (d["file"], kind)),
                    "w", encoding="utf-8").write(mk)

    def pct(a, b_):
        return "—" if not b_ else "%.0f%%" % (100.0 * a / b_)

    L = ["# 3단계 — 표 유형별 비식별 정확도", "",
         "본문 PII 와 표 PII 를 **다른 값**으로 심었다. 그래서 자리별로 따로 셀 수 있다.",
         "본문은 되는데 표에서 안 되면 **표 탓**이다.", "",
         "- 도달 = 기대값이 추출 텍스트에 온전히 온 비율",
         "- 분절 = 온전히는 안 왔지만 조각으로는 온 건수 ← **쪼개지면 정규식이 안 맞는다**",
         "- 잔존 = 마스킹 결과에 평문(또는 조각)으로 남은 횟수", "",
         "| 표 유형 | 포맷 | 경로 | AI-R | 표 도달 | **표 분절** | 표 잔존 | 본문 도달 | 본문 잔존 | 시간 |",
         "|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        t, b = r["tbl"], r["body"]
        L.append("| %s | %s | %s | %s | %d/%d (%s) | %s | %s | %d/%d (%s) | %s | %sms |" % (
            r["case"], r["format"], r["path"],
            r["status"] if r["status"] == 200 else "**%s**" % r["status"],
            t["whole"], t["want"], pct(t["whole"], t["want"]),
            "**%d건**" % t["frag"] if t["frag"] else "0",
            "**%d회**" % t["left"] if t["left"] else "0",
            b["whole"], b["want"], pct(b["whole"], b["want"]),
            "**%d회**" % b["left"] if b["left"] else "0",
            r["ms"]))
    L.append("")

    # 값별 상세 — 무엇이 어떻게 깨졌는지
    L.append("## 값별 상세 (경로 B)")
    L.append("")
    for r in rows:
        if r["path"] != "B":
            continue
        bad = [x for x in r["tbl"]["detail"] if x["whole"] < x["want"] or x["left"]]
        if not bad:
            continue
        L.append("**%s · %s** — %s" % (r["case"], r["format"], r["breaks"]))
        L.append("")
        L.append("| 종류 | 기대값 | 넣은 꼴 | 도달 | 분절 | 잔존 |")
        L.append("|---|---|---|---|---|---|")
        for x in bad:
            L.append("| %s | `%s` | %s | %d/%d | %s | %s |" % (
                x["kind"], x["expected"], x["as_placed"] or "-",
                x["whole"], x["want"], x["frag"] or "-", x["left"] or "-"))
        L.append("")

    io.open(os.path.join(out_dir, "REPORT.md"), "w", encoding="utf-8").write("\n".join(L) + "\n")
    io.open(os.path.join(out_dir, "rows.json"), "w", encoding="utf-8").write(
        json.dumps(rows, ensure_ascii=False, indent=2))
    print("wrote", os.path.join(out_dir, "REPORT.md"), len(rows), "rows")


if __name__ == "__main__":
    main()
