package egovframework.unstructured.collector.mock;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 개발계 E2E 데이터 주입 — 시뮬레이터 버튼 하나로 '어제(FAIL) → 오늘 오전(WAIT) → 방금(PARTIAL)' 배치 묶음을 만든다. dev · local 전용.
 */
@Log4j2
@Tag(name = "9-1. E2E 데이터 주입 (dev·local)", description = "긴급 재처리 체인 E2E 용 — 옛 더미 정리 + 상태별 더미 원본 · 실제 배치")
@RestController
@RequestMapping(value = "/api/v1/voice/simulator", produces = MediaType.APPLICATION_JSON_VALUE)
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class E2eDummyController {

    private final E2eDummyService service;

    @Operation(summary = "E2E 데이터 주입 — 옛 더미 정리 후 상태별 배치 묶음 생성",
            description = """
                    본문(모두 선택) `{"statuses":["FAIL","WAIT","PARTIAL"], "cleanup":true, "extraExecIds":[]}` — 비우면 이 기본값.

                    1. **정리**(`cleanup`, 기본 true) — 이전 E2E 묶음 · 재처리로 풀 수 없는 옛 더미 실패 배치(원본이 이미 지워진 것) · `extraExecIds`.
                       커넥터 보류 장부에 남은 건 먼저 보내 마감하고, 로그 컬렉터가 안전 조건(비정형 · 실행 중 아님 · T4 전부 더미 키)을 다시 걸어 T1 · T2 · T4 · T5 를 지운다
                    2. **생성** — 상태마다 접견 더미(DMY) 원본을 좁은 구간(10분)에 만들고 그 구간만 **실제 배치**(test=false · EXEC_ID UNS)로 돈다.
                       첫 배치 '어제' · 가운데 '오늘 오전' · 마지막 '방금'. 요청 순서대로 실행돼 이력 화면 맨 위에 그 순서로 뜨고 재처리 체인도 그 순서다

                    | 상태 | 더미 | 기대 결과(전송 방식 수동) |
                    |---|---|---|
                    | FAIL | 2건 모두 전송 실패 표식 | T1 FAIL · 전송 FAIL |
                    | WAIT | 정상 2건 | T1 SUCCESS · 전송 WAIT(커넥터 보류) |
                    | PARTIAL | 3건 중 1건 분석 실패 표식 | T1 PARTIAL · 전송 WAIT(2건 보류) |

                    장애 표식은 한 번만 실패하고 재처리는 통과한다. 응답 `batches[].asRequested=false` 면 요청과 다르게 끝났다(예: 전송 방식이 자동이면 WAIT 이 안 생김).
                    409 — 다른 배치가 실행 중 · 400 — 상태 값 오류 · 403 — 대시보드 더미 생성 꺼짐.
                    """)
    @PostMapping("/e2e-dummy")
    public Map<String, Object> e2eDummy(@RequestBody(required = false) E2eDummyService.Request req) {
        return service.run(req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        log.warn("[E2E] 요청 거절(400) — {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(E2eDummyService.BusyException.class)
    public ResponseEntity<Map<String, Object>> busy(E2eDummyService.BusyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(DummyDataService.DashboardDisabledException.class)
    public ResponseEntity<Map<String, Object>> disabled(DummyDataService.DashboardDisabledException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> state(IllegalStateException e) {
        log.warn("[E2E] 실패 — {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }
}
