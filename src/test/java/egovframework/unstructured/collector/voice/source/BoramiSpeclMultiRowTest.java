package egovframework.unstructured.collector.voice.source;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 특이수용자 지정이 둘 이상인 수용자 — 접견 · 전화 조회가 그 사람의 녹취를 <b>한 번만</b> 돌려주는가.
 *
 * <p><b>왜</b>: 특이수용자상세 PK 는 (CORR_NO, PTCR_PRSR_DTL_SN) 이라 한 사람이 '마약(1)' 과 '엄격(3)' 을 동시에
 * 지정받을 수 있다. 특이수용자를 INNER JOIN 으로 붙이던 때(~2026-11-26)는 녹취 1건이 지정 수만큼 나왔다 —
 * 같은 녹취를 STT · 전송 · T4 에 두 번 남기거나, 워커가 여럿이면 두 건이 같은 수신 파일을 두고 부딪혀 둘 다 실패했다
 * ({@code SpeclMultiRowBatchTest}). 지금은 EXISTS 다.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "voice.source.mode=MOCK")
class BoramiSpeclMultiRowTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        registry.add("log-collector.enabled", () -> "false");
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("voice.dirs.work", () -> tmp.resolve("work").toString());
    }

    private static final List<String> SPECL = List.of("0", "1", "2", "3", "5");

    @Autowired private BoramiSourceClient source;
    @Autowired private SimulationDataService sim;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BoramiTableNames tables;

    private BatchWindow wide() {
        LocalDateTime now = LocalDateTime.now();
        return BatchWindow.manual(now.minusDays(2), now.plusDays(1));
    }

    @BeforeEach
    void seed() {
        sim.seed();
    }

    /** SIM 수용자 001(이미 '마약' 지정)에 지정 행을 하나 더 넣는다. 해제일자를 주면 해제된 지정이다. */
    private void addDesignation(String code, String releasedYmd) {
        Timestamp ts = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO " + tables.imscPtprDt()
                        + " (CORR_NO, PTCR_PRSR_DTL_SN, SPECL_MNG_SE_CD, PTCR_PRSR_SE_CD, PTCR_PRSR_APNT_YMD, PTCR_PRSR_RMV_YMD,"
                        + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID) VALUES (?,?,?,?,?,?,?,?,?,?)",
                SimulationDataService.corrNo(1), 2, code, "A01", "20260201", releasedYmd, ts, "simadm", ts, "simadm");
    }

    private static long count(List<VoiceTarget> rows, String key) {
        return rows.stream().filter(t -> t.idempotencyKey().equals(key)).count();
    }

    @Test
    @DisplayName("지정이 둘(마약 + 엄격) — 접견 · 전화 모두 그 사람의 녹취를 한 번만 돌려준다 · 전체 건수도 그대로")
    void twoActiveDesignationsReturnOnce() {
        int meetBefore = source.findMeetTargets(wide(), SPECL, 100).size();
        int phoneBefore = source.findPhoneTargets(wide(), SPECL, 100).size();

        addDesignation("3", null);

        List<VoiceTarget> meet = source.findMeetTargets(wide(), SPECL, 100);
        List<VoiceTarget> phone = source.findPhoneTargets(wide(), SPECL, 100);
        assertThat(count(meet, "SIM-MEET-001")).isEqualTo(1);
        assertThat(count(phone, "SIM-PHONE-001")).isEqualTo(1);
        assertThat(meet).hasSize(meetBefore);
        assertThat(phone).hasSize(phoneBefore);
    }

    @Test
    @DisplayName("두 번째 지정이 대상 밖 코드(9)여도 — 대상 코드 지정이 하나 있으면 한 번 나온다")
    void otherCodeDoesNotMatter() {
        addDesignation("9", null);

        assertThat(count(source.findMeetTargets(wide(), SPECL, 100), "SIM-MEET-001")).isEqualTo(1);
    }

    @Test
    @DisplayName("두 번째 지정이 해제됐으면 — 해제 조건으로 걸러져 한 번")
    void releasedDesignationIgnored() {
        addDesignation("3", "20260301");

        assertThat(count(source.findMeetTargets(wide(), SPECL, 100), "SIM-MEET-001")).isEqualTo(1);
    }
}
