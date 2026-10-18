# 주민등록번호 패턴이 왜 안 걸리는가 — 체크섬 때문인지 꼴 때문인지 가른다.
import io, json, urllib.request

BASE = "http://127.0.0.1:18098"


def chk(d12):
    w = [2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5]
    s = sum(int(c) * k for c, k in zip(d12, w))
    return (11 - (s % 11)) % 10


cases = []
front = "900101"
back11 = "123456"
valid = str(chk(front + back11))
cases.append(("체크섬 맞음", front + "-" + back11 + valid))
bad = str((int(valid) + 1) % 10)
cases.append(("체크섬 틀림", front + "-" + back11 + bad))
cases.append(("하이픈 없음(맞음)", front + back11 + valid))
cases.append(("원래 쓴 값", "900101-1234567"))
cases.append(("문맥 있음(맞음)", "주민등록번호는 " + front + "-" + back11 + valid + " 입니다"))
cases.append(("문맥 있음(틀림)", "주민등록번호는 900101-1234567 입니다"))

L = []
for label, text in cases:
    body = json.dumps({"text": text, "masking": True}).encode("utf-8")
    req = urllib.request.Request(BASE + "/api/v1/deid/air/detect?server=dev",
                                 data=body, headers={"Content-Type": "application/json"})
    try:
        r = json.loads(urllib.request.urlopen(req, timeout=60).read().decode("utf-8"))
    except Exception as e:
        L.append("%-18s 호출실패 %s" % (label, e))
        continue
    masked = ""
    md = r.get("maskedData")
    if isinstance(md, list) and md:
        masked = md[0].get("txt", "") if isinstance(md[0], dict) else str(md[0])
    pats = []
    for doc in r.get("patternResult") or []:
        if isinstance(doc, list):
            for e in doc:
                if isinstance(e, dict) and not e.get("except"):
                    pats.append("%s(%s)" % (e.get("word"), e.get("pattern", "")[:8]))
    L.append("%-18s 입력=%-45s → 마스킹=%-45s 패턴=%s"
             % (label, text, masked, ", ".join(pats) or "없음"))

out = "\n".join(L)
io.open("rrn_test_out.txt", "w", encoding="utf-8").write(out + "\n")
print(out)
