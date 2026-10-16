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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 개발계 클라우드 전송 시뮬레이션 — 관리 화면 '클라우드 전송 제어' 의 다섯 상태를 상태별 개수만큼 만든다. dev · local 전용.
 */
@Log4j2
@Tag(name = "9-2. 클라우드 전송 시뮬레이션 (dev·local)",
        description = "클라우드 전송 제어 대기 목록의 대기 · 실패 · 부분 성공 · 전송중 · 취소 배치를 상태별 개수만큼 — 연속 전송(체인) 확인용")
@RestController
@RequestMapping(value = "/api/v1/voice/simulator/cloud-send-states", produces = MediaType.APPLICATION_JSON_VALUE)
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class CloudSendSimController {

    private final CloudSendSimService service;

    @Operation(summary = "상태별 배치 만들기 — 이전 묶음 정리 후 실제 배치로",
            description = """
                    본문(모두 선택) `{"counts":{"WAIT":2,"FAIL":2,"PARTIAL":2,"RUNNING":2,"CANCELED":2}, "cleanup":true}` — 본문이 없으면 모두 2.
                    상태마다 0 ~ 10 · 합계 1 ~ 30. 빠진 상태는 0.

                    | 상태 | 만드는 법 | 결과(전송 방식 수동) |
                    |---|---|---|
                    | WAIT 대기 | 실제 흐름 — 정상 2건 | SEND WAIT · 커넥터 보류 |
                    | FAIL 실패 | 실제 흐름 — 2건 모두 전송 실패 표식 | T1 FAIL · SEND FAIL · 장부 없음 |
                    | PARTIAL 부분 성공 | 화면 재현 — 3건 중 1건 전송 실패(2건 보류) 뒤 전송 단계를 PARTIAL | T1 PARTIAL · SEND PARTIAL · 보류 2건 |
                    | RUNNING 전송중 | 화면 재현 — 정상 2건 보류 뒤 전송 시작(claim)만 | SEND RUNNING · 보류 유지 |
                    | CANCELED 취소 | 화면 재현 — 정상 2건 보류 뒤 시작 → 중지로 마감 | SEND CANCELED · 보류 유지 |

                    재현 셋은 비정형에서 저절로 남지 않아(수동 모드는 보류가 이긴다 · 전송중은 순간 · 음성 전송은 중지 불가) 커넥터 관리자 트리거와 같은 순서
                    (로그 컬렉터 `steps/send/claim` → `PATCH steps/{id}`)로 전송 단계만 바꾼다. 실행 순서는 상태를 돌아가며 섞고 마지막(목록 맨 위)은 대기.
                    응답 `pick` = 연속 전송에서 누를 배치(가장 최근 미완료). 409 — 다른 배치 실행 중 · 400 — 개수 오류 · 403 — 대시보드 더미 생성 꺼짐.
                    """)
    @PostMapping
    public Map<String, Object> run(@RequestBody(required = false) CloudSendSimService.Request req) {
        return service.run(req);
    }

    @Operation(summary = "이번 묶음 지금 상태 — T1 · 전송 단계 · 보류 장부 · 폐기")
    @GetMapping
    public Map<String, Object> status() {
        return service.status();
    }

    @Operation(summary = "전송중 풀기 — 재현한 전송중(RUNNING)을 대기(WAIT)로",
            description = "이번 묶음에서 전송 단계가 RUNNING 이고 커넥터 보류 장부에 있는 배치만 되돌린다. 관리 화면 [클라우드 전송 중지]는 음성(학습셋) 전송엔 접수되지 않는다.")
    @PostMapping("/unstick")
    public Map<String, Object> unstick() {
        return service.unstick();
    }

    @Operation(summary = "이번 묶음 정리 — 전송중은 대기로 · 보류분은 먼저 보내 마감 · 이력 삭제(더미 안전 조건)")
    @PostMapping("/cleanup")
    public Map<String, Object> cleanup() {
        return service.cleanupOnly();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        log.warn("[CloudSim] 요청 거절(400) — {}", e.getMessage());
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
        log.warn("[CloudSim] 실패 — {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }
}
