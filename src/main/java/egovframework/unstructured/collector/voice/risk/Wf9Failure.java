package egovframework.unstructured.collector.voice.risk;

/**
 * WF9 작업 한 건의 실패 — 어느 단계에서, 다시 하면 풀릴 수 있는지.
 *
 * <p><b>메시지에는 단계 · 상태 코드 · 오류 코드 · 예외 이름만 싣는다.</b> 전사 · 가린 문서 · WF9 응답 · 복원한 내용 ·
 * 상대 서버 본문은 싣지 않는다 — 이 메시지가 그대로 운영 로그에 남는다.</p>
 */
public class Wf9Failure extends RuntimeException {

    /** 실패한 단계. */
    public enum Stage {
        /** 스풀에서 작업을 읽지 못함(사본 · job.json). */
        SPOOL,
        /** 제논 음성 전처리 {@code audio/process}. */
        PROCESS,
        /** 커넥터 비식별 {@code /api/v1/deid/speech-results}. */
        DEID,
        /** 제논 채팅 WF9(로그인 · SSE). */
        PORTAL,
        /** WF9 응답에서 {@code {findings, reviews}} 를 읽지 못함. */
        PARSE,
        /** 로그 컬렉터 적재. */
        STORE
    }

    private final Stage stage;
    private final boolean retryable;

    private Wf9Failure(Stage stage, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.retryable = retryable;
    }

    /** 다시 하면 풀릴 수 있다(통신 · 시간 초과 · 5xx · 혼잡). */
    public static Wf9Failure retry(Stage stage, String message) {
        return new Wf9Failure(stage, true, message, null);
    }

    public static Wf9Failure retry(Stage stage, String message, Throwable cause) {
        return new Wf9Failure(stage, true, message, cause);
    }

    /** 같은 입력이면 다시 해도 같은 결과다(400 · 422 · 사전 검증 위반) — 바로 포기한다. */
    public static Wf9Failure permanent(Stage stage, String message) {
        return new Wf9Failure(stage, false, message, null);
    }

    public static Wf9Failure permanent(Stage stage, String message, Throwable cause) {
        return new Wf9Failure(stage, false, message, cause);
    }

    public Stage stage() {
        return stage;
    }

    public boolean retryable() {
        return retryable;
    }
}
