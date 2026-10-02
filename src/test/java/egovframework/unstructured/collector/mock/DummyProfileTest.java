package egovframework.unstructured.collector.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영 안전장치 — 운영 프로필에서는 더미 생성 빈이 생기지 않고 키 표식 장애가 꺼진다 · 수용자기본/신상 테이블에 쓰는 SQL 이 저장소에 없다.
 */
class DummyProfileTest {

    @Test
    @DisplayName("운영(prod) 프로필 — 생성 서비스 · API · 일 단위 스케줄러 빈이 생기지 않는다")
    void prodProfileCreatesNoDummyBeans() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=prod", "unstructured.mock.daily.enabled=true",
                        "unstructured.mock.dashboard.enabled=true")
                .withUserConfiguration(DummyDataService.class, DummyDataController.class, MockDataScheduler.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(DummyDataService.class);
                    assertThat(ctx).doesNotHaveBean(DummyDataController.class);
                    assertThat(ctx).doesNotHaveBean(MockDataScheduler.class);
                });
        for (Class<?> c : List.of(DummyDataService.class, DummyDataController.class, MockDataScheduler.class)) {
            assertThat(c.getAnnotation(Profile.class).value()).as(c.getSimpleName()).containsExactlyInAnyOrder("dev", "local");
        }
    }

    @Test
    @DisplayName("운영(prod) 프로필 — 키 표식 장애가 꺼진다(형식이 맞는 키도 실패시키지 않는다)")
    void prodProfileDisablesScenarioFaults() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        ScenarioFaults f = new ScenarioFaults(prod);
        assertThat(f.isActive()).isFalse();
        assertThat(f.failOnce(FailureScenario.SEND_FAIL, "DMY-MEET-20261001-SF-0001")).isFalse();

        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThat(new ScenarioFaults(dev).failOnce(FailureScenario.SEND_FAIL, "DMY-MEET-20261001-SF-0001")).isTrue();
    }

    @Test
    @DisplayName("대시보드 생성이 꺼져 있으면(unstructured.mock.dashboard.enabled=false) DASHBOARD 생성을 거절한다")
    void dashboardSwitchOffRejects() {
        MockDataProperties off = new MockDataProperties(new MockDataProperties.Dashboard(false),
                new MockDataProperties.Daily(false, "0 30 1 * * *", List.of(DummyDataType.PHONE), 1, 0, 0, 0, 7));
        DummyDataService svc = new DummyDataService(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, off);
        assertThatThrownBy(() -> svc.generate(new DummyDataService.GenerateRequest(DummyTarget.DASHBOARD, null, null, 1, Map.of(), true)))
                .isInstanceOf(DummyDataService.DashboardDisabledException.class);
    }

    /** INSERT/UPDATE/DELETE/MERGE 의 대상이 수용자기본 · 신상인 문장 — 주석의 테이블 이름은 걸리지 않는다. */
    private static final Pattern WRITE_TO_MASTER = Pattern.compile(
            "(?i)(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|MERGE\\s+INTO)\\s+[\\w.\"]*(IRIM_PRBS_BS|IRIM_PEIN_BS)");

    @Test
    @DisplayName("저장소 전체 — 수용자기본(TB_IRIM_PRBS_BS) · 신상(TB_IRIM_PEIN_BS)에 쓰는 SQL 이 없다(정형 오염 방지 규칙)")
    void noWritesToInmateMasterTablesInSource() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main"))) {
            for (Path f : s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().matches(".*\\.(java|xml|sql|yml)$")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (WRITE_TO_MASTER.matcher(text).find()) {
                    hits.add(f.toString());
                }
            }
        }
        assertThat(hits).isEmpty();
    }
}
