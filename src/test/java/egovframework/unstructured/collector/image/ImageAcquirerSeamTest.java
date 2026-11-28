package egovframework.unstructured.collector.image;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.inbound.SourceFileAcquirer;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * 사진 배치가 XVARM 을 <b>파일 확보 방식({@link SourceFileAcquirer}) 안에서만</b> 아는가 — 2026-11-28 XVARM 제거 1단계(사진).
 *
 * <p>확보 방식이 FILEKEY 를 요구하지 않으면 FILEKEY 없는 건도 받고, 매핑 {@code filekey} 자리(NOT NULL)는 확보 방식이 정한
 * 값으로 채워야 한다. 그래야 2단계 동기화 방식(SYNCED · FILEKEY 없음)이 사진 배치 코드를 고치지 않고 끼워진다.
 * 지금 기본(BROKER)의 동작은 {@code ImagePipelineTest} 가 지킨다.</p>
 *
 * <p>확보 빈은 실제 {@code BrokerAcquirer}(Mock 브로커)를 감싼 스파이다 — 받기는 실제로 하고, 두 질문(FILEKEY 가 필요한가 ·
 * filekey 에 무엇을 남기나)의 답만 바꾼다. Mock 브로커는 FILEKEY 가 없으면 더미 사진을 떨군다.</p>
 */
@SpringBootTest(properties = {
        "image.admin-db.url=jdbc:h2:mem:admin-seam-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "image.admin-db.username=sa",
        "image.admin-db.password=",
        "voice.source.mode=MOCK",
        "voice.broker.mode=MOCK",
        "voice.decrypt.mode=REAL",
        "log-collector.enabled=false",
        "agent-connector.enabled=false",
        "voice.sim.seed-on-startup=false",
        "voice.sync.wait-timeout-sec=10",
        "voice.sync.stable-check-ms=20"
})
class ImageAcquirerSeamTest {

    /** 동기화 방식(2단계)이 남길 값의 모양 — 받은 파일의 상대 경로(계획서 2.3.3 1안). */
    private static final String SYNCED_DIR = "esb/photo/20261128/";

    private static Path root;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("imageseam");
        Path key = root.resolve("rvs_key.txt");
        Files.writeString(key, String.join("\n",
                "secretkey=LOCAL_TEST_KEY_0123456789abcdefg", "iv=0000000000000000", "algorithm=AES",
                "ciphermode=CBC", "padding=PKCS5Padding", "charset=UTF-8"));
        registry.add("voice.dirs.base-dir", () -> root.toString().replace('\\', '/'));
        registry.add("voice.decrypt.rvs-key-path", key::toString);
    }

    @MockitoSpyBean
    private SourceFileAcquirer acquirer;
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

    private ImageCollectService.ImageRunResult runSim() {
        return collect.run(new ImageCollectService.ImageRunRequest(null, ImageSimulationService.PREFIX, null, 2, false,
                0L, "TEST", true, null, null));
    }

    /** filekey 자리에 동기화 방식처럼 '받은 파일의 상대 경로'를 남기게 한다. */
    private void recordSyncedPath() {
        doAnswer(inv -> SYNCED_DIR + ((SourceFileAcquirer.PhotoRequest) inv.getArgument(0)).docId() + ".jpg")
                .when(acquirer).photoRecordKey(any(), any());
    }

    @Test
    @DisplayName("매핑 filekey 는 확보 방식이 정한 값 — 사진 배치가 XVARM FILEKEY 를 직접 넣지 않는다")
    void recordKeyComesFromAcquirer() {
        sim.seed(2);
        recordSyncedPath();

        ImageCollectService.ImageRunResult r = runSim();

        assertThat(r.success()).as("실패: %s", r.outcomes()).isEqualTo(2);
        for (int i = 1; i <= 2; i++) {
            Map<String, Object> row = photos.find(ImageSimulationService.corrNo(i));
            assertThat(row.get("filekey")).isEqualTo(SYNCED_DIR + row.get("doc_id") + ".jpg");
        }
    }

    @Test
    @DisplayName("FILEKEY 가 필요 없는 확보 방식이면 XVARM 에 없는 건도 받는다 — 'FILEKEY 추출' 실패 없음 · filekey 는 확보 방식 값")
    void fileKeyNotRequiredReceivesWithoutXvarmRow() {
        sim.seed(3);
        // 이 수용자는 XVARM 에 행이 없다 — 브로커 방식(기본)이면 'FILEKEY 추출' 실패다(ImagePipelineTest)
        boramiJdbc.update("DELETE FROM " + tables.asysContentElement() + " WHERE ELEMENTID = ?", "SIMIMGD000022");
        doReturn(false).when(acquirer).requiresFileKey();
        recordSyncedPath();

        ImageCollectService.ImageRunResult r = runSim();

        assertThat(r.success()).as("실패: %s", r.outcomes()).isEqualTo(3);
        assertThat(r.failByStage()).isEmpty();
        Map<String, Object> row = photos.find(ImageSimulationService.corrNo(2));
        assertThat(row.get("doc_id")).isEqualTo("SIMIMGD000022");
        assertThat(row.get("filekey")).as("NOT NULL 자리 — XVARM 이 없어도 확보 방식 값으로 채운다")
                .isEqualTo(SYNCED_DIR + "SIMIMGD000022.jpg");
    }
}
