package egovframework.unstructured.collector.common.config;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 배포 환경 자동 감지 — <b>OS 와 active profile 로 ROOT_DIR · 브로커 URL · 로그 컬렉터 URL 기본값을 정한다.</b>
 *
 * <pre>
 *                     Windows / 로컬(local 프로파일)          Linux / 배포(K8s)
 *   ROOT_DIR           C:/k8s/unstructured_collector                 /k8s/unstructured_collector
 *   XVARM 브로커       http://localhost:8082                  http://borami-xvarm-broker-1joiuorqhl:8080
 *   로그 컬렉터        http://localhost:8090/logc             http://log-collector-a2z96kgyrm:8080/logc
 * </pre>
 *
 * <p><b>설정이 비어 있을 때만</b> 이 값을 쓴다. 환경변수(ConfigMap)로 {@code VOICE_BASE_DIR} ·
 * {@code VOICE_BROKER_BASE_URL} · {@code LOG_COLLECTOR_BASE_URL} 을 주면 그것이 이긴다.
 * 시뮬레이터는 {@code GET /api/v1/voice/config} 로 이 값을 받아 화면(경로 input · 브로커 라디오 · 로그 컬렉터 카드)을
 * 환경에 맞게 자동으로 맞춘다.</p>
 *
 * <p>ROOT_DIR 은 <b>OS</b> 로, URL 은 <b>환경 종류</b>(LOCAL/K8S)로 정한다 — Linux 노트북에서 local 프로파일로 띄우면
 * 경로는 {@code /k8s/unstructured_collector}, 브로커·컬렉터는 localhost 다.</p>
 */
@Log4j2
@Component
public class DeployEnvPreset {

    public enum Kind { LOCAL, K8S }

    public static final String ROOT_WINDOWS = "C:/k8s/unstructured_collector";
    public static final String ROOT_LINUX = "/k8s/unstructured_collector";
    public static final String BROKER_LOCAL = "http://localhost:8082";
    public static final String BROKER_K8S = "http://borami-xvarm-broker-1joiuorqhl:8080";
    public static final String LOGC_LOCAL = "http://localhost:8090/logc";
    /** 차트(data_HelmChart/pipeline/log-collector-a2z96kgyrm)의 Service 명·포트 — 컨테이너 8080, context-path /logc. */
    public static final String LOGC_K8S = "http://log-collector-a2z96kgyrm:8080/logc";

    private final String os;
    private final boolean windows;
    private final List<String> profiles;
    private final Kind kind;
    private final String rootDir;
    private final String brokerBaseUrl;
    private final String logCollectorBaseUrl;
    private final boolean rootDirPreset;
    private final boolean brokerPreset;
    private final boolean logCollectorPreset;

    public DeployEnvPreset(Environment env, VoiceProperties props,
                           @Value("${log-collector.base-url:}") String configuredLogc) {
        this.os = System.getProperty("os.name", "");
        this.windows = os.toLowerCase(Locale.ROOT).contains("win");
        this.profiles = Arrays.asList(env.getActiveProfiles().length > 0 ? env.getActiveProfiles() : env.getDefaultProfiles());
        this.kind = (windows || profiles.contains("local")) ? Kind.LOCAL : Kind.K8S;

        String cfgRoot = norm(props.dirs().baseDir());
        this.rootDirPreset = !StringUtils.hasText(cfgRoot);
        this.rootDir = rootDirPreset ? (windows ? ROOT_WINDOWS : ROOT_LINUX) : cfgRoot;

        String cfgBroker = norm(props.broker().baseUrl());
        this.brokerPreset = !StringUtils.hasText(cfgBroker);
        this.brokerBaseUrl = brokerPreset ? (kind == Kind.LOCAL ? BROKER_LOCAL : BROKER_K8S) : cfgBroker;

        String cfgLogc = norm(configuredLogc);
        this.logCollectorPreset = !StringUtils.hasText(cfgLogc);
        this.logCollectorBaseUrl = logCollectorPreset ? (kind == Kind.LOCAL ? LOGC_LOCAL : LOGC_K8S) : cfgLogc;

        log.info("[Env] {} · os={} · profiles={} → ROOT_DIR={}{} · 브로커={}{} · 로그컬렉터={}{}",
                kind, os, profiles, rootDir, rootDirPreset ? "(자동)" : "(설정)",
                brokerBaseUrl, brokerPreset ? "(자동)" : "(설정)", logCollectorBaseUrl, logCollectorPreset ? "(자동)" : "(설정)");
    }

    public String os() { return os; }
    public boolean isWindows() { return windows; }
    public List<String> profiles() { return profiles; }
    public Kind kind() { return kind; }
    /** ROOT_DIR — Windows {@code C:/k8s/unstructured_collector}, Linux/K8s {@code /k8s/unstructured_collector} (설정이 있으면 그 값). */
    public String rootDir() { return rootDir; }
    public String brokerBaseUrl() { return brokerBaseUrl; }
    public String logCollectorBaseUrl() { return logCollectorBaseUrl; }

    /** 화면·API 용 요약. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind.name());
        m.put("kindLabel", kind == Kind.LOCAL ? "로컬 (Windows / local 프로파일)" : "배포 (Linux / K8s)");
        m.put("os", os);
        m.put("windows", windows);
        m.put("profiles", profiles);
        m.put("rootDir", rootDir);
        m.put("rootDirSource", rootDirPreset ? "자동(OS)" : "설정(voice.dirs.base-dir)");
        m.put("brokerBaseUrl", brokerBaseUrl);
        m.put("brokerBaseUrlSource", brokerPreset ? "자동(환경)" : "설정(voice.broker.base-url)");
        m.put("logCollectorBaseUrl", logCollectorBaseUrl);
        m.put("logCollectorBaseUrlSource", logCollectorPreset ? "자동(환경)" : "설정(log-collector.base-url)");
        return m;
    }

    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        String v = s.trim().replace('\\', '/');
        while (v.length() > 1 && v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
