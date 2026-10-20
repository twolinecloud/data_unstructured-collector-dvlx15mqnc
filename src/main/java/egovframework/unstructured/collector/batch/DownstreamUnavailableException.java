package egovframework.unstructured.collector.batch;

/**
 * 관리 화면 요청을 하위 시스템(로그 컬렉터 · 에이전트 커넥터 보류 장부)을 못 읽어 처리하지 못함 — <b>503</b>.
 *
 * <p>요청이 틀린 것이 아니라 지금 판단할 근거를 못 읽은 것이다. 예전에는 400 으로 냈다 — 로그 컬렉터 장애가
 * "선택한 배치를 찾을 수 없습니다"(400)로 보여 사용자는 데이터 문제로 알았다(2026-10-20 HTTP 에러 점검).</p>
 *
 * <p>{@link UserFacingRejection} 과 같은 규칙 — {@link #getMessage()} 는 화면에 그대로 뜨는 짧은 안내(공백 포함
 * {@value UserFacingRejection#MAX_MESSAGE_LEN}자 이내 · 내부 값 없음), 하위 주소 · 원인은 {@link #detail()} 로 서버 로그에만.
 * 처리 안 됨이 확실하므로(계획 단계에서 끊음) 화면은 "잠시 후 다시 시도" 하면 된다.</p>
 */
public class DownstreamUnavailableException extends RuntimeException {

    private final String detail;

    /**
     * @param message 화면 안내 — 공백 포함 60자 이내, 내부 값 없이
     * @param detail  서버 로그용 — 하위 주소 · 원인
     */
    public DownstreamUnavailableException(String message, String detail) {
        super(message);
        this.detail = detail;
    }

    /** 서버 로그용 자세한 사유. */
    public String detail() {
        return detail;
    }
}
