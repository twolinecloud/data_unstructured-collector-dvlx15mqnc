package egovframework.unstructured.collector.mock;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

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
 *   <li>SEND — 제논 MOCK(HTTP 503 흉내) · REST 면 {@code tools/zenon-mock} 서버가 같은 표식에 503 · 이미지: Admin DB 매핑(커밋 전 롤백)</li>
 * </ul>
 *
 * <p><b>한계</b>: 소진 표시는 프로세스 메모리에 있다. 실패와 재처리 사이에 파드가 다시 뜨면 재처리에서 한 번 더 실패하고,
 * 그다음 재처리가 성공한다. 더미 초기화가 그 용도의 표시를 지운다.</p>
 *
 * <p><b>운영 프로필에서는 아무 일도 하지 않는다</b> — 형식이 맞는 키도 실패시키지 않는다.</p>
 */
@Log4j2
@Component
public class ScenarioFaults {

    /** 소진 표시 — "시나리오|키" → 실패시킨 시각. */
    private final Map<String, String> consumed = new ConcurrentHashMap<>();
    private final boolean active;

    @Autowired
    public ScenarioFaults(Environment env) {
        this(!env.acceptsProfiles(Profiles.of("prod")));
    }

    private ScenarioFaults(boolean active) {
        this.active = active;
        if (!active) {
            log.info("[Scenario] 운영 프로필 — 키 표식 장애를 쓰지 않는다");
        }
    }

    /** 아무것도 하지 않는 인스턴스 — 스프링 없이 만드는 단위 테스트용. */
    public static ScenarioFaults inactive() {
        return new ScenarioFaults(false);
    }

    /** 늘 켜진 인스턴스 — 스프링 없이 만드는 단위 테스트용. */
    public static ScenarioFaults activeForTest() {
        return new ScenarioFaults(true);
    }

    public boolean isActive() {
        return active;
    }

    /**
     * 이 키가 이 단계의 장애 표식을 달았고 아직 한 번도 실패하지 않았으면 {@code true} — 그리고 소진 표시를 남긴다.
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
        }
        return first;
    }

    /** 이 시나리오 표식이 이미 한 번 실패했는가. */
    public boolean isConsumed(FailureScenario stage, String key) {
        return consumed.containsKey(stage.name() + "|" + key);
    }

    /**
     * 소진 표시를 지운다 — 다음 실행에서 다시 한 번 실패한다. 더미 초기화가 그 용도의 키만 지운다.
     *
     * @return 지운 건수
     */
    public int forget(Predicate<String> key) {
        int before = consumed.size();
        consumed.keySet().removeIf(k -> key.test(k.substring(k.indexOf('|') + 1)));
        return before - consumed.size();
    }

    /** 화면 · 응답용 — 어느 키가 이미 실패했는지. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", active);
        m.put("consumed", consumed.size());
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
        m.put("note", "표식(CF·AF·SF)을 단 키는 그 단계에서 한 번만 실패한다 — 재처리는 통과. 소진 표시는 메모리에만 있다(재기동하면 다시 한 번 실패)");
        return m;
    }
}
