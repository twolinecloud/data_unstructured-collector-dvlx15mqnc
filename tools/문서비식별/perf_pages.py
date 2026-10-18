# 4단계 — 장수 임계 (기획 §5.1)
#
# 재는 것: 총 시간 · **장당 시간**(꺾이는 곳이 임계) · 추출/검출이 장수에 따라 무너지는가.
#
# 측정 규율
#   · **예열 1회를 버린다.** 첫 호출은 JVM 예열 때문에 10배 느리다(2단계에서 docx 추출이
#     1,216ms → 108ms 로 떨어졌다). 예열분을 섞으면 작은 장수가 과대평가된다.
#   · 회차마다 값을 남기고 **중앙값**을 쓴다. AI-R 은 비결정적이다.
#   · 호출 전에 결과 파일을 지우고, 200 이 아니면 **측정을 중단**한다.
#     조용히 옛 숫자를 보고하는 것이 틀린 숫자보다 나쁘다.
import io, json, os, statistics, subprocess, sys, time


def call(url, path, out):
    if os.path.exists(out):
        os.remove(out)
    t0 = time.time()
    r = subprocess.run(["curl", "-s", "--max-time", "1800", "-F", "file=@" + path,
                        "-o", out, "-w", "%{http_code}", url], capture_output=True, text=True)
    wall = int((time.time() - t0) * 1000)
    code = r.stdout.strip()
    if code != "200" or not os.path.exists(out):
        raise SystemExit("호출 실패 HTTP %s — %s (측정 중단)" % (code, url))
    return json.load(io.open(out, encoding="utf-8")), wall


def server_ms(j, kind):
    if kind == "A":
        return j.get("elapsedMs") or 0
    return (j.get("extract", {}).get("elapsedMs") or 0) + (j.get("airElapsedMs") or 0)


def air_status(j, kind):
    if kind == "A":
        return j.get("airStatus")
    st = [p.get("status") for p in j.get("air") or []]
    return max(st) if st else ("추출실패" if not j.get("airCalled") else 0)


def chars(j, kind):
    if kind == "A":
        b = j.get("body") or {}
        n = 0
        for e in b.get("detectedData") or []:
            n += len(e.get("txt", "") if isinstance(e, dict) else str(e))
        return n
    return j.get("extract", {}).get("chars") or 0


def main():
    sp = sys.argv[1]
    port = sys.argv[2] if len(sys.argv) > 2 else "18098"
    pages = [int(x) for x in (sys.argv[3].split(",") if len(sys.argv) > 3 else
                              ["1", "5", "10", "25", "50", "75", "100"])]
    fmts = (sys.argv[4].split(",") if len(sys.argv) > 4 else ["docx", "pptx"])
    kinds = (sys.argv[5].split(",") if len(sys.argv) > 5 else ["A", "B"])
    repeats = int(sys.argv[6]) if len(sys.argv) > 6 else 3

    base = "http://127.0.0.1:" + port
    URLS = {"A": base + "/api/v1/deid/air/detect-file?server=dev&masking=true",
            "B": base + "/api/v1/deid/doc/detect?server=dev&masking=true"}
    out_dir = os.path.join(sp, "perf_out")
    os.makedirs(out_dir, exist_ok=True)

    rows = []
    for f in fmts:
        for kind in kinds:
            # 예열 — 가장 작은 문서로 한 번 돌리고 버린다
            warm = os.path.join(sp, "pages", "p%d" % pages[0], "case_단순2열_%dp.%s" % (pages[0], f))
            if os.path.exists(warm):
                try:
                    call(URLS[kind], warm, os.path.join(out_dir, "warm.json"))
                except SystemExit as e:
                    print("예열 실패:", e)
            for n in pages:
                p = os.path.join(sp, "pages", "p%d" % n, "case_단순2열_%dp.%s" % (n, f))
                if not os.path.exists(p):
                    print("없음", p)
                    continue
                walls, servers, sts, chs = [], [], [], []
                for i in range(repeats):
                    j, wall = call(URLS[kind], p,
                                   os.path.join(out_dir, "%s_%s_%dp_%d.json" % (f, kind, n, i)))
                    walls.append(wall)
                    servers.append(server_ms(j, kind))
                    sts.append(air_status(j, kind))
                    chs.append(chars(j, kind))
                row = {"format": f, "path": kind, "pages": n,
                       "bytes": os.path.getsize(p),
                       "chars": int(statistics.median(chs)),
                       "wall_ms": int(statistics.median(walls)),
                       "server_ms": int(statistics.median(servers)),
                       "walls": walls, "servers": servers, "status": sts}
                rows.append(row)
                print("%-5s %s %4d장  %6d자  서버 %6dms  장당 %6.0fms  %s"
                      % (f, kind, n, row["chars"], row["server_ms"],
                         row["server_ms"] / n, sts))

    L = ["# 4단계 — 장수 임계 (기획 §5.1)", "",
         "장당 PII 고정 · 예열 1회 버림 · %d회 중앙값 · 개발계" % repeats, "",
         "| 포맷 | 경로 | 장수 | 글자 | 서버 시간 | **장당** | 벽시계 | 상태 |",
         "|---|---|---|---|---|---|---|---|"]
    for r in rows:
        L.append("| %s | %s | %d | %d자 | %dms | **%.0fms** | %dms | %s |" % (
            r["format"], r["path"], r["pages"], r["chars"], r["server_ms"],
            r["server_ms"] / r["pages"], r["wall_ms"],
            "OK" if all(s == 200 for s in r["status"]) else "**%s**" % r["status"]))
    L.append("")
    L.append("## 1,000자당 시간")
    L.append("")
    L.append("| 포맷 | 경로 | 장수 | 1,000자당 |")
    L.append("|---|---|---|---|")
    for r in rows:
        if r["chars"]:
            L.append("| %s | %s | %d | %.0fms |" % (
                r["format"], r["path"], r["pages"], 1000.0 * r["server_ms"] / r["chars"]))
    io.open(os.path.join(out_dir, "PAGES.md"), "w", encoding="utf-8").write("\n".join(L) + "\n")
    io.open(os.path.join(out_dir, "pages.json"), "w", encoding="utf-8").write(
        json.dumps(rows, ensure_ascii=False, indent=2))
    print("wrote", os.path.join(out_dir, "PAGES.md"))


if __name__ == "__main__":
    main()
