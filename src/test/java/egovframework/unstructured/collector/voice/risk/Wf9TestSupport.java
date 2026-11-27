package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** WF9 시험 공용 — 설정 빌더 · 움직이는 시계 · 가상 대상 · 가짜 서버 응답. AuthKey · 키는 모두 시험용 가짜 값이다. */
final class Wf9TestSupport {

    static final ObjectMapper OM = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Wf9TestSupport() {
    }

    /** 설정 빌더 — 시험이 필요한 칸만 바꾼다. */
    static final class Props {
        boolean enabled = true;
        String spoolDir = "";
        boolean skipTestRuns = true;
        int maxPending = 300;
        long maxSpoolMb = 4096;
        int analyzeMaxSec = 600;
        int maxRetries = 3;
        int retryBaseSec = 300;
        int maxAgeHours = 48;
        int dailyLimit = 0;
        String deidPath = "/api/v1/deid/speech-results";
        int deidTimeoutSec = 10;
        RiskWf9Properties.PortalMode portalMode = RiskWf9Properties.PortalMode.REST;
        String servingBaseUrl = "http://127.0.0.1:1/api/gateway/code_serving";
        String servingId = "709";
        String servingRev = "10721";
        String authKey = "wf9-test-key";
        String authKeyExpiresOn = "2026-12-11";
        String portalEndpoint = "pt6i_10rw_jsd9";
        int portalTimeoutSec = 10;
        List<String> contentFields = List.of("content", "description", "summary", "text");
        List<String> riskClassFields = List.of("riskClassCode", "category", "type");
        boolean afterSend = true;
        int sendWaitMaxHours = 168;

        RiskWf9Properties build() {
            return new RiskWf9Properties(enabled, spoolDir, skipTestRuns, maxPending, maxSpoolMb, analyzeMaxSec, maxRetries,
                    retryBaseSec, maxAgeHours, dailyLimit, 30, 60, deidPath, deidTimeoutSec, portalMode, servingBaseUrl,
                    servingId, servingRev, authKey, authKeyExpiresOn, portalEndpoint, 2, portalTimeoutSec, contentFields, riskClassFields,
                    afterSend, sendWaitMaxHours);
        }
    }

    /** 가린(마스킹) 전문 · 구간 — 본 배치 DEIDENT 단계가 돌려주는 꼴. 토큰이 아니라 별표로 가렸다. 내용은 지어낸 것이다. */
    static final String MASKED_1 = "***이 ***-****-**** 로 연락하래요";
    static final String MASKED_2 = "다음 주에 면회 갈게요";

    /** 구간 둘(0~2초 · 2.5~4초) · 길이 4.2초. */
    static SttResult masked() {
        return new SttResult(MASKED_1 + "\n" + MASKED_2, "MOCK", 4.2, false, "ko", List.of(
                new SttResult.Segment(0, 0.0, 2.0, MASKED_1),
                new SttResult.Segment(1, 2.5, 4.0, MASKED_2)));
    }

    /** 손으로 움직이는 시계. */
    static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(Instant start) {
            this.millis = new AtomicLong(start.toEpochMilli());
        }

        void advanceSec(long sec) {
            millis.addAndGet(sec * 1000);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    /** 가상 접견 대상 — 교정번호 · 녹음 ID 는 지어낸 값이다. */
    static VoiceTarget meet(String recFileId) {
        return new VoiceTarget(VoiceKind.MEET, "2026000123", recFileId, "DOC-1", "FK-1", false, null, "/v/m", "m.m4a", null,
                LocalDateTime.of(2026, 11, 16, 10, 30));
    }

    static VoiceTarget phone(String recFileId) {
        return new VoiceTarget(VoiceKind.PHONE, "2026000456", recFileId, null, null, false, "K", "/v/p", "p.wav", null,
                LocalDateTime.of(2026, 11, 16, 14, 5, 7));
    }

    static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        try {
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                ex.getResponseBody().write(b);
            }
        } finally {
            ex.close();
        }
    }

    static void json(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "application/json", body);
    }

    /** SSE 사건 한 줄 — {@code data: {"event":…,"data":…}} + 빈 줄. data 는 JSON 으로 직렬화한다. */
    static String sse(String event, Object data) {
        try {
            return "data: " + OM.writeValueAsString(java.util.Map.of("event", event, "data", data)) + "\n\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
