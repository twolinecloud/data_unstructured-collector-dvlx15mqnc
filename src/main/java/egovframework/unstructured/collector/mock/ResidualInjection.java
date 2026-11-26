package egovframework.unstructured.collector.mock;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>잔존 주입(시뮬레이터 전용)</b> — 고른 더미 키의 MOCK STT 전사에 <b>가상 전화번호를 한글로 읽은 줄</b>을 하나 덧붙인다(2026-11-26).
 *
 * <p>커넥터 음성 비식별(마스킹 엔진)은 숫자 표기만 가리므로 이 줄은 그대로 남고, 커넥터의 잔존 재검증이 그 파일을 격리한다
 * (T5 FAIL · RECONCILE · residual_pii_log · 응답 본문 없음 → 수집기 DEIDENT 실패 · 전송 제외). 개발계 E2E 확인용이다.</p>
 *
 * <ul>
 *   <li>키 표식(CF · AF · SF · DF)과 달리 '한 번만' 이 아니다 — 전사 내용이라 재처리(보관 전사 · FROM_SEND)도 다시 격리된다</li>
 *   <li>{@link ScenarioFaults#isActive()} 가 꺼진 환경(운영)에서는 MOCK STT 가 이 목록을 보지 않는다</li>
 *   <li>메모리에만 둔다 — 재기동하면 비고, 비식별 실패 시뮬레이터의 정리(cleanup)도 비운다</li>
 * </ul>
 */
@Log4j2
@Component
public class ResidualInjection {

    /** 덧붙이는 줄 — 값은 가상 번호(010-1234-5678)를 한글로 읽은 것. 엔진은 숫자 표기만 보아 놓친다. */
    public static final String LINE = "제 번호 공일공 일이삼사 오육칠팔 로 연락 주세요.";
    /** 한 번에 표시할 수 있는 키 상한 — 시뮬레이터 배치 건수 상한과 같은 규모. */
    static final int MAX_KEYS = 100;

    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    /** 이 키들의 전사에 잔존 줄을 넣는다. */
    public void mark(Collection<String> ks) {
        for (String k : ks) {
            if (k != null && !k.isBlank() && keys.size() < MAX_KEYS) {
                keys.add(k);
            }
        }
        log.info("[ResidualInjection] 잔존 주입 표시 {}건 · 누적 {}건", ks.size(), keys.size());
    }

    public boolean isMarked(String key) {
        return key != null && keys.contains(key);
    }

    /** 지금 표시된 키(정렬). */
    public List<String> marked() {
        return keys.stream().sorted().toList();
    }

    /** 모두 지운다 — 지운 건수. */
    public int clear() {
        int n = keys.size();
        keys.clear();
        return n;
    }
}
