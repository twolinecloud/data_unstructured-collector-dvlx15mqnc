package egovframework.unstructured.collector.batch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import egovframework.unstructured.collector.batch.schedule.BatchScheduleReq;
import egovframework.unstructured.collector.batch.schedule.UnstructuredBatchScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * admin 연동 — 관리자 화면(admin-api)이 비정형 수집기를 부르는 <b>단일 진입점</b>(UNSTRUCTURED).
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
        description = "관리자 화면의 비정형(UNSTRUCTURED) 설정 저장 · 바로 실행 · 긴급 재처리를 받는다 — 스케줄 · 실행 · 재처리가 한 실행기 · 한 잠금")
@RestController
@RequestMapping(value = "/internal", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AdminLinkController {

    private final UnstructuredBatchScheduler scheduler;
    private final UnstructuredBatchService service;
    private final UnstructuredJobRunner runner;
    private final UnstructuredBatchProperties props;

    /** 바로 실행 본문 — admin-api {@code BatchRunRequest(dataTypeCd, targetToDtm)}. 시각은 ISO 또는 공백 구분. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BatchRunReq(
            @Schema(example = "UNSTRUCTURED") String dataTypeCd,
            @Schema(description = "수집 구간 끝(미포함). 비우면 지금-lag", example = "2026-10-02T10:00:00") String targetToDtm) {}

    /** 긴급 재처리 본문 — admin-api {@code ReprocessRequest(execId, dataTypeCd, stepTypeCd, stepSeq)}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReprocessReq(
            @Schema(description = "재처리할 원배치 실행 ID", example = "20261001VOC001") String execId,
            @Schema(example = "UNSTRUCTURED") String dataTypeCd,
            @Schema(description = "단계(C05) COLLECT · ANALYZE · SEND. 비우면 처음부터(전체 재처리)", example = "SEND") String stepTypeCd,
            @Schema(description = "단계 순번 — 비정형은 단계가 한 번씩이라 쓰지 않는다(기록만)", example = "3") Integer stepSeq) {}

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
                    - **400** — `dataTypeCd` 가 `UNSTRUCTURED` 가 아님(주소 설정 오류) · `targetToDtm` 이 미래 · 이미 그 시각까지 수집함
                    - 음성 → (`unstructured.batch.include-image=true` 면 — 개발계 기본) 이미지 순차
                    - 시뮬레이터 데이터(`SIM…`)는 집지 않는다 — 시뮬레이터 수동 시험 전용. 대시보드 더미(`DMY…`)와 실제 행만
                    """)
    @PostMapping("/batch/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody(required = false) BatchRunReq body) {
        UnstructuredBatchService.requireUnstructured(body == null ? null : body.dataTypeCd());
        UnstructuredBatchService.Plan plan = service.planRun(parseDtm(body.targetToDtm()), LocalDateTime.now());
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
                    | `SEND` | 제논 전송부터(STT 생략) | `stt_temp/{원 execId}` 전사 |

                    - 실행 ID 는 **새로 받는다**(TRIGGER_BY `ADMIN/reprocess:<원 execId>`). 이미 성공한 건은 멱등 표식이 건너뛴다
                    - 시험 배치(TST)를 재처리하면 재처리도 시험 이력으로 남는다
                    - **202 / 409** 는 바로 실행과 같다. **400** — 유형 · 단계 오류, 원배치를 찾을 수 없음(로그 컬렉터 미연동 포함)
                    - 음성 배치만 다시 돈다(이미지는 로그 컬렉터 배치가 없다)
                    """)
    @PostMapping("/batch/reprocess")
    public ResponseEntity<Map<String, Object>> reprocess(@RequestBody(required = false) ReprocessReq body) {
        if (body == null) {
            throw new IllegalArgumentException("본문 필수 — {execId, dataTypeCd, stepTypeCd, stepSeq}");
        }
        UnstructuredBatchService.Plan plan = service.planReprocess(body.execId(), body.dataTypeCd(), body.stepTypeCd());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("stepTypeCd", body.stepTypeCd());
        extra.put("stepSeq", body.stepSeq());
        return accepted(UnstructuredJobRunner.Kind.REPROCESS, plan, extra);
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

    /** {@code 2026-10-02T10:00:00} · {@code 2026-10-02T10:00} · {@code 2026-10-02 10:00:00}. 비면 null. */
    static LocalDateTime parseDtm(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(v.trim().replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("targetToDtm 형식 오류(yyyy-MM-dd'T'HH:mm[:ss]): " + v);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        log.warn("[Admin] 요청 거절(400) — {}", e.getMessage());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(m);
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
