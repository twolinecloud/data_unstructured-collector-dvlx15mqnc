package egovframework.unstructured.collector.image.controller;

import egovframework.unstructured.collector.image.perf.ImagePerfService;
import egovframework.unstructured.collector.image.sim.ImageSimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 수용자 이미지 시뮬레이션 · 검증(시뮬레이터 6번 탭). {@code /api/v1/mock/**} — 운영에서는 막는다.
 */
@Tag(name = "11. 수용자 이미지 수집 검증 (Mock 전용)",
        description = "SIMIMG 사진 N명분을 만들어 이미지 파이프라인으로 처리하고 TPS · 단계별 시간(복호화 · DB 매핑) · 정합성을 잰다. 끝나면 SIM 데이터를 지운다")
@RestController
@RequestMapping(value = "/api/v1/mock/image", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ImageMockController {

    private final ImageSimulationService sim;
    private final ImagePerfService perf;

    @Operation(summary = "SIM 사진 데이터 생성", description = "수용자마다 사진 2장(순번 1·2) + 사진 아닌 이미지 1장(구분 2). 파이프라인은 순번 2를 골라야 한다.")
    @PostMapping("/sim")
    public Map<String, Object> seed(@RequestParam(defaultValue = "10") int count) {
        return perf.seedSim(count);
    }

    @Operation(summary = "SIM 사진 데이터 삭제 — 6번 탭 [SIM 데이터 정리]",
            description = "SIMIMG 접두의 보라미 행 · Admin 매핑 · 더미 원본 · 저장 사진. 실제 수용자 행은 손대지 않는다. "
                    + "검증·수집이 도는 중에는 409.")
    @DeleteMapping("/sim")
    public Map<String, Object> clean() {
        return perf.cleanSim();
    }

    @Operation(summary = "남아 있는 SIM 현황", description = "SIM 수용자 수 · 매핑 행 수 — [기존 데이터로 재실행] 대상.")
    @GetMapping("/sim")
    public Map<String, Object> residual() {
        return sim.residual();
    }

    @Operation(summary = "수용자 이미지 수집 검증 시작 (비동기)",
            description = """
                    진행은 `GET /perf/runs/current`. SIM 데이터는 끝나도 남긴다(재실행용) — 지우려면 `DELETE /sim`.
                    - `mode=NEW` — 남은 SIM 을 지우고 `count` 명분을 새로 만들어 처리
                    - `mode=RERUN` — 남아 있는 SIM 을 다시 처리(멱등성). `rerunScope=ALL` 전건 UPSERT · `MISSING` 실패·누락분만
                    - `failRatePct` · `failStage` — 대상 × 비율만큼 정확히 의도적으로 실패(MAP 은 커밋 전 롤백)
                    - 검증: 스냅샷·주입 목록으로 계산한 기대값(신규·UPSERT·건너뜀·실패·최종 매핑) 대조 · 롤백 · PK 중복 0 · 풀 반납
                    - `target=DASHBOARD`(대시보드용 REAL) — DMY 더미를 지우고 DMYIMG 를 만들어 **실제 배치**(test=false · EXEC_ID UNS)로 돈다.
                      최대 300명 · 의도적 실패 주입은 쓰지 않는다(장애는 키 표식 더미로)
                    """)
    @PostMapping("/perf/runs")
    public ResponseEntity<Map<String, Object>> start(
            @RequestBody(required = false) ImagePerfService.ImagePerfRequest req,
            @RequestParam(required = false) egovframework.unstructured.collector.mock.DummyTarget target) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(perf.start(req, target));
    }

    @Operation(summary = "진행 중(또는 마지막) 회차", description = "`traceAfter` 뒤에 붙은 단계별 실행 기록(SQL · cURL · 셸 명령과 결과)만 `trace` 로 싣는다 — 화면이 마지막 seq 를 넘기며 폴링한다.")
    @GetMapping("/perf/runs/current")
    public Map<String, Object> current(@RequestParam(defaultValue = "0") int traceAfter) {
        return perf.current(traceAfter);
    }

    @Operation(summary = "중지")
    @PostMapping("/perf/runs/cancel")
    public Map<String, Object> cancel() {
        return perf.cancel();
    }

    @Operation(summary = "실행 이력", description = "`{ROOT}/perf/image-history.jsonl` — 마지막 실행 한 건만 남긴다(덮어쓰기 · 2026-10-01).")
    @GetMapping("/perf/runs")
    public Map<String, Object> history() {
        return perf.history();
    }

    @Operation(summary = "실행 이력 비우기")
    @DeleteMapping("/perf/runs")
    public Map<String, Object> clearHistory() {
        return perf.clearHistory();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, Object>> dataAccess(org.springframework.dao.DataAccessException e) {
        Throwable c = e.getMostSpecificCause();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message",
                "DB 오류 — " + c.getClass().getSimpleName() + ": " + c.getMessage()));
    }
}
