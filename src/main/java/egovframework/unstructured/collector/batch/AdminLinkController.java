package egovframework.unstructured.collector.batch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import egovframework.unstructured.collector.batch.schedule.BatchScheduleReq;
import egovframework.unstructured.collector.batch.schedule.UnstructuredBatchScheduler;
import egovframework.unstructured.collector.common.util.FlexibleLocalDateTimeDeserializer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * admin 연동 — 관리자 화면(admin-api)이 비정형 수집기를 부르는 <b>단일 진입점</b>(UNSTRUCTURED).
 * 설정 반영 · 바로 실행 · 긴급 재처리 · 클라우드 전송({@code /internal/batch/cloud-send} · 단건 2026-10-19).
 *
 * <p>경로·본문은 admin-api 의 호출 규약을 그대로 따른다(admin-api {@code DataCollectorApi} · {@code ReprocessApi}).
 * 처리 방식(비동기 202 · 실행 중 409 · 원배치 구간 기준 재처리)은 data-collector 를 따른다 — data-collector 에는
 * {@code /internal/batch/run} · {@code /internal/batch/reprocess} 가 없어 본문은 admin 규약이 기준이다.</p>
 *
 * <p>클러스터 안에서만 부른다. admin-fe nginx 가 수집기를 공개 주소로 프록시하므로({@code /voice/} · 예정 {@code /unstruct/})
 * 그쪽에서 {@code /internal/} 을 막아야 한다(패치 초안 {@code ref/admin-fe_unstruct_nginx_패치초안.md}).</p>
 */
@Log4j2
@Tag(name = "0. 비정형 배치(admin 연동)",
        description = "관리자 화면의 비정형(UNSTRUCTURED) 설정 저장 · 바로 실행 · 긴급 재처리 · 클라우드 전송을 받는다 — 스케줄 · 실행 · 재처리 · 전송이 한 실행기 · 한 잠금")
@RestController
@RequestMapping(value = "/internal", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AdminLinkController {

    private final UnstructuredBatchScheduler scheduler;
    private final UnstructuredBatchService service;
    private final UnstructuredJobRunner runner;
    private final UnstructuredBatchProperties props;
    private final CloudSendService cloudSend;

    /**
     * 바로 실행 본문 — admin-api {@code BatchRunRequest(dataTypeCd, targetToDtm)}.
     *
     * <p>{@code targetToDtm} 은 <b>배열 {@code [yyyy, MM, dd, HH, mm(, ss)]}</b> 과 <b>ISO 문자열</b>(공백 구분 포함)을 다 받는다
     * ({@link FlexibleLocalDateTimeDeserializer}). admin-api 가 {@code RestClient.builder()} 를 직접 써서
     * 시각이 {@code [2026,10,2,10,0]} 으로 나가 400 이 나던 문제(인수인계 1002155708 9.3)를 수집기 쪽에서 받아 준다.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BatchRunReq(
            @Schema(example = "UNSTRUCTURED") String dataTypeCd,
            @Schema(description = "수집 구간 끝(미포함). 비우면 지금-lag. ISO 문자열(`2026-10-02T10:00:00` · `2026-10-02 10:00:00`) 또는 "
                    + "배열 `[2026,10,2,10,0]`(`[yyyy, MM, dd, HH, mm(, ss)]`)", type = "string", example = "2026-10-02T10:00:00")
            @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class) LocalDateTime targetToDtm) {}

    /** 긴급 재처리 본문 — admin-api {@code ReprocessRequest(execId, dataTypeCd, stepTypeCd, stepSeq)} + 요청한 관리자. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReprocessReq(
            @Schema(description = "재처리할 원배치 실행 ID", example = "20261002UNS001") String execId,
            @Schema(example = "UNSTRUCTURED") String dataTypeCd,
            @Schema(description = "단계(C05) COLLECT · ANALYZE · SEND. 비우면 처음부터(전체 재처리)", example = "SEND") String stepTypeCd,
            @Schema(description = "단계 순번 — 비정형은 단계가 한 번씩이라 쓰지 않는다(기록만)", example = "3") Integer stepSeq,
            @Schema(description = "(선택) 요청한 관리자 ID — T1 TRIGGER_BY(`<관리자>/reprocess:<누른 배치>`) · 커넥터 감사 로그. 비우면 ADMIN",
                    example = "admin01") String triggerBy) {}

    /** 클라우드 전송 본문 — 누른 배치 · 데이터 구분 · 요청한 관리자. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CloudSendReq(
            @Schema(description = "전송할 배치 실행 ID(전송 대기 목록에서 누른 배치)", example = "20261016UNS001") String execId,
            @Schema(example = "UNSTRUCTURED") String dataTypeCd,
            @Schema(description = "요청한 관리자 ID — 커넥터 감사 로그 · T1 TRIGGER_BY(`<관리자>/cloud-send:<누른 배치>`). 비우면 admin",
                    example = "admin01") String triggerBy) {}

    // ── 설정 반영 ────────────────────────────────────────────────────────

    @Operation(summary = "스케줄 즉시 반영(본문 우선)",
            description = """
                    admin 이 배치 스케줄(UNSTRUCTURED)을 저장한 뒤 부른다 — data-collector `/internal/schedule/refresh` 와 같은 규약.

                    - 본문 `{dataTypeCd, activeYn, execSchedTypeCd, schedVal}` 이 있으면 **재조회 없이** 트리거를 바꾼다
                    - 본문이 없거나 비어 있으면 admin 을 **다시 조회**한다(폴백)
                    - `UNSTRUCTURED` 가 아니면 무시하고 200(`ignored:<유형>`)
                    - `schedVal` 은 `HH:mm` · `HH:mm:ss` 둘 다. 값이 잘못되면 400 — 지금 스케줄은 그대로 둔다
                    - `VOICE_SCHEDULE_ENABLED=false` 면 값만 받고 트리거는 걸지 않는다(상태에 표시)
                    """)
    @PostMapping(value = "/schedule/refresh")
    public ResponseEntity<AdminLinkResponse<String>> refresh(@RequestBody(required = false) BatchScheduleReq body) {
        if (body != null && body.hasSchedule()) {
            UnstructuredBatchScheduler.Apply r = scheduler.applySchedule(body.toSchedule(), UnstructuredBatchScheduler.Source.REFRESH);
            log.info("[Schedule] refresh 본문 반영 — {} {} {} {} → {}", body.dataTypeCd(), body.activeYn(), body.execSchedTypeCd(),
                    body.schedVal(), r);
            return switch (r) {
                case IGNORED -> ResponseEntity.ok(AdminLinkResponse.of("ignored:" + body.dataTypeCd()));
                case INVALID -> ResponseEntity.badRequest().body(new AdminLinkResponse<>(false, 400, 400,
                        "invalid:" + body.execSchedTypeCd() + " " + body.schedVal()));
                default -> ResponseEntity.ok(AdminLinkResponse.of("applied:" + body.dataTypeCd()));
            };
        }
        boolean fetched = scheduler.reschedule(UnstructuredBatchScheduler.Source.REFRESH_FETCH);
        log.info("[Schedule] refresh 본문 없음 — admin 재조회 {}", fetched ? "성공" : "실패(지금 스케줄 유지)");
        return ResponseEntity.ok(AdminLinkResponse.of("rescheduled"));
    }

    // ── 바로 실행 ────────────────────────────────────────────────────────

    @Operation(summary = "바로 실행 (비동기)",
            description = """
                    스케줄과 무관하게 지금 돌린다. 구간 = **워터마크(마지막 SUCCESS 배치의 끝) ~ `targetToDtm`**(비우면 지금-lag).

                    - **202** `{execId, status:"RUNNING", execIdSource, ...}` — 접수하고 곧바로 돌려준다. execId 는 로그 컬렉터가 채번한 실제 값
                      (`execIdSource=COLLECTOR`). 잠깐 기다려도 안 오면 접수 handle(`HANDLE`) — 진행은 `GET /internal/batch/status?execId=`
                    - **409** — 이미 실행 중(스케줄 · 재처리 · 시뮬레이터 수동 배치 포함). 본문에 실행 중 execId
                    - **400** — `dataTypeCd` 가 `UNSTRUCTURED` 가 아님(주소 설정 오류) · `targetToDtm` 형식 오류 · 미래 · 이미 그 시각까지 수집함.
                      `message` 는 화면용 짧은 안내(공백 포함 60자 이내)만 — 내부 값 · 자세한 사유는 서버 로그(WARN `[Admin] 요청 거절(400)`)
                    - `targetToDtm` 은 ISO 문자열(`2026-10-02T10:00:00` · 공백 구분)과 배열 `[2026,10,2,10,0]`(`[yyyy, MM, dd, HH, mm(, ss)]`)을 다 받는다
                    - 음성 → (`unstructured.batch.include-image=true` 면 — 개발계 기본) 이미지 순차
                    - 시뮬레이터 데이터(`SIM…`)는 집지 않는다 — 시뮬레이터 수동 시험 전용. 대시보드 더미(`DMY…`)와 실제 행만
                    """)
    @PostMapping("/batch/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody(required = false) BatchRunReq body) {
        UnstructuredBatchService.requireUnstructured(body == null ? null : body.dataTypeCd());
        UnstructuredBatchService.Plan plan = service.planRun(body.targetToDtm(), LocalDateTime.now());
        return accepted(UnstructuredJobRunner.Kind.RUN, plan, null);
    }

    // ── 긴급 재처리 ──────────────────────────────────────────────────────

    @Operation(summary = "긴급 재처리 (비동기)",
            description = """
                    실패·부분 성공 배치를 그 단계부터 다시 돈다. 대상 구간은 **원배치(execId)가 훑었던 구간**(로그 컬렉터 T1 `target_from ~ target_to`).

                    | stepTypeCd | 이어서 하기 | 쓰는 보존물 |
                    |---|---|---|
                    | (비움) · `COLLECT` | 처음부터 | 없음 |
                    | `ANALYZE` | STT 부터 | 복호화 오디오 |
                    | `SEND` | 에이전트 커넥터 전송부터(STT 생략) | `stt_temp/{원 execId}` 전사 |

                    - **원배치를 같은 EXEC_ID 로 다시 연다(reopen) — 새 실행 ID 를 만들지 않는다.** 누른 배치와 그 이전의 **미완료 배치**
                      (배치 실패/부분성공/취소 · 전송 실패/부분성공/취소/**대기 WAIT**)를 오래된 순으로 각자 ID 로 처리한다(이미지 배치는 지금까지의 실패 전부).
                      배치마다 커넥터 보류분(대기)을 먼저 보내고, 배치가 실패류면 다시 열어 실패 건을 다시 돈다. 이미 성공한 건은 멱등 표식이 건너뛴다
                    - **대기(WAIT)도 포함한다(PL 협의 2026-10-16)** — 화면이 "하위 대기 건을 모두 확인했습니까?" 팝업으로 승인받고 부른다.
                      지금 실행 · 전송 중인 배치는 건너뛴다(누른 배치면 409)
                    - 시험 배치(TST)를 재처리하면 재처리도 시험 이력으로 남는다
                    - **202 / 409** 는 바로 실행과 같다. **400** — 유형 · 단계 오류, 원배치를 찾을 수 없음(로그 컬렉터 미연동 포함),
                      이미 처리됨(실패/부분성공/취소 아님)
                    - **503** `{message}` — 로그 컬렉터를 읽지 못함(장애 · 타임아웃 — 배치 상세 · 재처리 체인 failed-chain). 처리하지 않았다 — 잠시 후 다시
                    """)
    @PostMapping("/batch/reprocess")
    public ResponseEntity<Map<String, Object>> reprocess(@RequestBody(required = false) ReprocessReq body) {
        if (body == null) {
            throw new UserFacingRejection(UnstructuredBatchService.MSG_BAD_BODY,
                    "재처리 거절 — 본문 필수 {execId, dataTypeCd, stepTypeCd, stepSeq}");
        }
        UnstructuredBatchService.Plan p = service.planReprocess(body.execId(), body.dataTypeCd(), body.stepTypeCd());
        UnstructuredBatchService.Plan plan = body.triggerBy() == null || body.triggerBy().isBlank() ? p
                : new UnstructuredBatchService.Plan(p.window(), p.resume(), p.originExecId(), p.testRun(),
                        body.triggerBy().trim() + "/reprocess:" + p.originExecId(), p.includeImage(), p.imageOnly());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("stepTypeCd", body.stepTypeCd());
        extra.put("stepSeq", body.stepSeq());
        return accepted(UnstructuredJobRunner.Kind.REPROCESS, plan, extra);
    }

    // ── 클라우드 전송 ────────────────────────────────────────────────────

    @Operation(summary = "클라우드 전송 (비동기)",
            description = """
                    전송 대기 목록에서 누른 음성 배치 **한 건만** 보낸다(단건 독립 전송 · PL 최종 협의 2026-10-19).
                    배치마다 데이터를 가진 쪽이 보낸다.

                    | 배치 | 보내는 쪽 |
                    |---|---|
                    | 커넥터 보류 장부에 있음(전송 대기 · 커넥터 쪽 실패) | 커넥터 전송 트리거(`/api/v1/transmit/holds/{execId}/release`) |
                    | 장부에 없고 배치가 실패/부분성공/취소 | 수집기 — 같은 EXEC_ID 로 다시 열어 **전송부터**(보존 전사 · 처리된 건 건너뜀) |

                    - **이전 미완료 배치를 함께 보내지 않는다.** 2026-10-16 ~ 10-18 에는 누른 배치 이전의 실패/부분성공/취소/**대기**를
                      오래된 순으로 함께 보냈다(체인). 그중 대기(WAIT)는 비식별 결과를 사람이 검수하라고 전송 가드가 멈춰 둔 건이어서
                      체인이 그것까지 보내면 **미검수 유출**이 된다. 그래서 폐기했다 — 이전 건은 목록에서 각각 눌러 보낸다
                    - 전송 대기(WAIT)인 배치를 눌렀는데 **커넥터 장부에 없으면** 보내지 않는다(400)
                    - **202** `{execId, status:"RUNNING", originExecId, sendStsCd, …}` — 접수하고 곧바로 돌려준다.
                      결과는 전송 단계(T2)로 본다(목록 새로고침). `chain` 은 더 내려가지 않는다
                    - **409** — 수집기가 다른 작업을 돌리는 중이거나 그 배치가 지금 실행 · 전송 중
                    - **400** — 유형 · execId 오류, 배치 없음, 수용자 이미지, 전송할 데이터 없음, 이미 전송됨.
                      `message` 는 화면용 짧은 안내(공백 포함 60자 이내)만 — 자세한 사유는 서버 로그(WARN `[Admin] 요청 거절(400)`)
                    - **503** `{message}` — 로그 컬렉터 · 커넥터 보류 장부를 읽지 못함(장애 · 타임아웃). 보내지 않았다 — 잠시 후 다시
                    """)
    @PostMapping("/batch/cloud-send")
    public ResponseEntity<Map<String, Object>> cloudSend(@RequestBody(required = false) CloudSendReq body) {
        if (body == null) {
            throw new UserFacingRejection(UnstructuredBatchService.MSG_BAD_BODY,
                    "클라우드 전송 거절 — 본문 필수 {execId, dataTypeCd, triggerBy}");
        }
        CloudSendService.Plan plan = cloudSend.plan(body.execId(), body.dataTypeCd(), body.triggerBy());
        Map<String, Object> detail = plan.detail();
        UnstructuredJobRunner.Job job = runner.submitAndAwaitExecId(UnstructuredJobRunner.Kind.CLOUD_SEND, plan.triggerBy(), detail,
                sink -> cloudSend.execute(plan, sink::publish), props.batch().execIdWaitMs());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", job.currentExecId());
        out.put("status", job.running() ? "RUNNING" : job.snapshot().get("status"));
        out.put("handle", job.handle());
        out.putAll(detail);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
    }

    // ── 상태 ────────────────────────────────────────────────────────────

    @Operation(summary = "실행 · 스케줄 상태",
            description = "`execId`(실제 execId 또는 접수 handle)를 주면 그 작업, 비우면 현재(또는 마지막) 작업. "
                    + "`schedule` 은 지금 적용된 스케줄 · 다음 실행 시각 · 마지막 반영 출처(STARTUP/POLL/REFRESH) · 반영 시각.")
    @GetMapping("/batch/status")
    public Map<String, Object> status(
            @Parameter(description = "실행 ID 또는 접수 handle — 비우면 현재 작업") @RequestParam(required = false) String execId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job", runner.status(execId));
        m.put("schedule", scheduler.snapshot());
        m.put("includeImage", props.batch().includeImage());
        return m;
    }

    // ── 공통 ────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> accepted(UnstructuredJobRunner.Kind kind, UnstructuredBatchService.Plan plan,
                                                         Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>(plan.detail());
        if (extra != null) {
            detail.putAll(extra);
        }
        UnstructuredJobRunner.Job job = runner.submitAndAwaitExecId(kind, plan.triggerBy(), detail,
                sink -> service.execute(plan, sink::publish), props.batch().execIdWaitMs());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("execId", job.currentExecId());
        body.put("status", job.running() ? "RUNNING" : job.snapshot().get("status"));
        body.put("execIdSource", job.execIdSource());
        body.put("handle", job.handle());
        body.putAll(detail);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /** {@code 2026-10-02T10:00:00} · {@code 2026-10-02T10:00} · {@code 2026-10-02 10:00:00} · 오프셋 ISO. 비면 null. */
    static LocalDateTime parseDtm(String v) {
        try {
            return FlexibleLocalDateTimeDeserializer.parse(v);
        } catch (IllegalArgumentException e) {
            throw new UserFacingRejection(UnstructuredBatchService.MSG_TO_FORMAT, "바로 실행 거절 — targetToDtm=" + v + " " + e.getMessage());
        }
    }

    /**
     * 본문을 읽지 못함(JSON 문법 · 시각 형식) — 400. 역직렬화기가 적어 주는 이유(필드명 · 받는 형식)는 서버 로그에만 남기고,
     * 화면용 {@code message} 에는 짧은 안내만 — 시각 필드면 종료 시간 안내, 아니면 일반 형식 안내.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        Throwable c = e.getMostSpecificCause();
        String why = c instanceof JsonMappingException jm ? jm.getOriginalMessage() : c.getMessage();
        boolean timeField = c instanceof JsonMappingException jme
                && jme.getPath().stream().anyMatch(r -> "targetToDtm".equals(r.getFieldName()));
        String message = timeField ? UnstructuredBatchService.MSG_TO_FORMAT : UnstructuredBatchService.MSG_BAD_BODY;
        log.warn("[Admin] 본문 거절(400) — {} · 화면 안내: {}", why, message);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", message);
        return ResponseEntity.badRequest().body(m);
    }

    /** 400 — 화면용 짧은 안내만 본문에, 내부 값 · 구체적인 사유는 서버 로그에({@link UserFacingRejection}). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        String message = UserFacingRejection.messageOf(e);
        log.warn("[Admin] 요청 거절(400) — {} · 화면 안내: {}", UserFacingRejection.detailOf(e), message);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", message);
        return ResponseEntity.badRequest().body(m);
    }

    /** 503 — 하위(로그 컬렉터 · 커넥터 장부)를 못 읽어 처리하지 않음. 화면용 짧은 안내만 본문에, 원인은 서버 로그에. */
    @ExceptionHandler(DownstreamUnavailableException.class)
    public ResponseEntity<Map<String, Object>> unavailable(DownstreamUnavailableException e) {
        log.warn("[Admin] 요청 거절(503) — {} · 화면 안내: {}", e.detail(), e.getMessage());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(m);
    }

    @ExceptionHandler(UnstructuredJobRunner.AlreadyRunningException.class)
    public ResponseEntity<Map<String, Object>> conflict(UnstructuredJobRunner.AlreadyRunningException e) {
        log.warn("[Admin] 요청 거절(409) — {}", e.getMessage());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", e.getMessage());
        m.put("kind", e.kind());
        m.put("execId", e.execId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(m);
    }
}
