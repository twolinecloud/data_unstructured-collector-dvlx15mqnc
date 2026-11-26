package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.common.model.ProcStatus;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.voice.source.BoramiSourceRouter;
import egovframework.unstructured.collector.voice.source.SimulationDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * 같은 녹취가 원천 조회에 두 번 나와도 — 배치 한 번에서 <b>한 번만</b> 처리되는가.
 *
 * <p><b>왜</b>: 멱등 표식은 배치 끝 전송 수락 뒤에야 남아, 같은 실행 안의 두 건은 둘 다 '미처리' 로 통과한다.
 * 고치기 전(2026-11-26 dev d172f02) 로컬 재현: 지정이 둘인 수용자의 접견이 워커 1개면 STT · 전송 · T4 에 두 번,
 * 워커 4개면 두 건이 같은 수신 파일을 두고 부딪혀 <b>둘 다 수집 단계 실패</b>(복호화 실패 + 대기 타임아웃)였다.
 * 둑은 둘이다 — SQL 의 EXISTS(원인) · {@code scan} 의 키 중복 제거(원천이 다른 이유로 중복을 줄 때).</p>
 */
@SpringBootTest
@ActiveProfiles("local")
class SpeclMultiRowBatchTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        registry.add("voice.source.mode", () -> "MOCK");
        registry.add("voice.broker.mode", () -> "MOCK");
        registry.add("log-collector.enabled", () -> "false");
        registry.add("voice.dirs.base-dir", () -> tmp.toString());
        registry.add("voice.dirs.receive-meet", () -> tmp.resolve("raw/meet").toString());
        registry.add("voice.dirs.receive-phone", () -> tmp.resolve("raw/phone").toString());
        registry.add("voice.dirs.work", () -> tmp.resolve("work").toString());
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("voice.sync.wait-timeout-sec", () -> "15");
        registry.add("voice.sync.stable-check-ms", () -> "50");
    }

    private static final String KEY = "SIM-MEET-001";

    @Autowired private VoiceCollectService collect;
    @Autowired private SimulationDataService sim;
    @Autowired private IdempotencyGuard idempotency;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BoramiTableNames tables;
    @MockitoSpyBean private LogCollectorClient logCollector;
    @MockitoSpyBean private BoramiSourceRouter source;

    @BeforeEach
    void seed() {
        idempotency.clearAll();
        sim.seed();
        clearInvocations(logCollector);
    }

    private static BatchWindow wide() {
        LocalDateTime now = LocalDateTime.now();
        return BatchWindow.manual(now.minusDays(2), now.plusDays(1));
    }

    private VoiceBatchResult runMeet(int workers) {
        return collect.run(wide(), List.of(VoiceKind.MEET), "TEST", true, ResumeMode.FULL, null, workers);
    }

    @SuppressWarnings("unchecked")
    private List<LogCollectorClient.FileProcReq> t4Rows() {
        ArgumentCaptor<List<LogCollectorClient.FileProcReq>> rows = ArgumentCaptor.forClass(List.class);
        verify(logCollector, atLeastOnce()).createFileProcs(anyString(), rows.capture());
        List<LogCollectorClient.FileProcReq> all = new ArrayList<>();
        rows.getAllValues().forEach(all::addAll);
        return all;
    }

    /** 대상 · 결과 · T4 · 전송 모두에서 그 녹취가 한 번 · 성공 */
    private void assertProcessedOnce(VoiceBatchResult r, int expectedTargets) {
        List<FileProcOutcome> mine = r.outcomes().stream()
                .filter(o -> o.target().idempotencyKey().equals(KEY)).toList();
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).status()).isEqualTo(ProcStatus.SUCCESS);
        assertThat(r.targetCnt()).isEqualTo(expectedTargets);
        assertThat(r.failCnt()).isZero();
        assertThat(t4Rows()).filteredOn(x -> KEY.equals(x.recFileId())).hasSize(1);
        assertThat(r.transfer().get("sent")).isEqualTo(String.valueOf(expectedTargets));
    }

    @ParameterizedTest(name = "워커 {0}개")
    @ValueSource(ints = {1, 4})
    @DisplayName("특이수용자 지정이 둘인 수용자의 접견 — 한 번만 처리 · 성공 (순차 · 동시 모두)")
    void twoDesignationsProcessedOnce(int workers) {
        int baseline = source.findMeetTargets(wide(), List.of("0", "1", "2", "3", "5"), 100).size();
        Timestamp ts = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO " + tables.imscPtprDt()
                        + " (CORR_NO, PTCR_PRSR_DTL_SN, SPECL_MNG_SE_CD, PTCR_PRSR_SE_CD, PTCR_PRSR_APNT_YMD, PTCR_PRSR_RMV_YMD,"
                        + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID) VALUES (?,?,?,?,?,?,?,?,?,?)",
                SimulationDataService.corrNo(1), 2, "3", "A01", "20260201", null, ts, "simadm", ts, "simadm");

        assertProcessedOnce(runMeet(workers), baseline);
    }

    @ParameterizedTest(name = "워커 {0}개")
    @ValueSource(ints = {1, 4})
    @DisplayName("두 번째 둑 — 원천 조회가 같은 행을 두 번 돌려줘도 배치는 한 번만 싣는다")
    void sourceDuplicateRowsDeduplicated(int workers) {
        int baseline = source.findMeetTargets(wide(), List.of("0", "1", "2", "3", "5"), 100).size();
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<VoiceTarget> real = (List<VoiceTarget>) inv.callRealMethod();
            List<VoiceTarget> withDup = new ArrayList<>(real);
            real.stream().filter(t -> t.idempotencyKey().equals(KEY)).findFirst().ifPresent(withDup::add);
            return withDup;
        }).when(source).findMeetTargets(any(BatchWindow.class), anyList(), anyInt(), eq(0));

        assertProcessedOnce(runMeet(workers), baseline);
    }

    @Test
    @DisplayName("중복이 없으면 — 종전과 같다(대상 전부 성공)")
    void noDuplicateUnchanged() {
        int baseline = source.findMeetTargets(wide(), List.of("0", "1", "2", "3", "5"), 100).size();

        VoiceBatchResult r = runMeet(1);

        assertThat(r.targetCnt()).isEqualTo(baseline);
        assertThat(r.successCnt()).isEqualTo(baseline);
        assertThat(r.failCnt()).isZero();
    }
}
