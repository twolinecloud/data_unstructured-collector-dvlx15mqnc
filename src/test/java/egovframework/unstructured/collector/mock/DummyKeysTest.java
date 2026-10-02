package egovframework.unstructured.collector.mock;

import egovframework.unstructured.collector.image.model.ImageStage;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 더미 키 규칙 · 장애 표식 · 시각 칸 · 1회 장애 — 스프링 없이.
 */
class DummyKeysTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 1);

    @Test
    @DisplayName("용도별 접두 — DASHBOARD 는 DMY, SIMULATOR 는 기존 시뮬레이션 정리 조건(SIM-MEET- · SIMCMFI · SIMDOC · SIMIMG · mock_)과 같다")
    void prefixesPerTarget() {
        assertThat(DummyKeys.meetKey(DummyTarget.DASHBOARD, D, 1, null)).isEqualTo("DMY-MEET-20261001-0001");
        assertThat(DummyKeys.phoneKey(DummyTarget.DASHBOARD, D, 12, FailureScenario.SEND_FAIL)).isEqualTo("DMY-PHONE-20261001-SF-0012");
        assertThat(DummyKeys.voiceCorrNo(DummyTarget.DASHBOARD, DummyDataType.MEET, D, 3)).isEqualTo("DMYM202610010003");
        assertThat(DummyKeys.imageCorrNo(DummyTarget.DASHBOARD, D, 3, FailureScenario.ANALYZE_FAIL)).isEqualTo("DMYIMG2610010003AF");
        assertThat(DummyKeys.meetFileName(DummyTarget.DASHBOARD, D, 3, null)).isEqualTo("dmy_meet_20261001_0003.m4a");

        assertThat(DummyKeys.meetKey(DummyTarget.SIMULATOR, D, 1, null)).startsWith("SIM-MEET-");
        assertThat(DummyKeys.phoneKey(DummyTarget.SIMULATOR, D, 1, null)).startsWith("SIM-PHONE-");
        assertThat(DummyKeys.cmfiId(DummyTarget.SIMULATOR, D, 1)).startsWith("SIMCMFI");
        assertThat(DummyKeys.docId(DummyTarget.SIMULATOR, D, 1)).startsWith("SIMDOC");
        assertThat(DummyKeys.imageCorrNo(DummyTarget.SIMULATOR, D, 1, null)).startsWith("SIMIMG");
        assertThat(DummyKeys.imageFileId(DummyTarget.SIMULATOR, D, 1, 2)).startsWith("SIMIMGF");
        assertThat(DummyKeys.imageDocId(DummyTarget.SIMULATOR, D, 1, 2)).startsWith("SIMIMGD");
        assertThat(DummyKeys.meetFileName(DummyTarget.SIMULATOR, D, 1, null)).startsWith("mock_meet_");
        assertThat(DummyKeys.phoneFileName(DummyTarget.SIMULATOR, D, 1, null)).startsWith("mock_phone_");
        assertThat(DummyTarget.SIMULATOR.usr()).isEqualTo("simadm");
        assertThat(DummyTarget.DASHBOARD.usr()).isEqualTo("dmyadm");
    }

    @Test
    @DisplayName("컬럼 길이 — 교정번호 18 · 녹취파일번호 26 · 공통파일ID/문서ID 20 (표식 · 최대 순번 포함)")
    void fitsColumnLengths() {
        for (DummyTarget t : DummyTarget.values()) {
            for (FailureScenario s : new FailureScenario[] {null, FailureScenario.COLLECT_FAIL}) {
                assertThat(DummyKeys.meetKey(t, D, 9999, s)).hasSizeLessThanOrEqualTo(26);
                assertThat(DummyKeys.phoneKey(t, D, 9999, s)).hasSizeLessThanOrEqualTo(50);
                assertThat(DummyKeys.imageCorrNo(t, D, 9999, s)).hasSizeLessThanOrEqualTo(18);
            }
            assertThat(DummyKeys.voiceCorrNo(t, DummyDataType.PHONE, D, 9999)).hasSizeLessThanOrEqualTo(18);
            assertThat(DummyKeys.cmfiId(t, D, 9999)).hasSizeLessThanOrEqualTo(20);
            assertThat(DummyKeys.docId(t, D, 9999)).hasSizeLessThanOrEqualTo(20);
            assertThat(DummyKeys.imageFileId(t, D, 9999, 3)).hasSizeLessThanOrEqualTo(20);
            assertThat(DummyKeys.imageDocId(t, D, 9999, 3)).hasSizeLessThanOrEqualTo(20);
            assertThat(DummyKeys.recordFileId(t, D, 9999)).hasSizeLessThanOrEqualTo(20);
        }
        assertThatThrownBy(() -> DummyKeys.meetKey(DummyTarget.DASHBOARD, D, 10000, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("표식 판정 — 형식이 정확히 맞는 키만. 기존 시연 키 · 실제 같은 키 · 표식 없는 더미는 아니다")
    void markerParsing() {
        assertThat(FailureScenario.ofKey("DMY-MEET-20261001-CF-0001")).contains(FailureScenario.COLLECT_FAIL);
        assertThat(FailureScenario.ofKey("SIM-PHONE-20261001-AF-0002")).contains(FailureScenario.ANALYZE_FAIL);
        assertThat(FailureScenario.ofKey("DMYIMG2610010003SF")).contains(FailureScenario.SEND_FAIL);
        assertThat(FailureScenario.ofKey("DMY-MEET-20261001-0001")).isEmpty();
        assertThat(FailureScenario.ofKey("DMYIMG2610010003")).isEmpty();
        assertThat(FailureScenario.ofKey("SIM-MEET-001")).isEmpty();
        assertThat(FailureScenario.ofKey("2026091400000123SF")).isEmpty();
        assertThat(FailureScenario.ofKey("XYZ-MEET-20261001-SF-0001")).isEmpty();
        assertThat(FailureScenario.ofKey(null)).isEmpty();

        assertThat(FailureScenario.COLLECT_FAIL.expectedResume()).isEqualTo(ResumeMode.FULL);
        assertThat(FailureScenario.ANALYZE_FAIL.expectedResume()).isEqualTo(ResumeMode.FROM_ANALYZE);
        assertThat(FailureScenario.SEND_FAIL.expectedResume()).isEqualTo(ResumeMode.FROM_SEND);
        assertThat(FailureScenario.SEND_FAIL.imageStage()).isEqualTo(ImageStage.MAP);
    }

    @Test
    @DisplayName("순번 해석 — 표식이 있어도 · 이미지 교정번호도")
    void seqParsing() {
        assertThat(DummyKeys.seqOf("DMY-MEET-20261001-0007")).isEqualTo(7);
        assertThat(DummyKeys.seqOf("DMY-PHONE-20261001-SF-0123")).isEqualTo(123);
        assertThat(DummyKeys.seqOf("SIMIMG2610010042AF")).isEqualTo(42);
        assertThat(DummyKeys.seqOf("SIM-MEET-001")).isZero();
        assertThat(DummyKeys.dashboardDateOf("meet-DMY-MEET-20261001-SF-0003")).contains(D);
        assertThat(DummyKeys.dashboardDateOf("decrypted_dmy_phone_20261001_0003.wav")).contains(D);
        assertThat(DummyKeys.dashboardDateOf("DMYIMG2610010003_2.jpg.enc")).contains(D);
        assertThat(DummyKeys.dashboardDateOf("mock_meet_001.m4a")).isEmpty();
    }

    @Test
    @DisplayName("날짜 분산 — 건수만큼 하루를 나눠 대상일 안에, 서로 다른 시각으로. 추가(다른 순번)도 대상일 안")
    void slotsSpreadWithinDay() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 2, 15, 0);
        DummyDataService.Slots s = DummyDataService.Slots.of(D, 10, now);
        Set<LocalDateTime> seen = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            LocalDateTime at = s.at(i, 1 + i);
            assertThat(at.toLocalDate()).isEqualTo(D);
            seen.add(at);
            LocalDateTime again = s.at(i, 11 + i);   // 같은 날짜로 두 번째 추가 — 칸은 같고 칸 안에서 비켜 놓는다
            assertThat(again.toLocalDate()).isEqualTo(D);
            assertThat(again).isAfterOrEqualTo(s.from(i)).isBefore(s.from(i + 1));
        }
        assertThat(seen).hasSize(10);
        assertThat(s.at(0, 1)).isBefore(D.atTime(3, 0));
        assertThat(s.at(9, 10)).isAfter(D.atTime(21, 0));

        // 대상일이 오늘이면 지금-1분 앞까지만(미래 시각을 만들지 않는다)
        DummyDataService.Slots today = DummyDataService.Slots.of(now.toLocalDate(), 5, now);
        for (int i = 0; i < 5; i++) {
            assertThat(today.at(i, 99)).isBefore(now).isAfterOrEqualTo(now.toLocalDate().atStartOfDay());
        }
    }

    @Test
    @DisplayName("1회 장애 — 표식 단계에서 처음 한 번만 true, 다른 단계 · 표식 없는 키는 false, 잊으면 다시 한 번")
    void scenarioFaultsFailOnce() {
        ScenarioFaults f = ScenarioFaults.activeForTest();
        String key = "DMY-MEET-20261001-AF-0002";
        assertThat(f.failOnce(FailureScenario.COLLECT_FAIL, key)).isFalse();
        assertThat(f.failOnce(FailureScenario.ANALYZE_FAIL, key)).isTrue();
        assertThat(f.failOnce(FailureScenario.ANALYZE_FAIL, key)).isFalse();
        assertThat(f.failOnce(FailureScenario.ANALYZE_FAIL, "DMY-MEET-20261001-0002")).isFalse();
        assertThat(f.isConsumed(FailureScenario.ANALYZE_FAIL, key)).isTrue();

        assertThat(f.forget(DummyTarget.SIMULATOR::ownsScenarioKey)).as("다른 용도의 소진 표시는 건드리지 않는다").isZero();
        assertThat(f.forget(DummyTarget.DASHBOARD::ownsScenarioKey)).isEqualTo(1);
        assertThat(f.failOnce(FailureScenario.ANALYZE_FAIL, key)).isTrue();

        ScenarioFaults off = ScenarioFaults.inactive();
        assertThat(off.failOnce(FailureScenario.ANALYZE_FAIL, "DMY-MEET-20261001-AF-0009")).as("운영 — 아무 일도 하지 않는다").isFalse();
    }

    @Test
    @DisplayName("녹취파일경로명(100자) — 깊은 데이터 루트면 뒤쪽만 남겨 넣는다(2026-10-02 로컬 검증에서 시딩이 통째로 실패)")
    void longOriginalDirFitsColumn() {
        String deep = "C:/Users/someone/AppData/Local/Temp/claude/" + "x".repeat(120) + "/xvram/original_voice_files/meet";
        String fit = egovframework.unstructured.collector.voice.source.SimulationDataService.fitPath(deep, 100);
        assertThat(fit).hasSize(100).startsWith("…").endsWith("/xvram/original_voice_files/meet");
        String dev = "/k8s/unstructured_collector/xvram/original_voice_files/meet";
        assertThat(egovframework.unstructured.collector.voice.source.SimulationDataService.fitPath(dev, 100)).isEqualTo(dev);
    }

    @Test
    @DisplayName("로컬 산출물 이름 — 대시보드 더미의 것만 골라낸다(시뮬레이터 초기화가 남길 것)")
    void dashboardLocalNames() {
        for (String n : List.of("meet-DMY-MEET-20261001-0001", "phone-DMY-PHONE-20261001-SF-0001",
                "meet-DMY-MEET-20261001-SF-0003.json", "decrypted_dmy_meet_20261001_0001.m4a", "dmy_phone_20261001_0001.wav",
                "DMYIMG2610010001", "img_DMYIMG2610010001_2.bin")) {
            assertThat(DummyTarget.isDashboardLocalName(n)).as(n).isTrue();
        }
        for (String n : List.of("meet-SIM-MEET-001", "phone-SIM-PHONE-20261001-0001", "decrypted_mock_meet_001.m4a",
                "mock_phone_001.wav", "SIMIMG00001", "meet-2026091400000123")) {
            assertThat(DummyTarget.isDashboardLocalName(n)).as(n).isFalse();
        }
    }
}
