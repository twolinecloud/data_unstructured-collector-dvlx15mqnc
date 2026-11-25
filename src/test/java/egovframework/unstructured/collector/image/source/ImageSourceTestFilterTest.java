package egovframework.unstructured.collector.image.source;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.config.SourceTestFilter;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.image.batch.ImageTrace;
import egovframework.unstructured.collector.image.config.ImageProperties;
import egovframework.unstructured.collector.image.model.ImageTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 사진 대상 조회에 운영 선별 테스트 조건이 붙는지 — 실제 SQL 을 H2(PostgreSQL 모드)에서 돌린다.
 * 수용자 6명: 1~3 은 기관 A0001 · 4~6 은 기관 B0002 · 모두 수용 중(Y).
 */
class ImageSourceTestFilterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 10, 0);
    private JdbcTemplate jdbc;
    private final BoramiTableNames tables = mock(BoramiTableNames.class);

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:imgtestfilter;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        tearDown();
        jdbc.execute("CREATE TABLE TB_IRIM_BSIF_DS (CORR_NO VARCHAR(18) NOT NULL, IMAGE_SN SMALLINT NOT NULL, IMAGE_SE_CD VARCHAR(1),"
                + " IMAGE_CMMN_FILE_ID VARCHAR(20), CRT_DT TIMESTAMP, PRIMARY KEY (CORR_NO, IMAGE_SN))");
        jdbc.execute("CREATE TABLE TB_IRIM_PRBS_BS (CORR_NO VARCHAR(18) NOT NULL PRIMARY KEY, CORR_INSTT_CD VARCHAR(7), ACMD_STTUS_CD VARCHAR(1))");
        when(tables.irimBsifDs()).thenReturn("TB_IRIM_BSIF_DS");
        when(tables.irimPrbsBs()).thenReturn("TB_IRIM_PRBS_BS");
        for (int i = 1; i <= 6; i++) {
            jdbc.update("INSERT INTO TB_IRIM_BSIF_DS VALUES (?, 1, '1', ?, ?)", corr(i), "F" + i, Timestamp.valueOf(NOW));
            jdbc.update("INSERT INTO TB_IRIM_PRBS_BS VALUES (?, ?, 'Y')", corr(i), i <= 3 ? "A0001" : "B0002");
        }
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS TB_IRIM_BSIF_DS");
        jdbc.execute("DROP TABLE IF EXISTS TB_IRIM_PRBS_BS");
    }

    private static String corr(int i) {
        return String.format("2026%06d", i);
    }

    private ImageSourceService service(String... kv) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        ImageProperties props = new ImageProperties("1", "", 4, 2000, List.of("Y", "I", "C"), false, null, "IMAGE_COLLECT");
        return new ImageSourceService(jdbc, tables, mock(VoiceProperties.class), props, mock(ImageTrace.class),
                SourceTestFilter.parse(env, NOW));
    }

    private static List<String> drain(ImageSourceService.Cursor c) {
        List<String> out = new ArrayList<>();
        for (int guard = 0; guard < 50; guard++) {
            List<ImageTarget> rows = c.next();
            if (rows.isEmpty()) {
                return out;
            }
            rows.forEach(t -> out.add(t.corrNo()));
        }
        throw new AssertionError("0건이 오지 않는다");
    }

    @Test
    @DisplayName("조건 없음 = 기존 그대로(6명)")
    void off() {
        assertThat(drain(service().open(ImageSourceService.Selection.all(null), 2))).hasSize(6);
    }

    @Test
    @DisplayName("기관코드 — 수용자기본의 교정기관코드로 거른다")
    void insttCd() {
        assertThat(drain(service("SOURCE_TEST_INSTT_CD", "B0002").open(ImageSourceService.Selection.all(null), 2)))
                .containsExactly(corr(4), corr(5), corr(6));
        assertThat(drain(service("SOURCE_TEST_INSTT_CD", "Z9999").open(ImageSourceService.Selection.all(null), 2))).isEmpty();
    }

    @Test
    @DisplayName("교정번호 · 상한 — 목록만 · 요청 목록과는 교집합(비면 0건, 전체로 새지 않는다)")
    void corrNoAndMax() {
        ImageSourceService s = service("SOURCE_TEST_CORR_NO", corr(2) + "," + corr(5), "SOURCE_TEST_MAX_ROWS", "1");
        assertThat(drain(s.open(ImageSourceService.Selection.all(null), 2))).containsExactly(corr(2));

        ImageSourceService s2 = service("SOURCE_TEST_CORR_NO", corr(2) + "," + corr(5));
        assertThat(drain(s2.open(new ImageSourceService.Selection(List.of(corr(5), corr(6)), null, null), 2)))
                .containsExactly(corr(5));
        assertThat(drain(s2.open(new ImageSourceService.Selection(List.of(corr(6)), null, null), 2))).isEmpty();
    }

    @Test
    @DisplayName("기간만 준 조건 · 틀린 조건은 사진 조회를 막는다(전원 조회 방지)")
    void blocksUnsafe() {
        assertThatThrownBy(() -> service("SOURCE_TEST_FROM", "2026-11-20").open(ImageSourceService.Selection.all(null), 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("사진은 기간 조건이 없어");
        assertThatThrownBy(() -> service("SOURCE_TEST_MAX_ROWS", "-1").open(ImageSourceService.Selection.all(null), 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SOURCE_TEST_MAX_ROWS");
    }
}
