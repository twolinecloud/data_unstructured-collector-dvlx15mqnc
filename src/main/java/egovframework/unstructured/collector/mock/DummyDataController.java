package egovframework.unstructured.collector.mock;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 더미 데이터 생성 API — 용도(SIMULATOR / DASHBOARD) · 유형 · 대상일 · 건수 · 장애 시나리오.
 *
 * <p>초기화는 기존 경로 {@code DELETE /api/v1/mock/sim-data?target=SIMULATOR|DASHBOARD} 다(VoiceMockController).
 * dev/local 프로필에서만 빈이 생긴다 — 운영은 이 경로가 없다(404). 공개 프록시에서는 {@code /api/v1/mock/**} 를 막는다.</p>
 */
@Tag(name = "9. 시뮬레이터 (Mock 전용)",
        description = "시연 반복을 위한 초기화·미리보기. 운영에서는 /api/v1/mock/** 를 차단한다")
@Log4j2
@RestController
@Profile({"dev", "local"})
@RequestMapping(value = "/api/v1/mock", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class DummyDataController {

    private final DummyDataService dummy;
    private final ScenarioFaults scenarioFaults;

    @Operation(summary = "더미 데이터 생성 (용도별 · 장애 시나리오)",
            description = """
                    보라미 원천 행 + 더미 원본 파일을 **추가(append)** 합니다. 기존 [시뮬레이션 데이터 생성](Clean & Seed)은 그대로입니다.

                    ```json
                    { "target": "DASHBOARD", "dataTypes": ["PHONE","MEET","IMAGE"], "targetDate": "2026-10-01",
                      "count": 10, "failures": {"COLLECT_FAIL": 1, "ANALYZE_FAIL": 1, "SEND_FAIL": 1}, "append": true }
                    ```

                    | target | 접두 · 생성자 | 누가 읽나 |
                    |---|---|---|
                    | `SIMULATOR` (기본) | `SIM` · `simadm` | 시뮬레이터 시험 실행(`test=true`, TEST_BATCH). 이미지 `SIMIMG…` 는 실제 수집이 뺀다 |
                    | `DASHBOARD` | `DMY` · `dmyadm` — 실존하지 않는 가상 교정번호 | 실제 스케줄러 · `/internal/batch/run` · `/internal/batch/reprocess` → 로그 컬렉터 이력 · 대시보드. 시험 실행은 집지 않는다 |

                    - `count` 는 **유형별** 건수(1~300, 유형 합계 300 이하) · `failures` 도 유형별 · 나머지는 정상 · `targetDate` 기본 어제(미래 불가)
                    - 키 예: `DMY-MEET-20261001-0001` · 장애 건 `DMY-MEET-20261001-SF-0003` · 이미지 교정번호 `DMYIMG2610010003SF` — 날짜·순번으로 유일
                    - **장애 표식 건은 그 단계에서 한 번만 실패**하고 재처리는 통과합니다 — COLLECT_FAIL→`FULL` · ANALYZE_FAIL→`FROM_ANALYZE` · SEND_FAIL→`FROM_SEND`
                      (이미지는 다시 돌리면 실패 건만 재처리 — `IMAGE_RERUN`)
                    - 응답 `windows` — 시나리오마다 겹치지 않는 `CRT_DT` 구간. `POST /api/v1/voice/batches/resume?resume=…&from=…&to=…` 로 그 시나리오만 재처리
                    - 응답 `sqlTables` — 실제로 건드린 테이블. 수용자기본(`TB_IRIM_PRBS_BS`) · 신상(`TB_IRIM_PEIN_BS`)은 어떤 경우에도 없습니다
                    - `DASHBOARD` 는 `unstructured.mock.dashboard.enabled=true` 일 때만(꺼져 있으면 403)
                    """)
    @PostMapping("/sim-data/generate")
    public Map<String, Object> generate(@RequestBody(required = false) DummyDataService.GenerateRequest req) {
        return dummy.generate(req);
    }

    @Operation(summary = "더미 데이터 현황 (용도별)",
            description = "용도별 원천 행 수 · 사진 매핑 수 · 키 표식 장애 소진 현황. SIMULATOR 는 SIM 접두 전체(시연 기본 데이터 포함)를 셉니다.")
    @GetMapping("/sim-data/generated")
    public Map<String, Object> status() {
        return dummy.status();
    }

    @Operation(summary = "키 표식 장애 — 소진 현황",
            description = "표식(CF·AF·SF)을 단 키 중 이미 한 번 실패한 것. 여기 있는 키는 다음 실행에서 통과합니다(메모리 — 재기동하면 비워짐).")
    @GetMapping("/sim-data/scenario-faults")
    public Map<String, Object> scenarioFaults() {
        return scenarioFaults.snapshot();
    }

    @Operation(summary = "키 표식 장애 — 다시 걸기",
            description = "소진 표시를 지워 표식 건이 **다음 실행에서 한 번 더** 실패하게 합니다. `target` 을 주면 그 용도의 키만, 비우면 전부.")
    @DeleteMapping("/sim-data/scenario-faults")
    public Map<String, Object> rearm(@RequestParam(required = false) DummyTarget target) {
        int n = scenarioFaults.forget(k -> target == null || target.ownsScenarioKey(k));
        Map<String, Object> out = new LinkedHashMap<>(scenarioFaults.snapshot());
        out.put("forgotten", n);
        return out;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        log.warn("[Dummy] 잘못된 요청 — {}", e.getMessage());
        return Map.of("error", "BAD_REQUEST", "message", String.valueOf(e.getMessage()));
    }

    @ExceptionHandler(DummyDataService.DashboardDisabledException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, Object> disabled(DummyDataService.DashboardDisabledException e) {
        return Map.of("error", "DASHBOARD_DISABLED", "message", String.valueOf(e.getMessage()));
    }

    /** DB 에 붙지 못했거나 SQL 이 실패 — 사유를 그대로(기본 처리는 'Internal Server Error' 만 남긴다). */
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,
            org.springframework.transaction.TransactionException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> failed(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String reason = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage().replaceAll("\\s+", " ").trim();
        log.error("[Dummy] 실패 — {}", reason);
        return Map.of("error", "DUMMY_DATA_ERROR", "message", reason);
    }
}
