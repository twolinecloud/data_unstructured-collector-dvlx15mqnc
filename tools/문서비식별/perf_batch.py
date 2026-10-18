# 4단계 — 묶음 모양 비교 (기획 §5.2)
#
# 같은 100장을 **묶음 모양만 바꿔** 보낸다. 100장 1개 vs 10장 10개에서 처리율과
# 거절률이 다른지 본다. 이 결과가 "첨부파일 1건 최대 n장 · 동시 최대 m건" 정책의 근거다.
#
# 재는 것
#   · 처리율(장/분) — 같은 100장을 끝내는 데 걸린 벽시계
#   · 거절률 — 4xx·5xx·타임아웃 비율
#   · 응답 시간 분포 — 최소·중앙·최대
#
# 측정 규율
#   · 예열 1회를 버린다
#   · 동시 요청은 스레드로 보낸다. 같은 묶음을 동시도만 바꿔 가며 돌린다
#   · 실패를 숨기지 않는다 — 거절도 측정값이다(여기서는 중단하지 않고 집계한다)
import io, json, os, statistics, subprocess, sys, time
from concurrent.futures import ThreadPoolExecutor


def call(url, path, out):
    """한 건 호출. 실패해도 중단하지 않고 (코드, 벽시계) 를 돌려준다 — 거절률이 측정 대상이다."""
    if os.path.exists(out):
        os.remove(out)
    t0 = time.time()
    r = subprocess.run(["curl", "-s", "--max-time", "1800", "-F", "file=@" + path,
                        "-o", out, "-w", "%{http_code}", url], capture_output=True, text=True)
    wall = int((time.time() - t0) * 1000)
    return r.stdout.strip(), wall, out


def air_ok(out):
    """커넥터는 AI-R 실패도 200 으로 감싼다. 그래서 본문의 airStatus 까지 봐야 한다."""
    try:
        j = json.load(io.open(out, encoding="utf-8"))
    except Exception:
        return False, None
    if "airStatus" in j:
        return j["airStatus"] == 200, j.get("elapsedMs")
    st = [p.get("status") for p in j.get("air") or []]
    ms = (j.get("extract", {}).get("elapsedMs") or 0) + (j.get("airElapsedMs") or 0)
    return bool(st) and all(s == 200 for s in st), ms


def main():
    sp = sys.argv[1]
    port = sys.argv[2] if len(sys.argv) > 2 else "18098"
    kind = sys.argv[3] if len(sys.argv) > 3 else "B"
    fmt = sys.argv[4] if len(sys.argv) > 4 else "docx"
    # (파일 수, 장/파일) — 곱이 100 이 되게
    shapes = [(1, 100), (2, 50), (4, 25), (10, 10), (20, 5)]
    concurrency = [int(x) for x in (sys.argv[5].split(",") if len(sys.argv) > 5 else ["1", "4"])]

    base = "http://127.0.0.1:" + port
    url = (base + "/api/v1/deid/air/detect-file?server=dev&masking=true") if kind == "A" \
        else (base + "/api/v1/deid/doc/detect?server=dev&masking=true")
    out_dir = os.path.join(sp, "perf_out", "batch")
    os.makedirs(out_dir, exist_ok=True)

    # 예열
    warm = os.path.join(sp, "pages", "p5", "case_단순2열_5p." + fmt)
    if os.path.exists(warm):
        call(url, warm, os.path.join(out_dir, "warm.json"))

    rows = []
    for files, per in shapes:
        src = os.path.join(sp, "pages", "p%d" % per, "case_단순2열_%dp.%s" % (per, fmt))
        if not os.path.exists(src):
            print("없음", src)
            continue
        for conc in concurrency:
            jobs = [(src, os.path.join(out_dir, "%s_%dx%d_c%d_%d.json" % (fmt, files, per, conc, i)))
                    for i in range(files)]
            t0 = time.time()
            with ThreadPoolExecutor(max_workers=conc) as pool:
                res = list(pool.map(lambda a: call(url, a[0], a[1]), jobs))
            total = time.time() - t0

            codes = [c for c, _, _ in res]
            walls = [w for _, w, _ in res]
            oks = [air_ok(o)[0] for _, _, o in res]
            good = sum(1 for c, k in zip(codes, oks) if c == "200" and k)
            # 처리율은 **성공한 장수**로 계산한다. 보낸 100장으로 나누면 거절이 많을수록
            #   빨라 보인다 — 20×5 동시8 이 344장/분으로 가장 빨라 보였는데 15건이 거절된
            #   것이었다. 거절을 처리한 것처럼 세면 정책을 거꾸로 세우게 된다.
            done_pages = good * per
            rows.append({
                "shape": "%d×%d장" % (files, per), "files": files, "per": per,
                "conc": conc, "total_s": round(total, 1),
                "sent_pages": files * per, "done_pages": done_pages,
                "throughput": round(done_pages / total * 60, 1) if total else 0,
                "ok": good, "reject": files - good,
                "min_ms": min(walls), "med_ms": int(statistics.median(walls)), "max_ms": max(walls),
                "codes": sorted(set(codes)),
            })
            print("%-10s 동시 %2d  총 %6.1fs  처리율 %6.1f장/분  성공 %d/%d  응답 %d~%dms"
                  % (rows[-1]["shape"], conc, total, rows[-1]["throughput"], good, files,
                     min(walls), max(walls)))

    L = ["# 4단계 — 묶음 모양 비교 (기획 §5.2)", "",
         "같은 **100장**을 묶음 모양만 바꿔 보냈다. 경로 %s · %s · 개발계" % (kind, fmt), "",
         "처리율은 **성공한 장수**로 계산한다 — 거절을 처리한 것처럼 세면 거절이 많을수록 빨라 보인다.", "",
         "| 묶음 | 동시도 | 총 시간 | 처리 장수 | **처리율** | 성공 | 거절 | 응답(최소/중앙/최대) |",
         "|---|---|---|---|---|---|---|---|"]
    for r in rows:
        L.append("| %s | %d | %.1fs | %d/%d장 | **%.1f장/분** | %d/%d | %s | %d / %d / %dms |" % (
            r["shape"], r["conc"], r["total_s"], r["done_pages"], r["sent_pages"],
            r["throughput"], r["ok"], r["files"],
            "**%d건**" % r["reject"] if r["reject"] else "0",
            r["min_ms"], r["med_ms"], r["max_ms"]))
    io.open(os.path.join(out_dir, "BATCH.md"), "w", encoding="utf-8").write("\n".join(L) + "\n")
    io.open(os.path.join(out_dir, "batch.json"), "w", encoding="utf-8").write(
        json.dumps(rows, ensure_ascii=False, indent=2))
    print("wrote", os.path.join(out_dir, "BATCH.md"))


if __name__ == "__main__":
    main()
