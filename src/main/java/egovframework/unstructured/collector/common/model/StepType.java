package egovframework.unstructured.collector.common.model;

import java.util.Locale;
import java.util.Optional;

/**
 * 비정형 파이프라인 단계 — 공통코드 C05 중 비정형 체인 <b>3단계</b>
 * {@code [수집 COLLECT] → [정제/분석 ANALYZE] → [적재/전송 SEND]}.
 *
 * <p>로그 컬렉터의 비정형 체인({@code StepType.CHAINS — UNSTRUCTURED: COLLECT · ANALYZE · SEND})과 같다. 순번은 컬렉터가
 * 체인 위치로 다시 정하지만, 같은 값을 보내 둔다. C05 의 나머지 코드는 정형 · 외부 연계 체인의 것이라 비정형은 쓰지 않는다 —
 * 들어오면 {@link #of} 가 거절한다.</p>
 *
 * <table>
 *   <tr><th>단계</th><th>음성(접견 · 전화)</th><th>수용자 이미지</th></tr>
 *   <tr><td>COLLECT</td><td>대상 조회 · 브로커 추출 / 전화 연계 · 수신 · 복호화</td><td>최신 사진 조회 · FILEKEY 추출 · 브로커 수신</td></tr>
 *   <tr><td>ANALYZE</td><td>STT</td><td>복호화 · 이미지 확인(매직 넘버)</td></tr>
 *   <tr><td>SEND</td><td>제논 전송</td><td>저장소 저장 · Admin DB 매핑(적재)</td></tr>
 * </table>
 */
public enum StepType {

    COLLECT((short) 1, "수집"),
    ANALYZE((short) 2, "정제/분석"),
    SEND((short) 3, "적재/전송");

    private final short seq;
    private final String label;

    StepType(short seq, String label) {
        this.seq = seq;
        this.label = label;
    }

    /** 비정형 체인 순번 — T2 {@code STEP_SEQ}. */
    public short seq() {
        return seq;
    }

    public String label() {
        return label;
    }

    /** C05 코드 → 단계. 비정형 3단계가 아니면 빈 값. */
    public static Optional<StepType> parse(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * C05 코드 → 단계.
     *
     * @throws IllegalArgumentException 비정형 3단계(COLLECT · ANALYZE · SEND)가 아닌 코드
     */
    public static StepType of(String code) {
        return parse(code).orElseThrow(() -> new IllegalArgumentException(
                "비정형 단계(C05)는 COLLECT · ANALYZE · SEND 뿐이다: " + code));
    }
}
