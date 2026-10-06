package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.transfer.StreamingHttpClient;
import egovframework.unstructured.collector.common.transfer.AgentConnectorClient;
import egovframework.unstructured.collector.common.transfer.AgentConnectorMockReceiver;
import egovframework.unstructured.collector.common.transfer.AgentConnectorProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>에이전트 커넥터 전송 시뮬레이션</b> — 에이전트 커넥터 시뮬레이터 '대용량 gzip 연동 시험'(표준 양식 · A/B/C · 수신 헤더 시나리오)을
 * 비정형 수집기에 그대로 옮겼다(2026-10-05 지시).
 *
 * <p>수집기가 <b>자기 자신에게</b> 송신단 흉내를 내어 실제 소켓으로 쏜다 — {@link StreamingHttpClient}(data-collector 원본)로
 * {@code 127.0.0.1:{port}/api/v1/mock/agent-connector/transfer}(내장 수신기)에. 같은 JVM 이라 힙 곡선까지 함께 본다.
 * 수신기는 본문을 저장하지 않고 흘려 읽는다 — 방어가 됐는지는 상태 코드로, OOM 이 없었는지는 힙으로 확인한다.</p>
 * <ul>
 *   <li><b>A 정상 청크</b> — sizeMb gzip(chunked) 1회 → 200 · 레코드 수 일치</li>
 *   <li><b>B 압축 폭탄</b> — 해제 상한을 넘는 공백 폭탄 → 413(끝까지 풀지 않는다)</li>
 *   <li><b>C 연속 부하</b> — sizeMb × rounds → 전 회차 200 · GC 뒤 힙이 시작 수준으로</li>
 *   <li><b>헤더 ①</b> 강제 중단 · 같은 런으로 재전송 → 마감(중복 아님) · <b>②</b> 청크 유실 → INGEST-GAP ·
 *       <b>③</b> 중복 청크 → 200 duplicate · <b>④</b> 헤더 누락 · 레코드 수 불일치 → 400</li>
 * </ul>
 * <p>시험 런 ID 는 {@code yyyyMMddTST-ZSIM-HHmmss} — 시뮬레이터 초기화가 TST 로 함께 지운다. dev · local 전용.</p>
 */
@Log4j2
@Service
@Profile({"dev", "local"})
public class TransferSimService {

    static final String SELF_PATH = "/api/v1/mock/agent-connector/transfer";
    private static final int MB = 1024 * 1024;
    /** 레코드 하나를 이만큼 부풀린다(50KB) — 커넥터 시험과 같다. 50MB 당 약 1,000건. */
    private static final int FILLER_BYTES = 50 * 1024;
    private static final int MAX_EVENTS = 400;

    private final AgentConnectorProperties props;
    private final AgentConnectorMockReceiver receiver;
    private final AgentConnectorClient connector;
    private final ObjectMapper objectMapper;
    private final Environment env;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Map<String, Object>> events = new ArrayList<>();
    private volatile String jobId;
    private volatile String jobStatus = "NONE";

    public TransferSimService(AgentConnectorProperties props, AgentConnectorMockReceiver receiver, AgentConnectorClient connector, ObjectMapper objectMapper,
                           Environment env) {
        this.props = props;
        this.receiver = receiver;
        this.connector = connector;
        this.objectMapper = objectMapper;
        this.env = env;
    }

    private int port() {
        String p = env.getProperty("local.server.port");
        if (p == null) {
            p = env.getProperty("server.port", "8080");
        }
        return Integer.parseInt(p.trim());
    }

    public String selfEndpoint() {
        return "http://127.0.0.1:" + port() + SELF_PATH;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  표준 양식
    // ══════════════════════════════════════════════════════════════════════

    /** 송신 표준 양식 — 헤더 · 본문 예시 · 규칙 · curl. 화면 '양식 복사' 가 이것을 그대로 쓴다. */
    public Map<String, Object> template() {
        String runId = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "UNS001";
        Map<String, String> headers = connector.headers(runId, 1, false, 6, 2);
        headers.put("Content-Encoding", props.gzip() ? "gzip" : "(끔)");
        headers.put("Transfer-Encoding", "chunked");
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("runId", runId);
        header.put("dataTypeCd", props.dataTypeCd());
        header.put("collectDtm", LocalDateTime.now().withNano(0) + "+09:00");
        header.put("setTypeCd", props.setTypeCd());
        body.put("header", header);
        List<Map<String, Object>> payload = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("recFileId", i == 1 ? "TARE0000000001" : "VRFC0000000001");
            raw.put("kind", i == 1 ? "MEET" : "PHONE");
            raw.put("inmatePid", "P" + "3f9a2c1d7b6e5a40".substring(0, 15) + i);
            raw.put("execId", runId);
            raw.put("srcFileName", i == 1 ? "meet_0001.wav" : "phone_0001.wav");
            raw.put("engine", "whisper");
            raw.put("durationSec", 182.4);
            raw.put("charCount", 1200);
            raw.put("processedAt", LocalDateTime.now().withNano(0).toString());
            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("text", "(전사 본문)");
            tr.put("language", "ko");
            tr.put("duration", 182.4);
            tr.put("segments", List.of(Map.of("id", 0, "start", 0.0, "end", 4.2, "text", "(구간 전사)")));
            raw.put("transcript", tr);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("managementNo", "(교정번호 CORR_NO)");
            rec.put("rawDataset", raw);
            payload.add(rec);
        }
        body.put("payload", payload);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", "POST");
        m.put("path", props.transferPath());
        m.put("selfEndpoint", selfEndpoint());
        m.put("headers", headers);
        m.put("body", body);
        m.put("rules", List.of(
                "data-collector FeatureSetTransferSink 와 같은 양식 · 같은 수신 API — 에이전트 커넥터 bypass(비식별 없이 제논으로 중계)",
                "X-Run-Id = 전송 런 ID = 수집 실행 ID(EXEC_ID). FROM_SEND 재처리는 원배치 런을 이어 받아 다음 순번부터 보낸다",
                "X-Seq 1부터 오름차순 · 마지막 청크만 X-Is-Last: true · X-Target-Cnt = 런 전체 레코드 · X-Chunk-Cnt = 이 청크 레코드",
                "(X-Run-Id + X-Seq) 멱등 — 중복 청크는 200 + duplicate:true(재전송 무해)",
                "성공 = HTTP 2xx(응답 꼬리에 \"aborted\" 가 있으면 실패). 한 청크가 실패하면 뒤 청크를 보내지 않는다",
                "Content-Encoding: gzip + Transfer-Encoding: chunked — 해제 후 길이를 미리 알 수 없어 Content-Length 를 쓰지 않는다",
                "해제 상한 " + props.mockMaxDecompressedBytes() / MB + "MB 초과는 413 — 청크(권장 50MB)로 나눠 보낸다",
                "응답을 본문 쓰기와 동시에 읽는다(전이중) — 다 쓰고 읽으면 서버가 먼저 413 을 던질 때 교착",
                "managementNo = 교정번호(data-collector 예측과 같음) · rawDataset.inmatePid = T4 INMATE_PID 와 같은 가명 ID",
                "collectDtm 은 KST(+09:00) — UTC 'Z' 금지"));
        m.put("curl", "printf '%s' '" + compact(body) + "' | gzip | curl -sS -X POST '" + selfEndpoint() + "' \\\n"
                + "  -H 'Content-Type: application/json;charset=UTF-8' -H 'Content-Encoding: gzip' \\\n"
                + "  -H 'X-Run-Id: " + runId + "' -H 'X-Data-Type: " + props.dataTypeCd() + "' \\\n"
                + "  -H 'X-Target-Cnt: 2' -H 'X-Chunk-Cnt: 2' -H 'X-Seq: 1' -H 'X-Is-Last: true' \\\n"
                + "  --data-binary @-");
        m.put("maxDecompressedMb", props.mockMaxDecompressedBytes() / MB);
        m.put("chunkRecords", connector.chunkRecords());
        m.put("mode", connector.mode());
        m.put("endpoint", connector.endpoint());
        return m;
    }

    private String compact(Object o) {
        try {
            return objectMapper.writeValueAsString(o).replace("'", "'\\''");
        } catch (IOException e) {
            return "{}";
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  A / B / C — 비동기(화면은 상태를 폴링한다)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 부하 시험을 시작한다 — 이미 돌고 있으면 예외.
     *
     * @param scenario A · B · C · ALL
     */
    public Map<String, Object> startLoad(String scenario, int sizeMb, int rounds, int bombMb) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("이미 다른 시험이 진행 중입니다 — 끝난 뒤에 다시 실행하세요");
        }
        String s = scenario == null ? "ALL" : scenario.trim().toUpperCase();
        int size = Math.max(1, Math.min(512, sizeMb));
        int r = Math.max(1, Math.min(100, rounds));
        int bomb = Math.max(1, Math.min(4096, bombMb));
        synchronized (events) {
            events.clear();
        }
        jobId = "ZLOAD-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        jobStatus = "RUNNING";
        Thread t = new Thread(() -> runLoad(s, size, r, bomb), "transfer-sim-load");
        t.setDaemon(true);
        t.start();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", jobId);
        m.put("status", jobStatus);
        m.put("scenario", s);
        m.put("endpoint", selfEndpoint());
        return m;
    }

    /** 진행 — after 이후 이벤트만. */
    public Map<String, Object> loadStatus(int after) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", jobId);
        m.put("status", jobStatus);
        m.put("running", running.get());
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (events) {
            for (int i = Math.max(0, after); i < events.size(); i++) {
                out.add(events.get(i));
            }
            m.put("next", events.size());
        }
        m.put("events", out);
        return m;
    }

    private void runLoad(String s, int sizeMb, int rounds, int bombMb) {
        try {
            Map<String, Object> b = event("banner", "info", "대용량 gzip 연동 시험 — 송신단(수집기) → 내장 에이전트 커넥터 수신기");
            b.put("scenario", s);
            b.put("sizeMb", sizeMb);
            b.put("rounds", rounds);
            b.put("bombMb", bombMb);
            b.put("endpoint", selfEndpoint());
            b.put("maxHeapMb", Runtime.getRuntime().maxMemory() / MB);
            b.put("limitMb", props.mockMaxDecompressedBytes() / MB);
            emit(b);
            if (s.equals("A") || s.equals("ALL")) {
                scenarioA(sizeMb);
            }
            if (s.equals("B") || s.equals("ALL")) {
                scenarioB(bombMb);
            }
            if (s.equals("C") || s.equals("ALL")) {
                scenarioC(sizeMb, rounds);
            }
            emit(event("done", "info", "시험 종료"));
            jobStatus = "DONE";
        } catch (RuntimeException e) {
            log.error("[TransferSim] 시험 실행 실패", e);
            emit(event("log", "error", "시험이 중단되었습니다 — " + e));
            emit(event("done", "error", "중단"));
            jobStatus = "FAILED";
        } finally {
            running.set(false);
        }
    }

    private void scenarioA(int sizeMb) {
        emit(event("log", "info", "▶ 시나리오 A — 정상 청크 " + sizeMb + "MB 를 gzip(chunked)으로 1회 전송합니다."));
        String runId = simRunId("A");
        long records = recordsFor((long) sizeMb * MB);
        Shot shot = send(runId, 1, true, records, records, out -> writeBulk(out, runId, records));
        boolean ok = shot.status == 200 && shot.records == records;
        Map<String, Object> r = result("A", "정상 청크(" + sizeMb + "MB)", ok, shot);
        r.put("expected", "200 OK · 레코드 " + records + "건 수신");
        r.put("verdict", ok
                ? "해제·파싱 모두 정상. 압축 " + mb(shot.bytes) + " → 해제 " + mb(shot.rawBytes) + " (" + shot.ratio()
                  + "배), 수신기가 " + shot.records + "건을 받았습니다."
                : "기대와 다릅니다 — 상태 " + shot.status + " · " + shot.note());
        emit(r);
    }

    private void scenarioB(int bombMb) {
        long limit = props.mockMaxDecompressedBytes();
        emit(event("log", "info", "▶ 시나리오 B — 해제 상한 " + mb(limit) + " 을 넘기는 압축 폭탄(공백 " + bombMb + "MB)을 보냅니다."));
        String runId = simRunId("B");
        Shot shot = send(runId, 1, true, 0, 0, out -> writeBomb(out, runId, (long) bombMb * MB));
        boolean ok = shot.status == 413;
        Map<String, Object> r = result("B", "압축 폭탄", ok, shot);
        r.put("expected", "413 Payload Too Large");
        r.put("verdict", ok
                ? "압축 " + mb(shot.bytes) + " 만 보냈는데 해제하면 " + bombMb + "MB 입니다(" + (bombMb * (long) MB / Math.max(1, shot.bytes))
                  + "배). 상한에서 끊고 413 을 돌려줬으며 "
                  + "힙 최대치는 " + shot.heapPeakMb + "MB 로 평온했습니다."
                : (bombMb * (long) MB <= limit
                    ? "폭탄 크기(" + bombMb + "MB)가 상한(" + mb(limit) + ")보다 작습니다 — 상한보다 크게 주세요"
                    : "기대(413)와 다릅니다 — 상태 " + shot.status + " · " + shot.note()));
        emit(r);
        emit(event("note", ok ? "info" : "warn",
                "송신단이 알아야 할 점 — 상한 초과는 <b>413</b> 으로 옵니다. 서버가 본문을 다 읽기 전에 응답하므로 "
                + "<b>응답을 쓰기와 동시에 읽어야</b>(전이중) 교착 없이 이 신호를 받습니다. 4GB~10GB 급은 청크(권장 50MB)로 나눠 보내세요."));
    }

    private void scenarioC(int sizeMb, int rounds) {
        emit(event("log", "info", "▶ 시나리오 C — " + sizeMb + "MB × " + rounds + "회(총 " + (sizeMb * rounds) + "MB) 연속 전송합니다."));
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long heapStart = mem.getHeapMemoryUsage().getUsed();
        long totalRaw = 0;
        long totalSent = 0;
        long totalRecords = 0;
        long worstMs = 0;
        long peak = heapStart;
        int failedRound = 0;
        String runId = simRunId("C");
        long per = recordsFor((long) sizeMb * MB);
        for (int i = 1; i <= rounds; i++) {
            boolean last = i == rounds;
            Shot shot = send(runId, i, last, per * rounds, per, out -> writeBulk(out, runId, per));
            totalRaw += shot.rawBytes;
            totalSent += shot.bytes;
            totalRecords += shot.records;
            worstMs = Math.max(worstMs, shot.elapsedMs);
            peak = Math.max(peak, (long) shot.heapPeakMb * MB);
            if (shot.status != 200) {
                failedRound++;
            }
            Map<String, Object> p = event("progress", "info",
                    i + "/" + rounds + "회 — " + shot.status + " · " + shot.elapsedMs + "ms · 힙 " + shot.heapAfterMb + "MB");
            p.put("round", i);
            p.put("of", rounds);
            p.put("heapAfterMb", shot.heapAfterMb);
            p.put("heapPeakMb", shot.heapPeakMb);
            p.put("elapsedMs", shot.elapsedMs);
            p.put("status", shot.status);
            emit(p);
        }
        System.gc();
        sleepQuietly(400);
        long heapEnd = mem.getHeapMemoryUsage().getUsed();
        boolean ok = failedRound == 0 && heapEnd <= heapStart + 256L * MB;
        Map<String, Object> led = receiver.ledger(runId);
        Map<String, Object> r = event("result", ok ? "ok" : "fail", "시나리오 C 종료");
        r.put("scenario", "C");
        r.put("title", "연속 부하(" + sizeMb + "MB × " + rounds + "회)");
        r.put("ok", ok);
        r.put("rounds", rounds);
        r.put("failedRound", failedRound);
        r.put("totalRawMb", totalRaw / MB);
        r.put("totalSentMb", totalSent / MB);
        r.put("records", totalRecords);
        r.put("worstMs", worstMs);
        r.put("heapStartMb", heapStart / MB);
        r.put("heapEndMb", heapEnd / MB);
        r.put("heapPeakMb", peak / MB);
        r.put("maxHeapMb", Runtime.getRuntime().maxMemory() / MB);
        r.put("ledger", led);
        r.put("expected", "전 회차 200 · 런 마감(COMPLETE) · GC 후 힙이 시작 수준으로 회수");
        r.put("verdict", ok
                ? "총 " + (totalRaw / MB) + "MB 를 " + rounds + "청크(같은 런 seq 1~" + rounds + ")로 흘렸고 " + totalRecords
                  + "건을 받았습니다. 수신 장부 " + (led == null ? "-" : led.get("state")) + ". GC 후 힙은 " + (heapStart / MB) + "MB → "
                  + (heapEnd / MB) + "MB (최대 " + (peak / MB) + "MB / 상한 " + (Runtime.getRuntime().maxMemory() / MB)
                  + "MB) — 회차가 쌓여도 우상향하지 않았습니다."
                : "실패 회차 " + failedRound + "건, GC 후 힙 " + (heapStart / MB) + "MB → " + (heapEnd / MB) + "MB.");
        emit(r);
    }

    // ── 한 발 ─────────────────────────────────────────────────────────────

    private static final class Shot {
        int status;
        long bytes;        // 네트워크로 나간 압축 바이트(송신측이 센다 — 수신기가 끊어도 정확)
        long sentBytes;    // 송신측이 쓴 압축 전 바이트
        long rawBytes;     // 수신기가 푼 바이트
        long records;
        long elapsedMs;
        int heapAfterMb;
        int heapPeakMb;
        String tail;
        String error;

        String ratio() {
            return bytes > 0 ? String.valueOf(rawBytes / Math.max(1, bytes)) : "-";
        }

        String note() {
            if (error != null) {
                return error;
            }
            return tail == null || tail.isBlank() ? "(응답 본문 없음)" : tail.strip();
        }
    }

    private Shot send(String runId, int seq, boolean last, long target, long chunkCnt, StreamingHttpClient.BodyWriter body) {
        Shot shot = new Shot();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        AtomicLong peak = new AtomicLong(mem.getHeapMemoryUsage().getUsed());
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                peak.accumulateAndGet(mem.getHeapMemoryUsage().getUsed(), Math::max);
                sleepQuietly(150);
            }
        }, "transfer-sim-heap");
        sampler.setDaemon(true);
        sampler.start();
        long t0 = System.currentTimeMillis();
        AtomicLong written = new AtomicLong();
        AtomicLong compressed = new AtomicLong();
        try {
            Map<String, String> h = connector.headers(runId, seq, last, target, (int) Math.min(Integer.MAX_VALUE, chunkCnt));
            // gzip 은 여기서 씌운다(클라이언트 gzip 과 같은 바이트) — 네트워크로 나간 압축 바이트를 세기 위해서다
            h.put("Content-Encoding", "gzip");
            StreamingHttpClient.Result r = StreamingHttpClient.post("127.0.0.1", port(), SELF_PATH, h, false, out -> {
                java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(new CountingOut(out, compressed), 64 * 1024);
                body.write(new CountingOut(gz, written));
                gz.finish();
            }, 3000, 300_000, 64 * 1024);
            shot.status = r.status();
            shot.tail = r.tail();
            if (r.writeError() != null && r.status() < 0) {
                shot.error = "본문 전송 중 연결이 끊겼습니다 — " + r.writeError();
            } else if (r.readError() != null && r.status() < 0) {
                shot.error = "응답 읽기 실패 — " + r.readError();
            }
            parseAck(shot);
        } catch (RuntimeException e) {
            shot.status = -1;
            shot.error = e.toString();
        } finally {
            sampling.set(false);
        }
        shot.sentBytes = written.get();
        shot.bytes = compressed.get();
        shot.elapsedMs = System.currentTimeMillis() - t0;
        shot.heapAfterMb = (int) (mem.getHeapMemoryUsage().getUsed() / MB);
        shot.heapPeakMb = (int) (peak.get() / MB);
        if (shot.error != null) {
            emit(event("log", "warn", "  " + shot.error));
        }
        return shot;
    }

    private void parseAck(Shot shot) {
        if (shot.tail == null || shot.tail.isBlank()) {
            return;
        }
        try {
            var n = objectMapper.readTree(shot.tail);
            shot.records = n.path("records").asLong(0);
            shot.rawBytes = n.path("rawBytes").asLong(0);
        } catch (IOException ignored) {
            // 꼬리가 JSON 이 아니다 — 상태 코드로만 판정
        }
    }

    /** 정상 레코드를 records 건 흘린다 — 레코드 하나 ≈ 50KB(문장 풀에서 골라 붙여 실제 전사처럼 압축된다). */
    private void writeBulk(OutputStream out, String runId, long records) throws IOException {
        Random rnd = new Random(42);
        out.write(("{\"header\":{\"runId\":\"" + runId + "\",\"dataTypeCd\":\"" + props.dataTypeCd()
                + "\",\"collectDtm\":\"" + LocalDateTime.now().withNano(0) + "+09:00\",\"setTypeCd\":\"" + props.setTypeCd()
                + "\"},\"payload\":[").getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(FILLER_BYTES + 512);
        for (long i = 0; i < records; i++) {
            sb.setLength(0);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"managementNo\":\"SIMLOAD").append(String.format("%08d", i))
                    .append("\",\"rawDataset\":{\"recFileId\":\"LOAD-").append(String.format("%08d", i))
                    .append("\",\"kind\":\"MEET\",\"charCount\":").append(FILLER_BYTES / 3)
                    .append(",\"transcript\":{\"language\":\"ko\",\"text\":\"");
            int start = sb.length();
            while (sb.length() - start < FILLER_BYTES / 3) {
                sb.append(SENTENCES[rnd.nextInt(SENTENCES.length)]).append(' ');
            }
            sb.append("\"}}}");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        out.write("]}".getBytes(StandardCharsets.UTF_8));
    }

    /** 헤더 구간 폭탄 — payload 여는 괄호 뒤에 공백 bytes. 문법상 끝까지 읽어야 하므로 상한에서 걸린다. */
    private void writeBomb(OutputStream out, String runId, long bytes) throws IOException {
        out.write(("{\"header\":{\"runId\":\"" + runId + "\"},\"payload\":[").getBytes(StandardCharsets.UTF_8));
        byte[] spaces = new byte[64 * 1024];
        java.util.Arrays.fill(spaces, (byte) ' ');
        for (long w = 0; w < bytes; w += spaces.length) {
            out.write(spaces, 0, (int) Math.min(spaces.length, bytes - w));
        }
        out.write("]}".getBytes(StandardCharsets.UTF_8));
    }

    private static long recordsFor(long bytes) {
        // 레코드 하나 ≈ (FILLER/3 글자 × 한글 3바이트) + 메타 ≈ FILLER_BYTES
        return Math.max(1, bytes / FILLER_BYTES);
    }

    private static final String[] SENTENCES = {
            "접견 내용은 가족 안부와 건강 이야기가 대부분이었다.", "다음 주에 다시 연락하기로 했다.",
            "변호인과 재판 일정에 대해 상의하였다.", "영치금 입금 여부를 확인하였다.", "운동 시간과 식사에 대해 말하였다.",
            "편지를 받았는지 물어보았다.", "아이들 학교 생활 이야기를 나누었다.", "작업장 배치에 대해 문의하였다.",
            "통화 중 특이 발언은 확인되지 않았다.", "출소 후 계획을 짧게 이야기하였다."
    };

    // ══════════════════════════════════════════════════════════════════════
    //  수신 헤더 시나리오 — 동기(빠르다)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 헤더 시나리오.
     *
     * @param n 1 강제 중단 · 재전송 / 2 청크 유실 타임아웃 / 3 중복 청크 멱등 / 4 헤더 누락 · 레코드 수 불일치
     */
    public Map<String, Object> headerScenario(int n) {
        if (running.get()) {
            throw new IllegalStateException("부하 시험이 진행 중입니다 — 끝난 뒤에 실행하세요");
        }
        List<String> lines = new ArrayList<>();
        String runId = simRunId("H" + n);
        lines.add("런 " + runId + " · 수신 " + selfEndpoint());
        Map<String, Object> m = new LinkedHashMap<>();
        boolean ok;
        switch (n) {
            case 1 -> {
                lines.add("── ① 강제 중단 · 같은 X-Run-Id 로 재전송");
                for (int seq : new int[]{1, 2}) {
                    lines.add("  seq " + seq + " → " + chunk(runId, seq, false, 6, 2, true).line());
                }
                lines.add("  ⏸ 여기서 중단 — 3번을 보내지 않았습니다");
                lines.add("  장부: " + ledgerMsg(runId));
                Hit r3 = chunk(runId, 3, true, 6, 2, true);
                lines.add("  ▶ 같은 런으로 3번(마지막) 재전송 → " + r3.line());
                Map<String, Object> led = receiver.ledger(runId);
                ok = led != null && Boolean.TRUE.equals(led.get("complete")) && !r3.duplicate;
                lines.add("  결과: " + ledgerMsg(runId));
                lines.add(ok ? "  ✔ 재전송이 정상 수신되어 마감됐습니다(중복 아님)." : "  ✘ 마감되지 않았습니다.");
            }
            case 2 -> {
                lines.add("── ② 청크 유실 타임아웃 (2번 미전송)");
                lines.add("  seq 1 → " + chunk(runId, 1, false, 6, 2, true).line());
                lines.add("  seq 3(last) → " + chunk(runId, 3, true, 6, 2, true).line());
                lines.add("  장부: " + ledgerMsg(runId));
                lines.add("  ⏱ 운영은 " + props.mockGapTimeoutSec() + "초 뒤 스케줄러가 판정합니다 — 시연에서는 즉시 돌립니다.");
                Map<String, Object> sw = receiver.sweep(0);
                lines.add("  스윕: " + sw.get("message"));
                Map<String, Object> led = receiver.ledger(runId);
                ok = led != null && "INGEST-GAP".equals(led.get("state"));
                lines.add(ok ? "  ✔ INGEST-GAP 으로 유실 판정됐습니다(빈 순번 " + led.get("missingSeqs") + ")."
                        : "  ✘ 판정되지 않았습니다.");
            }
            case 3 -> {
                lines.add("── ③ 중복 청크 — (X-Run-Id + X-Seq) 멱등");
                lines.add("  seq 1 → " + chunk(runId, 1, false, 4, 2, true).line());
                Hit dup = chunk(runId, 1, false, 4, 2, true);
                lines.add("  seq 1 다시 → " + dup.line());
                lines.add("  seq 2(last) → " + chunk(runId, 2, true, 4, 2, true).line());
                Map<String, Object> led = receiver.ledger(runId);
                ok = dup.status == 200 && dup.duplicate && led != null && Boolean.TRUE.equals(led.get("complete"))
                        && Long.valueOf(4).equals(led.get("records"));
                lines.add("  결과: " + ledgerMsg(runId));
                lines.add(ok ? "  ✔ 중복은 200 + duplicate=true 로 받고 건수에 더하지 않았습니다." : "  ✘ 멱등 처리가 기대와 다릅니다.");
            }
            case 4 -> {
                lines.add("── ④ 헤더 누락 · 레코드 수 불일치 — 400");
                Hit noRun = chunk(null, 1, true, 2, 2, true);
                lines.add("  X-Run-Id 없이 → " + noRun.line());
                Hit mismatch = chunk(runId, 1, true, 2, 5, true);
                lines.add("  X-Chunk-Cnt 5 인데 payload 2건 → " + mismatch.line());
                ok = noRun.status == 400 && mismatch.status == 400;
                lines.add(ok ? "  ✔ 둘 다 400 — 수신단은 헤더로 순번·유실을, 본문으로 건수를 검증합니다." : "  ✘ 거절되지 않았습니다.");
            }
            default -> throw new IllegalArgumentException("헤더 시나리오는 1~4");
        }
        m.put("scenario", n);
        m.put("runId", runId);
        m.put("ok", ok);
        m.put("lines", lines);
        m.put("ledger", receiver.ledger(runId));
        return m;
    }

    private record Hit(int status, boolean duplicate, String message) {
        String line() {
            return "HTTP " + status + (duplicate ? " · duplicate=true" : "") + (message == null ? "" : " · " + message);
        }
    }

    /** 레코드 2건짜리 청크 하나 — 실제 소켓으로. runId 가 null 이면 X-Run-Id 를 뺀다. */
    private Hit chunk(String runId, int seq, boolean last, int target, int chunkCnt, boolean gzip) {
        Map<String, String> h = connector.headers(runId == null ? "x" : runId, seq, last, target, chunkCnt);
        if (runId == null) {
            h.remove("X-Run-Id");
        }
        String body = "{\"header\":{\"runId\":\"" + (runId == null ? "" : runId) + "\",\"dataTypeCd\":\"" + props.dataTypeCd()
                + "\",\"setTypeCd\":\"" + props.setTypeCd() + "\"},\"payload\":["
                + "{\"managementNo\":\"SIMHDR0" + seq + "1\",\"rawDataset\":{\"recFileId\":\"HDR-" + seq + "-1\",\"kind\":\"MEET\"}},"
                + "{\"managementNo\":\"SIMHDR0" + seq + "2\",\"rawDataset\":{\"recFileId\":\"HDR-" + seq + "-2\",\"kind\":\"PHONE\"}}]}";
        StreamingHttpClient.Result r = StreamingHttpClient.post("127.0.0.1", port(), SELF_PATH, h, gzip,
                out -> out.write(body.getBytes(StandardCharsets.UTF_8)), 3000, 30_000, 16 * 1024);
        boolean dup = false;
        String msg = null;
        try {
            var n = objectMapper.readTree(r.tail());
            dup = n.path("duplicate").asBoolean(false);
            msg = n.hasNonNull("message") ? n.get("message").asText() : (n.hasNonNull("state") ? n.get("state").asText() : null);
        } catch (IOException | RuntimeException ignored) {
            msg = r.readError() != null ? r.readError() : r.writeError();
        }
        return new Hit(r.status(), dup, msg);
    }

    private String ledgerMsg(String runId) {
        Map<String, Object> led = receiver.ledger(runId);
        return led == null ? "(장부 없음)" : "받은 순번 " + led.get("receivedSeqs") + " · " + led.get("message");
    }

    // ── 공통 ─────────────────────────────────────────────────────────────

    /** 시험 런 ID — TST 가 들어가 시뮬레이터 초기화가 함께 지운다. */
    static String simRunId(String tag) {
        LocalDateTime now = LocalDateTime.now();
        return now.format(DateTimeFormatter.BASIC_ISO_DATE) + "TST-ZSIM-" + tag + "-" + now.format(DateTimeFormatter.ofPattern("HHmmssSSS"));
    }

    private Map<String, Object> result(String scenario, String title, boolean ok, Shot shot) {
        Map<String, Object> r = event("result", ok ? "ok" : "fail", title);
        r.put("scenario", scenario);
        r.put("title", title);
        r.put("ok", ok);
        r.put("status", shot.status);
        r.put("sentMb", round1(shot.bytes));
        r.put("rawMb", round1(shot.rawBytes > 0 ? shot.rawBytes : shot.sentBytes));
        r.put("ratio", shot.ratio());
        r.put("records", shot.records);
        r.put("elapsedMs", shot.elapsedMs);
        r.put("heapAfterMb", shot.heapAfterMb);
        r.put("heapPeakMb", shot.heapPeakMb);
        r.put("maxHeapMb", Runtime.getRuntime().maxMemory() / MB);
        return r;
    }

    private Map<String, Object> event(String type, String level, String msg) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", type);
        n.put("level", level);
        n.put("msg", msg);
        n.put("at", LocalDateTime.now().withNano(0).toString());
        return n;
    }

    private void emit(Map<String, Object> e) {
        synchronized (events) {
            if (events.size() >= MAX_EVENTS) {
                events.remove(0);
            }
            events.add(e);
        }
    }

    private static double round1(long bytes) {
        return Math.round(bytes * 10.0 / MB) / 10.0;
    }

    private static String mb(long bytes) {
        return round1(bytes) + "MB";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 송신측이 쓴(압축 전) 바이트를 센다. */
    private static final class CountingOut extends OutputStream {
        private final OutputStream out;
        private final AtomicLong n;

        CountingOut(OutputStream out, AtomicLong n) {
            this.out = out;
            this.n = n;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            n.incrementAndGet();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            n.addAndGet(len);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }
}
