package egovframework.unstructured.collector.common.health;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.config.DbKindDetector;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 연계 5종 헬스체크 — <b>화면 대신 서버가 찔러 본다.</b>
 *
 * <p><b>왜 서버가 대신 도는가</b>: 배지가 브라우저에서 직접 각 시스템을 부르면 오리진이 달라
 * CORS 에 걸리고, K8s 서비스명(<code>borami-xvarm-broker-1joiuorqhl</code>)은 애초에 브라우저가
 * 풀 수 없는 이름이다. 수집기는 그 다섯을 모두 볼 수 있는 자리에 있으므로 한 번에 묶어 내려준다.</p>
 *
 * <p><b>값은 캐시한다</b>: 화면이 1분마다 부르지만 배치·시나리오 중에는 멈춘다. 그래도 여러 탭이
 * 열려 있거나 [연결 새로고침] 을 연타하면 같은 순간에 여러 번 붙을 수 있다. 짧은 캐시
 * ({@link #CACHE_MS})로 그 몰림만 걷어 낸다 — 시험 리소스(DB 커넥션·포트)에 대한 간섭을
 * 줄이는 것이 이 기능의 전제이기 때문이다. {@code force=true} 는 캐시를 무시한다.</p>
 */
@Log4j2
@Service
public class HealthProbeService {

    /** 같은 결과를 이 시간 동안 재사용한다 — 연타·다중 탭이 겹쳐 붙는 것만 막는 짧은 창. */
    private static final long CACHE_MS = 3_000L;

    /** 배지 상태 — 화면의 초록/빨강/회색에 그대로 대응한다. */
    public enum State { UP, DOWN, OFF, UNKNOWN }

    private final DbKindDetector db;
    private final egovframework.unstructured.collector.common.broker.RestXvarmBrokerClient restBroker;
    private final egovframework.unstructured.collector.common.broker.XvarmBrokerClient broker;
    private final egovframework.unstructured.collector.common.logging.LogCollectorClient logCollector;
    private final egovframework.unstructured.collector.common.transfer.ZenonClient zenon;
    private final egovframework.unstructured.collector.common.transfer.ZenonProperties zenonProps;
    private final VoiceModeState modeState;
    private final VoiceProperties props;
    private final VoiceDirState dirs;
    private final RestTemplate rest;

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public HealthProbeService(DbKindDetector db,
                              egovframework.unstructured.collector.common.broker.RestXvarmBrokerClient restBroker,
                              egovframework.unstructured.collector.common.broker.XvarmBrokerClient broker,
                              egovframework.unstructured.collector.common.logging.LogCollectorClient logCollector,
                              egovframework.unstructured.collector.common.transfer.ZenonClient zenon,
                              egovframework.unstructured.collector.common.transfer.ZenonProperties zenonProps,
                              VoiceModeState modeState, VoiceProperties props, VoiceDirState dirs,
                              RestTemplate voiceRestTemplate) {
        this.db = db;
        this.restBroker = restBroker;
        this.broker = broker;
        this.logCollector = logCollector;
        this.zenon = zenon;
        this.zenonProps = zenonProps;
        this.modeState = modeState;
        this.props = props;
        this.dirs = dirs;
        this.rest = voiceRestTemplate;
    }

    /** 다섯 배지 한 장. {@code force} 면 캐시를 무시하고 지금 붙어 본다. */
    public Map<String, Object> health(boolean force) {
        long now = System.currentTimeMillis();
        Map<String, Object> hit = cached;
        if (!force && hit != null && now - cachedAt < CACHE_MS) {
            Map<String, Object> copy = new LinkedHashMap<>(hit);
            copy.put("cached", true);
            return copy;
        }
        List<Map<String, Object>> items = List.of(probeDb(), probeEsb(), probeBroker(), probeLogCollector(), probeZenon());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkedAt", java.time.LocalDateTime.now().withNano(0).toString());
        out.put("cached", false);
        out.put("items", items);
        out.put("down", items.stream().filter(m -> State.DOWN.name().equals(m.get("state"))).count());
        cached = out;
        cachedAt = now;
        return out;
    }

    // ── 5종 ───────────────────────────────────────────────────────────────

    /** DB — 지금 조회 모드가 가리키는 쪽(로컬 H2 / 개발계 PostgreSQL)에 실제로 붙어 본다. */
    private Map<String, Object> probeDb() {
        Map<String, Object> p = db.probe();
        boolean up = Boolean.TRUE.equals(p.get("reachable"));
        return badge("db", "DB", up ? State.UP : State.DOWN,
                String.valueOf(p.getOrDefault("label", "")),
                String.valueOf(p.getOrDefault("url", "")),
                up ? String.valueOf(p.getOrDefault("product", "연결됨")) : String.valueOf(p.getOrDefault("error", "")));
    }

    /**
     * ESB — 주소가 설정돼 있을 때만 붙어 본다.
     *
     * <p>접견·전화 모두 ESB 를 타지 않는 모드(MOCK)로 돌 수 있고, 로컬·개발계에는 ESB 자체가 없다.
     * 그럴 때 빨간불을 켜면 "고쳐야 할 것" 처럼 보인다 — 쓰지 않는 중이면 회색(OFF)이다.</p>
     */
    private Map<String, Object> probeEsb() {
        String base = props.source().esbBaseUrl();
        boolean usingEsb = modeState.source() == VoiceProperties.SourceMode.ESB_HTTP2DB
                || modeState.phone() == VoiceProperties.PhoneMode.ESB;
        if (!StringUtils.hasText(base)) {
            return badge("esb", "ESB", usingEsb ? State.DOWN : State.OFF, "", "(주소 미설정)",
                    usingEsb ? "ESB 모드인데 주소가 비어 있습니다 — voice.source.esb-base-url 확인"
                             : "쓰지 않는 중(조회·전화 모두 ESB 모드가 아님)");
        }
        Probe r = ping(base);
        return badge("esb", "ESB", r.ok ? State.UP : State.DOWN, "", base, r.detail);
    }

    /** 브로커 — 붙는지에 더해 <b>출력 경로가 우리 수신 폴더와 같은지</b>까지 본다. */
    private Map<String, Object> probeBroker() {
        if (modeState.broker() == VoiceProperties.BrokerMode.MOCK) {
            return badge("broker", "브로커", State.OFF, "MOCK", "(내부 Mock)",
                    "Mock 브로커 — 외부 연결을 쓰지 않습니다");
        }
        Map<String, Object> p = restBroker.probe();
        boolean up = Boolean.TRUE.equals(p.get("reachable"));
        String base = String.valueOf(p.getOrDefault("baseUrl", ""));
        if (!up) {
            return badge("broker", "브로커", State.DOWN, broker.mode(), base,
                    String.valueOf(p.getOrDefault("error", "응답 없음")));
        }
        String brokerDir = (String) p.get("brokerOutputDir");
        String meetDir = dirs.receiveMeet();
        boolean same = brokerDir != null && sameDir(brokerDir, meetDir);
        return badge("broker", "브로커", same ? State.UP : State.DOWN, broker.mode(), base,
                same ? "연결됨 · 출력 경로 일치"
                     : "붙었지만 출력 경로가 다릅니다 — 브로커 " + brokerDir + " / 수집기 " + meetDir);
    }

    /** 로그 컬렉터 — 꺼져 있으면 회색이다. 끈 것은 고장이 아니다. */
    private Map<String, Object> probeLogCollector() {
        if (!logCollector.isEnabled()) {
            return badge("logCollector", "로그 컬렉터", State.OFF, "", logCollector.baseUrl(),
                    "미연동(log-collector.enabled=false) — 로그 테이블 적재를 건너뜁니다");
        }
        String base = logCollector.baseUrl();
        if (!StringUtils.hasText(base)) {
            return badge("logCollector", "로그 컬렉터", State.DOWN, "", "(주소 미설정)", "주소가 비어 있습니다");
        }
        Probe r = ping(base);
        return badge("logCollector", "로그 컬렉터", r.ok ? State.UP : State.DOWN, "", base, r.detail);
    }

    /**
     * 제논(Zenon) — 파이프라인 3단계 SEND 의 전송 대상. MOCK 이면 수집기 내장 수신기로 보낸다(네트워크 없음 · 같은 양식).
     * REST 면 {@code {base-url}/health} 를 부른다(제논 목 서버 {@code tools/zenon-mock} 도 이 경로를 연다).
     */
    private Map<String, Object> probeZenon() {
        if (zenonProps.mode() == egovframework.unstructured.collector.common.transfer.ZenonProperties.Mode.MOCK) {
            return badge("zenon", "제논 전송", State.UP, "MOCK", zenon.endpoint(), "MOCK — 수집기 내장 수신기(data-collector 양식 · 장부 · 장애 흉내)");
        }
        String base = zenonProps.baseUrl();
        if (!StringUtils.hasText(base)) {
            return badge("zenon", "제논 전송", State.DOWN, "REST", "(주소 미설정)", "zenon.base-url 이 비어 있습니다");
        }
        Probe r = ping(base.replaceAll("/+$", "") + "/health");
        return badge("zenon", "제논 전송", r.ok ? State.UP : State.DOWN, "REST", zenon.endpoint(), r.detail);
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    private record Probe(boolean ok, String detail) { }

    /**
     * 살아 있는지만 본다 — <b>4xx 도 살아 있는 것</b>이다.
     *
     * <p>헬스 경로를 모르는 상대가 많아 루트를 친다. 404/401 이 오면 "그 자리에 서버가 있고
     * 응답한다" 는 뜻이라 연결로 본다. 못 붙는 것(ConnectException·타임아웃)만 빨간불이다.</p>
     */
    private Probe ping(String base) {
        String url = base.replaceAll("/+$", "") + "/actuator/health";
        try {
            HttpStatusCode sc = rest.exchange(url, HttpMethod.GET, null, String.class).getStatusCode();
            return new Probe(true, "연결됨 (HTTP " + sc.value() + ")");
        } catch (org.springframework.web.client.HttpStatusCodeException http) {
            return new Probe(true, "연결됨 (HTTP " + http.getStatusCode().value() + " — 헬스 경로 없음)");
        } catch (Exception e) {
            return new Probe(false, rootMessage(e));
        }
    }

    private static Map<String, Object> badge(String key, String label, State state,
                                             String mode, String target, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("state", state.name());
        m.put("mode", mode == null ? "" : mode);
        m.put("target", target == null ? "" : target);
        m.put("detail", detail == null ? "" : detail);
        return m;
    }

    private static boolean sameDir(String a, String b) {
        try {
            return Path.of(a).toAbsolutePath().normalize()
                    .equals(Path.of(b).toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m.replaceAll("\\s+", " ").trim();
    }
}
