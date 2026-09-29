# -*- coding: utf-8 -*-
"""
시뮬레이터 HTML 정적 점검.

태그 균형 · id 중복 · JS 가 찾는 id 존재 여부에 더해, **정의되지 않은 함수를 부르는 곳**을 잡는다.

2026-09-22 에 옛 재처리 블록을 걷어내며 `renderTimeline();` 호출 한 줄이 남았다. 스크립트가 그
자리에서 죽어 화면 전체가 빈 채로 떴는데(배지 '조회 중…', 로그도 빈 상태) 태그·id 검사는 전부
통과했다. 같은 사고를 다시 잡으려면 호출-정의 대조가 필요하다.

    python scripts/check-simulator.py
"""
import io
import re
import sys
from html.parser import HTMLParser

HTML = r"C:\Projects\data_unstructured-collector-dvlx15mqnc\src\main\resources\static\voice_collector_simulator.html"
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"}

# 브라우저·문법이 주는 이름. 여기에 없으면 오탐이 나므로 넉넉히 둔다.
KNOWN = {
    "console", "document", "window", "location", "localStorage", "sessionStorage",
    "fetch", "setTimeout", "setInterval", "clearTimeout", "clearInterval",
    "Promise", "Object", "Array", "String", "Number", "Boolean", "Math", "JSON", "Date",
    "Map", "Set", "RegExp", "Error", "URL", "URLSearchParams", "KeyboardEvent", "Event",
    "encodeURIComponent", "decodeURIComponent", "parseInt", "parseFloat", "isNaN",
    "alert", "confirm", "prompt", "bootstrap", "requestAnimationFrame", "structuredClone",
    # 제어 구문 — `if (` 처럼 괄호가 붙어 호출처럼 보인다
    "if", "for", "while", "switch", "catch", "return", "function", "typeof", "new",
    "await", "async", "of", "in", "do", "else", "try", "finally", "throw", "case", "delete",
}

# 화면이 만들어 붙이는 id — 마크업에 없는 것이 정상이다.
DYNAMIC_IDS = {"cmp-acc", "files-acc", "result-acc", "sim-acc", "health-meta"}


def body_only(s):
    return re.sub(r"<(script|style)\b.*?</\1>", "", s, flags=re.S)


def script_only(s):
    return "\n".join(re.findall(r"<script[^>]*>(.*?)</script>", s, flags=re.S))


def strip_comments_and_strings(js):
    """주석과 문자열 리터럴을 지운다 — 그 안의 글자를 코드로 읽으면 오탐이 쏟아진다."""
    out = []
    i, n = 0, len(js)
    while i < n:
        c = js[i]
        nxt = js[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = js.find("\n", i)
            i = n if j < 0 else j
        elif c == "/" and nxt == "*":
            j = js.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c in ("'", '"', "`"):
            quote = c
            i += 1
            while i < n:
                if js[i] == "\\":
                    i += 2
                    continue
                if js[i] == quote:
                    i += 1
                    break
                i += 1
            out.append('""')
        else:
            out.append(c)
            i += 1
    return "".join(out)


class Bal(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.bad = []

    def handle_starttag(self, tag, attrs):
        if tag not in VOID:
            self.stack.append((tag, self.getpos()))

    def handle_endtag(self, tag):
        if tag in VOID:
            return
        if not self.stack:
            self.bad.append("여는 태그 없이 </%s> %s" % (tag, self.getpos()))
            return
        t, pos = self.stack.pop()
        if t != tag:
            self.bad.append("<%s> %s 가 </%s> %s 로 닫힘" % (t, pos, tag, self.getpos()))


def main():
    src = io.open(HTML, encoding="utf-8").read()
    body = body_only(src)
    js = script_only(src)
    problems = []

    p = Bal()
    p.feed(body)
    p.close()
    problems += ["태그: " + b for b in p.bad]
    problems += ["태그: 안 닫힘 <%s> %s" % (t, pos) for t, pos in p.stack]

    ids = re.findall(r'\bid="([^"]+)"', body)
    problems += ["id 중복: " + d for d in sorted({i for i in ids if ids.count(i) > 1})]

    for i in sorted(set(re.findall(r"\$\('([A-Za-z0-9_-]+)'\)", js))):
        if i not in ids and i not in DYNAMIC_IDS:
            problems.append("마크업에 없는 id 를 찾는다: " + i)

    # 호출-정의 대조는 **줄 맨 앞의 호출**만 본다.
    #   JS 를 제대로 토큰화하려면 정규식 리터럴(따옴표가 든 /[&<>"']/ 같은 것)과 나눗셈을
    #   갈라야 하는데, 그 한 줄 때문에 문자열 파싱이 어긋나 오탐이 쏟아진다.
    #   실제로 잡아야 하는 사고는 "블록을 걷어냈는데 최상위 호출 한 줄이 남는 것"이고
    #   그것은 항상 줄 맨 앞에 온다. 범위를 거기로 좁히면 오탐 없이 그 유형만 걸린다.
    defined = set(re.findall(r"\bfunction\s+([A-Za-z_$][\w$]*)", js))
    defined |= set(re.findall(r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=", js))
    for m in re.finditer(r"^([A-Za-z_$][\w$]*)\s*\(", js, flags=re.M):
        name = m.group(1)
        if name not in defined and name not in KNOWN:
            line = js[:m.start()].count("\n") + 1
            problems.append("정의되지 않은 함수를 부른다: %s() (script %d번째 줄)" % (name, line))

    if problems:
        print("NG — %d건" % len(problems))
        for x in problems:
            print("  -", x)
        return 1
    print("OK — 태그 균형 · id 중복 · id 참조 · 함수 정의 모두 이상 없음")
    return 0


if __name__ == "__main__":
    sys.exit(main())
