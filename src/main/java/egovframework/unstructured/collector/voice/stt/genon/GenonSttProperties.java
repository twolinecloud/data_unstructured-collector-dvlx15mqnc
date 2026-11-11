package egovframework.unstructured.collector.voice.stt.genon;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 제논(GenOS) 음성 STT — 외부망 호출 가이드(2026-11-05) 기준 설정.
 *
 * <p><b>한도는 외부망 시험 키의 것이다</b>(1007 전용 · 동시 1건 · 20 MiB · 600초). 가이드는 Code Serving 1007 을
 * 온프레미스 NPU 호출점으로 가정해 연동하라고 하므로 같은 규격이 안으로 들어오면 주소와 한도만 바꾸면 된다 —
 * 그래서 숫자를 코드에 박지 않고 설정으로 뺐다.</p>
 *
 * <p><b>키는 레포에 적지 않는다</b> — {@code GENON_STT_API_KEY} 로만 받는다. 비어 있으면 REST 호출이
 * 시작 전에 거절된다(MOCK 은 키 없이 돈다).</p>
 *
 * @param mode              MOCK(네트워크 없음 · 제논 모양 응답) / REST(실호출). 기동 초기값 — 시뮬레이터가 재시작 없이 바꾼다
 * @param baseUrl           {@code https://genos.genon.ai/api/gateway/code_serving/1007/8690} — 런타임에 바꾸지 않는다(토큰이 엉뚱한 곳으로 가지 않게)
 * @param apiKey            Bearer 토큰
 * @param keyExpiresOn      키 만료일(가이드 2026-12-11) — 화면이 남은 날을 보여 준다. 모르면 비운다
 * @param timeoutSec        요청 하나의 응답 상한(가이드 연동 기준 60초)
 * @param connectTimeoutSec 연결 상한
 * @param maxBytes          파일 크기 상한(20 MiB = 20,971,520 B). 넘으면 보내지 않는다
 * @param maxDurationSec    재생 시간 상한(600초). 넘으면 보내지 않는다
 * @param maxConcurrency    동시 호출 수(키 한도 1). 이 프로세스 안에서만 지킨다 — 같은 키를 쓰는 다른 곳은 못 막는다
 * @param queueTimeoutSec   차례를 기다리는 상한. 넘으면 그 건은 실패로 남는다
 * @param busyRetries       제논이 '동시 진행 한도' 로 거절했을 때 다시 보내는 횟수
 * @param busyRetryMaxWaitMs 다시 보내기 전 기다리는 상한 — 제논이 주는 retry_after_ms 를 따르되 이 값을 넘기지 않는다
 * @param chunkMaxSec       나눠 보낼 때 조각 하나의 상한(초) — 재생 시간이 {@code maxDurationSec} 를 넘는 PCM WAV 만 나눈다.
 *                          기본은 한도와 같은 600초(20분 → 600 × 2 · 30분 → 600 × 3). 제논이 딱 600초를 거절하면 낮춘다
 * @param chunkSearchSec    조각 경계를 고른 자리에서 앞뒤로 옮겨 볼 폭(초) — 이 안에서 가장 조용한 20ms 에서 자른다
 */
@ConfigurationProperties(prefix = "genon.stt")
public record GenonSttProperties(
        @DefaultValue("MOCK") Mode mode,
        @DefaultValue("https://genos.genon.ai/api/gateway/code_serving/1007/8690") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String keyExpiresOn,
        @DefaultValue("60") int timeoutSec,
        @DefaultValue("10") int connectTimeoutSec,
        @DefaultValue("20971520") long maxBytes,
        @DefaultValue("600") int maxDurationSec,
        @DefaultValue("1") int maxConcurrency,
        @DefaultValue("180") int queueTimeoutSec,
        @DefaultValue("3") int busyRetries,
        @DefaultValue("5000") long busyRetryMaxWaitMs,
        @DefaultValue("600") int chunkMaxSec,
        @DefaultValue("15") int chunkSearchSec
) {

    /** MOCK — 네트워크 없이 제논과 같은 모양을 돌려준다 · REST — 실제로 부른다. */
    public enum Mode { MOCK, REST }
}
