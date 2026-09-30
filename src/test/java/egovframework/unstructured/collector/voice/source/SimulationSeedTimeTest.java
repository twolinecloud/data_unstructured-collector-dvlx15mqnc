package egovframework.unstructured.collector.voice.source;

import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SIM 발생 시각 — <b>언제 시딩해도</b> 일배치용은 어제 일배치 창에만, 주기용은 오늘 주기 창(lag 10·20)에만 든다.
 *
 * <p>2026-09-30 00:15 UTC Jenkins 빌드가 8건 실패했다 — 주기용 '지금-18분' 이 어제로 넘어가고 일배치용 23:59:59 가
 * 주기 창에 걸렸다. 빌드 에이전트가 UTC 라 한국 09:00~09:20 푸시가 매일 걸렸다. 여기서는 시각을 못 박아 자정 직후를 본다.</p>
 */
class SimulationSeedTimeTest {

    private static final int DAILY = 5;
    private static final int GUARD = 30;

    @ParameterizedTest(name = "지금 {0}")
    @ValueSource(strings = {"2026-09-30T00:00:02", "2026-09-30T00:03:00", "2026-09-30T00:15:43", "2026-09-30T00:29:59",
            "2026-09-30T00:31:00", "2026-09-30T09:15:00", "2026-09-30T23:59:59"})
    void windowsNeverOverlap(String nowText) {
        LocalDateTime now = LocalDateTime.parse(nowText);
        BatchWindow daily = BatchWindow.daily(now);
        for (VoiceKind kind : VoiceKind.values()) {
            for (int i = 1; i <= DAILY; i++) {
                LocalDateTime at = SimulationDataService.occurredAt(now, i, DAILY, kind, GUARD);
                assertThat(daily.contains(at)).as("%s 일배치용 %d (%s) — 어제 일배치 창", kind, i, at).isTrue();
                for (int lag : new int[] {10, 20}) {
                    assertThat(BatchWindow.periodic(now, lag).contains(at))
                            .as("%s 일배치용 %d (%s) — 주기 창(lag %d)에 걸리면 안 된다", kind, i, at, lag).isFalse();
                }
            }
            LocalDateTime p1 = SimulationDataService.occurredAt(now, DAILY + 1, DAILY, kind, GUARD);
            LocalDateTime p2 = SimulationDataService.occurredAt(now, DAILY + 2, DAILY, kind, GUARD);
            for (LocalDateTime at : new LocalDateTime[] {p1, p2}) {
                assertThat(daily.contains(at)).as("%s 주기용 (%s) — 어제 일배치 창에 넘어가면 안 된다", kind, at).isFalse();
                for (int lag : new int[] {10, 20}) {
                    assertThat(BatchWindow.periodic(now, lag).contains(at))
                            .as("%s 주기용 (%s) — 주기 창(lag %d)", kind, at, lag).isTrue();
                }
            }
            assertThat(p2).as("%s 지연 건(뒤엣것)이 더 오래됐다 — 조회 순서", kind).isBefore(p1);
        }
    }

    @ParameterizedTest(name = "지금 {0}")
    @ValueSource(strings = {"2026-09-30T00:31:00", "2026-09-30T09:15:00", "2026-09-30T23:59:59"})
    void awayFromMidnightKeepsTheUsualShape(String nowText) {
        LocalDateTime now = LocalDateTime.parse(nowText);
        // 한낮에는 예전 그대로 — 일배치 창의 양 끝(00:00:00 · 23:59:59), 주기용은 지금-6분 · 지금-18분
        assertThat(SimulationDataService.occurredAt(now, 1, DAILY, VoiceKind.MEET, GUARD))
                .isEqualTo(now.toLocalDate().minusDays(1).atStartOfDay());
        assertThat(SimulationDataService.occurredAt(now, DAILY, DAILY, VoiceKind.MEET, GUARD))
                .isEqualTo(now.toLocalDate().atStartOfDay().minusSeconds(1));
        assertThat(SimulationDataService.occurredAt(now, DAILY + 1, DAILY, VoiceKind.MEET, GUARD)).isEqualTo(now.minusMinutes(6));
        assertThat(SimulationDataService.occurredAt(now, DAILY + 2, DAILY, VoiceKind.MEET, GUARD)).isEqualTo(now.minusMinutes(18));
    }
}
