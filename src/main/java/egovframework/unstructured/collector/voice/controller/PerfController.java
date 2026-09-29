package egovframework.unstructured.collector.voice.controller;

import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import egovframework.unstructured.collector.voice.perf.PerfRequest;
import egovframework.unstructured.collector.voice.perf.PerfRunService;
import egovframework.unstructured.collector.voice.perf.RampRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 성능 테스트 API — 시뮬레이터 4번 탭이 쓴다.
 *
 * <p><b>{@code /api/v1/mock/**} 아래에 둔다</b> — 공용 DB 에 SIM 데이터를 만들고 지우는 시험 기능이라
 * 운영에서는 다른 시뮬레이터 API 와 함께 인그레스·게이트웨이에서 막는다.</p>
 */
@Tag(name = "10. 성능 시험 (Mock 전용)",
        description = "기본 부하 검증(4번 탭)·임계 성능 시험(5번 탭) — 실제 파이프라인을 N건(최대 300)으로 돌려 처리량·단계별 시간·자원을 잰다. STT 만 MOCK(건당 처리 시간 · 기본 고속 모드). 운영에서는 /api/v1/mock/** 를 차단한다")
@RestController
@RequestMapping(value = "/api/v1/mock/perf", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class PerfController {

    private final PerfRunService perf;
    private final VoiceCollectService collect;

    @Operation(summary = "입력 범위 · 지금 구성",
            description = "건수·지연·워커(XVARM 확보 · STT 처리)의 범위와 기본값, STT 엔진(MOCK/NPU), 조회·브로커·복호화 모드, 원천 풀(Hikari) 설정, 이력 파일 위치.")
    @GetMapping("/info")
    public Map<String, Object> info() {
        return perf.info(collect.defaultWorkers());
    }

    @Operation(summary = "기본 부하 검증 시작 (비동기)",
            description = """
                    한 회차를 시작하고 바로 돌아옵니다. 진행은 `GET /runs/current` 로 봅니다.

                    1. **준비** — 로컬 산출물을 비우고 원천 DB 에 SIM 데이터(접견 N · 전화 N, 전화 중 기 STT 비율만큼 `TELP_STT_FLPTH_NM` 채움)를 **어제 하루**에 만듭니다.
                       개발계에서는 **공용 DB** 에 만들어지므로 다른 작업자와 시간이 겹치지 않게 하십시오
                    2. **측정** — `[어제 00:00, 오늘 00:00)` 를 처리합니다(`TEST_BATCH` · `MANUAL` · 실행 주체 `PERF`).
                       **XVARM 확보 워커**(`acquireWorkers`, 기본 1)가 파일을 차례로 받아 대기열에 넣고, **STT 처리 워커**
                       (`sttWorkers`, 기본 31)가 곧바로 집어 복호화 · STT · 비식별 · 저장을 합니다(생산자-소비자).
                       STT 가 MOCK 이면 건당 STT 처리 시간·타임아웃을 겁니다
                    - **고속 모드**(`realSleep` 비움·false, 기본) — STT 를 기다리지 않고 즉시 통과합니다. 실제로 돈 확보 시각에
                      건당 처리 시간을 얹어 STT 워커에 다시 배정한 **파이프라인 모의**로 총 소요를 냅니다(확보와 STT 가 겹친다).
                      TPS = 처리 건수 ÷ 총 소요. 처리 시간이 타임아웃을 넘은 건은 기다리지 않고 바로 타임아웃 실패(T4)로 남습니다
                    - **실제 대기 모드**(`realSleep=true`) — 처리 시간만큼 실제로 기다립니다
                    3. **검증** — 로그 컬렉터에서 T1·T2·T4 를 되읽어 맞춰 봅니다
                    4. **정리** — SIM 행과 원본 더미 파일을 지웁니다. 로그(TST)와 STT 출력은 남습니다

                    - 400 — 범위 밖(접견+전화 2~300 · 기 STT 0~100% · 지연 0~600,000ms · 워커 1~64 · 타임아웃 100~1,800,000ms)
                    - 409 — 이미 성능 테스트나 배치가 돌고 있음
                    """)
    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> start(@RequestBody(required = false) PerfRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(perf.start(req));
    }

    @Operation(summary = "진행 중(또는 마지막) 회차",
            description = "`phase` — PREPARING 준비 · RUNNING 측정 · VERIFYING 검증 · CLEANING 정리 · DONE 완료 · FAILED 실패. 측정 중에는 `progress` 가, 끝나면 `result` 가 실립니다.")
    @GetMapping("/runs/current")
    public Map<String, Object> current() {
        return perf.current();
    }

    @Operation(summary = "성능 테스트 중단",
            description = "준비 중이면 측정을 건너뛰고, 측정 중이면 처리 중인 건만 끝낸 뒤 멈춥니다. 어느 쪽이든 SIM 데이터는 지웁니다.")
    @PostMapping("/runs/cancel")
    public Map<String, Object> cancel() {
        return perf.cancel();
    }

    @Operation(summary = "실행 이력", description = "`{ROOT}/perf/history.jsonl` — 최근 회차가 앞. 워커 구성을 바꿔 가며 돌린 결과를 나란히 비교합니다.")
    @GetMapping("/runs")
    public Map<String, Object> history() {
        return perf.history();
    }

    @Operation(summary = "실행 이력 비우기", description = "이력 파일만 지웁니다. 로그 컬렉터의 TST 이력은 [시뮬레이션 데이터 초기화]가 지웁니다.")
    @DeleteMapping("/runs")
    public Map<String, Object> clearHistory() {
        return perf.clearHistory();
    }

    @Operation(summary = "임계 성능 시험 시작 — 워커 램프업 (비동기)",
            description = """
                    시작 워커부터 단계마다 워커를 늘려(+N 또는 ×N) **같은 건수**를 처리합니다. 단계마다 SIM 데이터를 다시 만듭니다.
                    늘릴 워커는 `rampTarget` — `STT`(기본, STT 처리 워커를 늘리고 XVARM 확보 워커는 `acquireWorkers` 로 고정)
                    또는 `ACQUIRE`(XVARM 확보 워커를 늘리고 STT 처리 워커는 `sttWorkers` 로 고정).
                    진행은 `GET /runs/current`(`kind=RAMP` · `steps` 가 단계마다 쌓임), 중지는 `POST /runs/cancel` 로 봅니다.

                    **조기 종료**
                    - 최저 응답 시간(단계 총 소요) 뒤 `patience` 번 연속 그보다 빨라지지 않으면 — 포화
                    - XVARM 확보 대기가 `acquireLimitSec` 초를 넘으면 — 단계 도중에도 즉시(처리 중인 건만 끝낸다)
                    - STT 에러·타임아웃이 나면 — 단계 도중에도 즉시(`stopOnSttError`)

                    - 400 — 범위 밖 · 단계 64개 초과
                    - 409 — 이미 성능 시험이나 배치가 돌고 있음
                    """)
    @PostMapping("/ramp/runs")
    public ResponseEntity<Map<String, Object>> startRamp(@RequestBody(required = false) RampRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(perf.startRamp(req));
    }

    @Operation(summary = "임계 성능 시험 이력", description = "`{ROOT}/perf/ramp-history.jsonl` — 최근 회차가 앞. 회차마다 단계 표와 최적 워커가 실린다.")
    @GetMapping("/ramp/runs")
    public Map<String, Object> rampHistory() {
        return perf.rampHistory();
    }

    @Operation(summary = "임계 성능 시험 이력 비우기")
    @DeleteMapping("/ramp/runs")
    public Map<String, Object> clearRampHistory() {
        return perf.clearRampHistory();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }
}
