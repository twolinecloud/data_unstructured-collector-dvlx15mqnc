package egovframework.unstructured.collector.image;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin DB 공통코드 C05 3단계 정비 스크립트({@code db/admin/V2__c05_unstructured_three_steps.sql})를
 * admin-api V1 과 같은 표 · 행에 실제로 돌려 본다(H2 PostgreSQL 모드).
 */
class AdminC05MigrationTest {

    private static final String SCRIPT = "db/admin/V2__c05_unstructured_three_steps.sql";

    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        ds = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-c05;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS kcais");
        // admin-api V1__create_common_code_tables.sql 의 상세 표(키 · 사용 여부 · 정렬 제약 포함)
        jdbc.execute("""
                CREATE TABLE kcais.tb_comm_code (
                    code_grp_id VARCHAR(20) NOT NULL, code_val VARCHAR(30) NOT NULL, code_nm VARCHAR(100) NOT NULL,
                    code_desc VARCHAR(500), sort_seq SMALLINT NOT NULL DEFAULT 1, upper_code_val VARCHAR(30),
                    use_yn CHAR(1) NOT NULL DEFAULT 'Y', reg_user_id VARCHAR(50), reg_dtm TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    mod_user_id VARCHAR(50), mod_dtm TIMESTAMP,
                    CONSTRAINT pk_comm_code PRIMARY KEY (code_grp_id, code_val),
                    CONSTRAINT ck_comm_code_use_yn CHECK (use_yn IN ('Y', 'N')),
                    CONSTRAINT ck_comm_code_sort_seq CHECK (sort_seq > 0))""");
        jdbc.execute("""
                INSERT INTO kcais.tb_comm_code (code_grp_id, code_val, code_nm, code_desc, sort_seq) VALUES
                  ('C01', 'UNSTRUCTURED', '비정형', '음성 등 비정형 파일 데이터', 2),
                  ('C05', 'COLLECT', '수집',     '원천 데이터 수집 단계',               1),
                  ('C05', 'CLEANSE', '정제',     '수집 데이터 정제 및 표준화 단계',     2),
                  ('C05', 'ANALYZE', '분석',     '데이터 분석 단계',                    3),
                  ('C05', 'DEIDENT', '비식별화', '개인정보 비식별 처리 단계',           4),
                  ('C05', 'STORE',   '저장',     '처리 결과 저장 단계',                 5),
                  ('C05', 'SEND',    '전송',     '클라우드 또는 외부 시스템 전송 단계', 6)""");
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP SCHEMA kcais CASCADE");
    }

    private void apply() {
        new ResourceDatabasePopulator(new ClassPathResource(SCRIPT)).execute(ds);
    }

    private Map<String, String> c05() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT code_val, sort_seq, use_yn FROM kcais.tb_comm_code WHERE code_grp_id = 'C05'");
        return rows.stream().collect(Collectors.toMap(r -> (String) r.get("code_val"),
                r -> r.get("sort_seq") + "/" + String.valueOf(r.get("use_yn")).trim()));
    }

    @Test
    @DisplayName("DEIDENT · CLEANSE 는 사용 안 함, COLLECT · ANALYZE · SEND 는 정렬 1 · 2 · 3 — 행은 지우지 않고 STORE · 다른 그룹은 그대로")
    void threeStepsActiveOthersOff() {
        apply();

        assertThat(c05()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "COLLECT", "1/Y", "ANALYZE", "2/Y", "SEND", "3/Y",
                "CLEANSE", "2/N", "DEIDENT", "4/N", "STORE", "5/Y"));
        assertThat(jdbc.queryForList("SELECT code_val FROM kcais.tb_comm_code WHERE code_grp_id = 'C05' AND use_yn = 'Y' "
                + "AND code_val <> 'STORE' ORDER BY sort_seq", String.class))
                .as("admin-api 공통코드 API(USE_YN='Y' · 정렬순)가 보는 비정형 3단계").containsExactly("COLLECT", "ANALYZE", "SEND");
        assertThat(jdbc.queryForObject("SELECT use_yn || sort_seq FROM kcais.tb_comm_code WHERE code_grp_id = 'C01'", String.class))
                .isEqualTo("Y2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kcais.tb_comm_code WHERE mod_user_id = 'unstructured-collector'", Integer.class))
                .as("바뀐 행만 수정자를 남긴다 — COLLECT 는 이미 1 · Y 라 그대로").isEqualTo(4);
    }

    @Test
    @DisplayName("두 번 돌려도 결과가 같다(멱등) — 두 번째는 아무 행도 고치지 않는다")
    void idempotent() {
        apply();
        jdbc.update("UPDATE kcais.tb_comm_code SET mod_user_id = 'first-run'");
        apply();

        assertThat(c05()).containsEntry("ANALYZE", "2/Y").containsEntry("DEIDENT", "4/N");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kcais.tb_comm_code WHERE mod_user_id = 'unstructured-collector'", Integer.class))
                .isZero();
    }
}
