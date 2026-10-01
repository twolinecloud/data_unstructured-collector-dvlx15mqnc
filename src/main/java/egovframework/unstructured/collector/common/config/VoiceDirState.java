package egovframework.unstructured.collector.common.config;

import egovframework.unstructured.collector.common.model.VoiceKind;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 디렉터리 5종의 <b>런타임 상태</b> — ROOT_DIR 아래 표준 배치. 서버를 재시작하지 않고 바꿀 수 있다.
 *
 * <pre>
 *   ROOT_DIR           Windows C:/k8s/unstructured_collector · Linux/K8s /k8s/unstructured_collector ({@link DeployEnvPreset})
 *   xvarmOriginal      {ROOT}/xvram/original_voice_files   XVARM 접견 원본 (시뮬레이션 더미 파일)
 *   receiveMeet        {ROOT}/esb/meet                     ESB 원본 수신 (접견)
 *   receivePhone       {ROOT}/esb/phone                    ESB 원본 수신 (전화)
 *   work               {ROOT}/xvram/decoding               XVARM 접견 복호화 (작업 · 멱등 표식)
 * </pre>
 *
 * <p>최종 결과 폴더({@code xenon/meet|phone})는 2026-10-01 없앴다 — STT 결과는 PV 에 남기지 않고 제논(Zenon)
 * 수신 API 로 바로 보낸다({@code SEND}).</p>
 *
 * <p>{@link VoiceModeState} 와 같은 이유로 런타임에 둔다. 로컬에서는 브로커가 떨구는 폴더에,
 * 개발계에서는 PV 마운트 지점에 맞춰야 하는데, 경로가 어긋나면 배치는 "추출 완료" 뒤 빈 폴더를 보며
 * 타임아웃이 난다. 시뮬레이터에서 프리셋을 고르는 즉시 반영되어야 그 자리에서 잡힌다.</p>
 *
 * <p><b>기동 초기값</b>: 설정({@link VoiceProperties.Dirs})이 비면 ROOT_DIR 로 파생한다. 런타임 변경은 이 프로세스에만
 * 남고 재기동하면 돌아간다. 없는 폴더는 기동·변경·시뮬레이션 데이터 생성/초기화 때 만든다(CREATE_IF_NOT_EXISTS).</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class VoiceDirState {

    public static final String REL_XVARM_ORIGINAL = "xvram/original_voice_files";
    public static final String REL_RECEIVE_MEET = "esb/meet";
    public static final String REL_RECEIVE_PHONE = "esb/phone";
    public static final String REL_WORK = "xvram/decoding";

    /** 화면 표기 순서·라벨 — 시뮬레이터 상단 서브헤더와 같다. */
    public static final List<String[]> LABELS = List.of(
            new String[] {"xvarmOriginal", "XVARM 접견 원본", "더미 음성 파일이 놓이는 곳 (시뮬레이션 데이터 생성)"},
            new String[] {"receiveMeet", "ESB 수신 (접견)", "브로커·ESB 가 떨구는 곳 — 로컬 브로커 BROKER_OUTPUT_DIR 과 같아야 한다"},
            new String[] {"receivePhone", "ESB 수신 (전화)", "ESB 전화 프로바이더가 떨구는 곳"},
            new String[] {"work", "XVARM 복호화", "복호화 산출물 · 멱등 표식"});

    private final VoiceProperties props;
    private final DeployEnvPreset env;

    private volatile String baseDir;
    private volatile String xvarmOriginal;
    private volatile String receiveMeet;
    private volatile String receivePhone;
    private volatile String work;

    @PostConstruct
    void init() {
        Map<String, String> c = configured();
        this.baseDir = c.get("baseDir");
        this.xvarmOriginal = c.get("xvarmOriginal");
        this.receiveMeet = c.get("receiveMeet");
        this.receivePhone = c.get("receivePhone");
        this.work = c.get("work");
        log.info("[Dirs] ROOT_DIR={} · XVARM원본={} · 수신(접견={} 전화={}) · 복호화={} · 결과는 PV 에 남기지 않음(제논 전송)",
                baseDir, xvarmOriginal, receiveMeet, receivePhone, work);
        ensureDirs();
    }

    public String baseDir() { return baseDir; }
    public String xvarmOriginal() { return xvarmOriginal; }
    public String receiveMeet() { return receiveMeet; }
    public String receivePhone() { return receivePhone; }
    public String work() { return work; }

    /** 종류별 수신 디렉터리. */
    public Path receiveDir(VoiceKind kind) {
        return Path.of(kind == VoiceKind.MEET ? receiveMeet : receivePhone);
    }

    /** XVARM 원본 폴더 — 접견·전화 더미 파일이 같은 폴더에 놓인다(이름으로 구분). */
    public Path xvarmOriginalDir(VoiceKind kind) {
        return Path.of(xvarmOriginal);
    }

    /**
     * 표준 5개 폴더를 만든다(CREATE_IF_NOT_EXISTS) — 앱 기동 · 경로 변경 · 시뮬레이션 데이터 생성/초기화 때.
     * 못 만들어도 기동은 막지 않는다(권한·마운트 문제는 배치 때 사유와 함께 드러난다).
     *
     * @return 폴더별 결과(있음/만듦/실패)
     */
    public Map<String, String> ensureDirs() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] l : LABELS) {
            String d = snapshot().get(l[0]);
            try {
                Path p = Path.of(d);
                boolean existed = Files.isDirectory(p);
                Files.createDirectories(p);
                out.put(l[0], existed ? "exists" : "created");
                if (!existed) {
                    log.info("[Dirs] 디렉터리 생성 — {} ({})", d, l[1]);
                }
            } catch (Exception e) {
                out.put(l[0], "error: " + e.getMessage());
                log.warn("[Dirs] 디렉터리 생성 실패 — {} ({})", d, e.getMessage());
            }
        }
        return out;
    }

    /** 현재 경로 전체(조회·화면 표시용). */
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("baseDir", baseDir);
        m.put("xvarmOriginal", xvarmOriginal);
        m.put("receiveMeet", receiveMeet);
        m.put("receivePhone", receivePhone);
        m.put("work", work);
        return m;
    }

    /** 기동 시 값(되돌리기 기준) — 설정이 비면 ROOT_DIR(OS 자동) 아래 표준 배치. */
    public Map<String, String> configured() {
        VoiceProperties.Dirs d = props.dirs();
        Map<String, String> std = layoutOf(env.rootDir());
        Map<String, String> m = new LinkedHashMap<>();
        m.put("baseDir", std.get("baseDir"));
        m.put("xvarmOriginal", or(d.xvarmOriginal(), std.get("xvarmOriginal")));
        m.put("receiveMeet", or(d.receiveMeet(), std.get("receiveMeet")));
        m.put("receivePhone", or(d.receivePhone(), std.get("receivePhone")));
        m.put("work", or(d.work(), std.get("work")));
        return m;
    }

    private static String or(String configured, String fallback) {
        String v = norm(configured);
        return v.isEmpty() ? fallback : v;
    }

    /** ROOT_DIR 하나로 표준 배치를 만든다 — 프리셋과 [ROOT_DIR 로 채우기] 가 쓴다. */
    public static Map<String, String> layoutOf(String root) {
        String b = norm(root);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("baseDir", b);
        m.put("xvarmOriginal", b + "/" + REL_XVARM_ORIGINAL);
        m.put("receiveMeet", b + "/" + REL_RECEIVE_MEET);
        m.put("receivePhone", b + "/" + REL_RECEIVE_PHONE);
        m.put("work", b + "/" + REL_WORK);
        return m;
    }

    /**
     * 시뮬레이터 프리셋.
     * <ul>
     *   <li><b>이 환경 기본</b> — 기동 시 값(OS 자동 ROOT_DIR + 설정 덮어쓰기)</li>
     *   <li><b>Windows 로컬</b> — {@code C:/k8s/unstructured_collector} 표준 배치</li>
     *   <li><b>Linux / K8s PV</b> — {@code /k8s/unstructured_collector} 표준 배치</li>
     * </ul>
     */
    public List<Map<String, Object>> presets() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(preset("이 환경 기본 (" + (env.isWindows() ? "Windows" : "Linux") + ")", "configured", configured()));
        out.add(preset("Windows 로컬 " + DeployEnvPreset.ROOT_WINDOWS, "win", layoutOf(DeployEnvPreset.ROOT_WINDOWS)));
        out.add(preset("Linux / K8s PV " + DeployEnvPreset.ROOT_LINUX, "pv", layoutOf(DeployEnvPreset.ROOT_LINUX)));
        return out;
    }

    private static Map<String, Object> preset(String label, String key, Map<String, String> dirs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("dirs", dirs);
        return m;
    }

    /**
     * 경로를 바꾼다. 주어진 키만 바꾸고 나머지는 그대로 둔다. 바꾼 뒤 폴더를 만든다.
     *
     * @param values baseDir / xvarmOriginal / receiveMeet / receivePhone / work → 경로
     * @return 바뀌기 전 값 전체
     * @throws IllegalArgumentException 모르는 키, 빈 값
     */
    public Map<String, String> set(Map<String, String> values) {
        Map<String, String> before = snapshot();
        if (values == null || values.isEmpty()) {
            return before;
        }
        Map<String, String> next = new LinkedHashMap<>(before);
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim();
            String val = norm(e.getValue());
            if (!before.containsKey(key)) {
                throw new IllegalArgumentException("알 수 없는 디렉터리 키: " + key
                        + " (baseDir/xvarmOriginal/receiveMeet/receivePhone/work)");
            }
            if (val.isEmpty()) {
                throw new IllegalArgumentException(key + " 경로가 비어 있다");
            }
            next.put(key, val);
        }
        this.baseDir = next.get("baseDir");
        this.xvarmOriginal = next.get("xvarmOriginal");
        this.receiveMeet = next.get("receiveMeet");
        this.receivePhone = next.get("receivePhone");
        this.work = next.get("work");
        log.info("[Dirs] 경로 변경 — {} → {}", before, snapshot());
        ensureDirs();
        return before;
    }

    /** 기동 시 값으로 되돌린다. */
    public void resetToConfigured() {
        init();
    }

    /** 표기를 통일한다 — 역슬래시를 슬래시로. Windows 도 슬래시를 받아들이고, 화면·로그에서 비교하기 쉽다. */
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

    /** 절대경로 표기(화면 대조용). 상대경로는 프로세스 작업 디렉터리 기준. */
    public static String toAbs(String p) {
        try {
            return Path.of(p).toAbsolutePath().normalize().toString().replace('\\', '/');
        } catch (Exception e) {
            return p;
        }
    }
}
