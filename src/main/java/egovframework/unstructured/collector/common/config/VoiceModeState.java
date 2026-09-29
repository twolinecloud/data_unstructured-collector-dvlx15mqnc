package egovframework.unstructured.collector.common.config;

import egovframework.unstructured.collector.common.config.VoiceProperties.BrokerMode;
import egovframework.unstructured.collector.common.config.VoiceProperties.DecryptMode;
import egovframework.unstructured.collector.common.config.VoiceProperties.PhoneMode;
import egovframework.unstructured.collector.common.config.VoiceProperties.SourceMode;
import egovframework.unstructured.collector.common.config.VoiceProperties.SttMode;
import egovframework.unstructured.collector.common.config.VoiceProperties.XvarmMode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 처리 구간 스위치의 <b>런타임 상태</b>. 서버를 재시작하지 않고 MOCK ↔ REAL 을 바꾼다.
 *
 * <p><b>왜 설정(@ConditionalOnProperty)으로 두지 않았나</b>: 조건부 빈은 기동 시점에 한 번
 * 결정되고 끝난다. 그런데 이 과제는 외부 의존 네 가지가 서로 다른 시점에 열리고,
 * 시연 중에도 "이건 아직 Mock, 이건 실물" 을 바꿔 가며 보여줘야 한다. 재시작마다
 * 시연 흐름이 끊기면 곤란하므로 상태를 런타임으로 뺐다.</p>
 *
 * <p><b>기동 초기값은 여전히 설정에서 온다</b>({@link VoiceProperties}). 런타임 변경은
 * 이 프로세스에만 남고 재기동하면 설정값으로 돌아간다 — 운영 배포에서 실수로 바꾼 모드가
 * 영구히 남지 않게 하려는 것이다.</p>
 *
 * <p>모든 필드가 {@code volatile} 이다. 배치 스레드와 API 스레드가 동시에 읽고 쓴다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class VoiceModeState {

    private final VoiceProperties props;
    private final DeployEnvPreset env;

    private volatile SourceMode source;
    private volatile BrokerMode broker;
    /**
     * 전화 파일 연계 — 접견의 브로커 스위치와 <b>별개</b>다.
     * 전화는 XVARM 을 타지 않고 ESB 전화 전용 프로바이더를 쓴다.
     */
    private volatile PhoneMode phone;
    private volatile DecryptMode decrypt;
    private volatile SttMode stt;
    /**
     * XVARM 연동 — 개발계 DB(DIRECT_JDBC)에서 접견 4단 조인의 공통파일·XVARM 테이블을 어디서 볼지.
     * MOCK_DEV 는 우리가 개발계 DB 에 만들어 시딩한 테이블, REAL 은 설정된 실제 테이블.
     */
    private volatile XvarmMode xvarm;

    /**
     * XVARM 브로커 주소 — <b>모드가 아니라 주소지만 같은 곳에서 관리한다.</b>
     *
     * <p>브로커가 사는 곳이 환경마다 다르다(개발계는 K8s 서비스명, 로컬은 localhost).
     * 모드만 REST 로 올리고 주소를 안 바꾸면 배치가 전건 실패하는데, 그러면 모드 스위치만
     * 런타임으로 두는 의미가 없다. 주소도 같이 바꿀 수 있어야 스위치가 제 역할을 한다.</p>
     */
    private volatile String brokerBaseUrl;

    @PostConstruct
    void init() {
        this.source = props.source().mode();
        this.broker = props.broker().mode();
        this.phone = props.phone().mode();
        this.decrypt = props.decrypt().mode();
        this.stt = props.stt().mode();
        this.xvarm = props.source().xvarmMode();
        // 설정(voice.broker.base-url)이 비면 환경 프리셋 — 로컬 localhost:8082 · K8s 브로커 서비스명
        this.brokerBaseUrl = nullToBlank(env.brokerBaseUrl());
        log.info("[Mode] 초기 모드 — 접견트랙(source={} xvarm={} broker={}) · 전화트랙(source={} phone={}) · 공통(decrypt={} stt={})",
                source, xvarm, broker, source, phone, decrypt, stt);
    }

    public SourceMode source() {
        return source;
    }

    public BrokerMode broker() {
        return broker;
    }

    /** 전화 파일 연계 모드. 브로커와 별개다. */
    public PhoneMode phone() {
        return phone;
    }

    public DecryptMode decrypt() {
        return decrypt;
    }

    public SttMode stt() {
        return stt;
    }

    /** XVARM 연동 모드 — DIRECT_JDBC 에서만 의미가 있다. */
    public XvarmMode xvarm() {
        return xvarm;
    }

    /** 현재 브로커 주소. 비어 있을 수 있다(REST 모드에서는 그 상태로 호출하면 실패한다). */
    public String brokerBaseUrl() {
        return brokerBaseUrl;
    }

    /**
     * 브로커 주소를 바꾼다. 빈 값은 "주소 없음"으로 허용한다 — 일부러 지우고
     * 실패를 재현해 볼 수 있어야 한다.
     *
     * @return 바뀌기 전 값
     * @throws IllegalArgumentException http/https 로 시작하지 않는 값
     */
    public String setBrokerBaseUrl(String value) {
        String v = (value == null) ? "" : value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);   // 뒤 슬래시는 경로를 붙일 때 이중이 된다
        }
        if (!v.isEmpty() && !(v.startsWith("http://") || v.startsWith("https://"))) {
            throw new IllegalArgumentException(
                    "브로커 주소는 http:// 또는 https:// 로 시작해야 한다: " + value);
        }
        String before = brokerBaseUrl;
        brokerBaseUrl = v;
        log.info("[Mode] 브로커 주소 변경 — {} → {}",
                before.isEmpty() ? "(없음)" : before, v.isEmpty() ? "(없음)" : v);
        return before;
    }

    private static String nullToBlank(String s) {
        return s == null ? "" : s.trim();
    }

    /** 현재 모드 전체(조회·화면 표시용). */
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("source", source.name());
        m.put("broker", broker.name());
        m.put("phone", phone.name());
        m.put("decrypt", decrypt.name());
        m.put("stt", stt.name());
        m.put("xvarm", xvarm.name());
        return m;
    }

    /** 기동 시 설정값(되돌리기 기준). */
    public Map<String, String> configured() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("source", props.source().mode().name());
        m.put("broker", props.broker().mode().name());
        m.put("phone", props.phone().mode().name());
        m.put("decrypt", props.decrypt().mode().name());
        m.put("stt", props.stt().mode().name());
        m.put("xvarm", props.source().xvarmMode().name());
        return m;
    }

    /**
     * 스위치 하나를 바꾼다.
     *
     * @param name  source / broker / phone / decrypt / stt
     * @param value 해당 스위치가 허용하는 값
     * @throws IllegalArgumentException 이름이나 값이 잘못된 경우
     */
    public String set(String name, String value) {
        String key = name == null ? "" : name.trim().toLowerCase();
        String val = value == null ? "" : value.trim().toUpperCase();
        String before;
        switch (key) {
            case "source" -> {
                before = source.name();
                source = parse(SourceMode.class, val, key);
            }
            case "broker" -> {
                before = broker.name();
                broker = parse(BrokerMode.class, val, key);
            }
            case "phone" -> {
                before = phone.name();
                phone = parse(PhoneMode.class, val, key);
            }
            case "decrypt" -> {
                before = decrypt.name();
                decrypt = parse(DecryptMode.class, val, key);
            }
            case "stt" -> {
                before = stt.name();
                stt = parse(SttMode.class, val, key);
            }
            case "xvarm" -> {
                before = xvarm.name();
                xvarm = parse(XvarmMode.class, val, key);
            }
            default -> throw new IllegalArgumentException(
                    "알 수 없는 스위치: " + name + " (source/broker/phone/decrypt/stt/xvarm 중 하나여야 한다)");
        }
        log.info("[Mode] {} 변경 — {} → {}", key, before, val);
        return before;
    }

    /** 기동 시 설정값으로 되돌린다. */
    public void resetToConfigured() {
        init();
    }

    /** 기동 시 브로커 주소(되돌리기 기준) — 설정이 비면 환경 프리셋 값. */
    public String configuredBrokerBaseUrl() {
        return nullToBlank(env.brokerBaseUrl());
    }

    private <E extends Enum<E>> E parse(Class<E> type, String value, String key) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            StringBuilder allowed = new StringBuilder();
            for (E c : type.getEnumConstants()) {
                allowed.append(allowed.isEmpty() ? "" : ", ").append(c.name());
            }
            throw new IllegalArgumentException(
                    "%s 에 쓸 수 없는 값: %s (허용: %s)".formatted(key, value, allowed));
        }
    }
}
