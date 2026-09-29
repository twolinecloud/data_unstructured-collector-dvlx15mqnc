package egovframework.unstructured.collector.voice.source;

import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 보라미 조회 라우터 — 현재 모드에 맞는 구현으로 <b>호출 시점에</b> 위임한다.
 *
 * <p>어느 것을 쓸지는 {@link VoiceModeState} 가 정한다. {@code MOCK (로컬 H2)} 와 {@code 개발계 DB}(DIRECT_JDBC) 는
 * 둘 다 JDBC 구현이고, <b>어느 DB 에 붙는지는 DataSource 라우터가 모드로 정한다</b>(MOCK → H2, DIRECT_JDBC → 개발계).
 * 그래서 재시작 없이 MOCK → DIRECT_JDBC → ESB_HTTP2DB 로 옮겨 갈 수 있다.</p>
 */
@Log4j2
@Primary
@Component
public class BoramiSourceRouter implements BoramiSourceClient {

    private final VoiceModeState state;
    private final Map<String, BoramiSourceClient> byMode = new HashMap<>();

    public BoramiSourceRouter(VoiceModeState state, List<BoramiSourceClient> impls) {
        this.state = state;
        for (BoramiSourceClient impl : impls) {
            if (impl instanceof BoramiSourceRouter) {
                continue;   // 자기 자신은 제외 — 무한 위임을 막는다
            }
            byMode.put(impl.mode(), impl);
        }
        log.info("[Source] 사용 가능한 구현 — {}", byMode.keySet());
    }

    private BoramiSourceClient current() {
        // MOCK(로컬 H2)도 JDBC 로 조회한다 — 라우터가 H2 로 붙여 준다
        String mode = state.source() == egovframework.unstructured.collector.common.config.VoiceProperties.SourceMode.MOCK
                ? "DIRECT_JDBC" : state.source().name();
        BoramiSourceClient impl = byMode.get(mode);
        if (impl == null) {
            throw new IllegalStateException("보라미 조회 구현이 없다: " + mode + " (등록: " + byMode.keySet() + ")");
        }
        return impl;
    }

    @Override
    public List<VoiceTarget> findMeetTargets(BatchWindow window, List<String> speclCodes, int limit) {
        return current().findMeetTargets(window, speclCodes, limit);
    }

    @Override
    public List<VoiceTarget> findPhoneTargets(BatchWindow window, List<String> speclCodes, int limit) {
        return current().findPhoneTargets(window, speclCodes, limit);
    }

    @Override
    public List<VoiceTarget> findMeetTargets(BatchWindow window, List<String> speclCodes, int limit, int offset) {
        return current().findMeetTargets(window, speclCodes, limit, offset);
    }

    @Override
    public List<VoiceTarget> findPhoneTargets(BatchWindow window, List<String> speclCodes, int limit, int offset) {
        return current().findPhoneTargets(window, speclCodes, limit, offset);
    }

    @Override
    public String mode() {
        return state.source().name();
    }
}
