package egovframework.unstructured.collector.common.model;

import java.util.Locale;
import java.util.Optional;

/**
 * 비정형 파이프라인 단계 — 공통코드 C05 중 비정형 체인 <b>4단계</b>
 * {@code [수집 COLLECT] → [정제/분석 ANALYZE] → [비식별 DEIDENT] → [적재/전송 SEND]}.
 *
 * <p>로그 컬렉터의 비정형 체인({@code StepType.CHAINS — UNSTRUCTURED: COLLECT · ANALYZE · DEIDENT · SEND})과 같다. 순번은 컬렉터가
 * 체인 위치로 다시 정하지만, 같은 값을 보내 둔다. C05 의 나머지 코드는 정형 · 외부 연계 체인의 것이라 비정형은 쓰지 않는다 —
 * 들어오면 {@link #of} 가 거절한다.</p>
 *
 * <p>DEIDENT 는 음성만 쓴다(2026-10-13 — 에이전트 커넥터 {@code /api/v1/pipeline/deident/voice} 를 건마다 부른다). 이미지는 텍스트가
 * 없어 그 칸을 건너뛴다 — 체인 위치는 컬렉터가 정하므로 SEND 순번은 같다.</p>
 *
 * <table>
 *   <tr><th>단계</th><th>음성(접견 · 전화)</th><th>수용자 이미지</th></tr>
 *   <tr><td>COLLECT</td><td>대상 조회 · 브로커 추출 / 전화 연계 · 수신 · 복호화</td><td>최신 사진 조회 · FILEKEY 추출 · 브로커 수신</td></tr>
 *   <tr><td>ANALYZE</td><td>STT</td><td>복호화 · 이미지 확인(매직 넘버)</td></tr>
 *   <tr><td>DEIDENT</td><td>전사 텍스트 비식별(에이전트 커넥터)</td><td>—</td></tr>
 *   <tr><td>SEND</td><td>에이전트 커넥터 전송(클라우드)</td><td>—</td></tr>
 *   <tr><td>STORE</td><td>—</td><td>내부 저장소 저장 · Admin DB 매핑</td></tr>
 * </table>
 *
 * <p><b>이미지의 마지막 단계는 STORE(C05 '저장')다</b>(2026-10-16). 이미지는 외부 클라우드로 보내지 않고 내부에만 저장한다(PL 확인) —
 * 예전엔 SEND 로 남겨 화면 · 전송 대기 목록에서 클라우드로 보낸 것처럼 보였다. STORE 는 C05 · 로그 컬렉터 {@code StepType} 에 원래 있는 코드라
 * 따로 등록할 것이 없다(로그 컬렉터 비정형 체인 밖이라 순번은 컬렉터가 체인 뒤로 매긴다).</p>
 */
public enum StepType {

    COLLECT((short) 1, "수집"),
    ANALYZE((short) 2, "정제/분석"),
    DEIDENT((short) 3, "비식별"),
    SEND((short) 4, "적재/전송"),
    STORE((short) 4, "저장");

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
     * @throws IllegalArgumentException 비정형 단계(COLLECT · ANALYZE · DEIDENT · SEND · STORE)가 아닌 코드
     */
    public static StepType of(String code) {
        return parse(code).orElseThrow(() -> new IllegalArgumentException(
                "비정형 단계(C05)는 COLLECT · ANALYZE · DEIDENT · SEND · STORE 뿐이다: " + code));
    }
}
