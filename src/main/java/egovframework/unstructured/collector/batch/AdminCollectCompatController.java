package egovframework.unstructured.collector.batch;

import egovframework.unstructured.collector.common.util.FlexibleLocalDateTimeDeserializer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>PL 규약 호환</b> — data-collector 의 관리자 API({@code /internal/collect/...}, 2026-10 PL 이 admin 백엔드에 전달)와
 * <b>같은 경로 모양 · 같은 파라미터 · 같은 응답 모양</b>으로 비정형 바로 실행 · 긴급 재처리를 받는다.
 *
 * <p>admin-api 가 유형(dataTypeCd)에 따라 <b>기본 주소만 바꿔</b> 같은 코드로 부를 수 있게 하려는 것이다
 * (STRUCTURED → data-collector, UNSTRUCTURED → 이 수집기). 실행은 기존 {@link AdminLinkController}
 * ({@code /internal/batch/run|reprocess} · 본문 규약)와 같은 실행기 · 같은 잠금 · 같은 계획을 쓴다 — 둘 중 어느 쪽으로 불러도 결과가 같다.</p>
 *
 * <pre>
 * POST /internal/collect/unstructured-incremental?execType=MANUAL&triggerBy=admin01&to=2026-10-05T10:00:00
 *   → 200 {"success":true,"code":0,"http_status_code":200,"result":{"accepted":true,"kind":"UNSTRUCTURED_INCREMENTAL","execId":"20261005UNS001","status":"RUNNING","hint":"…"}}
 *     이미 실행 중이면 200 {"result":{"accepted":false,"reason":"이미 실행 중","running":{…}}}  ← data-collector 와 같다
 * GET  /internal/collect/unstructured-incremental/status?execId=
 * POST /internal/collect/reprocess?triggerBy=admin&originExecId=20261005UNS001[&stepTypeCd=SEND]
 *   → 202 {"execId","status":"RUNNING","originExecId","until",…} · 400 {"message"} · 409 {"message","execId"}
 * GET  /internal/collect/reprocess/status
 * </pre>
 */
@Log4j2
@Tag(name = "0. 비정형 배치(admin 연동) — PL 규약 호환",
        description = "data-collector `/internal/collect/structured-incremental` · `/internal/collect/reprocess` 와 같은 모양(쿼리 파라미터 · 응답) — "
                + "admin-api 가 dataTypeCd 로 기본 주소만 바꿔 부르면 된다")
@RestController
@RequestMapping(value = "/internal/collect", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AdminCollectCompatController {

    static final String KIND_INCREMENTAL = "UNSTRUCTURED_INCREMENTAL";

    private final UnstructuredBatchService service;
    private final UnstructuredJobRunner runner;
    private final UnstructuredBatchProperties props;

    // ── 바로 실행 ────────────────────────────────────────────────────────

    @Operation(summary = "비정형 증분 즉시 실행 (PL 규약)",
            description = """
                    data-collector `POST /internal/collect/structured-incremental` 과 같은 모양. 구간 = **워터마크 ~ `to`**(비우면 지금-lag).
                    음성 → (`include-image=true` 면) 이미지. 내부는 `/internal/batch/run` 과 같다.

                    - 200 `Response{result:{accepted:true, kind, execId, status:"RUNNING", hint}}` — execId 는 로그 컬렉터 채번 값(잠깐 안 오면 접수 handle)
                    - 이미 실행 중 → 200 `Response{result:{accepted:false, reason:"이미 실행 중", running:{kind, execId}}}`(data-collector 와 같다)
                    - 400 `{message}` — `to` 형식 오류 · 미래 · 이미 그 시각까지 수집함
                    - `to` 는 ISO(`2026-10-05T10:00:00` · 공백 구분 · 오프셋)
                    """)
    @PostMapping("/unstructured-incremental")
    public AdminLinkResponse<Map<String, Object>> runIncremental(
            @Parameter(description = "실행 유형(즉시 실행은 MANUAL) — 기록만", example = "MANUAL")
            @RequestParam(defaultValue = "MANUAL") String execType,
            @Parameter(description = "실행한 관리자 ID — T1 TRIGGER_BY", example = "admin01")
            @RequestParam(defaultValue = "manual") String triggerBy,
            @Parameter(description = "여기까지만 수집(선택). 비우면 지금-lag", example = "2026-10-05T10:00:00")
            @RequestParam(required = false) String to) {
        LocalDateTime toDtm = parse(to);
        UnstructuredBatchService.Plan p = service.planRun(toDtm, LocalDateTime.now());
        UnstructuredBatchService.Plan plan = new UnstructuredBatchService.Plan(p.window(), p.resume(), p.originExecId(),
                p.testRun(), blankTo(triggerBy, "manual"), p.includeImage(), p.imageOnly());
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Map<String, Object> detail = new LinkedHashMap<>(plan.detail());
            detail.put("execType", execType);
            UnstructuredJobRunner.Job job = runner.submitAndAwaitExecId(UnstructuredJobRunner.Kind.RUN, plan.triggerBy(), detail,
                    sink -> service.execute(plan, sink::publish), props.batch().execIdWaitMs());
            body.put("accepted", true);
            body.put("kind", KIND_INCREMENTAL);
            body.put("execId", job.currentExecId());
            body.put("status", job.running() ? "RUNNING" : job.snapshot().get("status"));
            body.put("execIdSource", job.execIdSource());
            body.put("handle", job.handle());
            body.putAll(detail);
            body.put("hint", "진행상태: GET /internal/collect/unstructured-incremental/status?execId=" + job.currentExecId());
        } catch (UnstructuredJobRunner.AlreadyRunningException e) {
            body.put("accepted", false);
            body.put("reason", "이미 실행 중");
            body.put("running", Map.of("kind", String.valueOf(e.kind()), "execId", String.valueOf(e.execId())));
        }
        return AdminLinkResponse.of(body);
    }

    @Operation(summary = "비정형 증분 실행 상태 (PL 규약)", description = "실행 ID 또는 접수 handle. 비우면 현재(또는 마지막) 작업.")
    @GetMapping("/unstructured-incremental/status")
    public AdminLinkResponse<Map<String, Object>> runStatus(@RequestParam(required = false) String execId) {
        return AdminLinkResponse.of(runner.status(execId));
    }

    // ── 긴급 재처리 ──────────────────────────────────────────────────────

    @Operation(summary = "긴급 재처리 (PL 규약)",
            description = """
                    data-collector `POST /internal/collect/reprocess?triggerBy=&originExecId=` 와 같은 모양. 원배치가 훑었던 구간을 다시 돈다.

                    - `stepTypeCd`(선택) — 비정형 3단계 COLLECT · ANALYZE · SEND. **비우면 SEND**(자동 — 건마다 남은 보존물로 가장 늦은 단계부터:
                      전사가 있으면 전송만, 없고 복호화 오디오가 있으면 STT 부터, 둘 다 없으면 수집부터)
                    - 202 `{execId, status:"RUNNING", originExecId, until, resume, …}` · 400 `{message}`(originExecId 없음 · 원배치 없음 · 비정형 아님) · 409 `{message, execId}`
                    - 새 실행 ID 로 돈다(TRIGGER_BY `{triggerBy}/reprocess:{원 execId}`). 전송은 원배치 런이 마감 전이면 그 런을 이어 받는다
                    """)
    @PostMapping("/reprocess")
    public ResponseEntity<Map<String, Object>> reprocess(
            @Parameter(description = "실행한 관리자 ID", example = "admin") @RequestParam(defaultValue = "admin") String triggerBy,
            @Parameter(description = "다시 실행할 원래 배치 ID(필수)", example = "20261005UNS001") @RequestParam(required = false) String originExecId,
            @Parameter(description = "(선택) 단계 COLLECT · ANALYZE · SEND — 비우면 SEND(자동)", example = "SEND")
            @RequestParam(required = false) String stepTypeCd) {
        if (originExecId == null || originExecId.isBlank()) {
            throw new IllegalArgumentException("originExecId 필수(재처리 대상 원배치 실행ID)");
        }
        String step = stepTypeCd == null || stepTypeCd.isBlank() ? "SEND" : stepTypeCd.trim();
        UnstructuredBatchService.Plan p = service.planReprocess(originExecId.trim(), UnstructuredBatchService.DATA_TYPE, step);
        UnstructuredBatchService.Plan plan = new UnstructuredBatchService.Plan(p.window(), p.resume(), p.originExecId(),
                p.testRun(), blankTo(triggerBy, "admin") + "/reprocess:" + originExecId.trim(), p.includeImage(), p.imageOnly());
        Map<String, Object> detail = new LinkedHashMap<>(plan.detail());
        detail.put("stepTypeCd", step);
        UnstructuredJobRunner.Job job = runner.submitAndAwaitExecId(UnstructuredJobRunner.Kind.REPROCESS, plan.triggerBy(), detail,
                sink -> service.execute(plan, sink::publish), props.batch().execIdWaitMs());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("execId", job.currentExecId());
        body.put("status", job.running() ? "RUNNING" : job.snapshot().get("status"));
        body.put("originExecId", originExecId.trim());
        body.put("until", plan.window().to().toString());   // 원배치 상한(자동 도출) — data-collector 와 같은 이름
        body.put("execIdSource", job.execIdSource());
        body.put("handle", job.handle());
        body.putAll(detail);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @Operation(summary = "긴급 재처리 상태 (PL 규약)", description = "execId(또는 handle)를 주면 그 작업, 비우면 현재(또는 마지막) 작업.")
    @GetMapping("/reprocess/status")
    public ResponseEntity<Map<String, Object>> reprocessStatus(@RequestParam(required = false) String execId) {
        return ResponseEntity.ok(runner.status(execId));
    }

    // ── 공통 ────────────────────────────────────────────────────────────

    private static String blankTo(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static LocalDateTime parse(String v) {
        try {
            return FlexibleLocalDateTimeDeserializer.parse(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("to " + e.getMessage());
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        log.warn("[Admin/PL] 요청 거절(400) — {}", e.getMessage());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(m);
    }

    @ExceptionHandler(UnstructuredJobRunner.AlreadyRunningException.class)
    public ResponseEntity<Map<String, Object>> conflict(UnstructuredJobRunner.AlreadyRunningException e) {
        log.warn("[Admin/PL] 요청 거절(409) — {}", e.getMessage());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", e.getMessage());
        m.put("execId", e.execId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(m);
    }
}
