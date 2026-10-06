package egovframework.unstructured.collector.common.transfer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/**
 * <b>에이전트 커넥터 bypass 수신기 흉내(MOCK)</b> — 커넥터 없이도(로컬 · 시연) 전송 규약을 끝까지 검증하는 수신단.
 *
 * <p>에이전트 커넥터 {@code LearnTransferController}(학습셋 수신 → 제논 bypass)의 동작을 그대로 옮기고, 커넥터 시뮬레이터
 * '수신 헤더 6종 · 네트워크 예외 시나리오' 의 장부(유실 판정)를 붙였다. 실제 bypass API 가 하는 검사는 헤더 누락(400) · 멱등(200 duplicate)
 * 둘이다 — 본문을 풀지도 저장하지도 않는다. 아래의 해제 상한 · 본문 대조 · 마감 · 유실 판정은 송신단을 더 엄하게 확인하려고 이 흉내에만 있다.</p>
 * <ul>
 *   <li><b>멱등</b> — (X-Run-Id + X-Seq) 이미 받은 청크는 본문을 소비만 하고 200 + {@code duplicate:true}</li>
 *   <li><b>헤더 누락</b> — {@code X-Run-Id} · {@code X-Seq} 가 없으면 400</li>
 *   <li><b>해제 상한</b> — gzip 을 풀며 {@code agent-connector.mock-max-decompressed-bytes} 를 넘으면 413(압축 폭탄 방어)</li>
 *   <li><b>본문 검증</b> — JSON 을 흘려 읽으며(통째로 올리지 않는다) {@code header.runId} = X-Run-Id ·
 *       {@code payload} 건수 = X-Chunk-Cnt(이 청크의 레코드 수 — data-collector 규약) 를 대조, 어긋나면 400</li>
 *   <li><b>마감</b> — {@code X-Is-Last} 를 받았고 1 ~ 마지막 순번이 다 있고 레코드 합이 {@code X-Target-Cnt} 와 같으면 COMPLETE</li>
 *   <li><b>유실 판정</b> — 빈 순번이 있는 채로 {@code mock-gap-timeout-sec} 동안 조용하면 INGEST-GAP(스케줄러 · 시연은 즉시 스윕)</li>
 *   <li><b>장애 흉내</b> — {@link #setFault}: DOWN(전부 503) · FAIL_FROM_SEQ(그 순번부터 503). 전송 재처리 시나리오가 쓴다</li>
 * </ul>
 *
 * <p>MOCK 모드에서는 {@link AgentConnectorClient} 가 네트워크 없이 이 객체를 직접 부르고(본문 바이트 · 헤더는 REST 와 같다),
 * dev · local 에서는 {@code POST /api/v1/mock/agent-connector/transfer} 로도 열려 있어 REST 모드 · curl · 전송 시뮬레이션이 같은 수신단을 쓴다.
 * 본문은 저장하지 않는다 — 장부(순번 · 건수 · 바이트)와 앞 몇 건의 요약만 남는다.</p>
 */
@Log4j2
@Component
public class AgentConnectorMockReceiver {

    /** 장애 흉내. */
    public enum FaultMode { UP, DOWN, FAIL_FROM_SEQ }

    /** 수신 결과 — HTTP 상태와 응답 본문. */
    public record Ack(int status, Map<String, Object> body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** 장부에 남기는 레코드 요약 수(런마다). */
    private static final int SAMPLE = 3;
    /** 장부에 남기는 런 수 — 넘으면 오래된 것부터 버린다. */
    private static final int MAX_RUNS = 300;

    private final AgentConnectorProperties props;
    private final ObjectMapper objectMapper;
    private final JsonFactory jsonFactory;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    private volatile FaultMode faultMode = FaultMode.UP;
    private volatile int failFromSeq = 0;
    private volatile String faultSetAt;
    private final AtomicLong faultRejected = new AtomicLong();
    private final AtomicLong totalChunks = new AtomicLong();

    public AgentConnectorMockReceiver(AgentConnectorProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.jsonFactory = objectMapper.getFactory();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  수신
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 청크 하나를 받는다.
     *
     * @param header  요청 헤더 조회(대소문자 무시) — 없으면 null
     * @param body    요청 본문(네트워크 바이트 그대로 — gzip 이면 압축된 채)
     * @param gzipped {@code Content-Encoding: gzip}
     */
    public Ack receive(java.util.function.Function<String, String> header, InputStream body, boolean gzipped) {
        String runId = trim(header.apply("X-Run-Id"));
        String seqText = trim(header.apply("X-Seq"));
        if (runId == null || seqText == null) {
            drainQuietly(body);
            return reject(400, "MISSING_HEADER", "필수 헤더 누락 — " + (runId == null ? "X-Run-Id " : "")
                    + (seqText == null ? "X-Seq" : "") + " (수신단은 순번 · 중복 · 유실을 헤더로 판정한다)");
        }
        int seq;
        try {
            seq = Integer.parseInt(seqText);
        } catch (NumberFormatException e) {
            drainQuietly(body);
            return reject(400, "BAD_HEADER", "X-Seq 가 숫자가 아니다: " + seqText);
        }
        if (seq < 1) {
            drainQuietly(body);
            return reject(400, "BAD_HEADER", "X-Seq 는 1부터: " + seq);
        }
        long targetCnt = parseLong(header.apply("X-Target-Cnt"), -1L);
        long chunkCnt = parseLong(header.apply("X-Chunk-Cnt"), -1L);
        boolean last = Boolean.parseBoolean(trim(header.apply("X-Is-Last")));
        String dataType = trim(header.apply("X-Data-Type"));

        // 장애 흉내 — 실제 수신 서버가 내려간 것처럼 본문을 읽기 전에 거절한다(장부에 남기지 않는다)
        if (faultMode == FaultMode.DOWN || (faultMode == FaultMode.FAIL_FROM_SEQ && seq >= failFromSeq)) {
            drainQuietly(body);
            faultRejected.incrementAndGet();
            Run r = run(runId);
            synchronized (r) {
                r.rejected.add(seq);
                r.lastSeenAt = LocalDateTime.now().withNano(0);
            }
            log.warn("[TransferMock] 장애 흉내 503 — runId={} seq={} ({})", runId, seq, faultLabel());
            return reject(503, "UNAVAILABLE", "(MOCK) 에이전트 커넥터 수신 장애 — " + faultLabel() + " · seq " + seq);
        }

        // 중복 확인은 있는 장부로만 — 거절(400 · 413)로 끝날 요청이 빈 장부를 만들지 않게 장부는 받아들일 때 연다
        Run seen = runs.get(runId);
        if (seen != null) {
            synchronized (seen) {
                if (seen.seqs.containsKey(seq)) {
                    long bytes = drainQuietly(body);
                    seen.duplicates++;
                    seen.lastSeenAt = LocalDateTime.now().withNano(0);
                    log.info("[TransferMock] 중복 청크(멱등) runId={} seq={} bytes={}", runId, seq, bytes);
                    return new Ack(200, ackBody(seen, seq, last, true, bytes, 0L, 0));
                }
            }
        }

        // 본문 — 흘려 읽는다. 해제 상한을 넘으면 413
        CountingInputStream wire = new CountingInputStream(body);
        Parsed p;
        try {
            InputStream plain = gzipped ? new GZIPInputStream(wire, 64 * 1024) : wire;
            LimitedInputStream limited = new LimitedInputStream(plain, props.mockMaxDecompressedBytes());
            p = parse(limited);
            p.rawBytes = limited.count;
        } catch (IOException | RuntimeException e) {
            if (!limitExceeded(e)) {
                drainQuietly(body);
                return reject(400, "BAD_BODY", "본문을 읽지 못함 — " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            log.warn("[TransferMock] 해제 상한 초과 413 — runId={} seq={} 상한 {}MB", runId, seq,
                    props.mockMaxDecompressedBytes() / (1024 * 1024));
            return reject(413, "PAYLOAD_TOO_LARGE", "해제 상한 " + props.mockMaxDecompressedBytes() / (1024 * 1024)
                    + "MB 초과 — 청크를 더 잘게 나눠 보내세요(권장 50MB)");
        }
        if (p.headerRunId != null && !p.headerRunId.equals(runId)) {
            return reject(400, "RUN_ID_MISMATCH", "header.runId(" + p.headerRunId + ") ≠ X-Run-Id(" + runId + ")");
        }
        if (chunkCnt >= 0 && chunkCnt != p.records) {
            return reject(400, "CHUNK_CNT_MISMATCH", "X-Chunk-Cnt " + chunkCnt + " ≠ payload " + p.records + "건");
        }
        if (p.missingManagementNo > 0) {
            return reject(400, "BAD_RECORD", "managementNo 없는 레코드 " + p.missingManagementNo + "건");
        }

        totalChunks.incrementAndGet();
        Run r = run(runId);
        synchronized (r) {
            boolean duplicate = r.seqs.putIfAbsent(seq, p.records) != null;
            if (!duplicate) {
                r.records += p.records;
                r.bytes += wire.count;
                r.rawBytes += p.rawBytes;
                r.rejected.remove(seq);
                if (targetCnt >= 0) {
                    r.targetCnt = targetCnt;
                }
                if (last) {
                    r.lastSeq = seq;
                }
                if (dataType != null) {
                    r.dataType = dataType;
                }
                if (p.setTypeCd != null) {
                    r.setTypeCd = p.setTypeCd;
                }
                for (Map<String, Object> s : p.sample) {
                    if (r.sample.size() < SAMPLE) {
                        r.sample.add(s);
                    }
                }
                r.lastSeenAt = LocalDateTime.now().withNano(0);
                r.refreshState();
            } else {
                r.duplicates++;
            }
            log.info("[TransferMock] 청크 수신 runId={} seq={} last={} 레코드 {} · 압축 {}B → 해제 {}B · {}", runId, seq, last,
                    p.records, wire.count, p.rawBytes, r.state);
            return new Ack(200, ackBody(r, seq, last, duplicate, wire.count, p.rawBytes, p.records));
        }
    }

    private Map<String, Object> ackBody(Run r, int seq, boolean last, boolean duplicate, long bytes, long rawBytes,
                                        int records) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "SUCCESS");
        m.put("runId", r.runId);
        m.put("seq", seq);
        m.put("duplicate", duplicate);       // 멱등 — 이미 받은 청크(재전송 무해)
        m.put("receivedChunks", r.seqs.size());
        m.put("receivedRecords", r.records);
        m.put("last", last);
        m.put("records", records);
        m.put("bytes", bytes);
        m.put("rawBytes", rawBytes);
        m.put("state", r.state);
        m.put("missingSeqs", r.missingSeqs());
        return m;
    }

    private Ack reject(int status, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        return new Ack(status, m);
    }

    // ── 본문 해석(흘려 읽기) ──────────────────────────────────────────────

    private static final class Parsed {
        String headerRunId;
        String setTypeCd;
        int records;
        int missingManagementNo;
        long rawBytes;
        final List<Map<String, Object>> sample = new ArrayList<>();
    }

    /**
     * {@code {header:{...}, payload:[{managementNo, rawDataset}, ...]}} 를 흘려 읽는다 — payload 원소 하나씩만 메모리에 올린다.
     */
    private Parsed parse(InputStream in) throws IOException {
        Parsed p = new Parsed();
        try (JsonParser jp = jsonFactory.createParser(in)) {
            if (jp.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("최상위가 객체가 아니다 — {header, payload}");
            }
            boolean sawPayload = false;
            while (jp.nextToken() == JsonToken.FIELD_NAME) {
                String name = jp.currentName();
                JsonToken t = jp.nextToken();
                if ("header".equals(name) && t == JsonToken.START_OBJECT) {
                    JsonNode h = objectMapper.readTree(jp);
                    p.headerRunId = h.hasNonNull("runId") ? h.get("runId").asText() : null;
                    p.setTypeCd = h.hasNonNull("setTypeCd") ? h.get("setTypeCd").asText() : null;
                } else if ("payload".equals(name) && t == JsonToken.START_ARRAY) {
                    sawPayload = true;
                    while (jp.nextToken() == JsonToken.START_OBJECT) {
                        JsonNode one = objectMapper.readTree(jp);
                        p.records++;
                        if (!one.hasNonNull("managementNo")) {
                            p.missingManagementNo++;
                        }
                        if (p.sample.size() < SAMPLE) {
                            p.sample.add(summary(one));
                        }
                    }
                } else {
                    jp.skipChildren();
                }
            }
            if (!sawPayload) {
                throw new IOException("payload 배열이 없다");
            }
        }
        return p;
    }

    /** 장부에 남길 요약 — 관리번호는 앞 3자만(화면 · 로그에 교정번호를 통째로 남기지 않는다). */
    private static Map<String, Object> summary(JsonNode one) {
        Map<String, Object> s = new LinkedHashMap<>();
        String mn = one.path("managementNo").asText("");
        s.put("managementNo", mn.length() <= 3 ? mn : mn.substring(0, 3) + "***");
        JsonNode raw = one.path("rawDataset");
        s.put("recFileId", raw.path("recFileId").asText(null));
        s.put("kind", raw.path("kind").asText(null));
        s.put("inmatePid", raw.path("inmatePid").asText(null));
        s.put("charCount", raw.path("charCount").isMissingNode() ? null : raw.path("charCount").asInt());
        return s;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  장부
    // ══════════════════════════════════════════════════════════════════════

    /** 런 하나의 수신 장부. */
    static final class Run {
        final String runId;
        final String firstSeenAt = LocalDateTime.now().withNano(0).toString();
        final TreeMap<Integer, Integer> seqs = new TreeMap<>();   // 순번 → 레코드 수
        final TreeSet<Integer> rejected = new TreeSet<>();          // 장애 흉내로 거절한 순번(아직 못 받은 것)
        long records;
        long bytes;
        long rawBytes;
        long targetCnt = -1;
        int lastSeq = -1;
        int duplicates;
        String dataType;
        String setTypeCd;
        String state = "RECEIVING";
        String gapAt;
        LocalDateTime lastSeenAt = LocalDateTime.now().withNano(0);
        final List<Map<String, Object>> sample = new ArrayList<>();

        Run(String runId) {
            this.runId = runId;
        }

        List<Integer> missingSeqs() {
            int upTo = lastSeq > 0 ? lastSeq : (seqs.isEmpty() ? 0 : seqs.lastKey());
            List<Integer> miss = new ArrayList<>();
            for (int i = 1; i <= upTo; i++) {
                if (!seqs.containsKey(i)) {
                    miss.add(i);
                }
            }
            return miss;
        }

        void refreshState() {
            if ("INGEST-GAP".equals(state)) {
                // 유실 판정 뒤에 빈 순번이 채워지면 다시 본다(재전송 복구)
                state = "RECEIVING";
            }
            boolean full = lastSeq > 0 && missingSeqs().isEmpty();
            if (full && (targetCnt < 0 || records == targetCnt)) {
                state = "COMPLETE";
            } else if (full) {
                state = "COUNT_MISMATCH";
            } else if (!missingSeqs().isEmpty()) {
                state = "GAP_SUSPECT";
            } else {
                state = "RECEIVING";
            }
        }

        Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", runId);
            m.put("state", state);
            m.put("complete", "COMPLETE".equals(state));
            m.put("receivedSeqs", new ArrayList<>(seqs.keySet()));
            m.put("missingSeqs", missingSeqs());
            m.put("rejectedSeqs", new ArrayList<>(rejected));
            m.put("lastSeq", lastSeq);
            m.put("records", records);
            m.put("targetCnt", targetCnt);
            m.put("bytes", bytes);
            m.put("rawBytes", rawBytes);
            m.put("duplicates", duplicates);
            m.put("dataType", dataType);
            m.put("setTypeCd", setTypeCd);
            m.put("firstSeenAt", firstSeenAt);
            m.put("lastSeenAt", lastSeenAt.toString());
            if (gapAt != null) {
                m.put("gapAt", gapAt);
            }
            m.put("sample", sample);
            m.put("message", message());
            return m;
        }

        String message() {
            return switch (state) {
                case "COMPLETE" -> "마감 — 청크 " + seqs.size() + "개 · 레코드 " + records + "건"
                        + (targetCnt >= 0 ? " (대상 " + targetCnt + "건 일치)" : "");
                case "COUNT_MISMATCH" -> "순번은 다 받았으나 레코드 합 " + records + "건 ≠ X-Target-Cnt " + targetCnt;
                case "GAP_SUSPECT" -> "빈 순번 " + missingSeqs() + " — 재전송을 기다린다(유실 판정 전)";
                case "INGEST-GAP" -> "유실 판정(INGEST-GAP) — 빈 순번 " + missingSeqs() + " · " + gapAt;
                default -> "수신 중 — 받은 순번 " + seqs.keySet() + (lastSeq > 0 ? "" : " · 마지막 청크 아직");
            };
        }
    }

    private Run run(String runId) {
        Run r = runs.computeIfAbsent(runId, Run::new);
        if (runs.size() > MAX_RUNS) {
            runs.values().stream().min(Comparator.comparing(x -> x.lastSeenAt))
                    .filter(x -> x != r).ifPresent(x -> runs.remove(x.runId));
        }
        return r;
    }

    /** 런 하나의 장부 — 없으면 null. */
    public Map<String, Object> ledger(String runId) {
        Run r = runId == null ? null : runs.get(runId);
        if (r == null) {
            return null;
        }
        synchronized (r) {
            return r.view();
        }
    }

    /** 최근 런 장부(최근 것부터). */
    public List<Map<String, Object>> ledgers(int limit) {
        List<Run> all = new ArrayList<>(runs.values());
        all.sort(Comparator.comparing((Run x) -> x.lastSeenAt).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Run r : all.subList(0, Math.min(Math.max(1, limit), all.size()))) {
            synchronized (r) {
                out.add(r.view());
            }
        }
        return out;
    }

    /**
     * 유실 판정 — 빈 순번이 있거나 마지막 청크가 오지 않은 채 {@code idleSec} 이상 조용한 런을 INGEST-GAP 으로 닫는다.
     * 운영 흉내는 {@code mock-gap-timeout-sec} 주기 스케줄, 시연은 {@code idleSec=0} 으로 즉시.
     */
    public Map<String, Object> sweep(int idleSec) {
        LocalDateTime cut = LocalDateTime.now().minusSeconds(Math.max(0, idleSec));
        List<String> swept = new ArrayList<>();
        for (Run r : runs.values()) {
            synchronized (r) {
                boolean open = !"COMPLETE".equals(r.state) && !"INGEST-GAP".equals(r.state);
                boolean incomplete = !r.missingSeqs().isEmpty() || (r.lastSeq < 0 && !r.seqs.isEmpty());
                if (open && incomplete && !r.lastSeenAt.isAfter(cut)) {
                    r.state = "INGEST-GAP";
                    r.gapAt = LocalDateTime.now().withNano(0).toString();
                    swept.add(r.runId);
                    log.warn("[TransferMock] 유실 판정 INGEST-GAP — runId={} 빈 순번 {} · 마지막 {}", r.runId, r.missingSeqs(),
                            r.lastSeq < 0 ? "미수신" : r.lastSeq);
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idleSec", idleSec);
        m.put("sweptCount", swept.size());
        m.put("swept", swept);
        m.put("message", swept.isEmpty() ? "유실 판정 대상 없음" : "INGEST-GAP " + swept.size() + "건 — " + swept);
        return m;
    }

    @Scheduled(fixedDelayString = "${agent-connector.mock-sweep-ms:30000}", initialDelayString = "${agent-connector.mock-sweep-ms:30000}")
    void scheduledSweep() {
        if (props.mode() == AgentConnectorProperties.Mode.MOCK && !runs.isEmpty()) {
            sweep(props.mockGapTimeoutSec());
        }
    }

    /** 장부를 비운다 — runId 접두(예: TST)만 지우거나, null 이면 전부. */
    public int clear(String containing) {
        int before = runs.size();
        if (containing == null) {
            runs.clear();
        } else {
            runs.keySet().removeIf(k -> k.toUpperCase().contains(containing.toUpperCase()));
        }
        return before - runs.size();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  장애 흉내
    // ══════════════════════════════════════════════════════════════════════

    /** 장애를 건다 — UP(정상화) · DOWN(전부 503) · FAIL_FROM_SEQ(그 순번부터 503). */
    public synchronized Map<String, Object> setFault(FaultMode mode, int fromSeq) {
        this.faultMode = mode == null ? FaultMode.UP : mode;
        this.failFromSeq = this.faultMode == FaultMode.FAIL_FROM_SEQ ? Math.max(1, fromSeq) : 0;
        this.faultSetAt = LocalDateTime.now().withNano(0).toString();
        log.warn("[TransferMock] 장애 설정 — {}", faultLabel());
        return fault();
    }

    public Map<String, Object> fault() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", faultMode.name());
        m.put("failFromSeq", failFromSeq);
        m.put("label", faultLabel());
        m.put("setAt", faultSetAt);
        m.put("rejected", faultRejected.get());
        return m;
    }

    public boolean isUp() {
        return faultMode == FaultMode.UP;
    }

    private String faultLabel() {
        return switch (faultMode) {
            case UP -> "정상(200)";
            case DOWN -> "다운 — 모든 청크 503";
            case FAIL_FROM_SEQ -> failFromSeq + "번 청크부터 503";
        };
    }

    /** 상태 — 화면 · 헬스용. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fault", fault());
        m.put("runs", runs.size());
        m.put("chunks", totalChunks.get());
        m.put("maxDecompressedMb", props.mockMaxDecompressedBytes() / (1024 * 1024));
        m.put("gapTimeoutSec", props.mockGapTimeoutSec());
        return m;
    }

    // ── 스트림 도우미 ─────────────────────────────────────────────────────

    private static long drainQuietly(InputStream in) {
        if (in == null) {
            return 0L;
        }
        byte[] buf = new byte[8192];
        long total = 0;
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
            }
        } catch (IOException ignored) {
            // 상대가 끊었다 — 장부와는 무관
        }
        return total;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null || s.isBlank() ? def : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 네트워크로 들어온(압축된) 바이트를 센다. */
    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }

    /** 상한 초과가 원인인가 — 파서가 감싸 올려도 원인 사슬에서 찾는다. */
    private static boolean limitExceeded(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof LimitExceeded) {
                return true;
            }
        }
        return false;
    }

    /** 해제 상한을 넘는 순간 멈춘다 — 끝까지 풀지 않는다(폭탄 방어). */
    private static final class LimitExceeded extends IOException {
        LimitExceeded() {
            super("해제 상한 초과");
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        long count;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && ++count > limit) {
                throw new LimitExceeded();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
                if (count > limit) {
                    throw new LimitExceeded();
                }
            }
            return n;
        }
    }
}
