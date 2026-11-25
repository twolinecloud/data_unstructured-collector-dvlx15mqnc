package egovframework.unstructured.collector.voice.source;

import egovframework.unstructured.collector.common.config.SourceTestFilter;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 운영 선별 테스트 조건이 실제 음성 조회 SQL(매퍼)에 붙는지 — H2 의 Mock 보라미를 실제로 조회한다.
 * 시뮬레이션 데이터는 기관코드가 전부 {@code CI00001} 이다({@link SimulationDataService}).
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "voice.source.mode=MOCK")
class BoramiSourceTestFilterTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        registry.add("log-collector.enabled", () -> "false");
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("voice.dirs.work", () -> tmp.resolve("work").toString());
    }

    private static final List<String> SPECL = List.of("0", "1", "2", "3", "5");

    @MockitoBean
    private SourceTestFilter filter;

    @Autowired
    private BoramiSourceClient source;

    @Autowired
    private SimulationDataService sim;

    @Autowired
    private egovframework.unstructured.collector.voice.batch.VoiceCollectService voice;

    @BeforeEach
    void seed() {
        when(filter.insttCds()).thenReturn(List.of());
        when(filter.corrNos()).thenReturn(List.of());
        when(filter.apply(any())).thenAnswer(inv -> inv.getArgument(0));
        when(filter.limitOr(anyInt())).thenAnswer(inv -> inv.getArgument(0));
        sim.seed();
    }

    private BatchWindow wide() {
        LocalDateTime now = LocalDateTime.now();
        return BatchWindow.manual(now.minusDays(2), now.plusDays(1));
    }

    @Test
    @DisplayName("조건 없음 = 기존 결과 그대로(접견 7건)")
    void off() {
        assertThat(source.findMeetTargets(wide(), SPECL, 100)).hasSize(7);
    }

    @Test
    @DisplayName("기관코드 — 맞는 기관이면 전부 · 없는 기관이면 0건(전체 조회로 새지 않는다)")
    void insttCd() {
        int meet = source.findMeetTargets(wide(), SPECL, 100).size();
        int phone = source.findPhoneTargets(wide(), SPECL, 100).size();

        when(filter.insttCds()).thenReturn(List.of("CI00001"));
        assertThat(source.findMeetTargets(wide(), SPECL, 100)).hasSize(meet);
        assertThat(source.findPhoneTargets(wide(), SPECL, 100)).hasSize(phone);

        when(filter.insttCds()).thenReturn(List.of("ZZ99999"));
        assertThat(source.findMeetTargets(wide(), SPECL, 100)).isEmpty();
        assertThat(source.findPhoneTargets(wide(), SPECL, 100)).isEmpty();
    }

    @Test
    @DisplayName("교정번호 — 지정한 수용자 건만")
    void corrNo() {
        List<String> two = List.of(SimulationDataService.corrNo(1), SimulationDataService.corrNo(2));
        when(filter.corrNos()).thenReturn(two);

        List<VoiceTarget> meet = source.findMeetTargets(wide(), SPECL, 100);
        assertThat(meet).isNotEmpty().allSatisfy(t -> assertThat(two).contains(t.corrNo()));
        List<VoiceTarget> phone = source.findPhoneTargets(wide(), SPECL, 100);
        assertThat(phone).allSatisfy(t -> assertThat(two).contains(t.corrNo()));
    }

    @Test
    @DisplayName("배치 연결 — 테스트 기간이 있으면 배치가 보는 창이 그 기간으로 바뀐다(대상 미리보기 기준)")
    void batchUsesTestWindow() {
        LocalDateTime f = LocalDateTime.of(2026, 1, 1, 0, 0);
        BatchWindow test = BatchWindow.manual(f, f.plusDays(1));
        when(filter.apply(any())).thenReturn(test);

        java.util.Map<String, Object> p = voice.pending(BatchWindow.daily(LocalDateTime.now()), null, false);
        assertThat(p.get("from")).isEqualTo(f.toString());
        assertThat(p.get("total")).as("시뮬레이션 데이터는 그 기간에 없다").isEqualTo(0);
    }

    @Test
    @DisplayName("배치 연결 — 조건 값이 틀렸으면 조회 전에 멈춘다(기본 기준으로 돌아가지 않는다)")
    void batchRefusesInvalid() {
        doThrow(new IllegalStateException("운영 테스트 조건(SOURCE_TEST_*) 값 오류로 조회하지 않음")).when(filter).requireValid();
        assertThatThrownBy(() -> voice.pending(BatchWindow.daily(LocalDateTime.now()), null, false))
                .hasMessageContaining("SOURCE_TEST_");
    }
}
