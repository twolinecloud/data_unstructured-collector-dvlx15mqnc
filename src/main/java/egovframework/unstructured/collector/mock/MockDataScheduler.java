package egovframework.unstructured.collector.mock;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 대시보드 더미 <b>일 단위 자동 생성</b> — 매일 일배치 전에 <b>어제 날짜</b>로 DMY 를 추가하고, 보존 기간이 지난 DMY 를 지운다.
 *
 * <p>{@code unstructured.mock.daily.enabled=true} 일 때만 빈이 생긴다(기본 끔) · dev/local 프로필 전용. 놓친 날을 따라잡지 않는다
 * (catch-up 없음) — 수집기가 내려가 있던 날은 비어 있다. 실패해도 예외를 밖으로 던지지 않는다(다음 날 다시 돈다).</p>
 */
@Log4j2
@Component
@Profile({"dev", "local"})
@ConditionalOnProperty(prefix = "unstructured.mock.daily", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class MockDataScheduler {

    private final DummyDataService dummy;
    private final MockDataProperties props;

    @Scheduled(cron = "${unstructured.mock.daily.cron:0 30 1 * * *}", zone = "Asia/Seoul")
    public void daily() {
        runOnce(LocalDate.now());
    }

    /**
     * 한 번 — 어제 날짜로 추가 · 보존 기간 정리. 시험이 날짜를 정해 부른다.
     *
     * @return 생성 · 정리 결과 요약(실패면 사유)
     */
    public Map<String, Object> runOnce(LocalDate today) {
        MockDataProperties.Daily d = props.daily();
        Map<String, Object> out = new LinkedHashMap<>();
        if (!props.dashboard().enabled()) {
            log.warn("[Dummy:daily] 대시보드 더미가 꺼져 있어 건너뛴다 — unstructured.mock.dashboard.enabled=false");
            out.put("skipped", "dashboard disabled");
            return out;
        }
        try {
            Map<FailureScenario, Integer> failures = new EnumMap<>(FailureScenario.class);
            failures.put(FailureScenario.COLLECT_FAIL, d.collectFail());
            failures.put(FailureScenario.ANALYZE_FAIL, d.analyzeFail());
            failures.put(FailureScenario.SEND_FAIL, d.sendFail());
            Map<String, Object> g = dummy.generate(new DummyDataService.GenerateRequest(DummyTarget.DASHBOARD, d.types(),
                    today.minusDays(1), d.count(), failures, true));
            out.put("generated", g.get("totals"));
            log.info("[Dummy:daily] {} 대시보드 더미 추가 — {}", today.minusDays(1), g.get("totals"));
        } catch (Exception e) {
            out.put("generateError", e.getMessage());
            log.error("[Dummy:daily] 생성 실패 — {}", e.getMessage(), e);
        }
        if (d.retainDays() > 0) {
            try {
                Map<String, Object> p = dummy.purgeDashboardBefore(today.minusDays(d.retainDays()));
                out.put("purged", p.get("counts"));
            } catch (Exception e) {
                out.put("purgeError", e.getMessage());
                log.error("[Dummy:daily] 보존 기간 정리 실패 — {}", e.getMessage(), e);
            }
        }
        return out;
    }
}
