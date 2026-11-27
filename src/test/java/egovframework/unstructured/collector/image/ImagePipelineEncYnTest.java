package egovframework.unstructured.collector.image;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.image.model.ImageFormat;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사진 복호화 <b>예전 동작</b>({@code image.decrypt=ENC_YN}) — 기본(SKIP · 사진 평문 정책)에서 되돌릴 때의 경로가 그대로 도는지.
 * 복호화 REAL 이면 더미 원본을 접견과 같은 RVS 키로 암호화해 두고({@code .jpg.enc} · 메타 'Y'), 수집이 풀어 원문과 같게 저장한다.
 *
 * <p>Admin DB 는 {@link ImagePipelineTest} 와 다른 이름의 H2 로 둔다(설정이 달라 컨텍스트가 따로 뜬다).</p>
 */
@SpringBootTest(properties = {
        "image.admin-db.url=jdbc:h2:mem:admin-encyn;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "image.admin-db.username=sa",
        "image.admin-db.password=",
        "image.decrypt=ENC_YN",
        "voice.source.mode=MOCK",
        "voice.broker.mode=MOCK",
        "voice.decrypt.mode=REAL",
        "log-collector.enabled=false",
        "agent-connector.enabled=false",
        "voice.sim.seed-on-startup=false",
        "voice.sync.wait-timeout-sec=10",
        "voice.sync.stable-check-ms=20"
})
class ImagePipelineEncYnTest {

    private static Path root;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("imagepipe-encyn");
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
    }

    @AfterEach
    void cleanAfter() {
        sim.clean();
    }

    @Test
    @DisplayName("ENC_YN · REAL — 더미 원본은 암호문 .jpg.enc · 메타 'Y', 수집이 RVS 키로 풀어 원문과 같은 사진을 저장한다")
    void encYnDecryptsEncryptedOriginals() throws Exception {
        sim.seed(2);
        Path original = sim.originalDir().resolve(ImageSimulationService.corrNo(1) + "_2.jpg.enc");
        assertThat(original).isRegularFile();
        assertThat(ImageFormat.detect(Files.readAllBytes(original))).as("원본은 암호문").isNull();
        assertThat(boramiJdbc.queryForList("SELECT DISTINCT CMMN_FILE_ENC_YN FROM " + tables.smsmCmfiBs()
                + " WHERE CMMN_FILE_ID LIKE 'SIMIMGF%'", String.class)).containsExactly("Y");

        ImageCollectService.ImageRunResult r = collect.run(new ImageCollectService.ImageRunRequest(null,
                ImageSimulationService.PREFIX, null, 2, false, 0L, "TEST", true, null, null));

        assertThat(r.success()).as("실패: %s", r.outcomes()).isEqualTo(2);
        List<Map<String, Object>> rows = photos.listByPrefix(ImageSimulationService.PREFIX, 10);
        Map<String, String> paths = rows.stream().collect(Collectors.toMap(m -> (String) m.get("corr_no"), m -> (String) m.get("photo_path")));
        assertThat(sim.verifyPlain(paths)).as("복호화 결과 = 원문").hasSize(2).doesNotContainValue(false);
    }
}
