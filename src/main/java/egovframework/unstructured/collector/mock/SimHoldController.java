package egovframework.unstructured.collector.mock;

import egovframework.unstructured.collector.batch.UnstructuredJobRunner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 장시간 배치 점유 시뮬레이션 — dev · local 전용.
 *
 * <p>같은 기능을 두 경로로 연다. {@code /internal/sim/hold-running} 은 클러스터 안(포트포워드)용이고, 공개 주소에서는
 * nginx 가 {@code /internal/} 을 403 으로 막으므로 시뮬레이터 화면은 {@code /api/v1/mock/sim/hold-running} 을 부른다.
 */
@Log4j2
@Tag(name = "9-3. 장시간 배치 점유 시뮬레이션 (dev·local)",
        description = "실행 잠금을 정해진 시간 동안 잡아 [바로 실행] · [긴급 재처리] 의 '이미 실행 중' 거절을 재현")
@RestController
@RequestMapping(value = {"/internal/sim/hold-running", "/api/v1/mock/sim/hold-running"},
        produces = MediaType.APPLICATION_JSON_VALUE)
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class SimHoldController {

    private final SimHoldService service;

    @Operation(summary = "가상 배치 점유 시작 — 실행 잠금을 duration 초 동안 잡는다",
            description = """
                    `duration` 1 ~ 600초(기본 30). 진짜 배치와 같은 실행기 · 같은 잠금(프로세스 안 + 파드 간 DB 잠금)을 잡는다.
                    점유 중 거절되는 것:

                    | 경로 | 응답 |
                    |---|---|
                    | `POST /internal/collect/unstructured-incremental`(바로 실행 · PL 규약) · `/internal/batch/run` · `/internal/batch/reprocess` · `/internal/collect/reprocess` · `/internal/batch/cloud-send` | 409 `{message, execId}` |
                    | 정기 · 주기 스케줄 회차 | 건너뜀(다음 회차) |

                    시뮬레이터가 직접 부르는 수동 배치(`/api/v1/voice/batches/*`)는 원래 실행기를 거치지 않아 막히지 않는다.
                    409 — 이미 다른 작업(또는 가상 점유)이 실행 중 · 400 — 시간 범위 밖.
                    """)
    @PostMapping
    public Map<String, Object> hold(@RequestParam(value = "duration", required = false) Integer duration,
                                    @RequestParam(value = "triggerBy", required = false) String triggerBy) {
        return service.hold(duration, triggerBy);
    }

    @Operation(summary = "점유 상태 — 남은 초 · 실행기 상태")
    @GetMapping
    public Map<String, Object> status() {
        return service.status();
    }

    @Operation(summary = "점유 강제 해제 — 기다리던 가상 배치를 깨워 잠금을 푼다")
    @PostMapping("/release")
    public Map<String, Object> release() {
        return service.release();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        log.warn("[SimHold] 요청 거절(400) — {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> busy(IllegalStateException e) {
        log.warn("[SimHold] 요청 거절(409) — {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(UnstructuredJobRunner.AlreadyRunningException.class)
    public ResponseEntity<Map<String, Object>> running(UnstructuredJobRunner.AlreadyRunningException e) {
        log.warn("[SimHold] 요청 거절(409) — {}", e.detail());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }
}
