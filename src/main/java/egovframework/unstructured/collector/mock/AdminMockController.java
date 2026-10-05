package egovframework.unstructured.collector.mock;

import egovframework.unstructured.collector.batch.AdminCollectCompatController;
import egovframework.unstructured.collector.batch.AdminLinkController;
import egovframework.unstructured.collector.batch.AdminLinkResponse;
import egovframework.unstructured.collector.batch.UnstructuredJobRunner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>admin 흉내</b> — 시뮬레이터가 관리자 화면과 <b>같은 API</b>({@code /internal/batch/*} · PL 규약 {@code /internal/collect/*})를
 * 부르게 하는 통로. dev · local 전용.
 *
 * <p>개발계는 {@code /internal/*} 을 공개 주소에서 막는다(403 — 클러스터 안 admin-api 만 부른다). 시뮬레이터(브라우저)는
 * 공개 주소로 들어오므로 이 통로로 같은 컨트롤러 메서드를 그대로 부른다 — 계획 · 실행기 · 잠금 · 응답이 admin 경로와 똑같다.
 * 대시보드용(REAL) 실행이 실제 운영처럼 UNS 로 채번되는 근거다.</p>
 */
@Tag(name = "9. admin 흉내 (dev·local)", description = "시뮬레이터 → /internal/batch/* · /internal/collect/* 와 같은 메서드")
@RestController
@RequestMapping(value = "/api/v1/mock/admin", produces = MediaType.APPLICATION_JSON_VALUE)
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class AdminMockController {

    private final AdminLinkController admin;
    private final AdminCollectCompatController compat;

    @Operation(summary = "= POST /internal/batch/run (바로 실행 · 본문 규약)")
    @PostMapping("/batch/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody(required = false) AdminLinkController.BatchRunReq body) {
        return admin.run(body);
    }

    @Operation(summary = "= POST /internal/batch/reprocess (긴급 재처리 · 본문 규약)")
    @PostMapping("/batch/reprocess")
    public ResponseEntity<Map<String, Object>> reprocess(@RequestBody(required = false) AdminLinkController.ReprocessReq body) {
        return admin.reprocess(body);
    }

    @Operation(summary = "= GET /internal/batch/status")
    @GetMapping("/batch/status")
    public Map<String, Object> status(@RequestParam(required = false) String execId) {
        return admin.status(execId);
    }

    @Operation(summary = "= POST /internal/collect/unstructured-incremental (PL 규약)")
    @PostMapping("/collect/unstructured-incremental")
    public AdminLinkResponse<Map<String, Object>> incremental(@RequestParam(defaultValue = "MANUAL") String execType,
                                                              @RequestParam(defaultValue = "simulator") String triggerBy,
                                                              @RequestParam(required = false) String to) {
        return compat.runIncremental(execType, triggerBy, to);
    }

    @Operation(summary = "= POST /internal/collect/reprocess (PL 규약)")
    @PostMapping("/collect/reprocess")
    public ResponseEntity<Map<String, Object>> collectReprocess(@RequestParam(defaultValue = "simulator") String triggerBy,
                                                                @RequestParam(required = false) String originExecId,
                                                                @RequestParam(required = false) String stepTypeCd) {
        return compat.reprocess(triggerBy, originExecId, stepTypeCd);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return admin.unreadable(e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return admin.badRequest(e);
    }

    @ExceptionHandler(UnstructuredJobRunner.AlreadyRunningException.class)
    public ResponseEntity<Map<String, Object>> conflict(UnstructuredJobRunner.AlreadyRunningException e) {
        return admin.conflict(e);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> busy(IllegalStateException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(m);
    }
}
