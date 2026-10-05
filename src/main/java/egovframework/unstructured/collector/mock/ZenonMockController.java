package egovframework.unstructured.collector.mock;

import egovframework.unstructured.collector.batch.UnstructuredJobRunner;
import egovframework.unstructured.collector.common.transfer.ZenonClient;
import egovframework.unstructured.collector.common.transfer.ZenonMockReceiver;
import egovframework.unstructured.collector.common.transfer.ZenonTransferRuns;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 제논 전송 — 내장 수신기(MOCK) · 전송 시뮬레이션 · 전송 재처리 시나리오. dev · local 전용.
 *
 * <p>시뮬레이터 7번 탭 '제논 전송 시뮬레이션' 이 쓴다. 에이전트 커넥터 시뮬레이터의 '대용량 gzip 연동 시험'(표준 양식 · A/B/C ·
 * 수신 헤더 시나리오)과 '긴급 재처리(PPP 전송)'(전 청크 실패 · 부분 전송 실패 → 정상화 → 재처리 이어달리기)를 같은 순서로 옮겼다.</p>
 */
@Log4j2
@Tag(name = "9. 제논 전송 시뮬레이션 (dev·local)",
        description = "data-collector 와 같은 전송 양식(gzip · chunked · X-Run-Id/X-Seq/X-Is-Last/X-Target-Cnt/X-Chunk-Cnt/X-Data-Type) — "
                + "내장 수신기 · 부하 시험 · 헤더 시나리오 · 전송 재처리")
@RestController
@RequestMapping(value = "/api/v1/mock/zenon", produces = MediaType.APPLICATION_JSON_VALUE)
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class ZenonMockController {

    private final ZenonMockReceiver receiver;
    private final ZenonClient zenon;
    private final ZenonTransferRuns runs;
    private final ZenonSimService sim;
    private final ZenonReprocessSimService reprocessSim;

    // ── 내장 수신기 ──────────────────────────────────────────────────────

    @Operation(summary = "제논 수신(흉내) — 청크 1건",
            description = """
                    에이전트 커넥터 `POST /api/v1/learn/transfer` 와 같은 수신단. 본문(gzip 이면 Content-Encoding: gzip)을 흘려 읽고 장부에 남긴다.

                    - 200 `{code:SUCCESS, runId, seq, duplicate, receivedChunks, receivedRecords, state, missingSeqs, bytes, rawBytes}`
                    - 400 헤더 누락(X-Run-Id · X-Seq) · header.runId ≠ X-Run-Id · payload 건수 ≠ X-Chunk-Cnt · managementNo 없음
                    - 413 해제 상한 초과 · 503 장애 흉내(`POST /fault`)
                    - 수집기를 `ZENON_MODE=REST ZENON_BASE_URL=http://127.0.0.1:{port} ZENON_TRANSFER_PATH=/api/v1/mock/zenon/transfer` 로 띄우면 실제 소켓으로 자기 자신에게 보낸다
                    """)
    @PostMapping(value = "/transfer", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Map<String, Object>> transfer(HttpServletRequest request) throws IOException {
        String enc = request.getHeader("Content-Encoding");
        ZenonMockReceiver.Ack ack = receiver.receive(request::getHeader, request.getInputStream(),
                enc != null && enc.toLowerCase().contains("gzip"));
        return ResponseEntity.status(ack.status()).body(ack.body());
    }

    @Operation(summary = "전송 상태 — 모드 · 주소 · 청크 · 수신기 장애 · 최근 런")
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>(zenon.status());
        m.put("receiver", receiver.status());
        m.put("recentRuns", runs.recent(10).stream().map(ZenonReprocessSimService::runView).toList());
        return m;
    }

    @Operation(summary = "수신 장부 — runId 하나 또는 최근 목록")
    @GetMapping("/ledger")
    public Object ledger(@RequestParam(required = false) String runId, @RequestParam(defaultValue = "20") int limit) {
        if (runId != null && !runId.isBlank()) {
            Map<String, Object> l = receiver.ledger(runId.trim());
            if (l == null) {
                throw new IllegalArgumentException("장부 없음 — runId=" + runId);
            }
            return l;
        }
        return receiver.ledgers(limit);
    }

    @Operation(summary = "유실 판정 스윕 — idleSec 동안 조용한 미완 런을 INGEST-GAP 으로(시연은 0)")
    @PostMapping("/sweep")
    public Map<String, Object> sweep(@RequestParam(defaultValue = "0") int idleSec) {
        return receiver.sweep(idleSec);
    }

    @Operation(summary = "수신기 장애 흉내 — UP(정상화) · DOWN(전부 503) · FAIL_FROM_SEQ(fromSeq 부터 503)")
    @PostMapping("/fault")
    public Map<String, Object> fault(@RequestParam(defaultValue = "UP") ZenonMockReceiver.FaultMode mode,
                                     @RequestParam(defaultValue = "3") int fromSeq) {
        return receiver.setFault(mode, fromSeq);
    }

    @GetMapping("/fault")
    public Map<String, Object> faultNow() {
        return receiver.fault();
    }

    @Operation(summary = "청크 레코드 수 덮어쓰기(시연) — 비우면 설정값(zenon.chunk-records)")
    @PostMapping("/chunk-records")
    public Map<String, Object> chunkRecords(@RequestParam(required = false) Integer records) {
        zenon.overrideChunkRecords(records);
        return Map.of("chunkRecords", zenon.chunkRecords());
    }

    // ── 전송 시뮬레이션(커넥터 '대용량 gzip 연동 시험') ─────────────────────

    @Operation(summary = "송신 표준 양식 — 헤더 · 본문 · 규칙 · curl")
    @GetMapping("/sim/template")
    public Map<String, Object> template() {
        return sim.template();
    }

    @Operation(summary = "점진적 용량 시험 시작 — A 정상 · B 압축 폭탄 · C 연속 부하 · ALL",
            description = "수집기가 자기 자신(내장 수신기)에게 실제 소켓으로 쏜다. 진행은 `GET /sim/load/status?after=`")
    @PostMapping("/sim/load")
    public ResponseEntity<Map<String, Object>> load(@RequestParam(defaultValue = "ALL") String scenario,
                                                    @RequestParam(defaultValue = "50") int sizeMb,
                                                    @RequestParam(defaultValue = "20") int rounds,
                                                    @RequestParam(defaultValue = "600") int bombMb) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(sim.startLoad(scenario, sizeMb, rounds, bombMb));
    }

    @GetMapping("/sim/load/status")
    public Map<String, Object> loadStatus(@RequestParam(defaultValue = "0") int after) {
        return sim.loadStatus(after);
    }

    @Operation(summary = "수신 헤더 시나리오 — 1 강제 중단·재전송 · 2 청크 유실 타임아웃 · 3 중복 청크 멱등 · 4 헤더 누락·건수 불일치")
    @PostMapping("/sim/headers/{n}")
    public Map<String, Object> headers(@PathVariable int n) {
        return sim.headerScenario(n);
    }

    // ── 전송 재처리(커넥터 '긴급 재처리 — PPP 전송') ───────────────────────

    @Operation(summary = "① 오류 상황 재현 — 더미 생성 → 제논 장애 · 청크 작게 → 배치 실행",
            description = "scenario ALL_FAIL(1번 청크부터 503 → FAIL) · PARTIAL(3번 청크부터 503 → PARTIAL). "
                    + "target SIMULATOR(SIM · TST) · DASHBOARD(DMY · 실제 배치 UNS)")
    @PostMapping("/sim/reprocess/arm")
    public Map<String, Object> arm(@RequestParam(defaultValue = "ALL_FAIL") ZenonReprocessSimService.Scenario scenario,
                                   @RequestParam(defaultValue = "SIMULATOR") DummyTarget target,
                                   @RequestParam(required = false) Integer perType,
                                   @RequestParam(required = false) Integer chunkRecords,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate) {
        return reprocessSim.arm(scenario, target, perType, chunkRecords, targetDate);
    }

    @Operation(summary = "② 제논 정상화 — 수신기 200")
    @PostMapping("/sim/reprocess/fix")
    public Map<String, Object> fix() {
        return reprocessSim.fix();
    }

    @Operation(summary = "③ 긴급 재처리 — admin 과 같은 경로(단계 SEND) · 끝날 때까지 기다린다")
    @PostMapping("/sim/reprocess/run")
    public Map<String, Object> reprocess(@RequestParam String execId,
                                         @RequestParam(defaultValue = "SEND") String stepTypeCd,
                                         @RequestParam(defaultValue = "180000") long waitMs) {
        return reprocessSim.reprocess(execId, stepTypeCd, waitMs);
    }

    @GetMapping("/sim/reprocess/state")
    public Map<String, Object> state(@RequestParam(required = false) String execId) {
        return reprocessSim.state(execId);
    }

    @Operation(summary = "상태 초기화 — 장애 해제 · 청크 레코드 수 설정값")
    @PostMapping("/sim/reprocess/reset")
    public Map<String, Object> reset() {
        return reprocessSim.reset();
    }

    // ── 오류 ─────────────────────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler({IllegalStateException.class, UnstructuredJobRunner.AlreadyRunningException.class})
    public ResponseEntity<Map<String, Object>> conflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
