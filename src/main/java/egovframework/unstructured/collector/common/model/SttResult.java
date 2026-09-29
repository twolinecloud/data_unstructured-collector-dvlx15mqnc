package egovframework.unstructured.collector.common.model;

import java.util.List;

/**
 * STT 결과 — <b>Whisper 표준 JSON 의 모양을 따른다.</b>
 *
 * <p><b>왜 Whisper 스키마인가</b>: NPU STT 의 API 사양이 아직 없다(계획서 Q9). 그런데 접견·통화는
 * 화자가 둘 이상이고, 비식별·검색 요건이 "몇 분에 나온 주민번호인지" 를 물을 수 있다. 전문을 한
 * 덩어리 문자열로만 들고 있으면 엔진이 구간을 주더라도 <b>받을 그릇이 없다</b>. 대부분의
 * 오픈소스·상용 STT 가 Whisper 스키마를 따르거나 변환을 제공하므로, 사양이 확정될 때까지
 * 그것을 기본 가정으로 둔다 — 실물이 나오면 파싱만 맞추면 된다.</p>
 *
 * <p><b>{@code segments} 는 비어 있을 수 있다.</b> 구간을 주지 않는 엔진도 있고 보라미가 이미
 * 가지고 있던 STT 를 재사용하는 경우(계획서 Q1)에는 전문만 있다. 비어 있다고 실패가 아니다 —
 * 쓰는 쪽은 {@code text} 를 기준으로 삼고 구간은 있을 때만 쓴다.</p>
 *
 * @param text       인식 전문. 외부로 보내지 않는다 — 글자 수만 배치 결과(FileProcOutcome)에 실린다
 * @param engine     엔진 표기(로그용)
 * @param duration   오디오 길이(초). Whisper 는 실수로 준다. 모르면 0
 * @param fromSource 보라미가 이미 가지고 있던 STT 를 가져온 것인지 — 우리가 돌린 게 아니면 true
 * @param language   감지 언어(ISO 639-1, 예 {@code ko}). 모르면 null
 * @param segments   구간별 전사. 없으면 빈 목록
 */
public record SttResult(String text, String engine, double duration, boolean fromSource,
                        String language, List<Segment> segments) {

    /**
     * 구간 하나 — Whisper {@code segments[]} 의 항목.
     *
     * <p>Whisper 는 {@code tokens}·{@code avg_logprob}·{@code no_speech_prob} 등도 주지만
     * 이 서비스가 쓰지 않는다. 쓰지 않는 값을 실어 나르면 산출물만 부풀고, 하류가 그것을
     * 계약으로 오해한다. 필요해지면 그때 늘린다.</p>
     *
     * @param id    구간 번호(0부터)
     * @param start 시작 시각(초)
     * @param end   종료 시각(초)
     * @param text  이 구간의 전사
     */
    public record Segment(int id, double start, double end, String text) { }

    /** 빈 목록·null 방어 — 레코드는 넘겨받은 그대로 들고 있으므로 여기서 한 번 다듬는다. */
    public SttResult {
        segments = (segments == null) ? List.of() : List.copyOf(segments);
    }

    /**
     * 구간 없이 전문만 있는 결과 — 기존 호출부와 Mock·재사용 경로가 쓴다.
     *
     * @param durationSec 오디오 길이(초, 정수)
     */
    public SttResult(String text, String engine, int durationSec, boolean fromSource) {
        this(text, engine, durationSec, fromSource, null, List.of());
    }

    /**
     * 오디오 길이를 정수 초로 — T4·산출물 메타가 정수로 남긴다.
     *
     * <p>버림이 아니라 반올림이다. 29.6초를 29초로 적으면 합계가 계속 짧아진다.</p>
     */
    public int durationSec() {
        return (int) Math.round(duration);
    }

    public int charCount() {
        return text == null ? 0 : text.length();
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }

    public boolean hasSegments() {
        return segments != null && !segments.isEmpty();
    }

    /**
     * 비식별된 텍스트로 바꾼 결과 — <b>구간 텍스트도 같이 바꾼다.</b>
     *
     * <p>전문만 바꾸고 구간을 두면 산출물 {@code transcript.segments[].text} 에 원문(성명·주민번호)이 그대로 남는다.
     * 마스킹은 글자를 제자리에서 바꾸므로 줄 구성이 유지된다 — 줄 수가 구간 수와 같으면 줄마다 구간에 되돌려 넣고,
     * 다르면(엔진이 줄을 합쳤다) 구간 텍스트를 비운다. 시각 정보는 남긴다.</p>
     *
     * <p>텍스트가 같으면(단순 전달) 자기 자신을 돌려준다.</p>
     */
    public SttResult withText(String replaced) {
        if (replaced == null || replaced.equals(text)) {
            return this;
        }
        List<Segment> segs = segments;
        if (hasSegments()) {
            String[] lines = replaced.split("\\R", -1);
            java.util.List<Segment> out = new java.util.ArrayList<>(segments.size());
            boolean aligned = lines.length == segments.size();
            for (int i = 0; i < segments.size(); i++) {
                Segment s = segments.get(i);
                out.add(new Segment(s.id(), s.start(), s.end(), aligned ? lines[i] : ""));
            }
            segs = out;
        }
        return new SttResult(replaced, engine, duration, fromSource, language, segs);
    }
}
