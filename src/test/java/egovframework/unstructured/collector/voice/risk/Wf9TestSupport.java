package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.voice.stt.genon.GenonSttProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** WF9 시험 공용 — 설정 빌더 · 움직이는 시계 · 가상 대상 · 가짜 서버 응답. 계정 · 키는 모두 시험용 가짜 값이다. */
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
        String portalBaseUrl = "http://127.0.0.1:1";
        String portalUserId = "wf9-test-user";
        String portalPassword = "wf9-test-pass";
        String portalEndpoint = "pt6i_10rw_jsd9";
        int portalTimeoutSec = 10;
        List<String> contentFields = List.of("content", "description", "summary", "text");
        List<String> riskClassFields = List.of("riskClassCode", "category", "type");

        RiskWf9Properties build() {
            return new RiskWf9Properties(enabled, spoolDir, skipTestRuns, maxPending, maxSpoolMb, analyzeMaxSec, maxRetries,
                    retryBaseSec, maxAgeHours, dailyLimit, 30, 60, deidPath, deidTimeoutSec, portalMode, portalBaseUrl,
                    portalUserId, portalPassword, portalEndpoint, 2, portalTimeoutSec, contentFields, riskClassFields);
        }
    }

    /** 제논 STT 설정 — 기본 한도(20 MiB · 600초). */
    static GenonSttProperties genon(GenonSttProperties.Mode mode, String baseUrl, long maxBytes, int maxDurationSec) {
        return new GenonSttProperties(mode, baseUrl, "test-key", "2026-12-11", 10, 5, maxBytes, maxDurationSec, 1, 30, 3,
                300, 600, 15);
    }

    static GenonSttProperties genon() {
        return genon(GenonSttProperties.Mode.MOCK, "http://127.0.0.1:1", 20_971_520L, 600);
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
