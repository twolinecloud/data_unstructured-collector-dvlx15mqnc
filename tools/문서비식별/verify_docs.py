# 생성한 문서에 정답 PII 가 실제로 들어갔는지 확인한다.
# 콘솔 인코딩에 휘둘리지 않도록 결과를 파일로도 쓴다.
import html, io, json, os, sys, zipfile

d = sys.argv[1]
keys = json.load(io.open(os.path.join(d, "answers.json"), encoding="utf-8"))["pii"]
lines = []
for f in ("deid_probe.docx", "deid_probe.xlsx", "deid_probe.pptx", "deid_probe.hwpx"):
    p = os.path.join(d, f)
    if not os.path.exists(p):
        lines.append(f + " 없음")
        continue
    z = zipfile.ZipFile(p)
    txt = ""
    for n in z.namelist():
        if n.endswith(".xml") or n.endswith(".rels"):
            # openpyxl 은 한글을 XML 수치참조(&#54861;)로 쓴다 — 풀지 않으면 '누락' 으로 잘못 읽는다.
            txt += html.unescape(z.read(n).decode("utf-8", "ignore"))
    miss = [k for k, v in keys.items() if v not in txt]
    lines.append("%-20s 항목 %d/%d  누락=%s" % (f, len(keys) - len(miss), len(keys), ",".join(miss) or "없음"))
for f in ("deid_probe.pdf", "deid_probe.hwp"):
    p = os.path.join(d, f)
    lines.append("%-20s %d bytes (바이너리 — 문자열 검사 불가)" % (f, os.path.getsize(p)) if os.path.exists(p) else f + " 없음")

out = "\n".join(lines)
io.open(os.path.join(d, "_verify.txt"), "w", encoding="utf-8").write(out + "\n")
print(out)
