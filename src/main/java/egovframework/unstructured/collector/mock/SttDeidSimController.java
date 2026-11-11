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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 음성(STT) 비식별 시뮬레이션(11번 탭) 뒷단 — 가상 음원 생성 → 제논 STT(600초 넘으면 분할) → 커넥터 비식별 → (REAL) 이력 적재.
 * dev · local 전용.
 *
 * <p>실행은 백그라운드다 — {@code POST /runs} 는 접수(202)만 하고, 화면은 {@code GET /runs/{runId}} 를 1초마다 불러 단계를 그린다.
 * 한 번에 하나만 돈다(이미 돌고 있으면 409).</p>
 *
 * <p>상태 코드 — 202 접수 / 400 값 오류 / 404 없는 실행 / 409 이미 실행 중.</p>
 */
@Log4j2
@Tag(name = "9-6. 음성 STT·비식별 시뮬레이션 (dev·local)",
        description = "가상 접견·통화 음원 → 제논 STT 분할 전사 → 커넥터 비식별(/api/v1/deid/run) → REAL 이면 T1~T4")
@RestController
@RequestMapping(value = "/api/v1/voice/simulator/stt-deid", produces = MediaType.APPLICATION_JSON_VALUE)
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class SttDeidSimController {

    private final SttDeidSimService service;

    @Operation(summary = "상태 — 프리셋 · 제논 · 커넥터 · 로그 컬렉터 연동 · 진행 중 · 일 단위 설정",
            description = "키 원문 · 전사 글은 내려가지 않는다(키는 앞 4 · 뒤 4 글자만).")
    @GetMapping("/status")
    public Map<String, Object> status() {
        return service.status();
    }

    @Operation(summary = "실행 — 음원 생성 · STT(분할) · 비식별 · (REAL) 적재",
            description = """
                    `preset` — S20(20초) · M5(300초) · M10(600초) · M20(1,200초 → 600 × 2) · M30(1,800초 → 600 × 3).
                    `seconds` 를 주면(1~3600) 프리셋 길이 대신 쓴다. `kind` — MEET(접견) · PHONE(전화).

                    `mode` — **SIM**(기본) 은 결과를 메모리 · 화면에만 둔다(로그 컬렉터 안 부름).
                    **REAL** 은 T1(작업 VOICE_STT_DEID · 비정형) · T2(COLLECT · ANALYZE · DEIDENT · STORE) · T4 를 남겨
                    관리자 대시보드 '배치 수행 이력' 에 나온다.

                    `seedPii`(기본 설정값 true) — 합성 낭독에는 개인정보가 없어 가상 개인정보 문장 두 줄을 전사 끝에 덧붙인다.

                    STT 엔진은 11번 탭 ④ 의 제논 호출 모드(MOCK · REST)를, 비식별은 전송 모드(agent-connector MOCK · REST)를 따른다.
                    접수만 하고 202 를 돌려준다 — `GET /runs/{runId}` 로 진행을 본다.""")
    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> run(
            @RequestParam(name = "preset", defaultValue = "S20") String preset,
            @RequestParam(name = "seconds", required = false) Integer seconds,
            @RequestParam(name = "kind", defaultValue = "MEET") String kind,
            @RequestParam(name = "mode", defaultValue = "SIM") String mode,
            @RequestParam(name = "seedPii", required = false) Boolean seedPii) {
        return ResponseEntity.accepted().body(service.submit(preset, seconds, kind, mode, seedPii));
    }

    @Operation(summary = "최근 실행 — 새것부터(글 없이 · 최근 keep-runs 건)")
    @GetMapping("/runs")
    public List<Map<String, Object>> runs() {
        return service.runs();
    }

    @Operation(summary = "실행 하나 — 단계 · 조각별 호출 · 시간 · 전사 원문 · 가린 글",
            description = "전사 원문은 합성 낭독(+ 가상 개인정보 문장)이다. 비식별에 실패한 건은 가린 글이 없다(null).")
    @GetMapping("/runs/{runId}")
    public Map<String, Object> run(@PathVariable String runId) {
        return service.run(runId);
    }

    @Operation(summary = "일 단위 음성 건만 — 접견·전화 3~5건(20~300초) 접수",
            description = """
                    일 단위 자동 생성(01:30 · [즉시 실행])이 붙이는 것과 같은 건을 따로 돌린다. 앞 실행이 있으면 줄을 선다.
                    `mode` — REAL(기본 · 이력 적재 · 실행 유형 MANUAL) · SIM.""")
    @PostMapping("/daily")
    public ResponseEntity<Map<String, Object>> daily(@RequestParam(name = "mode", defaultValue = "REAL") String mode) {
        SttDeidSimService.Mode m;
        try {
            m = SttDeidSimService.Mode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode 은(는) SIM · REAL 중 하나입니다: " + mode);
        }
        return ResponseEntity.accepted().body(service.submitDaily(m, "SIMULATOR"));
    }

    // ---------------------------------------------------------------- 실패 모양

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        log.warn("[STT-DEID] 요청 거절(400) — {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> missing(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false, "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> busy(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("ok", false, "message", e.getMessage()));
    }
}
