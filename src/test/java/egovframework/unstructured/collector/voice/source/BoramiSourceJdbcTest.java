package egovframework.unstructured.collector.voice.source;

import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.util.AudioFormatDetector;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보라미 4단 조인 SQL 검증 — H2 의 Mock 보라미를 실제로 조회한다.
 *
 * <p><b>왜 이 테스트가 필요한가</b>: MOCK 모드 E2E 는 SQL 을 한 줄도 실행하지 않는다.
 * 조인 순서·필터 조건·타입 매핑이 맞는지는 여기서만 드러난다. 특히 아래 세 가지는
 * 실연동에서 발견하면 비싸다.</p>
 * <ul>
 *   <li>삭제 플래그가 <b>셋</b>이다 — 녹취 {@code DEL_YN}, 녹음파일 {@code RECRD_FILE_DEL_YN},
 *       공통파일 {@code DEL_YN}. 하나라도 놓치면 이미 지워진 파일을 XVARM 에 요청하게 된다.</li>
 *   <li>해제된 특이수용자({@code PTCR_PRSR_RMV_YMD})를 걸러야 한다.</li>
 *   <li>{@code FETCH FIRST n ROWS ONLY} 가 Oracle·PostgreSQL·H2 에서 모두 도는지.</li>
 * </ul>
 *
 * <p>유효 대상 14건은 {@link SimulationDataService} 가 만든다(기동 시 + 여기서 다시). <b>걸러져야 할 행</b>은
 * {@code data-borami-mock.sql} 에 섞여 있다(기대 결과는 그 파일 머리말 참조).</p>
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "voice.source.mode=MOCK")   // MOCK (로컬 H2) — JDBC 로 H2 를 조회한다
class BoramiSourceJdbcTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        // 로그 컬렉터를 끈다 — 테스트는 외부 프로세스에 기대면 안 된다.
        // local 프로파일 기본은 enabled=true + localhost:8090 이라, 그 포트에서 무언가 듣고 있지만
        // 응답하지 않으면 호출마다 30초씩 멈춰 빌드가 통째로 늘어진다(실제로 그렇게 늘어졌다).
        registry.add("log-collector.enabled", () -> "false");

        // 더미 파일이 실제 C:/XVARM_ORIGINAL_VOICE_FILES 를 건드리지 않게 임시 폴더로 돌린다
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("voice.dirs.work", () -> tmp.resolve("work").toString());
    }

    private static final List<String> SPECL = List.of("0", "1", "2", "3", "5");

    /** 주입되는 것은 라우터다(@Primary). 모드에 따라 실제 구현으로 위임한다. */
    @Autowired
    private BoramiSourceClient source;

    /** 지름길 대사처럼 JDBC 구현 고유 기능을 쓸 때는 직접 주입받는다. */
    @Autowired
    private JdbcBoramiSourceClient jdbc;

    @Autowired
    private SimulationDataService sim;

    private BatchWindow wide() {
        LocalDateTime now = LocalDateTime.now();
        return BatchWindow.manual(now.minusDays(2), now.plusDays(1));
    }

    @BeforeEach
    void seed() {
        sim.seed();   // Clean & Seed — 앞 테스트가 무엇을 했든 10건 + 더미 파일에서 시작한다
    }

    @Test
    @DisplayName("MOCK(로컬 H2) 모드도 JDBC 구현으로 위임한다 — DataSource 라우터가 H2 로 붙여 준다")
    void usesJdbcImplementation() {
        assertThat(source).isInstanceOf(BoramiSourceRouter.class);
        assertThat(source.mode()).isEqualTo("MOCK");
        assertThat(jdbc.mode()).isEqualTo("DIRECT_JDBC");
    }

    @Test
    @DisplayName("접견 4단 조인 — 삭제·해제·대상외 코드를 걸러 시뮬레이션 7건(일배치 5 + 주기 2)만 나온다")
    void meetQueryFiltersProperly() {
        List<VoiceTarget> targets = source.findMeetTargets(wide(), SPECL, 100);

        assertThat(targets).hasSize(7);
        assertThat(targets).extracting(VoiceTarget::idempotencyKey)
                .containsExactlyInAnyOrder("SIM-MEET-001", "SIM-MEET-002", "SIM-MEET-003", "SIM-MEET-004", "SIM-MEET-005",
                        "SIM-MEET-006", "SIM-MEET-007");
        assertThat(targets).allSatisfy(t -> assertThat(t.kind()).isEqualTo(VoiceKind.MEET));
    }

    @Test
    @DisplayName("접견 조회는 DOC_ID 와 XVARM FILEKEY(원본 더미 파일 경로)까지 끌어온다 — 브로커 호출에 둘 다 필요하다")
    void meetQueryResolvesXvarmKey() {
        List<VoiceTarget> targets = source.findMeetTargets(wide(), SPECL, 100);

        VoiceTarget first = targets.stream()
                .filter(t -> t.idempotencyKey().equals("SIM-MEET-001"))
                .findFirst().orElseThrow();

        assertThat(first.docId()).isEqualTo("SIMDOC0001");
        assertThat(first.fileKey()).endsWith("/xvarm_original/mock_meet_001.m4a");
        assertThat(Files.isRegularFile(Path.of(first.fileKey()))).as("DB 파일키와 1:1 인 물리 더미 파일").isTrue();
        assertThat(first.encrypted()).isTrue();
        assertThat(first.srcFileName()).isEqualTo("mock_meet_001.m4a");
    }

    @Test
    @DisplayName("CMMN_FILE_ENC_YN='N' 인 건은 encrypted=false 로 온다 — 복호화하면 원본이 깨진다")
    void meetQueryReflectsEncryptionFlag() {
        List<VoiceTarget> targets = source.findMeetTargets(wide(), SPECL, 100);

        VoiceTarget plain = targets.stream()
                .filter(t -> t.idempotencyKey().equals("SIM-MEET-002"))
                .findFirst().orElseThrow();

        assertThat(plain.encrypted()).isFalse();
    }

    @Test
    @DisplayName("전화 조회 — 녹음 안 됨·삭제된 건을 걸러 시뮬레이션 7건만 나온다")
    void phoneQueryFiltersProperly() {
        List<VoiceTarget> targets = source.findPhoneTargets(wide(), SPECL, 100);

        assertThat(targets).hasSize(7);
        assertThat(targets).extracting(VoiceTarget::idempotencyKey)
                .containsExactlyInAnyOrder("SIM-PHONE-001", "SIM-PHONE-002", "SIM-PHONE-003", "SIM-PHONE-004", "SIM-PHONE-005",
                        "SIM-PHONE-006", "SIM-PHONE-007");
    }

    @Test
    @DisplayName("시간창별 대상 — 일배치 창은 접견 5·전화 5, 20분 주기 창은 접견 2·전화 2 (총 14건)")
    void seedMatchesBatchWindows() {
        LocalDateTime now = LocalDateTime.now();
        BatchWindow daily = BatchWindow.daily(now);
        BatchWindow periodic = BatchWindow.periodic(now, 20);

        assertThat(source.findMeetTargets(daily, SPECL, 100)).extracting(VoiceTarget::idempotencyKey)
                .containsExactly("SIM-MEET-001", "SIM-MEET-002", "SIM-MEET-003", "SIM-MEET-004", "SIM-MEET-005");
        assertThat(source.findPhoneTargets(daily, SPECL, 100)).hasSize(5);
        // 007 이 먼저다 — 조회는 CRT_DT 오름차순이고 007(지금-18분)이 006(지금-6분)보다 오래됐다.
        // 007 은 10분 창을 벗어난 지연 건이라 20분 창에서만 잡힌다(아래 periodicNarrowWindowMissesLagged 참고).
        assertThat(source.findMeetTargets(periodic, SPECL, 100)).extracting(VoiceTarget::idempotencyKey)
                .containsExactly("SIM-MEET-007", "SIM-MEET-006");
        assertThat(source.findPhoneTargets(periodic, SPECL, 100)).extracting(VoiceTarget::idempotencyKey)
                .containsExactly("SIM-PHONE-007", "SIM-PHONE-006");
    }

    @Test
    @DisplayName("주기배치는 lag 와 무관하게 지연 건을 주워 온다 — 당일 전체를 보기 때문")
    void periodicPicksUpLaggedRegardlessOfLag() {
        LocalDateTime now = LocalDateTime.now();

        // 예전에는 lag 가 창의 길이였다. 10분으로 좁히면 -18분·-16분 건이 빠졌고,
        //   스케줄러가 멈췄던 구간의 미처리 건은 다음 창에도 들어오지 않아 영영 수집되지 않았다.
        //   이제 창은 [당일 00:00, 지금] 이라 lag 를 어떻게 주든 오늘 건은 다 들어온다.
        for (int lag : new int[] {10, 20}) {
            assertThat(source.findMeetTargets(BatchWindow.periodic(now, lag), SPECL, 100))
                    .as("lag=%d분", lag)
                    .extracting(VoiceTarget::idempotencyKey)
                    .contains("SIM-MEET-006", "SIM-MEET-007");
            assertThat(source.findPhoneTargets(BatchWindow.periodic(now, lag), SPECL, 100))
                    .as("lag=%d분", lag)
                    .extracting(VoiceTarget::idempotencyKey)
                    .contains("SIM-PHONE-006", "SIM-PHONE-007");
        }
    }

    @Test
    @DisplayName("생성은 멱등(Clean & Seed) — 두 번 만들어도 14건, 더미 파일 15개(전화 기존 STT 1개 포함) · 더미는 실제 오디오")
    void seedIsIdempotentAndWritesDummyFiles() throws Exception {
        Map<String, Object> r = sim.seed();

        assertThat(source.findMeetTargets(wide(), SPECL, 100)).hasSize(7);
        assertThat(source.findPhoneTargets(wide(), SPECL, 100)).hasSize(7);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) r.get("files");
        assertThat(files).hasSize(15);
        assertThat(files).allSatisfy(f -> {
            assertThat(f.get("written")).isEqualTo(true);
            assertThat((Integer) f.get("bytes")).as("빈 파일이면 만든 의미가 없다").isPositive();
            assertThat(Files.isRegularFile(Path.of((String) f.get("path")))).isTrue();
        });
        // 더미 음성은 <b>실제 오디오</b>다. 예전에는 한 줄짜리 텍스트였는데, 브로커가 REST 면
        //   이 파일이 그대로 수신 폴더로 와서 복호화·STT 를 타기 때문에 앞뒤가 맞지 않았다.
        //   암호화 대상이 아닌 건(MEET_UNENCRYPTED)은 복호화 모드와 무관하게 늘 평문 오디오다.
        //   암호화 대상의 암복호 규약은 SimulationDummyFileTest 가 본다.
        assertThat(AudioFormatDetector.byMagic(Files.readAllBytes(tmp.resolve(
                "xvarm_original/mock_meet_%03d.m4a".formatted(SimulationDataService.MEET_UNENCRYPTED)))))
                .as("ENC_YN='N' 인 건은 평문 오디오여야 한다").isEqualTo("wav");
        assertThat(Files.isRegularFile(tmp.resolve("xvarm_original/mock_phone_003.wav"))).isTrue();
        assertThat(Files.isRegularFile(tmp.resolve("xvarm_original/mock_meet_007.m4a"))).isTrue();
    }

    @Test
    @DisplayName("초기화 — SIM 행과 더미 파일이 전부 사라지고, 필터 검증용 행(MOCKX)은 남는다")
    void cleanRemovesRowsAndFiles() {
        Map<String, Object> r = sim.clean();

        assertThat(source.findMeetTargets(wide(), SPECL, 100)).isEmpty();
        assertThat(source.findPhoneTargets(wide(), SPECL, 100)).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Integer> deleted = (Map<String, Integer>) r.get("filesDeleted");
        assertThat(deleted.get("MEET")).isEqualTo(7);
        assertThat(deleted.get("PHONE")).isEqualTo(8);   // wav 7 + 기존 STT 텍스트 1
        try (var s = Files.list(tmp.resolve("xvarm_original"))) {
            assertThat(s.filter(f -> f.getFileName().toString().startsWith("mock_")).count()).isZero();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        // 필터 검증용 행은 남아 있어도 대상이 되지 않는다(전부 제외 조건)
        assertThat(sim.status().get("rows")).isNotNull();
    }

    @Test
    @DisplayName("전화 조회는 복호화 KEY(TELP_RECRD_FILE_ID)를 함께 가져온다")
    void phoneQueryCarriesDecryptKey() {
        List<VoiceTarget> targets = source.findPhoneTargets(wide(), SPECL, 100);

        assertThat(targets).allSatisfy(t -> assertThat(t.decryptKey()).isNotBlank());
    }

    @Test
    @DisplayName("기존 STT 가 있는 건은 sourceSttPath 가 채워져 오고 그 파일이 실제로 있다(Q1 시나리오)")
    void phoneQueryDetectsExistingStt() {
        List<VoiceTarget> targets = source.findPhoneTargets(wide(), SPECL, 100);

        assertThat(targets).filteredOn(VoiceTarget::hasSourceStt)
                .hasSize(1)
                .allSatisfy(t -> {
                    assertThat(t.idempotencyKey()).isEqualTo("SIM-PHONE-003");
                    assertThat(Files.isRegularFile(Path.of(t.sourceSttPath()))).isTrue();
                });
    }

    @Test
    @DisplayName("시간창 밖은 조회되지 않는다 — 증분 수집의 기본")
    void windowIsRespected() {
        LocalDateTime now = LocalDateTime.now();
        BatchWindow future = BatchWindow.manual(now.plusDays(1), now.plusDays(2));

        assertThat(source.findMeetTargets(future, SPECL, 100)).isEmpty();
        assertThat(source.findPhoneTargets(future, SPECL, 100)).isEmpty();
    }

    @Test
    @DisplayName("limit 이 FETCH FIRST 로 걸린다 — Oracle·PostgreSQL·H2 공통 문법")
    void limitIsApplied() {
        assertThat(source.findPhoneTargets(wide(), SPECL, 1)).hasSize(1);
    }

    @Test
    @DisplayName("특별관리구분코드를 좁히면 대상이 줄어든다")
    void speclCodeFilterWorks() {
        // 마약(1) 만 — SIM00000000000001 한 명
        List<VoiceTarget> onlyDrug = source.findPhoneTargets(wide(), List.of("1"), 100);

        assertThat(onlyDrug).hasSize(1);
        assertThat(onlyDrug.get(0).idempotencyKey()).isEqualTo("SIM-PHONE-001");
    }

    @Test
    @DisplayName("지름길 필터(TELP_PTCR_PRSR_YN)와 정공법의 건수를 대사한다")
    void shortcutMatchesJoinResult() {
        int viaJoin = source.findPhoneTargets(wide(), SPECL, 100).size();
        int viaFlag = jdbc.countByFlagShortcut(wide(), 100);

        // 시뮬레이션 데이터에서는 일치한다. 실연동에서 어긋나면 플래그를 믿을 수 없다는 뜻이다(계획서 4.2).
        assertThat(viaFlag).isEqualTo(viaJoin);
    }

    @Test
    @DisplayName("H2 에서는 XVARM MOCK 테이블 생성이 건너뛰어진다 — 스키마 스크립트가 이미 만든다")
    void ensureTablesSkipsOnH2() {
        assertThat(sim.ensureXvarmMockTables()).contains("H2");
    }
}
