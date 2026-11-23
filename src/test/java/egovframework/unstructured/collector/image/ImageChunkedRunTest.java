package egovframework.unstructured.collector.image;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.image.model.ImageOutcome;
import egovframework.unstructured.collector.image.sim.ImageSimulationService;
import egovframework.unstructured.collector.image.store.InmatePhotoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 호출부 — 청크(여기선 {@code image.chunk-size=2})를 0건이 나올 때까지 끝까지 돌려 전원을 처리한다.
 * 예전엔 한 실행이 첫 {@code max-per-run} 명만 보고 끝났다.
 *
 * <p>설정은 {@link ImagePipelineTest} 와 같다(로컬 H2 · 브로커 MOCK · 복호화 REAL 테스트 키 · Admin DB H2 고정).</p>
 */
@SpringBootTest(properties = {
        "image.admin-db.url=jdbc:h2:mem:admin-chunk;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "image.admin-db.username=sa",
        "image.admin-db.password=",
        "image.chunk-size=2",
        "voice.source.mode=MOCK",
        "voice.broker.mode=MOCK",
        "voice.decrypt.mode=REAL",
        "log-collector.enabled=false",
        "agent-connector.enabled=false",
        "voice.sim.seed-on-startup=false",
        "voice.sync.wait-timeout-sec=10",
        "voice.sync.stable-check-ms=20"
})
class ImageChunkedRunTest {

    private static Path root;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("imagechunk");
        Path key = root.resolve("rvs_key.txt");
        Files.writeString(key, String.join("\n",
                "secretkey=LOCAL_TEST_KEY_0123456789abcdefg", "iv=0000000000000000", "algorithm=AES",
                "ciphermode=CBC", "padding=PKCS5Padding", "charset=UTF-8"));
        registry.add("voice.dirs.base-dir", () -> root.toString().replace('\\', '/'));
        registry.add("voice.decrypt.rvs-key-path", key::toString);
    }

    @Autowired
    private ImageCollectService collect;
    @Autowired
    private ImageSimulationService sim;
    @Autowired
    private InmatePhotoRepository photos;
    @Autowired
    private JdbcTemplate boramiJdbc;
    @Autowired
    private BoramiTableNames tables;

    @BeforeEach
    void clean() {
        sim.clean();
        boramiJdbc.update("DELETE FROM " + tables.irimBsifDs() + " WHERE CORR_NO LIKE 'RLCHK%'");
        boramiJdbc.update("DELETE FROM " + tables.irimPrbsBs() + " WHERE CORR_NO LIKE 'RLCHK%'");
    }

    @AfterEach
    void cleanAfter() {
        clean();
    }

    @Test
    @DisplayName("5명 · 청크 2 — 2 · 2 · 1 청크를 다 돌고 0건에서 끝나 5명 전원 성공(상한 없이)")
    void processesEveryChunk() {
        sim.seed(5);

        ImageCollectService.ImageRunResult r = collect.run(new ImageCollectService.ImageRunRequest(null,
                ImageSimulationService.PREFIX, null, 2, false, 0L, "TEST", true, null, null));

        assertThat(r.total()).isEqualTo(5);
        assertThat(r.success()).as("실패: %s", r.outcomes()).isEqualTo(5);
        assertThat(r.outcomes()).extracting(ImageOutcome::corrNo).doesNotHaveDuplicates().isSorted();
        assertThat(photos.stats(ImageSimulationService.PREFIX).get("sim")).isEqualTo(5L);

        ImageCollectService.ImageRunResult again = collect.run(new ImageCollectService.ImageRunRequest(null,
                ImageSimulationService.PREFIX, null, 2, false, 0L, "TEST", true, null, null));
        assertThat(again.total()).as("다시 돌려도 전원을 본다 — 건너뛴 건이 앞자리를 막지 않는다").isEqualTo(5);
        assertThat(again.skipped()).isEqualTo(5);
    }

    @Test
    @DisplayName("limit 3 · 청크 2 — 총 3명에서 멈춘다(청크를 넘어 총수로 자른다)")
    void limitAcrossChunks() {
        sim.seed(5);

        ImageCollectService.ImageRunResult r = collect.run(new ImageCollectService.ImageRunRequest(null,
                ImageSimulationService.PREFIX, 3, 2, false, 0L, "TEST", true, null, null));

        assertThat(r.total()).isEqualTo(3);
        assertThat(r.outcomes()).extracting(ImageOutcome::corrNo)
                .containsExactly(ImageSimulationService.corrNo(1), ImageSimulationService.corrNo(2), ImageSimulationService.corrNo(3));
    }

    @Test
    @DisplayName("실제 수집 — 수용상태 N(출소) · 수용자기본 없는 수용자는 대상에서 빠지고 Y·I·C 만 모든 청크에서 대상")
    void realRunCurrentInmatesOnly() {
        String[] st = {"Y", "N", "I", null, "C"};
        for (int i = 0; i < st.length; i++) {
            String corr = "RLCHK" + i;
            boramiJdbc.update("INSERT INTO " + tables.irimBsifDs()
                    + " (CORR_NO, IMAGE_SN, IMAGE_SE_CD, IMAGE_CMMN_FILE_ID) VALUES (?, 1, '1', ?)", corr, "RLCHKF" + i);
            if (st[i] != null) {
                boramiJdbc.update("INSERT INTO " + tables.irimPrbsBs() + " (CORR_NO, ACMD_STTUS_CD) VALUES (?, ?)", corr, st[i]);
            }
        }

        ImageCollectService.ImageRunResult r = collect.run(new ImageCollectService.ImageRunRequest(null, null, null, 2, false,
                0L, "TEST", true, null, null));

        List<String> seen = r.outcomes().stream().map(ImageOutcome::corrNo).filter(c -> c.startsWith("RLCHK")).toList();
        assertThat(seen).containsExactly("RLCHK0", "RLCHK2", "RLCHK4");
    }
}
