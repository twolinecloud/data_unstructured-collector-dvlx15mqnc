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
 * 비식별 실패 배치 만들기 — 어드민 배치 상세 '실패 상태만 보기'를 비정형 비식별화 단계에서 확인하는 용도. dev · local 전용.
 */
@Log4j2
@Tag(name = "9-4. 비식별 실패 배치 (dev·local)", description = "어드민 배치 상세 비식별화 '실패 상태만 보기' 확인용 — 비식별 표식(DF) 더미 · 실제 배치")
@RestController
@RequestMapping(value = "/api/v1/voice/simulator", produces = MediaType.APPLICATION_JSON_VALUE)
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class DeidentFailSimController {

    private final DeidentFailSimService service;

    @Operation(summary = "비식별 실패 배치 만들기 — 접견 더미 중 일부에 비식별 표식을 박아 실제 배치로 돌린다",
            description = """
                    본문(모두 선택) `{"count":3, "deidentFail":2, "cleanup":true}` — 비우면 이 기본값. 총 건수 1~10 · 실패 1~총 건수.

                    1. **정리**(`cleanup`, 기본 true) — 이 API 가 이전에 만든 배치만 지운다(커넥터 보류 장부에 남은 건 먼저 보내 마감)
                    2. **생성** — 접견 더미(DMY) 원본을 지금 직전 10분 구간에 만들고 그 구간만 **실제 배치**(test=false · EXEC_ID UNS)로 돈다.
                       표식(`DMY-MEET-…-DF-…`) 건은 비식별 단계에서 한 번 실패한다(커넥터에 닿기 전 · 원문 전사는 보내지 않음)

                    결과: T1 PARTIAL(전부 실패면 FAIL) · T2 DEIDENT 부분 성공/실패 · T4 표식 건 FAIL(STEP_TYPE_CD=DEIDENT).
                    어드민 배치 상세 '비식별화' 카드 → '실패 상태만 보기'에 실패 건이 뜬다. 같은 배치 [긴급 재처리]는 성공한다(표식은 한 번만 실패).
                    응답 `asRequested=false` 면 비식별 실패 건수가 요청과 다르다.
                    409 — 다른 배치 실행 중 · 단계별 장애가 걸려 있음 · 비식별 단계 꺼짐 · 400 — 건수 오류 · 403 — 대시보드 더미 생성 꺼짐.
                    """)
    @PostMapping("/deident-fail")
    public Map<String, Object> deidentFail(@RequestBody(required = false) DeidentFailSimService.Request req) {
        return service.run(req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        log.warn("[DeidentFailSim] 요청 거절(400) — {}", e.getMessage());
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
        log.warn("[DeidentFailSim] 실패 — {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }
}
