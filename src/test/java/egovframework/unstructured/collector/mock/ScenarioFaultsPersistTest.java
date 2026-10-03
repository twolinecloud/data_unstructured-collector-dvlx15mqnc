package egovframework.unstructured.collector.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 키 표식 소진 표시를 PV 파일에 남긴다 — 파드가 다시 떠도(새 인스턴스) "이미 한 번 실패했다" 를 기억하는가.
 * 새 인스턴스를 만드는 것이 재기동 흉내다(기동할 때 파일을 읽는다).
 */
class ScenarioFaultsPersistTest {

    private static final String SF = "DMY-MEET-20261002-SF-0003";
    private static final String CF_IMG = "DMYIMG2610020001CF";

    @TempDir
    Path root;

    private Path file() {
        return root.resolve(ScenarioFaults.FILE);
    }

    @Test
    @DisplayName("재기동해도 기억한다 — 1차에 실패한 키는 재기동 뒤 재처리에서 통과")
    void survivesRestart() {
        ScenarioFaults before = ScenarioFaults.activeForTest(file());
        assertThat(before.failOnce(FailureScenario.SEND_FAIL, SF)).as("1차 — 실패").isTrue();
        assertThat(before.failOnce(FailureScenario.COLLECT_FAIL, CF_IMG)).isTrue();
        assertThat(file()).as("소진 표시가 PV 파일로").isRegularFile();

        ScenarioFaults after = ScenarioFaults.activeForTest(file());   // 파드가 새로 떴다
        assertThat(after.isConsumed(FailureScenario.SEND_FAIL, SF)).isTrue();
        assertThat(after.failOnce(FailureScenario.SEND_FAIL, SF)).as("재기동 뒤 재처리 — 통과(예전엔 한 번 더 실패)").isFalse();
        assertThat(after.failOnce(FailureScenario.COLLECT_FAIL, CF_IMG)).isFalse();
        assertThat(after.snapshot()).containsEntry("consumed", 2).containsKey("file");
    }

    @Test
    @DisplayName("다시 걸기(초기화) — 파일에서도 지워져 재기동 뒤에도 한 번 더 실패한다")
    void forgetIsPersisted() {
        ScenarioFaults a = ScenarioFaults.activeForTest(file());
        a.failOnce(FailureScenario.SEND_FAIL, SF);
        a.failOnce(FailureScenario.COLLECT_FAIL, CF_IMG);
        assertThat(a.forget(DummyTarget.DASHBOARD::ownsScenarioKey)).isEqualTo(2);

        ScenarioFaults b = ScenarioFaults.activeForTest(file());
        assertThat(b.snapshot()).containsEntry("consumed", 0);
        assertThat(b.failOnce(FailureScenario.SEND_FAIL, SF)).isTrue();
    }

    @Test
    @DisplayName("안전 — 파일이 깨져도 빈 채로 시작 · 표식 없는 키 · 지울 게 없으면 파일을 만들지 않는다")
    void safeWhenBrokenOrIdle() throws Exception {
        ScenarioFaults idle = ScenarioFaults.activeForTest(file());
        assertThat(idle.failOnce(FailureScenario.SEND_FAIL, "DMY-MEET-20261002-0004")).as("표식 없는 정상 키").isFalse();
        assertThat(idle.forget(k -> true)).isZero();
        assertThat(file()).as("바뀐 게 없으면 쓰지 않는다").doesNotExist();

        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{ 깨진 파일");
        ScenarioFaults broken = ScenarioFaults.activeForTest(file());
        assertThat(broken.snapshot()).containsEntry("consumed", 0);
        assertThat(broken.failOnce(FailureScenario.SEND_FAIL, SF)).isTrue();
        assertThat(ScenarioFaults.activeForTest(file()).isConsumed(FailureScenario.SEND_FAIL, SF)).as("다시 쓴 파일은 정상").isTrue();
    }

    @Test
    @DisplayName("운영(prod) — 파일을 읽지도 쓰지도 않는다")
    void prodTouchesNoFile() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{\"SEND_FAIL|" + SF + "\":\"2026-10-03T15:00:00\"}");
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        ScenarioFaults prod = new ScenarioFaults(env);
        assertThat(prod.isActive()).isFalse();
        assertThat(prod.failOnce(FailureScenario.SEND_FAIL, SF)).isFalse();
        assertThat(ScenarioFaults.inactive().snapshot()).containsEntry("consumed", 0);
    }
}
