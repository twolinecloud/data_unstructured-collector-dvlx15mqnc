package egovframework.unstructured.collector.common.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시간창 계산 — 회의에서 합의된 규칙을 그대로 검증한다.
 * "전날 00시부터 오늘 00시 전까지"(일배치), "20분 전 것까지"(주기배치).
 */
class BatchWindowTest {

    @Test
    @DisplayName("일배치는 전날 00시부터 오늘 00시 전까지다")
    void dailyCoversPreviousDay() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 15, 2, 0, 0);

        BatchWindow w = BatchWindow.daily(now);

        assertThat(w.from()).isEqualTo(LocalDateTime.of(2026, 9, 14, 0, 0, 0));
        assertThat(w.to()).isEqualTo(LocalDateTime.of(2026, 9, 15, 0, 0, 0));
        assertThat(w.label()).isEqualTo("DAILY");
    }

    @Test
    @DisplayName("주기배치는 당일 00:00 부터 지금까지를 훑는다 — 최근 20분만 보면 놓친 건이 영영 빠진다")
    void periodicCoversWholeDay() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 15, 14, 30, 0);

        BatchWindow w = BatchWindow.periodic(now, 20);

        assertThat(w.from()).isEqualTo(LocalDateTime.of(2026, 9, 15, 0, 0, 0));
        assertThat(w.to()).isEqualTo(now);
    }

    @Test
    @DisplayName("자정 직후에는 어제 끝자락까지 넓힌다 — 00:05 에 도는 배치가 23:55 건을 놓치지 않게")
    void periodicReachesBackAcrossMidnight() {
        LocalDateTime justAfterMidnight = LocalDateTime.of(2026, 9, 15, 0, 5, 0);

        BatchWindow w = BatchWindow.periodic(justAfterMidnight, 20);

        assertThat(w.from()).isEqualTo(LocalDateTime.of(2026, 9, 14, 23, 45, 0));
    }

    @Test
    @DisplayName("주기배치 창은 서로 겹친다 — 멱등 처리가 필요한 이유")
    void periodicWindowsOverlap() {
        LocalDateTime first = LocalDateTime.of(2026, 9, 15, 14, 0, 0);
        LocalDateTime second = first.plusMinutes(10);

        BatchWindow w1 = BatchWindow.periodic(first, 20);
        BatchWindow w2 = BatchWindow.periodic(second, 20);

        assertThat(w2.from()).isBefore(w1.to());
    }

    // ── T1 EXEC_TYPE_CD ───────────────────────────────────────────────────
    // 로그 테이블 설계서(TC-21223-01)가 허용하는 값은 SCHEDULED / MANUAL 둘뿐이다.
    //   DAILY·PERIODIC 을 그대로 보내고 있었는데, 코드 밖의 값이라 통계·필터에서 빠진다.

    @Test
    @DisplayName("일배치·주기배치는 SCHEDULED — 스케줄러가 도는 작업이다")
    void scheduledBatchesReportScheduled() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 15, 14, 30, 0);

        assertThat(BatchWindow.daily(now).execTypeCd()).isEqualTo("SCHEDULED");
        assertThat(BatchWindow.periodic(now, 20).execTypeCd()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("[바로 실행]은 MANUAL — 사람이 구간을 지정해 돌린 것")
    void manualWindowReportsManual() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 15, 14, 30, 0);

        assertThat(BatchWindow.manual(now.minusHours(1), now).execTypeCd()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("허용값 밖의 코드는 나오지 않는다")
    void onlyTwoCodesExist() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 15, 14, 30, 0);

        for (BatchWindow w : java.util.List.of(
                BatchWindow.daily(now), BatchWindow.periodic(now, 20), BatchWindow.manual(now, now))) {
            assertThat(w.execTypeCd()).isIn("SCHEDULED", "MANUAL");
        }
    }

    // ── [바로 실행] — DB 워터마크 ──────────────────────────────────────────

    @Test
    @DisplayName("워터마크가 있으면 거기서부터 지금까지")
    void onDemandStartsAtTheWatermark() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 27, 14, 30);
        LocalDateTime wm = LocalDateTime.of(2026, 9, 27, 0, 0);

        BatchWindow w = BatchWindow.onDemand(now, wm, 30);

        assertThat(w.from()).isEqualTo(wm);
        assertThat(w.to()).isEqualTo(now);
        assertThat(w.execTypeCd()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("워터마크가 없으면 최근 30일 — 첫 실행·시험 이력 초기화 직후")
    void onDemandFallsBackToLookback() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 27, 14, 30);

        assertThat(BatchWindow.onDemand(now, null, 30).from()).isEqualTo(now.minusDays(30));
        assertThat(BatchWindow.onDemand(now, null, 0).from()).as("0 이하면 1일").isEqualTo(now.minusDays(1));
    }

    @Test
    @DisplayName("워터마크가 30일보다 오래됐어도 그대로 쓴다 — 멈춰 있던 동안 쌓인 건을 놓치지 않게")
    void onDemandUsesAnOldWatermarkAsIs() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 27, 14, 30);
        LocalDateTime old = now.minusDays(45);

        assertThat(BatchWindow.onDemand(now, old, 30).from()).isEqualTo(old);
    }

    @Test
    @DisplayName("T1 에 남길 구간 끝은 '지금' 을 넘지 않는다 — 재처리 창(지금+1분)이 워터마크를 미래로 밀지 않게")
    void recordedEndNeverExceedsNow() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 27, 17, 56, 16);

        assertThat(BatchWindow.manual(now.minusMinutes(30), now.plusMinutes(1)).recordedEnd(now)).isEqualTo(now);
        assertThat(BatchWindow.daily(now).recordedEnd(now))
                .as("일배치 끝(오늘 00:00)은 과거라 그대로").isEqualTo(LocalDateTime.of(2026, 9, 27, 0, 0));
    }
}
