package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 키 표식 기반 장애 — 더미 키에 박힌 시나리오({@link FailureScenario})대로 <b>그 단계에서 한 번만</b> 실패시킨다.
 *
 * <p><b>왜 한 번만인가</b>: 시나리오의 요점은 "실패 → 해당 모드 재처리 → 성공" 이다. 데이터 성질(파일 누락·손상)로 실패시키면
 * 재처리도 똑같이 실패한다. 그래서 처음 닿을 때만 실패시키고 소진 표시를 남긴다 — 재처리(같은 키)는 통과한다.</p>
 *
 * <p><b>어디서 부르나</b> — 개발계 실제 경로(브로커 REST·DUMMY · 전화 MOCK · STT MOCK · 제논 MOCK)에서 정말 실패하는 자리.</p>
 * <ul>
 *   <li>COLLECT — 접견: 브로커 추출 요청 직전(수집기 · 개발계 DUMMY 어댑터는 FILEKEY 가 없어도 무음 WAV 를 만들어 줘 메타만으로는 실패하지 않는다)
 *       · 전화: Mock 전화 파일 연계 · 이미지: 브로커 수신 직전</li>
 *   <li>ANALYZE — Mock STT(HTTP 500 흉내) · 이미지: 복호화 직후(이미지 확인 실패)</li>
 *   <li>SEND — 제논 전송 런에 쌓기 직전({@code ZenonClient.precheck} — 그 건만 503 흉내, 청크는 멈추지 않는다 · MOCK/REST 공통)
 *       · 이미지: Admin DB 매핑(커밋 전 롤백)</li>
 * </ul>
 *
 * <p><b>소진 표시는 PV 파일에도 남긴다</b>(2026-10-03) — {@code {ROOT}/mock/scenario_faults.json}.
 * 예전에는 메모리에만 있어서 실패와 재처리 사이에 파드가 다시 뜨면(개발계는 dev 머지마다 재배포) 재처리에서 한 번 더 실패했다.
 * 이제 기동할 때 파일을 읽어 "이미 한 번 실패한 키" 를 그대로 기억한다. 표시가 생기거나 지워질 때마다 파일 전체를 새로 쓴다
 * (임시 파일에 쓰고 바꿔 끼운다 — 쓰다 죽어도 반쯤 쓴 파일이 남지 않는다). 파일을 읽거나 쓰지 못해도 배치는 멈추지 않는다
 * (경고만 남기고 메모리로 계속). 더미 초기화 · {@code DELETE /api/v1/mock/sim-data/scenario-faults} 가 지우면 파일에서도 빠지고,
 * 하나도 남지 않으면 파일을 지운다.</p>
 *
 * <p><b>운영 프로필에서는 아무 일도 하지 않는다</b> — 형식이 맞는 키도 실패시키지 않고 파일도 읽거나 쓰지 않는다.</p>
 */
@Log4j2
@Component
public class ScenarioFaults {

    /** PV 의 소진 표시 파일 — ROOT 기준 상대 경로. */
    public static final String FILE = "mock/scenario_faults.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 소진 표시 — "시나리오|키" → 실패시킨 시각. */
    private final Map<String, String> consumed = new ConcurrentHashMap<>();
    private final boolean active;
    /** 소진 표시 파일 — null 이면 메모리만(단위 테스트 · 프로필만 준 생성자). 부를 때마다 지금 ROOT 기준으로 푼다. */
    private final Supplier<Path> file;

    @Autowired
    public ScenarioFaults(Environment env, VoiceDirState dirs) {
        this(!env.acceptsProfiles(Profiles.of("prod")), () -> Path.of(dirs.baseDir(), FILE));
    }

    /** 프로필만 — 파일 없이 메모리만 쓴다(단위 테스트용). */
    public ScenarioFaults(Environment env) {
        this(!env.acceptsProfiles(Profiles.of("prod")), null);
    }

    private ScenarioFaults(boolean active, Supplier<Path> file) {
        this.active = active;
        this.file = active ? file : null;
        if (!active) {
            log.info("[Scenario] 운영 프로필 — 키 표식 장애를 쓰지 않는다");
            return;
        }
        load();
    }

    /** 아무것도 하지 않는 인스턴스 — 스프링 없이 만드는 단위 테스트용. */
    public static ScenarioFaults inactive() {
        return new ScenarioFaults(false, null);
    }

    /** 늘 켜진 인스턴스(메모리만) — 스프링 없이 만드는 단위 테스트용. */
    public static ScenarioFaults activeForTest() {
        return new ScenarioFaults(true, null);
    }

    /** 늘 켜진 인스턴스(이 파일에 소진 표시를 남긴다) — 재기동 흉내 단위 테스트용. */
    public static ScenarioFaults activeForTest(Path file) {
        return new ScenarioFaults(true, () -> file);
    }

    public boolean isActive() {
        return active;
    }

    /**
     * 이 키가 이 단계의 장애 표식을 달았고 아직 한 번도 실패하지 않았으면 {@code true} — 그리고 소진 표시를 남긴다(메모리 + 파일).
     *
     * @param stage 지금 지나는 단계
     * @param key   음성 대상 키(TARE_FILE_NO · VRFC_ESTL_ID) 또는 이미지 교정번호
     */
    public boolean failOnce(FailureScenario stage, String key) {
        if (!active || key == null || FailureScenario.ofKey(key).filter(s -> s == stage).isEmpty()) {
            return false;
        }
        boolean first = consumed.putIfAbsent(stage.name() + "|" + key, LocalDateTime.now().withNano(0).toString()) == null;
        if (first) {
            log.info("[Scenario] {} — {} 를 이번 한 번 실패시킨다(재처리는 통과)", stage, key);
            save();
        }
        return first;
    }

    /** 이 시나리오 표식이 이미 한 번 실패했는가. */
    public boolean isConsumed(FailureScenario stage, String key) {
        return consumed.containsKey(stage.name() + "|" + key);
    }

    /**
     * 소진 표시를 지운다 — 다음 실행에서 다시 한 번 실패한다. 더미 초기화가 그 용도의 키만 지운다. 지운 게 있으면 파일도 새로 쓴다.
     *
     * @return 지운 건수
     */
    public int forget(Predicate<String> key) {
        int before = consumed.size();
        consumed.keySet().removeIf(k -> key.test(k.substring(k.indexOf('|') + 1)));
        int removed = before - consumed.size();
        if (removed > 0) {
            save();
        }
        return removed;
    }

    /** 화면 · 응답용 — 어느 키가 이미 실패했는지. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", active);
        m.put("consumed", consumed.size());
        Path f = path();
        m.put("file", f == null ? null : f.toString().replace('\\', '/'));
        List<Map<String, String>> rows = new ArrayList<>();
        consumed.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(300).forEach(e -> {
            int bar = e.getKey().indexOf('|');
            Map<String, String> r = new LinkedHashMap<>();
            r.put("scenario", e.getKey().substring(0, bar));
            r.put("key", e.getKey().substring(bar + 1));
            r.put("failedAt", e.getValue());
            rows.add(r);
        });
        m.put("rows", rows);
        m.put("note", f == null
                ? "표식(CF·AF·SF)을 단 키는 그 단계에서 한 번만 실패한다 — 재처리는 통과. 소진 표시는 메모리에만 있다(재기동하면 다시 한 번 실패)"
                : "표식(CF·AF·SF)을 단 키는 그 단계에서 한 번만 실패한다 — 재처리는 통과. 소진 표시는 PV 파일에도 남아 재기동해도 다시 실패하지 않는다"
                  + "(다시 실패시키려면 DELETE /api/v1/mock/sim-data/scenario-faults)");
        return m;
    }

    // ── PV 파일 ─────────────────────────────────────────────────────────

    private Path path() {
        if (file == null) {
            return null;
        }
        try {
            return file.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 기동할 때 한 번 — 파일이 있으면 소진 표시를 되살린다. 읽지 못하면 빈 채로 시작한다(배치는 막지 않는다). */
    private void load() {
        Path f = path();
        if (f == null || !Files.isRegularFile(f)) {
            return;
        }
        try {
            Map<String, String> saved = JSON.readValue(f.toFile(), new TypeReference<Map<String, String>>() { });
            saved.forEach((k, v) -> {
                if (k != null && k.indexOf('|') > 0) {
                    consumed.put(k, v == null ? "" : v);
                }
            });
            log.info("[Scenario] 소진 표시 {}건을 파일에서 되살렸다 — {}", consumed.size(), f);
        } catch (IOException | RuntimeException e) {
            log.warn("[Scenario] 소진 표시 파일을 읽지 못했다 — 빈 채로 시작한다: {} ({})", f, e.getMessage());
        }
    }

    /**
     * 지금 표시 전체를 파일로 — 임시 파일에 쓰고 바꿔 끼운다. 실패해도 경고만(배치는 계속).
     * 초기화로 표시가 하나도 남지 않으면 파일을 지운다(2026-10-05 — 한 용도만 초기화하면 다른 용도의 표시는 남는다).
     */
    private synchronized void save() {
        Path f = path();
        if (f == null) {
            return;
        }
        try {
            if (consumed.isEmpty()) {
                Files.deleteIfExists(f);
                log.info("[Scenario] 소진 표시가 모두 지워져 파일도 지웠다 — {}", f);
                return;
            }
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new TreeMap<>(consumed));
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[Scenario] 소진 표시 파일을 쓰지 못했다 — 메모리로만 계속한다(재기동하면 한 번 더 실패할 수 있다): {} ({})", f, e.getMessage());
        }
    }
}
