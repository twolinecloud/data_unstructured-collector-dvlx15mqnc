package egovframework.unstructured.collector.voice.stt.genon;

import java.util.Map;

/**
 * 제논 호출 실패 — 종류({@link Kind})별로 시뮬레이터 상태 코드와 배치 실패 분류가 갈린다.
 *
 * <p><b>메시지 낱말이 분류를 정한다</b> — 배치 실패 사유는 {@code errStackOf} 가 본문 낱말로 C12 코드를 붙인다
 * ('타임아웃' → TIMEOUT, '연결' → CONNECTION, 나머지 DATA). 그래서 응답 타임아웃 · 차례 대기 타임아웃 메시지에는
 * '타임아웃' 을, 붙지 못한 것에는 '연결' 을 넣고, 그 밖의 종류에는 두 낱말을 쓰지 않는다.</p>
 */
public class GenonSttException extends RuntimeException {

    public enum Kind {
        /** 제논이 입력을 거절(400 계열) — 형식 · 크기 · 길이 · 요청 형식. */
        REJECTED,
        /** 401 · 403 — 토큰 없음 · 불일치 · 만료. */
        AUTH,
        /** 제논 '동시 진행 한도' — 다시 보내도 계속 사용 중. */
        BUSY,
        /** 5xx · 알 수 없는 응답. */
        UPSTREAM,
        /** 응답 타임아웃. */
        TIMEOUT,
        /** 붙지 못함 — DNS · TLS · 연결 거부 · 연결 타임아웃. */
        CONNECTION,
        /** 이 프로세스 안의 차례 대기가 상한을 넘음(동시 1건). */
        QUEUE_TIMEOUT,
        /** 설정 문제 — 키 없음 등. 보내지 않았다. */
        CONFIG
    }

    /** 제논 오류 코드 → 한 줄 풀이(2026-11-06 실측 기준). */
    static final Map<String, String> HINTS = Map.ofEntries(
            Map.entry("invalid_request", "요청 형식 오류 — audio 필드 · 필수 항목 확인"),
            Map.entry("unsupported_audio_format", "지원하지 않는 형식 — WAV · MP3 · M4A 만"),
            Map.entry("audio_size_limit_exceeded", "파일 크기 20MB 초과"),
            Map.entry("audio_duration_limit_exceeded", "재생 시간 600초 초과"),
            Map.entry("signal_analysis_incomplete", "제논 신호 분석 미완료 — 서버 측 처리 실패"),
            Map.entry(GenonSttClient.BUSY_CODE, "동시 진행 한도(1건) — 잠시 후 다시"),
            Map.entry("Bearer token required", "토큰 없음 — GENON_STT_API_KEY 확인"),
            Map.entry("Invalid token", "토큰 불일치 또는 만료"));

    private final Kind kind;
    private final int httpStatus;
    private final String errorCode;
    private final String upstreamBody;
    private final long elapsedMs;
    private final int attempts;

    public GenonSttException(Kind kind, String message) {
        this(kind, message, 0, null, null, 0, 0, null);
    }

    public GenonSttException(Kind kind, String message, int httpStatus, String errorCode, String upstreamBody,
                             long elapsedMs, int attempts, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.upstreamBody = upstreamBody;
        this.elapsedMs = elapsedMs;
        this.attempts = attempts;
    }

    public Kind kind() {
        return kind;
    }

    /** 제논이 준 HTTP 상태. 받지 못했으면 0. */
    public int httpStatus() {
        return httpStatus;
    }

    /** 제논 오류 코드(invalid_request · 13040001 · Invalid token …). 없으면 null. */
    public String errorCode() {
        return errorCode;
    }

    /** 오류 코드 풀이. 모르는 코드면 null. */
    public String hint() {
        return errorCode == null ? null : HINTS.get(errorCode);
    }

    /** 제논 오류 본문(앞부분). 오류 본문이라 전사 텍스트는 들어 있지 않다. 없으면 null. */
    public String upstreamBody() {
        return upstreamBody;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public int attempts() {
        return attempts;
    }
}
