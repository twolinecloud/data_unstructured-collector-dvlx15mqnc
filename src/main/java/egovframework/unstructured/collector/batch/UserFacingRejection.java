package egovframework.unstructured.collector.batch;

/**
 * 관리 화면에 그대로 뜨는 400 거절 — <b>화면에는 짧은 안내만, 내부 값은 서버 로그에만</b>.
 *
 * <p>admin-api 가 수집기 400 의 {@code message} 를 그대로 화면 토스트에 올린다(admin-api {@code 5a6a45b}).
 * 기획은 두 줄(공백 포함 {@value #MAX_MESSAGE_LEN}자 이내)만 보여 준다 — 토스트도 폭 420px · 15px 라 두 줄이 그쯤이다.
 * 그래서 {@link #getMessage()} 에는 사용자가 바로 할 일만 담고, targetToDtm · 실행 ID 같은 내부 값과 구체적인 사유는
 * {@link #detail()} 로 따로 들고 가 거절 핸들러가 WARN 로그에 남긴다(2026-10-15).
 * 예외 하나 — 워터마크 충돌 안내는 사용자가 정한 원문 그대로라 69자다({@code UnstructuredBatchService.watermarkMessage}).</p>
 *
 * <p>{@link IllegalArgumentException} 을 상속해 기존 400 처리 · 테스트(예외 종류)는 그대로 둔다.
 * 이 예외가 아닌 {@link IllegalArgumentException} 은 원문에 내부 값이 섞였을 수 있어 화면에는 {@link #GENERIC} 을 낸다.</p>
 */
public class UserFacingRejection extends IllegalArgumentException {

    /** 화면 문구 상한(공백 포함) — 토스트 두 줄. */
    public static final int MAX_MESSAGE_LEN = 60;

    /** 미리 정하지 않은 거절을 화면에 낼 때의 일반 안내(원문은 로그에만). */
    public static final String GENERIC = "요청 값을 확인한 뒤 다시 시도해 주세요.";

    private final String detail;

    /**
     * @param message 화면 안내 — 공백 포함 {@value #MAX_MESSAGE_LEN}자 이내, 내부 변수 · 실행 ID 없이
     * @param detail  서버 로그용 — 내부 값 · 구체적인 거절 사유
     */
    public UserFacingRejection(String message, String detail) {
        super(message);
        this.detail = detail;
    }

    /** 서버 로그용 자세한 사유. */
    public String detail() {
        return detail;
    }

    /** 화면(400 본문 {@code message})에 낼 문구 — 이 예외면 그 안내, 아니면 일반 안내. */
    public static String messageOf(IllegalArgumentException e) {
        return e instanceof UserFacingRejection ? e.getMessage() : GENERIC;
    }

    /** 로그에 남길 사유 — 이 예외면 자세한 사유, 아니면 원문. */
    public static String detailOf(IllegalArgumentException e) {
        return e instanceof UserFacingRejection r ? r.detail() : e.getMessage();
    }
}
