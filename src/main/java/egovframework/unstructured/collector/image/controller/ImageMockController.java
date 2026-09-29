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
        return sim.seed(count);
    }

    @Operation(summary = "SIM 사진 데이터 삭제", description = "SIMIMG 접두의 보라미 행 · Admin 매핑 · 더미 원본 · 저장 사진. 실제 수용자 행은 손대지 않는다.")
    @DeleteMapping("/sim")
    public Map<String, Object> clean() {
        return sim.clean();
    }

    @Operation(summary = "수용자 이미지 수집 검증 시작 (비동기)",
            description = "준비(SIM 생성) → 측정(워커 N · 건당 가상 지연) → 검증(매핑 행 · 최신 순번 · 원문 해시) → 정리. 진행은 `GET /perf/runs/current`.")
    @PostMapping("/perf/runs")
    public ResponseEntity<Map<String, Object>> start(@RequestBody(required = false) ImagePerfService.ImagePerfRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(perf.start(req));
    }

    @Operation(summary = "진행 중(또는 마지막) 회차")
    @GetMapping("/perf/runs/current")
    public Map<String, Object> current() {
        return perf.current();
    }

    @Operation(summary = "중지")
    @PostMapping("/perf/runs/cancel")
    public Map<String, Object> cancel() {
        return perf.cancel();
    }

    @Operation(summary = "실행 이력", description = "`{ROOT}/perf/image-history.jsonl` — 최근 회차가 앞.")
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
