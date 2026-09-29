package egovframework.unstructured.collector.voice.controller;

import egovframework.unstructured.collector.voice.batch.IdempotencyGuard;
import egovframework.unstructured.collector.common.config.FaultInjector;
import egovframework.unstructured.collector.common.config.MockDatasetState;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.voice.source.BoramiSourceClient;
import egovframework.unstructured.collector.common.util.StaleFiles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 시뮬레이터·시연 보조 API — <b>운영에서는 차단한다</b>.
 *
 * <p>커넥터도 같은 방식으로 시연용 API 를 한 애플리케이션에 담고 운영 배포 시 경로를 막는다.
 * 배포 시 {@code /api/v1/mock/**} 를 인그레스·게이트웨이에서 차단하면 된다.</p>
 *
 * <p>여기 있는 것은 <b>파괴적 초기화</b>다. 멱등 표식과 수신 디렉터리의 파일을 지운다.
 * Mock 모드에서만 의미가 있으므로, 실물 모드에서는 호출해도 실제 데이터를 건드리지 않도록
 * 삭제 범위를 <b>우리가 만든 작업 디렉터리 안</b>으로 한정했다.</p>
 */
@Tag(name = "9. 시뮬레이터 (Mock 전용)",
        description = "시연 반복을 위한 초기화·미리보기. 운영에서는 /api/v1/mock/** 를 차단한다")
@Log4j2
@RestController
@RequestMapping(value = "/api/v1/mock", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class VoiceMockController {

    private final VoiceProperties props;
    private final BoramiSourceClient source;
    private final IdempotencyGuard idempotency;
    private final VoiceModeState modeState;
    private final MockDatasetState dataset;
    private final FaultInjector faultInjector;
    private final VoiceDirState dirs;
    private final egovframework.unstructured.collector.voice.stt.SttOutputStore outputStore;
    private final egovframework.unstructured.collector.voice.source.SimulationDataService sim;
    private final egovframework.unstructured.collector.voice.source.DbKindDetector dbKind;
    private final egovframework.unstructured.collector.common.config.DeployEnvPreset deployEnv;
    private final egovframework.unstructured.collector.common.logging.LogCollectorClient logCollector;
    private final egovframework.unstructured.collector.voice.stt.SttTempStore sttTemp;

    @Operation(summary = "시뮬레이션 데이터 생성 (Complete Clean & Seed)",
            description = """
                    시연을 처음부터 다시 하기 위해 **전부 지우고 새로 만듭니다.**

                    1. **멱등 표식** — 이걸 지워야 같은 대상을 다시 처리할 수 있습니다
                    2. **수신 디렉터리의 파일** — 접견·전화 수신 폴더
                    3. **작업 디렉터리의 산출물** — 복호화 결과·Mock 기존 STT 텍스트
                    4. **DB 메타 데이터** — 접견 5건(re·im·sm·xvarm 4단 1:1:1:1) · 전화 5건(im 통화내역 + im 특이수용자 1:1).
                       트랙마다 3건은 어제(일배치 창), 2건은 최근 10분(주기배치 창). 로컬은 H2, 개발계는 borami-db(PostgreSQL)에 넣습니다.
                       XVARM 모드가 `MOCK_DEV` 면 누락 테이블(`sm.tb_smsm_cmfi_bs` · `xvarm.asyscontentelement`)을 먼저 만듭니다
                    5. **물리 더미 파일** — DB 파일명과 1:1 인 경량 파일(수십 byte)을 XVARM 원본 스토리지
                       (`{xvarmOriginalBase}/meet` · `/phone`)에 씁니다
                    6. **Mock 규모** 를 시연 기본(10건)으로 되돌립니다

                    `POST /api/v1/mock/reset` 은 같은 동작의 옛 이름입니다.
                    """)
    @PostMapping({"/sim-data", "/reset"})
    public Map<String, Object> createSimData() {
        Map<String, Object> cleared = clearLocal();
        dataset.reset();
        Map<String, Object> seed = sim.seed();

        List<VoiceTarget> targets = previewTargets();
        long meet = targets.stream().filter(t -> t.kind() == VoiceKind.MEET).count();
        log.info("[Sim] 생성 — 로컬 {} · DB {}", cleared, seed.get("rows"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", cleared);
        out.put("sim", seed);
        out.put("dataset", dataset.snapshot());
        out.put("targetCount", targets.size());
        out.put("targetMeet", meet);
        out.put("targetPhone", targets.size() - meet);
        out.put("message", "시뮬레이션 데이터 생성 완료 — 대상 %d건(접견 %d · 전화 %d) · 더미 파일 %d개"
                .formatted(targets.size(), meet, targets.size() - meet,
                        ((List<?>) seed.getOrDefault("files", List.of())).size()));
        return out;
    }

    @Operation(summary = "시뮬레이션 데이터 초기화 (Complete Clean)",
            description = """
                    시뮬레이션 데이터를 **전부 지웁니다** (만들지는 않습니다).

                    - DB 의 시뮬레이션 메타 행(SIM 접두: 수용자·녹취·통화·공통파일·XVARM) 일괄 DELETE — 운영·다른 사람 행은 건드리지 않습니다
                    - XVARM 원본 스토리지의 더미 파일(`mock_*`) 삭제
                    - 멱등 표식 · 수신 파일 · 작업 산출물 삭제
                    """)
    @DeleteMapping("/sim-data")
    public Map<String, Object> deleteSimData() {
        Map<String, Object> cleared = clearLocal();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", cleared);
        out.put("sim", sim.clean());
        out.put("message", "시뮬레이션 데이터 초기화 완료 — DB 메타·더미 파일·멱등 표식을 지웠습니다");
        return out;
    }

    @Operation(summary = "시뮬레이션 데이터 현황",
            description = "DB 종류 · 조립된 테이블명 · SIM 행 수 · 원본 스토리지의 더미 파일 목록.")
    @GetMapping("/sim-data")
    public Map<String, Object> simDataStatus() {
        return sim.status();
    }

    @Operation(summary = "DB 연결 확인",
            description = """
                    지금 조회 모드가 가리키는 DB(MOCK → 로컬 H2 · 개발계 DB → PostgreSQL)에 실제로 붙어 봅니다.

                    개발계 DB 는 로컬에서 포트포워딩(`kubectl port-forward -n data-pipeline svc/borami-db-gijoxearrw 15433:5432`)이
                    없으면 닿지 않습니다 — 그 경우 사유(connection refused 등)를 그대로 돌려줍니다.
                    """)
    @GetMapping("/db/probe")
    public Map<String, Object> dbProbe() {
        return dbKind.probe();
    }

    /** 멱등 표식 · 수신 파일 · Mock 작업 산출물 삭제 — 생성/초기화 공통. */
    private Map<String, Object> clearLocal() {
        return clearLocalFiles();
    }

    @Operation(summary = "시뮬레이션 데이터 초기화 (테스트 이력 + 시뮬레이션 데이터 일괄 삭제)",
            description = """
                    **시뮬레이터에서 돌린 시험 기록을 통째로 지웁니다.**

                    시뮬레이터에서 실행한 배치는 `JOB_ID=TEST_BATCH` 로 열려 EXEC_ID 의
                    작업코드 자리가 **`TST`** 가 됩니다 (예: `20260914TST001`).
                    운영 배치는 `VOC`(음성)·`STR`(정형)·`PUB`(공공)·`LAW`(법제처)라 **섞이지 않습니다.**

                    두 가지를 지웁니다.

                    1. **로그 컬렉터 이력** — `DELETE /api/v1/logs/test-data` 를 호출합니다.
                       컬렉터가 `JOB_ID='TEST_BATCH'` 인 T1 과 하위 T2~T8 을 FK 안전 순서로
                       연쇄 삭제합니다. 삭제 SQL 에 작업코드 조건이 박혀 있어 운영 배치는
                       어떤 경우에도 걸리지 않습니다.
                    2. **로컬 산출물** — 멱등 표식·수신 파일·작업 파일·**재처리 보존물**(`{ROOT}/stt_temp/**` 전사 · `{ROOT}/xvram/decoding/decrypted_*` 복호화 오디오)
                    3. **STT 출력 폴더** — `{output}/{execId}/` 중 EXEC_ID 에 `TST` 가 든 폴더째
                    4. **시뮬레이션 데이터** — DB 의 SIM 접두 메타 행 일괄 DELETE + XVARM 원본 더미 파일 삭제 (= `DELETE /sim-data`)

                    즉 시뮬레이터가 만든 것을 전부 되돌립니다. 다시 돌리려면 [시뮬레이션 데이터 생성] 을 누릅니다.
                    """)
    @DeleteMapping("/test-data")
    public Map<String, Object> deleteTestData() {
        Map<String, Object> out = new LinkedHashMap<>();

        // ① 로그 컬렉터 이력 (TST)
        com.fasterxml.jackson.databind.JsonNode logs = logCollector.deleteTestData();
        if (logs == null) {
            out.put("logCollector", logCollector.isEnabled()
                    ? "삭제 호출 실패 — 컬렉터 응답 없음"
                    : "미연동 — 지울 원격 이력이 없다");
        } else {
            out.put("logCollector", logs);
        }

        // ② 로컬 산출물
        Map<String, Object> local = clearLocalFiles();
        local.put("sttOutputDirs", outputStore.deleteTestOutputs());
        out.put("local", local);
        dataset.reset();
        // ③ 시뮬레이션 데이터 (DB 메타 SIM 행 + 더미 파일) — 만들지는 않는다
        out.put("sim", sim.clean());

        out.put("testJobId", props.batch().testJobId());
        out.put("message", "시뮬레이션 데이터 초기화 완료 — 테스트(TST) 이력·STT 출력·DB 메타·더미 파일을 지웠습니다. 운영 배치(VOC/STR/EXT)는 건드리지 않았습니다");
        log.info("[Mock] 테스트 데이터 초기화 — 컬렉터={} 로컬={}", out.get("logCollector"), local);
        return out;
    }

    @Operation(summary = "처리 대상 미리보기",
            description = """
                    배치를 실행하지 않고 **지금 조회되는 대상**만 확인합니다.
                    시연에서 "이번에 이만큼 처리됩니다"를 먼저 보여줄 때 씁니다.

                    이미 처리된 건(`processed=true`)은 배치를 돌려도 건너뜁니다.
                    """)
    @GetMapping("/targets")
    public Map<String, Object> targets(
            @RequestParam(required = false) List<VoiceKind> kinds) {
        List<VoiceTarget> found = previewTargets(kinds);
        List<Map<String, Object>> rows = new ArrayList<>(found.size());
        for (VoiceTarget t : found) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", t.kind().name());
            r.put("kindLabel", t.kind().label());
            r.put("key", t.idempotencyKey());
            r.put("fileName", t.srcFileName());
            r.put("encrypted", t.encrypted());
            r.put("hasSourceStt", t.hasSourceStt());
            r.put("occurredAt", t.occurredAt());
            r.put("processed", idempotency.isProcessed(t));
            rows.add(r);
        }
        long pending = rows.stream().filter(r -> !((Boolean) r.get("processed"))).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceMode", source.mode());
        out.put("total", rows.size());
        out.put("pending", pending);
        out.put("alreadyProcessed", rows.size() - pending);
        out.put("targets", rows);
        return out;
    }

    @Operation(summary = "수신 디렉터리 현황",
            description = "ESB(또는 Mock)가 떨궈 놓은 파일이 실제로 있는지 확인합니다.")
    @GetMapping("/files")
    public Map<String, Object> files() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meet", listFiles(dirs.receiveMeet()));
        out.put("phone", listFiles(dirs.receivePhone()));
        return out;
    }

    @Operation(summary = "STT 출력 확인 (배치 폴더)",
            description = """
                    한 배치가 남긴 STT 결과를 읽어 옵니다 — `{output}/{execId}/` 의 `.json` 메타와 `.txt` 텍스트.

                    **텍스트를 그대로 싣습니다.** 배치 응답에는 글자 수·경로만 실리므로(외부 전송 금지),
                    시뮬레이터가 "무엇이 저장됐는지" 를 보여줄 때만 이 API 를 씁니다. 운영에서는 차단됩니다.
                    """)
    @GetMapping("/stt-outputs")
    public Map<String, Object> sttOutputs(@RequestParam String execId,
                                          @RequestParam(required = false) List<VoiceKind> kinds,
                                          @RequestParam(defaultValue = "600") int maxTextChars) {
        List<VoiceKind> want = (kinds == null || kinds.isEmpty())
                ? List.of(VoiceKind.MEET, VoiceKind.PHONE) : kinds;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", execId);
        Map<String, Object> byKind = new LinkedHashMap<>();
        int total = 0;
        for (VoiceKind k : want) {
            List<Map<String, Object>> rows = outputStore.list(k, execId, Math.max(50, maxTextChars));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dir", dirs.outputDir(k, execId).toString().replace('\\', '/'));
            m.put("count", rows.size());
            m.put("files", rows);
            byKind.put(k.name(), m);
            total += rows.size();
        }
        out.put("total", total);
        out.put("outputs", byKind);
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  디렉터리 경로 — 재시작 없이 수신·작업·출력 폴더를 바꾼다
    // ══════════════════════════════════════════════════════════════════════

    @Operation(summary = "디렉터리 경로 조회",
            description = "현재 경로 5종(수신 접견·전화 / 작업 / 출력 접견·전화)과 기동 설정값, 프리셋을 돌려줍니다.")
    @GetMapping("/dirs")
    public Map<String, Object> getDirs() {
        return dirsView();
    }

    @Operation(summary = "디렉터리 경로 변경",
            description = """
                    경로를 **재시작 없이** 바꿉니다. 본문에 바꿀 키만 넣으면 됩니다.

                    ```json
                    { "baseDir": "C:/k8s",
                      "receiveMeet": "C:/k8s/voice_raw/meet", "receivePhone": "C:/k8s/voice_raw/phone",
                      "work": "C:/k8s/voice_work",
                      "outputMeet": "C:/k8s/xenon/voice", "outputPhone": "C:/k8s/xenon/phone" }
                    ```

                    STT 결과는 `{outputMeet|outputPhone}/{execId}/` 에 쌓입니다.
                    수신 폴더(`receiveMeet`)를 바꾸면 **로컬 브로커의 `BROKER_OUTPUT_DIR` 도 같은 곳**이어야
                    접견 배치가 파일을 찾습니다 — [브로커 연결 확인] 으로 대조하십시오.
                    변경은 이 프로세스에만 남고 재기동하면 설정값으로 돌아갑니다.
                    """)
    @PutMapping("/dirs")
    public Map<String, Object> setDirs(@RequestBody Map<String, String> body) {
        Map<String, String> before = dirs.set(body);
        Map<String, Object> out = dirsView();
        out.put("before", before);
        return out;
    }

    @Operation(summary = "디렉터리 프리셋 적용",
            description = """
                    프리셋 하나로 6종을 한 번에 바꿉니다.

                    - `configured` : 기동 시 설정값(로컬 기본)
                    - `win`        : Windows 로컬 `C:/k8s` 아래 표준 배치
                    - `pv`         : 개발계 PV `/k8s` 아래 표준 배치
                    - `base`       : `baseDir` 파라미터로 준 뿌리 아래 표준 배치 (예 `baseDir=D:/data`)

                    표준 배치: `{base}/voice_raw/meet` · `{base}/voice_raw/phone` · `{base}/voice_work`
                    · `{base}/xenon/voice` · `{base}/xenon/phone`
                    """)
    @PutMapping("/dirs/preset")
    public Map<String, Object> applyDirPreset(@RequestParam String key,
                                              @RequestParam(required = false) String baseDir) {
        Map<String, String> target = switch (key.trim().toLowerCase()) {
            case "configured" -> dirs.configured();
            case "win" -> VoiceDirState.layoutOf(egovframework.unstructured.collector.common.config.DeployEnvPreset.ROOT_WINDOWS);
            case "pv" -> VoiceDirState.layoutOf(egovframework.unstructured.collector.common.config.DeployEnvPreset.ROOT_LINUX);
            case "base" -> {
                if (baseDir == null || baseDir.isBlank()) {
                    throw new IllegalArgumentException("key=base 에는 baseDir 이 필요하다");
                }
                yield VoiceDirState.layoutOf(baseDir);
            }
            default -> throw new IllegalArgumentException("알 수 없는 프리셋: " + key + " (configured/win/pv/base)");
        };
        Map<String, String> before = dirs.set(target);
        Map<String, Object> out = dirsView();
        out.put("preset", key);
        out.put("before", before);
        return out;
    }

    @Operation(summary = "디렉터리 경로 초기화", description = "기동 시 설정값(application.yml / 환경변수)으로 되돌립니다.")
    @PostMapping("/dirs/reset")
    public Map<String, Object> resetDirs() {
        dirs.resetToConfigured();
        return dirsView();
    }

    private Map<String, Object> dirsView() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", dirs.snapshot());
        Map<String, String> abs = new LinkedHashMap<>();
        dirs.snapshot().forEach((k, v) -> abs.put(k, VoiceDirState.toAbs(v)));
        out.put("absolute", abs);
        out.put("configured", dirs.configured());
        out.put("presets", dirs.presets());
        out.put("suggestedBaseDir", deployEnv.rootDir());
        out.put("labels", VoiceDirState.LABELS);
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  동적 모드 전환 — 서버 재시작 없이 MOCK ↔ REAL
    // ══════════════════════════════════════════════════════════════════════

    @Operation(summary = "현재 모드 조회",
            description = "5개 스위치의 현재 값과 기동 시 설정값을 함께 돌려줍니다.")
    @GetMapping("/modes")
    public Map<String, Object> getModes() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", modeState.snapshot());
        out.put("configured", modeState.configured());
        out.put("allowed", Map.of(
                "source", List.of("MOCK", "DIRECT_JDBC", "ESB_HTTP2DB"),
                "xvarm", List.of("MOCK_DEV", "REAL"),
                "broker", List.of("MOCK", "REST"),
                "phone", List.of("MOCK", "ESB"),
                "decrypt", List.of("SKIP", "REAL"),
                "stt", List.of("MOCK", "NPU")));
        return out;
    }

    @Operation(summary = "모드 변경 (단건)",
            description = """
                    스위치 하나를 **즉시** 바꿉니다. 재시작이 필요 없습니다.

                    - `source` : MOCK / DIRECT_JDBC(개발계 DB) / ESB_HTTP2DB(메타빌드)
                    - `xvarm`  : MOCK_DEV(개발계 DB 에 우리가 만든 공통파일·XVARM 테이블) / REAL(실 테이블) — 개발계 DB 모드 전용
                    - `broker` : MOCK / REST — 접견 전용
                    - `phone`  : MOCK / ESB — 전화 전용. 브로커와 별개입니다
                    - `decrypt`: SKIP / REAL
                    - `stt`    : MOCK / NPU

                    변경은 **이 프로세스에만** 남습니다. 재기동하면 설정값으로 돌아갑니다 —
                    운영에서 실수로 바꾼 모드가 영구히 남지 않게 하려는 것입니다.
                    """)
    @PutMapping("/modes/{name}")
    public Map<String, Object> setMode(@PathVariable String name, @RequestParam String value) {
        String before = modeState.set(name, value);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("switch", name);
        out.put("before", before);
        out.put("after", value.toUpperCase());
        out.put("current", modeState.snapshot());
        return out;
    }

    @Operation(summary = "브로커 주소 변경",
            description = """
                    XVARM 브로커 주소(`voice.broker.base-url`)를 **재시작 없이** 바꿉니다.

                    브로커가 사는 곳은 환경마다 다릅니다.

                    | 환경 | 주소 |
                    |---|---|
                    | 개발계 K8s | `http://borami-xvarm-broker-1joiuorqhl:8080` |
                    | 로컬 PC (IntelliJ) | `http://localhost:8082` |

                    모드만 `REST` 로 올리고 주소를 안 바꾸면 접견 배치가 **전건 실패**합니다.
                    빈 값도 허용합니다 — 그 실패를 일부러 재현해 볼 수 있어야 하기 때문입니다.

                    변경은 **이 프로세스에만** 남습니다. 재기동하면 설정값으로 돌아갑니다.
                    """)
    @PutMapping("/endpoints/broker")
    public Map<String, Object> setBrokerBaseUrl(@RequestParam(required = false, defaultValue = "") String value) {
        String before = modeState.setBrokerBaseUrl(value);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpoint", "broker");
        out.put("before", before);
        out.put("after", modeState.brokerBaseUrl());
        out.put("configured", modeState.configuredBrokerBaseUrl());
        return out;
    }

    @Operation(summary = "모드 초기화", description = "기동 시 설정값(application.yml / 환경변수)으로 되돌립니다.")
    @PostMapping("/modes/reset")
    public Map<String, Object> resetModes() {
        modeState.resetToConfigured();
        return Map.of("current", modeState.snapshot());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  대용량 부하 — 스트리밍·청크 처리가 메모리를 지키는지 본다
    // ══════════════════════════════════════════════════════════════════════

    @Operation(summary = "Mock 데이터 규모 설정 (대용량 시험)",
            description = """
                    **일배치용** Mock 대상 건수를 바꿉니다(MOCK 소스 전용). **OOM 방어 검증용**입니다 —
                    시연 기본은 일배치 접견 5·전화 5 + 주기배치 접견 1·전화 1(총 12건)이고,
                    [Mock 데이터 초기화/생성] 이 이 값으로 되돌립니다.

                    실데이터 규모는 접견 약 1,300건(6.5GB) · 전화 약 1,300건입니다.
                    건수를 올려도 힙이 늘지 않아야 합니다 — 파일을 통째로 메모리에 올리지 않고
                    스트리밍으로 다루며, 건별로 처리한 뒤 바로 지우기 때문입니다.
                    올린 건수는 **일배치(daily) 창**에 놓이므로 `POST /api/v1/voice/batches/daily` 로 돌립니다.
                    10분 주기 배치는 여전히 접견 1·전화 1 만 집습니다.

                    **처리 시간 주의**: 건당 파일 안정성 검사(`voice.sync.stable-check-ms`)가
                    그대로 곱해집니다. local 기준 1,000건 ≈ 2분, 10,000건 ≈ 20분입니다.
                    200건을 넘으면 Mock WAV 를 1초짜리로 줄여 디스크를 아낍니다.
                    """)
    @PutMapping("/dataset")
    public Map<String, Object> setDataset(
            @RequestParam(defaultValue = "5") int meet,
            @RequestParam(defaultValue = "5") int phone) {
        // 로컬 H2 에 일배치용 행을 그 건수만큼 시딩한다(주기용 2·2 는 그대로). 개발계 DB 에서는 거절된다.
        Map<String, Object> seed = sim.seed(meet, phone);
        idempotency.clearAll();
        Map<String, Object> out = new LinkedHashMap<>(dataset.snapshot());
        out.put("sim", seed);
        out.put("message", "일배치용 접견 %d · 전화 %d (+주기 2·2) 를 %s 에 시딩했습니다. [전체 실행 (일배치)] 로 돌리세요"
                .formatted(meet, phone, seed.get("db")));
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  장애 주입 — 한 건이 실패해도 배치가 끝까지 도는지
    // ══════════════════════════════════════════════════════════════════════

    @Operation(summary = "장애 시뮬레이션 설정",
            description = """
                    STT 구간에 **의도적으로** 실패와 지연을 섞습니다.

                    확인하려는 것: 파일 1건이 터져도 배치가 죽지 않고 `failCnt` 만 올리며
                    끝까지 도는가(Fault Tolerance). 1,300건 배치에서 한 건 때문에 전체가 멈추면
                    나머지를 전부 다시 처리해야 합니다.

                    실패와 지연은 **독립 판정**입니다 — 지연되면서 실패할 수도 있습니다.
                    """)
    @PutMapping("/chaos")
    public Map<String, Object> setChaos(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer failPercent,
            @RequestParam(required = false) Integer delayPercent,
            @RequestParam(required = false) Long delayMs) {
        faultInjector.configure(enabled, failPercent, delayPercent, delayMs);
        return faultInjector.snapshot();
    }

    /**
     * 잘못된 입력은 <b>400</b> 으로 돌려준다.
     *
     * <p>모드 이름·값, Mock 건수, 장애 확률은 전부 호출자가 준 값이다. 그대로 두면
     * {@code IllegalArgumentException} 이 500(서버 오류)으로 나가 "우리 서버가 고장났다"는
     * 잘못된 신호를 준다. 무엇이 잘못됐고 무엇이 허용되는지도 메시지에 이미 담겨 있다.</p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException e) {
        log.warn("[Mock] 잘못된 요청 — {}", e.getMessage());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", "BAD_REQUEST");
        out.put("message", e.getMessage());
        return out;
    }

    /**
     * 설정이 실제 환경과 맞지 않아 생긴 실패는 <b>사유를 그대로 보여 준다</b>.
     *
     * <p>기본 처리에 맡기면 화면에 "Internal Server Error" 만 남아, 스키마 설정이 틀린 것인지
     * 테이블이 없는 것인지 로그를 뒤져야 알 수 있다. 시연 중에는 그럴 여유가 없다.</p>
     */
    /**
     * DB 에 붙지 못했거나 SQL 이 실패한 경우 — <b>어느 DB 였고 왜 실패했는지</b>를 그대로 보여 준다.
     *
     * <p>개발계 DB 모드에서 포트포워딩이 없으면 "Connection refused" 가 나는데, 기본 처리에 맡기면
     * "Internal Server Error" 만 남아 H2 를 본 것인지 개발계를 본 것인지조차 알 수 없다.</p>
     */
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,
            org.springframework.transaction.TransactionException.class})
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleDataAccess(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String reason = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage().replaceAll("\s+", " ").trim();
        log.error("[Mock] DB 오류 — {} ({})", dbKind.label(), reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", "DB_ERROR");
        out.put("db", dbKind.label());
        out.put("url", dbKind.url());
        out.put("message", dbKind.label() + " 접근 실패 — " + reason
                + (dbKind.target() == egovframework.unstructured.collector.common.config.BoramiDbRouter.DbTarget.DIRECT
                    ? " (로컬이면 포트포워딩 확인: kubectl port-forward -n data-pipeline svc/borami-db-gijoxearrw 15433:5432 · 계정 BORAMI_DB_USER/BORAMI_DB_PASSWORD)"
                    : ""));
        return out;
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleIllegalState(IllegalStateException e) {
        log.error("[Mock] 설정·환경 오류 — {}", e.getMessage());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", "CONFIGURATION_ERROR");
        out.put("message", e.getMessage());
        return out;
    }

    // ── 내부 ───────────────────────────────────────────────────────────────

    private List<VoiceTarget> previewTargets() {
        return previewTargets(null);
    }

    private List<VoiceTarget> previewTargets(List<VoiceKind> kinds) {
        List<VoiceKind> want = (kinds == null || kinds.isEmpty())
                ? List.of(VoiceKind.MEET, VoiceKind.PHONE) : kinds;
        LocalDateTime now = LocalDateTime.now();
        BatchWindow window = BatchWindow.manual(now.minusDays(2), now.plusDays(1));

        List<VoiceTarget> all = new ArrayList<>();
        int limit = props.batch().maxFilesPerRun();
        List<String> codes = props.batch().speclMngSeCd();
        if (want.contains(VoiceKind.MEET)) {
            all.addAll(source.findMeetTargets(window, codes, limit));
        }
        if (want.contains(VoiceKind.PHONE)) {
            all.addAll(source.findPhoneTargets(window, codes, limit));
        }
        return all;
    }

    /**
     * 디렉터리 안의 <b>파일만</b> 지운다(하위 디렉터리는 건드리지 않는다).
     *
     * <p>재귀 삭제를 쓰지 않는 이유: 설정이 잘못돼 엉뚱한 경로가 들어오면 피해가 걷잡을 수 없다.
     * 초기화에 필요한 것은 평평한 파일 목록뿐이다.</p>
     */
    /**
     * 수신·작업 폴더를 비우고 <b>지우지 못한 것까지</b> 결과에 담는다.
     *
     * <p>지운 건수만 돌려주던 때는 "3건 삭제" 라고 보고해 놓고 실제로는 이름이 잡혀 있어,
     * 다음 실행이 수신 대기 타임아웃으로 죽어도 그 연결을 아무도 못 봤다. 이제는 화면까지 올린다
     * — {@code stuckFiles} 가 비어 있지 않으면 초기화가 끝난 것이 아니다.</p>
     *
     * <p>성능 테스트가 매 회차 준비 단계에서도 부른다({@code PerfRunService}).</p>
     */
    public Map<String, Object> clearLocalFiles() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> stuck = new ArrayList<>();
        out.put("idempotencyMarkers", idempotency.clearAll());
        for (var e : Map.of("meetFiles", dirs.receiveMeet(),
                "phoneFiles", dirs.receivePhone(),
                "workFiles", Path.of(dirs.work(), "mock_source_stt").toString()).entrySet()) {
            StaleFiles.Result r = StaleFiles.deleteAllIn(Path.of(e.getValue()));
            out.put(e.getKey(), r.deleted());
            stuck.addAll(r.stuck());
        }
        // ── 재처리용 중간 산출물 ─────────────────────────────────────────────
        //   이것까지 지워야 '처음부터' 다. 남겨 두면 다음 재처리(FROM_ANALYZE·FROM_SEND)가
        //   지난 시험의 보존물을 집어 가서, 새로 만든 데이터로 돌렸는데 옛 전사가 나온다.
        //   보존물에는 평문 음성·전사(성명·주민번호)가 들어 있으니 PII 잔재이기도 하다.
        //
        //   ① {ROOT}/stt_temp/{execId}/  — 전사 결과(SEND 재처리용). 폴더째 지운다
        out.put("sttTempFiles", sttTemp.clearAll());
        //   ② {ROOT}/xvram/decoding/decrypted_*  — 복호화 오디오(ANALYZE 재처리용).
        //      이 폴더에는 다른 것도 사니 우리 접두사만 지운다
        int audio = 0;
        Path work = Path.of(dirs.work());
        if (Files.isDirectory(work)) {
            try (Stream<Path> ws = Files.list(work)) {
                for (Path f : ws.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().startsWith("decrypted_")).toList()) {
                    if (StaleFiles.delete(f)) {
                        audio++;
                    } else {
                        stuck.add(f.getFileName().toString());
                    }
                }
            } catch (IOException e) {
                log.warn("[Mock] 복호화 보존물 목록 실패 — {} ({})", work, e.getMessage());
            }
        }
        out.put("decryptedAudio", audio);

        if (!stuck.isEmpty()) {
            out.put("stuckFiles", stuck);
            out.put("stuckWarning", "이 파일들을 지우지 못했습니다 — 다른 프로그램(탐색기 미리보기·재생기·백신)이 "
                    + "열고 있으면 같은 이름으로 새 파일을 만들 수 없어 다음 실행이 '수신 파일 대기 타임아웃'으로 "
                    + "끝납니다. 해당 파일을 닫고 초기화를 다시 눌러 주세요");
        }
        return out;
    }

    private List<Map<String, Object>> listFiles(String dir) {
        Path p = Path.of(dir);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!Files.isDirectory(p)) {
            return rows;
        }
        try (Stream<Path> s = Files.list(p)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", f.getFileName().toString());
                try {
                    r.put("size", Files.size(f));
                } catch (IOException e) {
                    r.put("size", -1);
                }
                rows.add(r);
            });
        } catch (IOException e) {
            log.warn("[Mock] 디렉터리 조회 실패 — {} ({})", dir, e.getMessage());
        }
        return rows;
    }
}
