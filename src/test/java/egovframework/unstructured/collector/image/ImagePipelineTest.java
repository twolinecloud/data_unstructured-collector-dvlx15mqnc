package egovframework.unstructured.collector.image;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.config.SourcePoolPeak;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.image.config.AdminDb;
import egovframework.unstructured.collector.image.model.ImageOutcome;
import egovframework.unstructured.collector.image.model.ImageStage;
import egovframework.unstructured.collector.image.perf.ImagePerfService;
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

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수용자 이미지 파이프라인 — 보라미 조회 3단계 → 브로커 수신 → 복호화(접견과 같은 RVS 키) → 저장 → Admin DB 매핑.
 *
 * <p>로컬 H2(보라미 · Admin) · 브로커 MOCK · 복호화 REAL(테스트 키). SIM 데이터는 수용자마다 사진 2장(순번 1·2)과
 * 사진 아닌 이미지 1장(구분 '2', 순번 3)이라 <b>순번 2</b>가 골라져야 맞다.</p>
 */
@SpringBootTest(properties = {
        "voice.source.mode=MOCK",
        "voice.broker.mode=MOCK",
        "voice.decrypt.mode=REAL",
        "log-collector.enabled=false",
        "agent-connector.enabled=false",
        "voice.sim.seed-on-startup=false",
        "voice.sync.wait-timeout-sec=10",
        "voice.sync.stable-check-ms=20"
})
class ImagePipelineTest {

    private static Path root;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("imagepipe");
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
    private AdminDb adminDb;
    @Autowired
    private ImagePerfService perf;
    @Autowired
    private SourcePoolPeak pools;
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

    private ImageCollectService.ImageRunResult runSim(boolean force) {
        return collect.run(new ImageCollectService.ImageRunRequest(null, ImageSimulationService.PREFIX, null, 2, force,
                0L, "TEST", true));
    }

    @Test
    @DisplayName("3명 — 최신 사진(순번 2)을 받아 복호화해 저장하고, 매핑 3행 · 원문과 같은 이미지")
    void collectsLatestPhotoPerInmate() throws Exception {
        sim.seed(3);

        ImageCollectService.ImageRunResult r = runSim(false);

        assertThat(r.total()).isEqualTo(3);
        assertThat(r.success()).as("실패: %s", r.outcomes()).isEqualTo(3);
        assertThat(r.inserted()).isEqualTo(3);
        List<Map<String, Object>> rows = photos.listByPrefix(ImageSimulationService.PREFIX, 10);
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(m -> {
            assertThat(((Number) m.get("image_sn")).intValue()).as("구분 1 중 최신 — 순번 2").isEqualTo(2);
            assertThat(Files.isRegularFile(Path.of((String) m.get("photo_path")))).isTrue();
            assertThat(m.get("file_ext")).isEqualTo("jpg");
        });
        // 복호화가 정확했다 — 저장된 사진이 시딩한 원문과 같고, 실제로 이미지로 읽힌다
        Map<String, String> paths = rows.stream().collect(Collectors.toMap(m -> (String) m.get("corr_no"), m -> (String) m.get("photo_path")));
        assertThat(sim.verifyPlain(paths)).hasSize(3).containsOnlyKeys(paths.keySet()).doesNotContainValue(false);
        assertThat(ImageIO.read(Path.of(paths.values().iterator().next()).toFile())).isNotNull();
        // 단계 — 건마다 복호화·저장·매핑이 한 번씩, 조회·FILEKEY 는 배치 앞에서 한 번
        Map<String, Number> counts = r.stages().stream().collect(Collectors.toMap(s -> (String) s.get("key"), s -> (Number) s.get("count")));
        assertThat(counts.get(ImageStage.DECRYPT.name()).intValue()).isEqualTo(3);
        assertThat(counts.get(ImageStage.MAP.name()).intValue()).isEqualTo(3);
        assertThat(counts.get(ImageStage.QUERY.name()).intValue()).isEqualTo(1);
        // 받은 원본(암호문)은 남지 않는다
        try (var s = Files.list(Path.of(root.toString(), "esb", "meet"))) {
            assertThat(s.filter(p -> p.getFileName().toString().startsWith("img_")).toList()).isEmpty();
        }
    }

    @Test
    @DisplayName("다시 돌리면 변경 없음으로 건너뛰고, force 면 다시 받아 UPDATED")
    void skipsUnchangedAndForceUpdates() {
        sim.seed(2);
        runSim(false);

        ImageCollectService.ImageRunResult again = runSim(false);
        assertThat(again.skipped()).isEqualTo(2);
        assertThat(again.outcomes()).allSatisfy(o -> assertThat(o.errMsg()).contains("변경 없음"));

        ImageCollectService.ImageRunResult forced = runSim(true);
        assertThat(forced.success()).isEqualTo(2);
        assertThat(forced.updated()).isEqualTo(2);
    }

    @Test
    @DisplayName("옛 사진(순번이 더 작은 것)은 매핑을 덮지 않는다 — STALE")
    void staleDoesNotOverwrite() {
        sim.seed(1);
        runSim(false);
        String corr = ImageSimulationService.corrNo(1);

        InmatePhotoRepository.MapResult res = photos.upsert(new InmatePhotoRepository.PhotoRow(
                corr, 1, "OLD", "OLDDOC", "old-key", "/old/path.jpg", 1L, "jpg", "TEST-OLD"));

        assertThat(res).isEqualTo(InmatePhotoRepository.MapResult.STALE);
        assertThat(((Number) photos.find(corr).get("image_sn")).intValue()).isEqualTo(2);
        assertThat(photos.find(corr).get("photo_path")).isNotEqualTo("/old/path.jpg");
    }

    @Test
    @DisplayName("XVARM 에 FILEKEY 가 없는 수용자는 'FILEKEY 추출' 실패 — 나머지는 성공")
    void missingFileKeyFailsThatInmateOnly() {
        sim.seed(3);
        boramiJdbc.update("DELETE FROM " + tables.asysContentElement() + " WHERE ELEMENTID = ?", "SIMIMGD000022");

        ImageCollectService.ImageRunResult r = runSim(false);

        assertThat(r.success()).isEqualTo(2);
        ImageOutcome failed = r.outcomes().stream().filter(o -> "FAIL".equals(o.status())).findFirst().orElseThrow();
        assertThat(failed.corrNo()).isEqualTo(ImageSimulationService.corrNo(2));
        assertThat(failed.failedAt()).isEqualTo(ImageStage.FILEKEY);
        assertThat(failed.errMsg()).contains("ASYSCONTENTELEMENT");
    }

    @Test
    @DisplayName("PHOTO_REF 일괄 반영 — 수용자 기본에 있는 수용자만, 값이 다를 때만")
    void syncsPhotoRef() {
        sim.seed(2);
        runSim(false);
        String corr = ImageSimulationService.corrNo(1);
        String inmate = adminDb.table(AdminDb.INMATE_TABLE);
        adminDb.jdbc().update("INSERT INTO " + inmate + " (corr_no, prsr_nm) VALUES (?, ?)", corr, "시뮬레이션");
        try {
            assertThat(photos.syncPhotoRef()).isEqualTo(1);
            assertThat(adminDb.jdbc().queryForObject("SELECT photo_ref FROM " + inmate + " WHERE corr_no = ?", String.class, corr))
                    .isEqualTo(photos.find(corr).get("photo_path"));
            assertThat(photos.syncPhotoRef()).as("이미 같으면 바꾸지 않는다").isZero();
        } finally {
            adminDb.jdbc().update("DELETE FROM " + inmate + " WHERE corr_no = ?", corr);
        }
    }

    @Test
    @DisplayName("수용자 이미지 검증(6번 탭) — 준비 → 측정 → 검증 → 정리, 정합성 OK · 끝나면 SIM 이 남지 않는다")
    @SuppressWarnings("unchecked")
    void perfRunEndToEnd() throws Exception {
        perf.clearHistory();
        perf.start(new ImagePerfService.ImagePerfRequest(6, 3, 20L));

        Map<String, Object> cur = waitDone();
        assertThat(cur.get("phase")).as("오류: %s", cur.get("error")).isEqualTo("DONE");
        Map<String, Object> r = (Map<String, Object>) cur.get("result");
        assertThat(r.get("success")).isEqualTo(6);
        assertThat((Double) r.get("tps")).isPositive();
        assertThat((Double) r.get("decryptAvgMs")).isGreaterThanOrEqualTo(0d);
        Map<String, Object> checks = (Map<String, Object>) r.get("checks");
        assertThat(checks.get("ok")).as("정합성: %s", checks).isEqualTo(true);
        assertThat(checks.get("plainMatch")).isEqualTo(6L);
        Map<String, Object> hikari = (Map<String, Object>) r.get("hikari");
        assertThat(((Map<String, Object>) hikari.get("after")).get("returned")).as("풀 반납").isEqualTo(true);
        // 가상 지연 20ms 가 단계에 잡힌다
        List<Map<String, Object>> stages = (List<Map<String, Object>>) r.get("stages");
        assertThat(stages).anySatisfy(s -> {
            assertThat(s.get("key")).isEqualTo("VIRTUAL");
            assertThat(((Number) s.get("avgMs")).doubleValue()).isGreaterThanOrEqualTo(20d);
        });
        // 정리 — SIM 이 남지 않는다
        assertThat(photos.stats(ImageSimulationService.PREFIX).get("sim")).isEqualTo(0L);
        assertThat(boramiJdbc.queryForObject("SELECT COUNT(*) FROM " + tables.irimBsifDs() + " WHERE CORR_NO LIKE 'SIMIMG%'",
                Integer.class)).isZero();
        assertThat(perf.history().get("total")).isEqualTo(1);
        assertThat(pools.state().get("returned")).isEqualTo(true);
    }

    private Map<String, Object> waitDone() throws InterruptedException {
        long until = System.currentTimeMillis() + 60_000;
        Map<String, Object> cur = perf.current();
        while (System.currentTimeMillis() < until) {
            cur = perf.current();
            if (Boolean.FALSE.equals(cur.get("active"))) {
                return cur;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("수용자 이미지 검증이 60초 안에 끝나지 않았다 — " + cur);
    }
}
