package egovframework.unstructured.collector.image.source;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.image.batch.ImageTrace;
import egovframework.unstructured.collector.image.config.ImageProperties;
import egovframework.unstructured.collector.image.model.ImageTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 사진 대상 조회 커서 — 청크(키셋 {@code CORR_NO > 마지막})로 0건이 나올 때까지 · 현재 수용자 필터(수용자기본 수용상태) ·
 * 청크 경계(한 수용자 사진 여러 장) · 상한 · 수동 목록. 실제 SQL 을 H2(PostgreSQL 모드)에서 돌린다.
 */
class ImageSourceCursorTest {

    private static final Timestamp NOW = Timestamp.valueOf(LocalDateTime.of(2026, 11, 23, 10, 0));

    private JdbcTemplate jdbc;
    private final BoramiTableNames tables = mock(BoramiTableNames.class);
    private List<String> statusCodes = List.of("Y", "I", "C");

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:imgcursor;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS TB_IRIM_BSIF_DS");
        jdbc.execute("DROP TABLE IF EXISTS TB_IRIM_PRBS_BS");
        jdbc.execute("CREATE TABLE TB_IRIM_BSIF_DS (CORR_NO VARCHAR(18) NOT NULL, IMAGE_SN SMALLINT NOT NULL, IMAGE_SE_CD VARCHAR(1),"
                + " IMAGE_CMMN_FILE_ID VARCHAR(20), CRT_DT TIMESTAMP, PRIMARY KEY (CORR_NO, IMAGE_SN))");
        jdbc.execute("CREATE TABLE TB_IRIM_PRBS_BS (CORR_NO VARCHAR(18) NOT NULL PRIMARY KEY, ACMD_STTUS_CD VARCHAR(1))");
        when(tables.irimBsifDs()).thenReturn("TB_IRIM_BSIF_DS");
        when(tables.irimPrbsBs()).thenReturn("TB_IRIM_PRBS_BS");
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS TB_IRIM_BSIF_DS");
        jdbc.execute("DROP TABLE IF EXISTS TB_IRIM_PRBS_BS");
    }

    private ImageSourceService service() {
        ImageProperties props = new ImageProperties("1", "", 4, 2000, statusCodes, false, null, "IMAGE_COLLECT");
        return new ImageSourceService(jdbc, tables, mock(VoiceProperties.class), props, mock(ImageTrace.class),
                egovframework.unstructured.collector.common.config.SourceTestFilter.none());
    }

    /** 수용자 n명 — 각자 사진 1장(순번 1) · 수용상태는 status(i). status 가 null 이면 수용자기본 행이 없다. */
    private void inmates(int n, IntFunction<String> corr, IntFunction<String> status) {
        List<Object[]> photo = new ArrayList<>();
        List<Object[]> base = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            photo.add(new Object[]{corr.apply(i), 1, "1", "F" + i, NOW});
            String st = status.apply(i);
            if (st != null) {
                base.add(new Object[]{corr.apply(i), st});
            }
        }
        jdbc.batchUpdate("INSERT INTO TB_IRIM_BSIF_DS (CORR_NO, IMAGE_SN, IMAGE_SE_CD, IMAGE_CMMN_FILE_ID, CRT_DT) VALUES (?, ?, ?, ?, ?)", photo);
        if (!base.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO TB_IRIM_PRBS_BS (CORR_NO, ACMD_STTUS_CD) VALUES (?, ?)", base);
        }
    }

    private void photo(String corr, int sn, String seCd) {
        jdbc.update("INSERT INTO TB_IRIM_BSIF_DS (CORR_NO, IMAGE_SN, IMAGE_SE_CD, IMAGE_CMMN_FILE_ID, CRT_DT) VALUES (?, ?, ?, ?, ?)",
                corr, sn, seCd, corr + "-" + sn, NOW);
    }

    private void base(String corr, String status) {
        jdbc.update("INSERT INTO TB_IRIM_PRBS_BS (CORR_NO, ACMD_STTUS_CD) VALUES (?, ?)", corr, status);
    }

    private static String corr(int i) {
        return String.format("2026%06d", i);
    }

    private static List<List<ImageTarget>> drain(ImageSourceService.Cursor c) {
        List<List<ImageTarget>> chunks = new ArrayList<>();
        for (int guard = 0; guard < 100; guard++) {
            List<ImageTarget> rows = c.next();
            chunks.add(rows);
            if (rows.isEmpty()) {
                return chunks;
            }
        }
        throw new AssertionError("0건이 오지 않는다");
    }

    @Test
    @DisplayName("2,000건 초과 — 1차 2,000 · 2차 500 · 3차 0건으로 끝나고 2,500명을 빠짐없이 · 겹침 없이 오름차순으로")
    void readsAllChunksUntilEmpty() {
        inmates(2500, ImageSourceCursorTest::corr, i -> "Y");

        ImageSourceService.Cursor c = service().open(ImageSourceService.Selection.all(null), 2000);
        List<List<ImageTarget>> chunks = drain(c);

        assertThat(chunks).extracting(List::size).containsExactly(2000, 500, 0);
        List<String> all = chunks.stream().flatMap(List::stream).map(ImageTarget::corrNo).toList();
        assertThat(all).hasSize(2500).doesNotHaveDuplicates().isSorted();
        assertThat(all.get(0)).isEqualTo(corr(1));
        assertThat(all.get(2499)).isEqualTo(corr(2500));
        assertThat(chunks.get(0).get(1999).corrNo()).as("1차 마지막").isEqualTo(corr(2000));
        assertThat(chunks.get(1).get(0).corrNo()).as("2차 첫 행 = 1차 마지막 바로 다음").isEqualTo(corr(2001));
        assertThat(c.chunks()).isEqualTo(2);
        assertThat(c.taken()).isEqualTo(2500);
        assertThat(c.next()).as("끝난 뒤에도 0건").isEmpty();
    }

    @Test
    @DisplayName("청크 크기가 딱 나눠떨어져도(2,000 × 2) 마지막 0건 청크로 끝난다")
    void exactMultiple() {
        inmates(4000, ImageSourceCursorTest::corr, i -> "Y");
        assertThat(drain(service().open(ImageSourceService.Selection.all(null), 2000))).extracting(List::size)
                .containsExactly(2000, 2000, 0);
    }

    @Test
    @DisplayName("현재 수용자만 — 수용상태 Y·I·C 는 받고 N(출소)·수용자기본 없는 수용자는 뺀다 · 모든 청크에 같은 조건")
    void currentInmatesOnlyOnEveryChunk() {
        // 10명 중 i%5: 0=N, 1=Y, 2=I, 3=C, 4=수용자기본 없음 → Y·I·C 6명
        String[] st = {"N", "Y", "I", "C", null};
        inmates(10, ImageSourceCursorTest::corr, i -> st[i % 5]);

        List<List<ImageTarget>> chunks = drain(service().open(ImageSourceService.Selection.all(null), 2));

        assertThat(chunks).extracting(List::size).containsExactly(2, 2, 2, 0);
        assertThat(chunks.stream().flatMap(List::stream).map(ImageTarget::corrNo))
                .containsExactly(corr(1), corr(2), corr(3), corr(6), corr(7), corr(8));
    }

    @Test
    @DisplayName("청크 경계에 사진 여러 장인 수용자 — 최신 순번 1장만 · 다음 청크로 넘어가 겹치거나 빠지지 않는다")
    void boundaryInmateWithManyPhotos() {
        // A(1장) · B(3장 — 순번 1·2·5, 최신 5) · C(2장 + 사진 아닌 이미지 순번 9) · D(1장)
        String a = corr(1), b = corr(2), cc = corr(3), d = corr(4);
        photo(a, 1, "1");
        photo(b, 1, "1");
        photo(b, 2, "1");
        photo(b, 5, "1");
        photo(cc, 1, "1");
        photo(cc, 3, "1");
        photo(cc, 9, "2");
        photo(d, 1, "1");
        for (String x : List.of(a, b, cc, d)) {
            base(x, "Y");
        }

        List<List<ImageTarget>> chunks = drain(service().open(ImageSourceService.Selection.all(null), 2));

        assertThat(chunks).extracting(List::size).containsExactly(2, 2, 0);
        assertThat(chunks.get(0)).extracting(ImageTarget::corrNo).containsExactly(a, b);
        assertThat(chunks.get(0).get(1).imageSn()).as("B 는 청크 끝 — 최신 순번 5").isEqualTo(5);
        assertThat(chunks.get(1)).extracting(ImageTarget::corrNo).as("B 의 나머지 사진이 다음 청크에 오지 않는다").containsExactly(cc, d);
        assertThat(chunks.get(1).get(0).imageSn()).as("C 는 사진(구분 1) 중 최신 3 — 구분 2 순번 9 는 아님").isEqualTo(3);
    }

    @Test
    @DisplayName("수용자기본에 같은 교정번호가 여러 행이어도(EXISTS) 사진 행이 불어나지 않는다")
    void existsDoesNotDuplicate() {
        jdbc.execute("ALTER TABLE TB_IRIM_PRBS_BS DROP PRIMARY KEY");
        inmates(3, ImageSourceCursorTest::corr, i -> "Y");
        base(corr(2), "I");
        base(corr(2), "C");

        List<String> all = drain(service().open(ImageSourceService.Selection.all(null), 2)).stream().flatMap(List::stream)
                .map(ImageTarget::corrNo).toList();
        assertThat(all).containsExactly(corr(1), corr(2), corr(3));
    }

    @Test
    @DisplayName("수용상태 설정을 비우면 거르지 않는다(출소자 · 수용자기본 없는 수용자까지)")
    void emptyStatusCodesDisablesFilter() {
        statusCodes = List.of();
        String[] st = {"N", "Y", null};
        inmates(6, ImageSourceCursorTest::corr, i -> st[i % 3]);
        assertThat(drain(service().open(ImageSourceService.Selection.all(null), 4))).extracting(List::size)
                .containsExactly(4, 2, 0);
    }

    @Test
    @DisplayName("설정값은 어떤 코드든 그대로 — I 만 주면 이송자만")
    void customStatusCodes() {
        statusCodes = List.of(" I ", "");
        String[] st = {"N", "Y", "I", "C"};
        inmates(8, ImageSourceCursorTest::corr, i -> st[i % 4]);
        assertThat(drain(service().open(ImageSourceService.Selection.all(null), 10)).stream().flatMap(List::stream)
                .map(ImageTarget::corrNo)).containsExactly(corr(2), corr(6));
    }

    @Test
    @DisplayName("합성 데이터 — 실제 수집은 DMYIMG 를 상태 없이 받고 SIMIMG 는 뺀다 · SIM 접두 실행은 상태로 거르지 않는다")
    void syntheticRows() {
        inmates(2, i -> "DMYIMG261123000" + i, i -> null);
        inmates(2, i -> "SIMIMG261123000" + i, i -> null);
        inmates(2, ImageSourceCursorTest::corr, i -> i == 1 ? "Y" : "N");

        List<String> real = drain(service().open(ImageSourceService.Selection.all(null), 10)).stream().flatMap(List::stream)
                .map(ImageTarget::corrNo).toList();
        assertThat(real).containsExactly(corr(1), "DMYIMG2611230001", "DMYIMG2611230002");

        List<String> simRun = drain(service().open(new ImageSourceService.Selection(null, "SIMIMG", null), 1)).stream()
                .flatMap(List::stream).map(ImageTarget::corrNo).toList();
        assertThat(simRun).containsExactly("SIMIMG2611230001", "SIMIMG2611230002");

        List<String> realPrefix = drain(service().open(new ImageSourceService.Selection(null, "2026", null), 10)).stream()
                .flatMap(List::stream).map(ImageTarget::corrNo).toList();
        assertThat(realPrefix).as("합성 아닌 접두는 상태로 거른다").containsExactly(corr(1));
    }

    @Test
    @DisplayName("limit — 청크를 넘어 총수로 자른다(5명 · 청크 2 → 2 · 2 · 1 · 0)")
    void limitCapsAcrossChunks() {
        inmates(9, ImageSourceCursorTest::corr, i -> "Y");
        assertThat(drain(service().open(ImageSourceService.Selection.all(5), 2))).extracting(List::size)
                .containsExactly(2, 2, 1, 0);
    }

    @Test
    @DisplayName("수동 목록(corrNos) — 목록을 청크로 나눠 끝까지 · 상태 필터 적용 · 중복 · 공백 무시")
    void explicitList() {
        inmates(6, ImageSourceCursorTest::corr, i -> i == 4 ? "N" : "Y");
        List<String> ask = List.of(corr(5), corr(1), " " + corr(4) + " ", corr(1), corr(3), corr(99));

        List<List<ImageTarget>> chunks = drain(service().open(new ImageSourceService.Selection(ask, null, null), 2));

        assertThat(chunks.stream().flatMap(List::stream).map(ImageTarget::corrNo))
                .containsExactlyInAnyOrder(corr(5), corr(1), corr(3));
        assertThat(chunks.get(chunks.size() - 1)).isEmpty();
        assertThat(chunks).allMatch(ch -> ch.size() <= 2);
    }

    @Test
    @DisplayName("설정 — 기본값 chunk-size 2000 · active-status-codes [Y, I, C] · yml 의 'Y,I,C' 도 3개로(@DefaultValue 목록 함정)")
    void propertyBinding() {
        ImageProperties d = new Binder(new MapConfigurationPropertySource(Map.of("image.workers", "4")))
                .bindOrCreate("image", ImageProperties.class);
        assertThat(d.chunkSize()).isEqualTo(2000);
        assertThat(d.activeStatusCodes()).containsExactly("Y", "I", "C");

        ImageProperties y = new Binder(new MapConfigurationPropertySource(Map.of(
                "image.active-status-codes", "Y,I,C", "image.chunk-size", "500")))
                .bindOrCreate("image", ImageProperties.class);
        assertThat(y.activeStatusCodes()).containsExactly("Y", "I", "C");
        assertThat(y.chunkSize()).isEqualTo(500);
    }
}
