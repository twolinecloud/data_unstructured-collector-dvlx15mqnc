package egovframework.unstructured.collector.voice.controller;

import egovframework.unstructured.collector.voice.batch.IdempotencyGuard;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import egovframework.unstructured.collector.voice.batch.VoiceBatchResult;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import egovframework.unstructured.collector.common.broker.XvarmBrokerClient;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.decrypt.DecryptService;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.voice.source.BoramiSourceClient;
import egovframework.unstructured.collector.voice.stt.SttClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 음성 수집 배치 운영·시연용 API.
 *
 * <p>스케줄러를 기다리지 않고 배치를 돌려볼 수 있게 한다. 다음 주 시연에서
 * "지금 한 번 돌려 보겠습니다"가 가능해야 하기 때문이다.</p>
 */
@lombok.extern.log4j.Log4j2
@Tag(name = "1. 음성 수집 배치", description = "보라미 음성(접견·통화) 수집 → 복호화 → STT → 처리 이력 적재")
@RestController
@RequestMapping(value = "/api/v1/voice", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class VoiceBatchController {

    /** 화면·API 로 돌리는 배치의 워커 수 — 스케줄 배치와 같은 설정({@code voice.batch.concurrency}, 운영 1). */
    @org.springframework.beans.factory.annotation.Value("${voice.batch.concurrency:1}")
    private int batchConcurrency;

    private final VoiceCollectService service;
    /** 비정형 스케줄(admin 연동) — 상태에 지금 적용된 스케줄을 싣는다. */
    private final egovframework.unstructured.collector.batch.schedule.UnstructuredBatchScheduler scheduler;
    private final egovframework.unstructured.collector.batch.UnstructuredJobRunner runner;
    private final egovframework.unstructured.collector.voice.batch.BatchProgress progress;
    private final egovframework.unstructured.collector.common.health.HealthProbeService healthProbe;
    private final egovframework.unstructured.collector.voice.batch.StageFaultState stageFault;
    private final egovframework.unstructured.collector.voice.stt.SttTempStore sttTemp;
    private final IdempotencyGuard idempotency;
    private final VoiceProperties props;
    private final VoiceDirState dirs;
    private final BoramiSourceClient source;
    private final XvarmBrokerClient broker;
    private final egovframework.unstructured.collector.common.broker.RestXvarmBrokerClient restBroker;
    private final egovframework.unstructured.collector.common.sync.PhoneFileProvider phoneFileProvider;
    private final DecryptService decryptService;
    private final SttClient sttClient;
    private final LogCollectorClient logCollector;
    private final egovframework.unstructured.collector.common.config.VoiceModeState modeState;
    private final egovframework.unstructured.collector.common.config.FaultInjector faultInjector;
    private final egovframework.unstructured.collector.common.config.MockDatasetState dataset;
    private final egovframework.unstructured.collector.common.transfer.ZenonClient zenon;
    private final egovframework.unstructured.collector.common.config.DbKindDetector db;
    private final egovframework.unstructured.collector.common.config.BoramiTableNames tables;
    private final egovframework.unstructured.collector.common.config.DeployEnvPreset deployEnv;
    private final egovframework.unstructured.collector.voice.batch.VerificationService verification;

    /**
     * [바로 실행]이 기본으로 되짚어 보는 기간(일). 이 기간 안의 <b>미처리 건 전부</b>를 처리한다.
     * 마지막 성공 시점이 이보다 더 이르면 그 시점까지 넓힌다.
     */
    @org.springframework.beans.factory.annotation.Value("${voice.batch.catchup-lookback-days:30}")
    private int catchupLookbackDays;

    @Operation(summary = "일배치 실행",
            description = """
                    전날 00시 ~ 오늘 00시 구간을 처리한다. 스케줄과 무관하게 즉시 실행한다.

                    - `kinds` 를 비우면 접견·전화 둘 다. `MEET` / `PHONE` 로 한 트랙만
                    - `test=true` 면 EXEC_ID 가 `…TST…` 로 채번되어 [테스트 데이터 초기화] 로 지울 수 있다
                    - 로그 컬렉터에 T1(배치) · T2(COLLECT·ANALYZE·SEND — 3단계) · T4(파일별) 를 남기고,
                      STT 결과는 제논(Zenon)으로 보낸다(응답의 `zenon` — 모드·주소·보낸 건수). PV 에는 남기지 않는다
                    """)
    @PostMapping("/batches/daily")
    public VoiceBatchResult daily(@RequestParam(required = false) List<VoiceKind> kinds,
                                  @RequestParam(defaultValue = "false") boolean test) {
        return service.run(BatchWindow.daily(LocalDateTime.now()), kinds, "MANUAL", test, ResumeMode.FULL, null,
                batchConcurrency);
    }

    @Operation(summary = "주기배치 실행",
            description = """
                    **당일 00:00 ~ 지금**을 처리한다(자정 직후에는 periodic-lag-min 만큼 어제로 넓힌다).
                    최근 20분만 보면 스케줄러가 멈췄던 구간의 건이 다음 창에도 들어오지 않아 영영 빠진다.
                    이미 성공한 건은 멱등 표식이 건너뛴다.

                    - `kinds=MEET` 접견만 · `kinds=PHONE` 전화만 · 비우면 둘 다
                    - `test=true` 면 EXEC_ID 가 `…TST…` 로 채번된다(시뮬레이터 기본)
                    - 실패한 건은 멱등 표식이 남지 않아 **다음 실행에서 다시 처리된다**(재처리 시나리오)
                    """)
    @PostMapping("/batches/periodic")
    public VoiceBatchResult periodic(@RequestParam(required = false) List<VoiceKind> kinds,
                                     @RequestParam(defaultValue = "false") boolean test) {
        return service.run(BatchWindow.periodic(LocalDateTime.now(), props.batch().periodicLagMin()),
                kinds, "MANUAL", test, ResumeMode.FULL, null, batchConcurrency);
    }

    @Operation(summary = "단계별 장애 주입 설정",
            description = """
                    T4 `STEP_TYPE_CD`(C05)와 같은 단위로 **단계마다 따로** 장애를 켭니다.
                    전역 스위치 하나가 모든 구간에 걸리던 방식을 대체합니다 —
                    "STT 만 죽었을 때 이어서 처리되는가" 를 보려는데 수집까지 같이 깨지면 시나리오가 성립하지 않습니다.

                    - `mode=OFF` 정상 · `ALL` 전건 실패 · `PARTIAL` 정해진 건수만 실패
                    - `PARTIAL` 은 **확률이 아니라 건수**입니다. 10건 배치에 10% 면 정확히 1건이 실패해
                      배치가 `PARTIAL` 로 끝납니다. 작은 배치에서도 최소 1건은 실패시킵니다.
                    """)
    @PutMapping("/faults/{stage}")
    public Map<String, Object> setFault(
            @PathVariable egovframework.unstructured.collector.voice.batch.StageFaultState.Stage stage,
            @RequestParam egovframework.unstructured.collector.voice.batch.StageFaultState.Mode mode,
            @RequestParam(required = false) Integer failPercent) {
        stageFault.set(stage, mode, failPercent);
        return stageFault.snapshot();
    }

    @Operation(summary = "단계별 장애 전체 해제",
            description = "시나리오를 바꿀 때 앞 설정이 남아 간섭하지 않게 한 번에 끕니다.")
    @DeleteMapping("/faults")
    public Map<String, Object> clearFaults() {
        stageFault.clear();
        return stageFault.snapshot();
    }

    @Operation(summary = "단계별 장애 현황")
    @GetMapping("/faults")
    public Map<String, Object> faults() {
        return stageFault.snapshot();
    }

    @Operation(summary = "재처리 — 이어서 하기(Resume)",
            description = """
                    보존된 중간 산출물로 **끊긴 단계부터** 다시 돌립니다.

                    | resume | 시작 단계 | 쓰는 보존물 |
                    |---|---|---|
                    | `FULL` | 수집부터 전부 | 없음 |
                    | `FROM_ANALYZE` | STT 부터 | `{ROOT}/xvram/decoding/decrypted_*` |
                    | `FROM_SEND` | 제논 전송부터(STT 생략) | `{ROOT}/stt_temp/{execId}/*.json` |

                    **제논 전송에서 깨진 건**은 `FROM_SEND` 로 잇습니다 — 보존된 전사를 STT 없이 다시 보냅니다.
                    전송이 성공하면 그 건의 임시 파일(받은 원본 · 복호화 오디오 · 전사 보존물)을 지웁니다.

                    **보존물이 없으면 앞 단계로 내려갑니다.** 이어서 하기는 빠른 길이지 유일한 길이 아니라,
                    `voice.resume.keep-on-failure=false` 인 환경에서도 재처리가 그대로 동작합니다.

                    이미 성공한 건은 멱등 표식 때문에 `건너뜀` 이 되므로, 실패했던 건만 다시 처리됩니다.
                    `fromExecId` 를 주면 그 배치의 보존물만 봅니다(비우면 가장 최근 것).

                    `from`·`to` 를 주면 그 구간만 다시 봅니다(`lookbackMin` 무시) — 더미 데이터의 시나리오별 구간
                    (`POST /api/v1/mock/sim-data/generate` 응답의 `windows`)을 그 시나리오의 기대 모드로 재처리할 때 씁니다.
                    """)
    /** 구간 없이 — 최근 {@code lookbackMin} 분(종전 시그니처). */
    public VoiceBatchResult resume(ResumeMode resume, String fromExecId, List<VoiceKind> kinds, boolean test, int lookbackMin) {
        return resume(resume, fromExecId, kinds, test, lookbackMin, null, null);
    }

    @PostMapping("/batches/resume")
    public VoiceBatchResult resume(
            @RequestParam(defaultValue = "FROM_ANALYZE") ResumeMode resume,
            @RequestParam(required = false) String fromExecId,
            @RequestParam(required = false) List<VoiceKind> kinds,
            @RequestParam(defaultValue = "false") boolean test,
            @RequestParam(defaultValue = "2880") int lookbackMin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        LocalDateTime now = LocalDateTime.now();
        // 재처리는 실패한 건을 다시 잡아야 하므로 창을 넉넉히 연다. 기본 이틀치 —
        //   주기 창(20분)으로 잡으면 조금 전에 깨진 건도 이미 창 밖이고, 하루치로 잡으면
        //   일배치 픽스처의 첫 건(어제 00:00:00)이 24시간을 넘겨 빠진다.
        BatchWindow window = (from != null && to != null)
                ? BatchWindow.manual(from, to)
                : BatchWindow.manual(now.minusMinutes(Math.max(1, lookbackMin)), now.plusMinutes(1));
        log.info("[Batch] 재처리 — resume={} fromExecId={} 창=[{} ~ {})", resume, fromExecId, window.from(), window.to());
        return service.run(window, kinds, "RESUME", test, resume, fromExecId, batchConcurrency);
    }

    @Operation(summary = "중간 산출물 현황",
            description = "지금 보존된 전사 결과가 어느 배치에 몇 건 남아 있는지. 재처리가 무엇을 집어 갈지 미리 봅니다.")
    @GetMapping("/batches/preserved")
    public Map<String, Object> preserved() {
        Map<String, Object> out = new LinkedHashMap<>(sttTemp.status());
        out.put("decryptedAudioDir", dirs.work());
        return out;
    }

    @Operation(summary = "연계 5종 헬스체크",
            description = """
                    DB · ESB · 브로커 · 로그 컬렉터 · 에이전트 커넥터를 **서버가 대신 찔러 봅니다**.

                    화면에서 직접 부르면 오리진이 달라 CORS 에 걸리고, K8s 서비스명은 브라우저가 풀 수도 없습니다.

                    상태는 셋입니다 — `UP`(연결됨) · `DOWN`(연결 안 됨) · `OFF`(쓰지 않는 중).
                    **끈 것은 고장이 아닙니다**: Mock 브로커·미연동 로그 컬렉터는 회색(OFF)입니다.

                    같은 결과를 3초간 재사용합니다. `force=true` 면 캐시를 무시하고 지금 붙어 봅니다.
                    """)
    @GetMapping("/health")
    public Map<String, Object> health(@RequestParam(defaultValue = "false") boolean force) {
        return healthProbe.health(force);
    }

    @Operation(summary = "바로 실행 (온디맨드 · DB 워터마크 ~ 지금)",
            description = """
                    **마지막으로 성공한 수집 구간의 끝 ~ 지금**을 처리합니다. 기획서 배치 스케줄 목록의 [바로 실행] 버튼이 부르는 API 입니다.

                    - 시작점(워터마크) = 로그 컬렉터 T1(`kcais.tb_batch_exec_log`)에서
                      `exec_sts_cd = 'SUCCESS'` 인 이 작업 배치의 **`MAX(target_to_dtm)`** — `GET /api/v1/logs/batches/watermark`
                    - 성공한 배치가 아직 없으면 **최근 `lookbackDays` 일**(기본 `voice.batch.catchup-lookback-days`=30)을 되짚습니다
                    - `test=true`(시뮬레이터)는 `TEST_BATCH` 이력으로, 운영은 `VOICE_ANALYSIS` 이력으로 워터마크를 셉니다 —
                      시뮬레이터가 운영 기준점을 밀지 않게
                    - PARTIAL·FAIL·CANCELED 배치는 워터마크를 밀지 않습니다. 빠진 건이 있는 구간이 '다 수집했다' 로 둔갑하지 않게
                    - 이미 성공한 건은 멱등 표식이 건너뜁니다

                    예전에는 기준점을 파일(`{ROOT}/state/last_success.txt`)에 따로 적었습니다. 파드가 PV 를 잃으면 기준점도 잃고,
                    같은 사실이 T1 과 파일 두 군데에 있어 어긋날 수 있어 T1 에서 바로 읽도록 바꿨습니다.
                    """)
    @PostMapping("/batches/on-demand")
    public VoiceBatchResult onDemand(@RequestParam(required = false) List<VoiceKind> kinds,
                                     @RequestParam(defaultValue = "false") boolean test,
                                     @RequestParam(required = false) Integer lookbackDays) {
        LogCollectorClient.Watermark wm = watermark(test);
        BatchWindow w = BatchWindow.onDemand(LocalDateTime.now(), wm == null ? null : wm.at(), lookbackOr(lookbackDays));
        log.info("[Batch] 바로 실행 — 구간 {} (시작점: {})", w, wm != null
                ? "DB 워터마크 " + wm.at() + " · " + wm.execId()
                : "워터마크 없음 → 최근 " + lookbackOr(lookbackDays) + "일");
        return service.run(w, kinds, "ON_DEMAND", test, ResumeMode.FULL, null, batchConcurrency);
    }

    /**
     * [바로 실행]의 창 — DB 워터마크 ~ 지금. 워터마크가 없으면 최근 {@code lookbackDays} 일.
     *
     * <p>[미처리 건수]와 실제 실행이 <b>같은 창</b>을 써야 한다. 둘이 다르면 "0건이라 생성할까요?"
     * 라고 물어 놓고 실행하면 무언가를 처리하거나, 그 반대가 된다.</p>
     */
    private BatchWindow onDemandWindow(LocalDateTime now, Integer lookbackDays, boolean test) {
        LogCollectorClient.Watermark w = watermark(test);
        return BatchWindow.onDemand(now, w == null ? null : w.at(), lookbackOr(lookbackDays));
    }

    private int lookbackOr(Integer lookbackDays) {
        return lookbackDays == null ? catchupLookbackDays : Math.max(1, lookbackDays);
    }

    /** 이 작업의 DB 워터마크. 시험(test)과 운영은 다른 이력을 본다. 없거나 컬렉터 미연동이면 null. */
    private LogCollectorClient.Watermark watermark(boolean test) {
        return logCollector.watermark(props.batch().dataTypeCd(),
                test ? props.batch().testJobId() : props.batch().jobId());
    }

    @Operation(summary = "미처리 건수 (실행 전 확인)",
            description = """
                    배치를 **열지 않고** 이 창을 돌리면 처리할 건이 몇 개인지 셉니다. 시뮬레이터가 실행 버튼을 누르기 직전에 부릅니다.

                    - `type` — `daily`(어제 하루) · `periodic`(당일 00:00~지금) · `on-demand`(DB 워터마크 ~ 지금)
                    - `test` — `on-demand` 의 워터마크를 시험 이력(`TEST_BATCH`)으로 셀지. 시뮬레이터는 `true`
                    - `pending` 이 0 이면 화면이 "시뮬레이션 데이터를 생성하시겠습니까?" 를 묻습니다.
                      대상은 있는데 모두 처리가 끝난 경우(`total > 0`, `pending = 0`)도 0 입니다 —
                      그대로 누르면 전부 '건너뜀' 으로 끝나 확인할 것이 없으니까요
                    - 로그 컬렉터에 T1 을 남기지 않습니다. 보라미 조회와 멱등 표식 확인뿐입니다
                    """)
    @GetMapping("/batches/pending")
    public Map<String, Object> pending(@RequestParam(defaultValue = "daily") String type,
                                       @RequestParam(required = false) List<VoiceKind> kinds,
                                       @RequestParam(required = false) Integer lookbackDays,
                                       @RequestParam(defaultValue = "false") boolean test) {
        LocalDateTime now = LocalDateTime.now();
        BatchWindow w = switch (type.trim().toLowerCase()) {
            case "daily" -> BatchWindow.daily(now);
            case "periodic" -> BatchWindow.periodic(now, props.batch().periodicLagMin());
            case "on-demand", "ondemand" -> onDemandWindow(now, lookbackDays, test);
            default -> throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "type 은 daily · periodic · on-demand 중 하나입니다: " + type);
        };
        // 시험 실행은 대시보드 더미(DMY)를 집지 않는다 — 실제로 집을 것과 같게 센다
        Map<String, Object> out = new java.util.LinkedHashMap<>(service.pending(w, kinds, test));
        out.put("type", type);
        return out;
    }

    @Operation(summary = "검증 패널 — DB·PV 현황과 수동 확인 명령",
            description = """
                    한 배치(EXEC_ID)의 결과가 **로그 테이블과 PV 에 실제로 어떻게 남았는지**를 한 장으로 돌려줍니다.

                    - `db` — 로그 컬렉터를 통해 T1(`kcais.tb_batch_exec_log`) · T2(`tb_batch_step_log`) ·
                      T4(`tb_file_proc_log`, 상태별). 이 서비스는 로그 DB 에 직접 붙지 않습니다.
                      비정형은 3단계(COLLECT · ANALYZE · SEND) — T5(비식별·전송 로그)는 남지 않습니다
                    - `files` — 복호화 보존물(`{ROOT}/xvram/decoding/decrypted_*`) · 전사 보존물(`{ROOT}/stt_temp/{execId}`).
                      파일 이름은 처음 2개(`head`)·마지막 2개(`tail`)만 싣습니다
                    - `zenon` — 이 배치가 제논에 보낸 수신증(최근 것부터, 메모리에 남은 만큼)
                    - `sql` · `cli` — 위를 **손으로** 확인할 때 그대로 복사해 쓰는 SQL 과 `ls`/`cat` 명령.
                      배포 환경이면 `kubectl exec` 접두가 붙습니다
                    """)
    @GetMapping("/verify")
    public Map<String, Object> verify(@RequestParam(required = false) String execId) {
        return verification.verify(execId);
    }

    @Operation(summary = "T4 단계별 이력 (로그 컬렉터 STEP_TYPE_CD)",
            description = """
                    한 배치(EXEC_ID)의 T4 행을 로그 컬렉터에서 읽어 **파일마다 끝난 단계**(`STEP_TYPE_CD` · C05)와 상태를 돌려준다.
                    시뮬레이터 검증 패널 · ⑤ 키 표식 시나리오 · 6번 탭이 단계별 이력을 그린다. admin-api 를 거치지 않는다.

                    - `byStep` — 단계 × 상태 건수(`{COLLECT:{FAIL:1}, SEND:{SUCCESS:3}}`) · `byStatus` — 상태별 건수
                    - `rows` — 최대 300행(`truncated`). 실패는 실패한 단계, 성공은 마지막 단계 `SEND`
                    - `source=COLUMN` — 컬럼값 그대로. `ERR_STACK` — DB 에 V16 이 아직 없어 ERR_STACK 의 `[단계]` 로 추정(`stepColumn=false`)
                    - `available=false` — 로그 컬렉터 미연동 · EXEC_ID 없음 · T4 조회 API 이전 컬렉터
                    """)
    @GetMapping("/verify/file-procs")
    public Map<String, Object> verifyFileProcs(@RequestParam String execId) {
        return verification.t4Steps(execId);
    }

    @Operation(summary = "[바로 실행] 워터마크 (DB)",
            description = """
                    [바로 실행]이 어디서부터 볼지 — 로그 컬렉터 T1 의 `exec_sts_cd='SUCCESS'` 배치 `MAX(target_to_dtm)`.
                    없으면 `watermark` 가 null 이고 `from` 은 최근 30일 전입니다. `test=true` 면 시험 이력(`TEST_BATCH`) 기준.
                    """)
    @GetMapping("/batches/watermark")
    public Map<String, Object> watermarkInfo(@RequestParam(defaultValue = "false") boolean test,
                                             @RequestParam(required = false) Integer lookbackDays) {
        LocalDateTime now = LocalDateTime.now();
        LogCollectorClient.Watermark w = watermark(test);
        BatchWindow win = BatchWindow.onDemand(now, w == null ? null : w.at(), lookbackOr(lookbackDays));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("jobId", test ? props.batch().testJobId() : props.batch().jobId());
        out.put("dataTypeCd", props.batch().dataTypeCd());
        out.put("watermark", w == null ? null : w.at().toString());
        out.put("watermarkExecId", w == null ? null : w.execId());
        out.put("source", w != null ? "DB — kcais.tb_batch_exec_log MAX(target_to_dtm) WHERE exec_sts_cd='SUCCESS'"
                : (logCollector.isEnabled() ? "성공 배치 없음 → 최근 " + lookbackOr(lookbackDays) + "일"
                                             : "로그 컬렉터 미연동 → 최근 " + lookbackOr(lookbackDays) + "일"));
        out.put("from", win.from().toString());
        out.put("to", win.to().toString());
        return out;
    }

    @Operation(summary = "배치 중단",
            description = """
                    돌고 있는 배치를 멈춥니다.

                    **처리 중인 건은 끝까지 갑니다.** 브로커 왕복이나 STT 호출 한가운데서 끊으면
                    복호화 원본이 디스크에 남거나 반쯤 쓴 산출물이 생깁니다. 다음 건으로 넘어가기 직전에만 멈춥니다.

                    남은 건은 `건너뜀(중단됨)` 으로 남겨 T1·T4 의 합계가 어긋나지 않게 합니다.

                    - `accepted` — **이번 요청이 먹혔는지.** 돌고 있지 않으면 `false` 입니다
                    - `canceled` — 진행 상태에 남아 있는 중단 표식(직전 배치를 멈췄다면 그 배치가 끝난 뒤에도 `true`)
                    """)
    @PostMapping("/batches/cancel")
    public Map<String, Object> cancelBatch() {
        boolean accepted = progress.cancel();
        Map<String, Object> out = new LinkedHashMap<>(progress.snapshot());
        // 진행 상태를 먼저 깔고 그 위에 이번 요청의 결과를 얹는다.
        //   반대로 하면 스냅샷의 canceled(직전 배치의 표식)가 이번 응답을 덮어써,
        //   돌고 있지 않은데도 "중단했다"고 답하게 된다.
        out.put("accepted", accepted);
        out.put("message", accepted ? "중단을 요청했습니다 — 처리 중인 건이 끝나면 멈춥니다"
                                    : "돌고 있는 배치가 없습니다");
        return out;
    }

    @Operation(summary = "배치 진행률",
            description = """
                    지금 도는 배치가 **몇 건 중 몇 번째**인지. 화면 진행률 바가 짧은 주기로 폴링합니다.

                    배치 REST 는 동기라 전건이 끝나야 응답이 옵니다. 그동안 무엇을 하고 있는지 보려면
                    이 엔드포인트를 따로 읽어야 합니다. 배치가 끝나도 **마지막 상태를 지우지 않습니다** —
                    화면이 100% 를 한 번은 봐야 하고, 끝난 뒤에도 직전 배치 요약을 읽을 수 있어야 합니다.

                    `running=false` 이고 `total=0` 이면 이 프로세스에서 아직 배치가 돈 적이 없습니다.
                    """)
    @GetMapping("/batches/progress")
    public Map<String, Object> progress() {
        return progress.snapshot();
    }

    @Operation(summary = "구간 지정 실행 (재처리)",
            description = """
                    임의 시간창을 처리한다. Mock 모드에서는 시간창과 무관하게 고정 대상이 나온다.

                    실패 이력이 있는 구간을 다시 돌릴 때 쓴다 — 앞선 배치에서 실패한 건은 멱등 표식이 없어
                    다시 처리되고, 성공했던 건은 `건너뜀` 으로 잡힌다. 새 EXEC_ID 로 T1·T2·T4 가 따로 남는다.
                    """)
    @PostMapping("/batches/manual")
    public VoiceBatchResult manual(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) List<VoiceKind> kinds,
            @RequestParam(defaultValue = "false") boolean test) {
        return service.run(BatchWindow.manual(from, to), kinds, "MANUAL", test, ResumeMode.FULL, null,
                batchConcurrency);
    }

    @Operation(summary = "현재 구성 조회",
            description = "5개 스위치(source/broker/phone/decrypt/stt)가 각각 어느 모드인지, 로그 컬렉터 연결이 살아 있는지 본다.")
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> modes = new LinkedHashMap<>();
        modes.put("source", source.mode());
        modes.put("broker", broker.mode());
        modes.put("phone", phoneFileProvider.mode());
        modes.put("decrypt", decryptService.mode());
        modes.put("stt", sttClient.mode());
        modes.put("xvarm", modeState.xvarm().name());

        Map<String, Object> logc = new LinkedHashMap<>();
        logc.put("enabled", logCollector.isEnabled());
        logc.put("baseUrl", blankToNull(logCollector.baseUrl()));

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("scheduleEnabled", props.batch().scheduleEnabled());
        batch.put("running", runner.isRunning() || progress.isRunning());
        // 고정 cron(일배치 02:00 · 10분 주기)은 없앴다 — 관리 화면(admin-api) UNSTRUCTURED 설정 하나로 돈다(2026-10-02)
        batch.put("schedule", scheduler.snapshot());
        batch.put("job", runner.status());
        batch.put("periodicLagMin", props.batch().periodicLagMin());
        batch.put("maxFilesPerRun", props.batch().maxFilesPerRun());
        batch.put("speclMngSeCd", props.batch().speclMngSeCd());
        batch.put("retainSourceFile", props.batch().retainSourceFile());

        // 디렉터리 — 런타임 상태(시뮬레이터에서 바꾼 값)와 기동 설정값, 프리셋을 함께 내려준다.
        //   receiveMeet/receivePhone: 브로커·ESB 가 떨구는 곳 · work: 복호화 산출물·멱등 표식
        //   최종 결과 폴더는 없다 — STT 결과는 제논(Zenon)으로 보내고 PV 에 남기지 않는다(2026-10-01)
        Map<String, Object> dirMap = new LinkedHashMap<>(dirs.snapshot());
        dirMap.put("namingPolicy", props.sync().namingPolicy());
        dirMap.put("configured", dirs.configured());
        dirMap.put("presets", dirs.presets());
        dirMap.put("suggestedBaseDir", deployEnv.rootDir());
        dirMap.put("labels", VoiceDirState.LABELS);
        dirMap.put("outputPattern", "PV 에 남기지 않음 — 제논(Zenon) 전송 " + zenon.mode() + " · " + zenon.endpoint());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modes", modes);
        out.put("switchLabels", switchLabels());
        // 지금 붙어 있는 DB — 개발계 DB 모드에서 "어디를 보는지" 를 화면에 그대로 보여준다
        Map<String, Object> dbInfo = new LinkedHashMap<>();
        dbInfo.put("target", db.target().name());
        dbInfo.put("kind", db.kind().name());
        dbInfo.put("label", db.label());
        dbInfo.put("url", db.url());
        dbInfo.put("tables", tables.describe());
        dbInfo.put("xvarmMode", modeState.xvarm().name());
        // 마지막 연결 확인 결과(있으면). 상태 조회가 커넥션을 열어 느려지지 않게 캐시만 싣는다
        dbInfo.put("lastProbe", db.lastProbe());
        out.put("db", dbInfo);
        out.put("configuredModes", modeState.configured());
        out.put("logCollector", logc);
        out.put("batch", batch);
        out.put("dirs", dirMap);
        // 두 트랙의 단계·모드·실제 호출 대상을 서버가 직접 내려준다.
        //   화면에 하드코딩하면 설정을 바꿔도 그림이 그대로라 "무엇이 실제로 도는지"를
        //   화면만 보고는 알 수 없게 된다 — 실제로 그래서 전화 트랙이 브로커 스위치에
        //   끌려가는 것을 아무도 눈치채지 못했다.
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("brokerBaseUrl", modeState.brokerBaseUrl());
        endpoints.put("brokerBaseUrlConfigured", modeState.configuredBrokerBaseUrl());
        out.put("endpoints", endpoints);
        out.put("tracks", tracks());
        // "이 조합으로는 반드시 실패한다" 를 배치 전에 알린다.
        out.put("warnings", warnings());
        // 장애 주입이 켜진 줄 모르고 시연하면 실패 건수를 버그로 오해한다 — 항상 노출한다.
        out.put("chaos", faultInjector.snapshot());
        // 단계별 장애(T4 STEP_TYPE_CD 단위) — 화면이 켜져 있는 단계를 알아야 경고를 띄운다
        out.put("stageFaults", stageFault.snapshot());
        out.put("dataset", dataset.snapshot());
        // 환경 자동 감지(OS · profile) 결과 — 화면이 이 값으로 경로·브로커·컬렉터를 자동으로 맞춘다
        out.put("env", deployEnv.snapshot());
        return out;
    }

    @Operation(summary = "환경 설정 조회 (자동 프리셋)",
            description = """
                    서버가 기동 시 **OS 와 active profile 로 감지한 환경 프리셋**과 지금 적용 중인 값을 돌려줍니다.
                    시뮬레이터가 로드될 때 이 값으로 상단 디렉터리 input · [XVARM 브로커] 라디오 · 로그 컬렉터 카드 · DB 정보를 맞춥니다.

                    | | Windows / 로컬(local 프로파일) | Linux / 배포(K8s) |
                    |---|---|---|
                    | ROOT_DIR | `C:/k8s/unstructured_collector` | `/k8s/unstructured_collector` |
                    | XVARM 브로커 | `http://localhost:8082` | `http://borami-xvarm-broker-1joiuorqhl:8080` |
                    | 로그 컬렉터 | `http://localhost:8090/logc` | `http://log-collector-a2z96kgyrm:8080/logc` |

                    설정(환경변수 `VOICE_BASE_DIR` · `VOICE_BROKER_BASE_URL` · `LOG_COLLECTOR_BASE_URL`)이 있으면 그것이 이깁니다.
                    표준 디렉터리 5종은 `{ROOT_DIR}/xvram/original_voice_files` · `esb/meet` · `esb/phone` · `xvram/decoding` 이고
                    없으면 자동 생성됩니다. STT 결과는 PV 에 남기지 않고 제논으로 보냅니다.
                    """)
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", deployEnv.snapshot());
        Map<String, Object> dirMap = new LinkedHashMap<>(dirs.snapshot());
        dirMap.put("configured", dirs.configured());
        dirMap.put("presets", dirs.presets());
        dirMap.put("labels", VoiceDirState.LABELS);
        out.put("dirs", dirMap);
        Map<String, Object> brokerMap = new LinkedHashMap<>();
        brokerMap.put("mode", modeState.broker().name());
        brokerMap.put("baseUrl", modeState.brokerBaseUrl());
        brokerMap.put("baseUrlConfigured", modeState.configuredBrokerBaseUrl());
        brokerMap.put("presets", props.broker().presets());
        out.put("broker", brokerMap);
        Map<String, Object> logc = new LinkedHashMap<>();
        logc.put("enabled", logCollector.isEnabled());
        logc.put("baseUrl", blankToNull(logCollector.baseUrl()));
        out.put("logCollector", logc);
        Map<String, Object> dbInfo = new LinkedHashMap<>();
        dbInfo.put("target", db.target().name());
        dbInfo.put("kind", db.kind().name());
        dbInfo.put("label", db.label());
        dbInfo.put("url", db.url());
        dbInfo.put("xvarmMode", modeState.xvarm().name());
        dbInfo.put("lastProbe", db.lastProbe());
        out.put("db", dbInfo);
        out.put("modes", modeState.snapshot());
        return out;
    }

    /**
     * 접견·전화 두 트랙의 단계 정의.
     *
     * <p>각 단계마다 <b>지금 어떤 모드로 도는지</b>와 <b>실제로 무엇을 호출하는지</b>를 함께 싣는다.
     * 화면은 이걸 그대로 그리기만 하면 된다.</p>
     */
    private Map<String, Object> tracks() {
        Map<String, Object> meet = new LinkedHashMap<>();
        meet.put("label", "접견 (MEET)");
        meet.put("kind", "MEET");
        meet.put("desc", "보라미 DB 4단 조인으로 키를 얻어 XVARM 브로커에 추출을 지시하고, ESB FILE2FILE 로 받는다");
        meet.put("steps", List.of(
                sourceStep("보라미 조회",
                        "TB_IMSC_PTPR_DT → TB_RERD_TFIN_DS → TB_SMSM_CMFI_BS → XVARM.ASYSCONTENTELEMENT (4단 조인)", true),
                brokerStep(),
                step("ESB 수신", null, props.sync().namingPolicy().name(),
                        dirs.receiveMeet(),
                        "ESB FILE2FILE(P20) 이 동기화해 준 파일을 감시한다. 크기가 안정되어야 처리한다"),
                step("복호화", "decrypt", decryptService.mode(),
                        "R플레이어 로직 포팅",
                        "CMMN_FILE_ENC_YN='Y' 인 건만. 키 미수령(계획서 Q8)"),
                step("STT", "stt", sttClient.mode(),
                        sttEndpoint(),
                        "STT 텍스트 생성 후 T2 ANALYZE 마감. T4 에는 처리 상태만 남는다"),
                zenonStep()));

        Map<String, Object> phone = new LinkedHashMap<>();
        phone.put("label", "전화 (PHONE)");
        phone.put("kind", "PHONE");
        phone.put("desc", "단일 테이블 조회 후 ESB 전화 전용 연계 프로바이더가 떨궈 주는 파일을 받는다. XVARM·브로커를 타지 않는다");
        phone.put("steps", List.of(
                sourceStep("전화 DB 조회",
                        "TB_IMPH_UCDR_DS 단일 테이블. TELP_PCALL_RECRD_YN='Y' + 특이수용자", false),
                step("전화 파일 연계", "phone", phoneFileProvider.mode(),
                        dirs.receivePhone(),
                        "별도 서버의 파일을 ESB 전화 전용 프로바이더가 수신 디렉터리에 떨궈 준다. 우리는 대기만 한다"),
                step("복호화", "decrypt", decryptService.mode(),
                        "ARIA-128 / AES-256",
                        "KEY = TELP_RECRD_FILE_ID. 복호화 주체 미확정(계획서 Q13)"),
                step("STT", "stt", sttClient.mode(),
                        sttEndpoint(),
                        "TELP_STT_FLPTH_NM 에 기존 STT 가 있으면 재수행하지 않는다(계획서 Q1)"),
                zenonStep()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meet", meet);
        out.put("phone", phone);
        return out;
    }

    /**
     * 브로커 단계 — 모드 드롭다운에 더해 <b>주소를 화면에서 바꿀 수 있게</b> 정보를 싣는다.
     *
     * <p>모드만 REST 로 올리고 주소를 못 바꾸면 배치가 전건 실패한다. 실제로 그 상태로
     * 두 번 막혔다. 브로커가 사는 곳은 환경마다 다르므로(개발계 K8s 서비스명 / 로컬 localhost)
     * 스위치 옆에서 바로 고를 수 있어야 한다.</p>
     *
     * <p>화면은 프리셋을 라디오로 그리고, 현재 주소({@code url})와 같은 항목을 선택 상태로,
     * 기동 설정값({@code urlConfigured})과 같은 항목에 (Default) 를 붙인다 — 어느 것이
     * 재기동·모드 초기화 시 돌아가는 값인지 화면만 보고 알 수 있어야 한다.</p>
     */
    private Map<String, Object> brokerStep() {
        Map<String, Object> m = step("XVARM 브로커", "broker", broker.mode(),
                brokerEndpoint(),
                "POST /api/v1/xvarm/extract 로 추출 지시 후 상태 폴링. XVARM 이 보라미 임시 폴더에 파일을 만든다");
        // MOCK 일 때는 주소를 쓰지 않으므로 편집 UI 를 내보내지 않는다 — 안 쓰는 값을
        // 고치게 두면 "바꿨는데 왜 그대로지?" 가 된다.
        if (modeState.broker() == VoiceProperties.BrokerMode.REST) {
            m.put("urlKey", "broker");
            m.put("url", modeState.brokerBaseUrl());
            m.put("urlConfigured", modeState.configuredBrokerBaseUrl());
            m.put("urlPresets", props.broker().presets().stream()
                    .map(x -> {
                        Map<String, Object> pm = new LinkedHashMap<>();
                        pm.put("label", x.label());
                        pm.put("url", x.url());
                        return pm;
                    })
                    .toList());
        }
        return m;
    }

    private Map<String, Object> step(String label, String switchKey, String mode, String endpoint, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("switchKey", switchKey);   // null 이면 이 단계는 스위치로 바꾸는 것이 아니다
        m.put("mode", mode);
        m.put("endpoint", blankToNull(endpoint));
        m.put("note", note);
        return m;
    }

    private String sourceEndpoint() {
        return switch (modeState.source()) {
            case MOCK -> "내부 Mock 생성기 (외부 호출 없음)";
            case DIRECT_JDBC -> "JDBC — " + db.label() + " · " + tables.imscPtprDt();
            case ESB_HTTP2DB -> {
                String base = blankToNull(props.source().esbBaseUrl());
                String ifId = blankToNull(props.source().interfaceId());
                yield (base == null ? "ESB 주소 미설정" : base) + "/" + (ifId == null ? "{인터페이스ID 미정}" : ifId);
            }
        };
    }

    /** 드롭다운 라벨 — source: MOCK(로컬 H2) / 개발계 DB / 메타빌드(ESB) · xvarm: XVARM DB MOCK(개발계) / 실 XVARM DB. */
    private static Map<String, Map<String, String>> switchLabels() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("MOCK", "MOCK (로컬 H2)");
        source.put("DIRECT_JDBC", "개발계 DB");
        source.put("ESB_HTTP2DB", "메타빌드 (ESB)");
        Map<String, String> xvarm = new LinkedHashMap<>();
        xvarm.put("MOCK_DEV", "XVARM DB MOCK (개발계)");
        xvarm.put("REAL", "실 XVARM DB");
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("xvarm", xvarm);
        return m;
    }

    /**
     * 데이터 조회 단계 — 드롭다운(source)에 더해, <b>개발계 DB 모드일 때 XVARM 연동 라디오</b>를 붙인다.
     *
     * <p>2026-09-12 확인 기준 개발계 borami-db 에는 공통파일기본·XVARM 테이블이 없다. 라디오가 없으면 접견 4단 조인이
     * 조회 즉시 실패하는데 그 이유가 화면에 드러나지 않는다. MOCK_DEV(기본)는 우리가 만든 테이블을,
     * REAL 은 설정된 실 테이블을 조인한다.</p>
     */
    private Map<String, Object> sourceStep(String label, String note, boolean withXvarm) {
        Map<String, Object> m = step(label, "source", source.mode(), sourceEndpoint(), note);
        if (withXvarm && modeState.source() == VoiceProperties.SourceMode.DIRECT_JDBC) {
            m.put("radioKey", "xvarm");
            m.put("radioValue", modeState.xvarm().name());
            m.put("radioConfigured", props.source().xvarmMode().name());
            m.put("radioOptions", List.of(
                    radioOpt("MOCK_DEV", "XVARM DB MOCK (개발계)",
                            "개발계 DB 에 누락된 " + tables.smsmCmfiBs() + " · " + tables.asysContentElement()
                                    + " 를 자동 생성·시딩해 4단 조인이 돌게 한다"),
                    radioOpt("REAL", "실 XVARM DB",
                            "설정된 실 테이블(voice.source.schema.smsm/xvarm)을 직접 조인한다 — 없으면 조회가 실패한다")));
            m.put("radioNote", "지금 조인: " + tables.smsmCmfiBs() + " · " + tables.asysContentElement());
        }
        return m;
    }

    private static Map<String, Object> radioOpt(String value, String label, String note) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("value", value);
        o.put("label", label);
        o.put("note", note);
        return o;
    }

    private String brokerEndpoint() {
        if (modeState.broker() == VoiceProperties.BrokerMode.MOCK) {
            return "내부 Mock 브로커 — " + dirs.receiveMeet() + " 에 직접 생성";
        }
        String base = blankToNull(modeState.brokerBaseUrl());
        if (base == null) {
            // 주소를 경로와 이어붙이면 "브로커 주소 미설정/api/v1/..." 처럼 읽혀 설정된 것처럼 보인다.
            // 배치를 돌려야 비로소 실패하므로, 여기서 문제로 드러나게 한다.
            return "⚠ voice.broker.base-url 미설정 — REST 모드에서는 필수 (local 프로파일 또는 VOICE_BROKER_BASE_URL)";
        }
        return base + "/api/v1/xvarm/extract";
    }

    /** 이 조합으로는 배치가 반드시 실패한다는 것을 미리 알리는 경고들. */
    private List<String> warnings() {
        List<String> w = new java.util.ArrayList<>();
        if (modeState.broker() == VoiceProperties.BrokerMode.REST
                && blankToNull(modeState.brokerBaseUrl()) == null) {
            w.add("접견 트랙: 브로커가 REST 인데 주소가 비어 있다 — 접견 배치는 전건 실패한다. "
                    + "아래 [XVARM 브로커] 단계에서 주소 프리셋을 고르십시오(로컬 테스트는 로컬 PC).");
        }
        if (modeState.source() == VoiceProperties.SourceMode.ESB_HTTP2DB) {
            w.add("보라미 조회가 ESB_HTTP2DB 인데 연계가 아직 구현되지 않았다(계획서 Q2·Q15) — "
                    + "조회 즉시 실패한다. MOCK 또는 DIRECT_JDBC 로 두십시오.");
        }
        return w;
    }

    /** 3단계 SEND — 제논(Zenon) 전송. 결과를 PV 에 남기지 않는다. */
    private Map<String, Object> zenonStep() {
        return step("제논 전송", null, zenon.mode(), zenon.endpoint(),
                "STT 결과(전사 JSON)를 제논 수신 API 로 보내고, 성공하면 그 건의 임시 파일을 지운다(Purge). PV 에 남기지 않는다");
    }

    private String sttEndpoint() {
        if (modeState.stt() == VoiceProperties.SttMode.MOCK) {
            return "내부 Mock STT (고정 문구 반환)";
        }
        String base = blankToNull(props.stt().baseUrl());
        return base == null ? "NPU STT 주소 미설정" : base;
    }

    @Operation(summary = "브로커 연결 확인",
            description = """
                    XVARM 브로커에 붙어 상태를 물어봅니다. **배치를 돌리기 전 대조용**입니다.

                    확인할 것은 연결 여부보다 **브로커의 출력 디렉터리가 우리 접견 수신 폴더와
                    같은가**입니다. 두 경로가 어긋나면 브로커는 "추출 완료"를 돌려주는데 우리는
                    빈 폴더를 보며 수신 대기 타임아웃이 납니다 — 원인을 알려 주는 신호가 없습니다.

                    로컬에서 브로커를 띄울 때는 `local` 프로파일을 쓰거나
                    `BROKER_OUTPUT_DIR` 로 우리 `voice.sync.meet-dir` 과 맞추십시오.
                    """)
    @GetMapping("/broker/probe")
    public Map<String, Object> brokerProbe() {
        Map<String, Object> out = new LinkedHashMap<>(restBroker.probe());
        String meetDir = dirs.receiveMeet();
        out.put("collectorMeetDir", meetDir);
        out.put("brokerMode", broker.mode());

        String brokerDir = (String) out.get("brokerOutputDir");
        Boolean reachable = (Boolean) out.get("reachable");
        if (Boolean.TRUE.equals(reachable) && brokerDir != null) {
            boolean same = sameDir(brokerDir, meetDir);
            out.put("pathMatched", same);
            out.put("verdict", same
                    ? "출력 경로가 일치한다 — 접견 배치를 돌릴 수 있다"
                    : "⚠ 브로커 출력 경로와 우리 접견 수신 폴더가 다르다. "
                      + "브로커에 BROKER_OUTPUT_DIR=" + toAbs(meetDir) + " 를 주거나 local 프로파일로 띄울 것");
        } else {
            out.put("pathMatched", null);
            out.put("verdict", Boolean.TRUE.equals(reachable)
                    ? "브로커가 출력 경로를 알려주지 않았다"
                    : "브로커에 붙지 못했다 — 8082 포트로 떠 있는지 확인할 것");
        }
        return out;
    }

    /** 표기가 달라도 같은 폴더면 같다고 본다(상대·절대, 슬래시 방향, 대소문자). */
    private static boolean sameDir(String a, String b) {
        try {
            return java.nio.file.Path.of(a).toAbsolutePath().normalize()
                    .equals(java.nio.file.Path.of(b).toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    private static String toAbs(String p) {
        try {
            return java.nio.file.Path.of(p).toAbsolutePath().normalize()
                    .toString().replace(java.io.File.separatorChar, '/');
        } catch (Exception e) {
            return p;
        }
    }

    @Operation(summary = "멱등 표식 초기화",
            description = "처리 완료 표식을 지워 같은 대상을 다시 처리할 수 있게 한다. 시연 반복용.")
    @DeleteMapping("/idempotency")
    public Map<String, Object> clearIdempotency() {
        int cleared = idempotency.clearAll();
        return Map.of("cleared", cleared);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
