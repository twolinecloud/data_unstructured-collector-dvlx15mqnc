package egovframework.unstructured.collector.common.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/**
 * 에이전트 커넥터 전송 — 파이프라인 3단계 {@code SEND}. <b>data-collector 와 같은 전송 양식 · 같은 수신 API</b>.
 *
 * <p><b>제논으로 가는 길은 늘 에이전트 커넥터를 거친다</b>(2026-10-06 PL 확인). 다만 비식별 파이프라인
 * ({@code /api/v1/deid/pipeline} — gzip 을 풀어 비식별화)이 아니라, 비식별 없이 받은 그대로 제논으로 넘기는
 * <b>bypass API</b>({@code POST /api/v1/learn/transfer} — 커넥터 {@code LearnTransferController})를 부른다.
 * data-collector {@code FeatureSetTransferSink} 가 개발계에서 부르는 것과 같은 주소 · 같은 양식이다
 * ({@code COLLECTOR_TRANSFER_BASE_URL=http://agent-connector-dp8qbi7xqh:8080}).
 * 10-05 에 잠깐 '커넥터 없이 제논 직접' 으로 적었던 것을 되돌렸다 — 양식은 그때부터 이 bypass API 규약 그대로였다.</p>
 *
 * <h3>요청 하나 = 청크 하나</h3>
 * <pre>
 * POST {base-url}{transfer-path}            (기본 /api/v1/learn/transfer)
 * Content-Type: application/json;charset=UTF-8
 * Content-Encoding: gzip                     (agent-connector.gzip)
 * Transfer-Encoding: chunked
 * X-Run-Id: 20261005UNS001                   전송 런 ID = 실행 ID(재처리 이어달리기면 원배치 ID)
 * X-Data-Type: UNSTRUCTURED
 * X-Target-Cnt: 6                            이 런의 전체 레코드 수
 * X-Chunk-Cnt: 2                             이 청크의 레코드 수
 * X-Seq: 1                                   순번(1부터 오름차순)
 * X-Is-Last: false                           마지막 청크만 true
 *
 * {"header":{"runId":"20261005UNS001","dataTypeCd":"UNSTRUCTURED","collectDtm":"2026-10-05T10:00:00+09:00","setTypeCd":"VOICE"},
 *  "payload":[{"managementNo":"교정번호","rawDataset":{"recFileId":"…","kind":"MEET","inmatePid":"…","transcript":{…}}}, …]}
 * </pre>
 *
 * <ul>
 *   <li><b>성공 = HTTP 2xx</b>(응답 꼬리에 {@code "aborted"} 가 있으면 실패 — data-collector 와 같다). 중복 청크는 수신단이
 *       (X-Run-Id + X-Seq) 멱등으로 200 + {@code duplicate:true} — 보낸 것으로 친다.</li>
 *   <li><b>순서대로 · 실패하면 멈춘다</b> — 한 청크가 실패하면 뒤 청크는 보내지 않는다(수신단이 오름차순 도착을 전제로 유실을 판정).
 *       실패 청크와 그 뒤 청크의 레코드는 그 건만 SEND 실패로 남고, 보존된 전사({@code stt_temp})로 {@code FROM_SEND} 재처리가
 *       <b>원배치 런을 이어 받아 다음 순번부터</b> 보낸다({@link TransferRuns}).</li>
 *   <li><b>전이중</b> — {@link StreamingHttpClient}(data-collector 원본)로 본문을 쓰는 동안 응답을 동시에 읽는다.</li>
 *   <li><b>MOCK</b>(개발계 기본) — 네트워크 없이 {@link AgentConnectorMockReceiver} 를 직접 부른다. 본문 바이트 · 헤더는 REST 와 같다.</li>
 * </ul>
 *
 * <p>배치는 건마다 레코드를 {@link Session#stage 쌓고} 끝에 {@link Session#flush 한 번에} 청크로 나눠 보낸다 — 대상 건수
 * ({@code X-Target-Cnt})와 마지막 청크를 정확히 알기 위해서다(data-collector 도 레코드를 다 모은 뒤 청크로 나눈다).
 * 수신증은 최근 {@code keep-receipts} 개를 메모리에 남긴다 — 시뮬레이터 검증 화면의 근거다.</p>
 */
@Log4j2
@Component
public class AgentConnectorClient {

    /** 응답 꼬리(요약 판독용) — 작은 Ack 는 통째로 담긴다. data-collector RESP_TAIL_BYTES 와 같다. */
    private static final int RESP_TAIL_BYTES = 64 * 1024;
    /** 청크 레코드 수 상한 — 설정 · 시뮬레이터 덮어쓰기 모두 이 안에서. */
    private static final int MAX_CHUNK_RECORDS = 10_000;

    /** 수신증 — 레코드 1건이 어느 런 · 어느 순번으로 받아들여졌는가. */
    public record Receipt(String code, String execId, String type, String inmateNo, String fileName,
                          long fileSizeBytes, String receivedAt, String mode, String endpoint,
                          Map<String, Object> metadata, String preview, String runId, int seq) {

        /** T4 · 배치 결과에 남길 위치 표기 — 디스크 경로 대신 "어디로 · 어느 런 · 몇 번 청크로 보냈는가". */
        public String location() {
            return "agent-connector:" + ("MOCK".equals(mode) ? "mock" : endpoint) + "/" + runId + "#" + seq + "/" + fileName;
        }
    }

    /**
     * 전송 레코드 1건 — payload 원소 {@code {managementNo, rawDataset}}.
     *
     * @param type         VOICE
     * @param execId       이 레코드를 만든 실행
     * @param key          레코드 키(접견 TARE_FILE_NO · 전화 VRFC_ESTL_ID) — 키 표식 장애 · 수신증 대조
     * @param managementNo 관리번호 — data-collector 예측과 같은 교정번호(CORR_NO). 화면 · 로그에는 남기지 않는다
     * @param fileName     수신증 표기용 이름({@code {건ID}.json})
     * @param rawDataset   전송 대상 JSON(처리 메타 + 전사)
     * @param metadata     수신증에 남길 요약(kind · 키 · 글자 수 · 엔진)
     * @param preview      전사 미리보기(앞 200자) — 시뮬레이터 검증 화면용
     */
    public record Record(String type, String execId, String key, String managementNo, String fileName,
                         ObjectNode rawDataset, Map<String, Object> metadata, String preview) {}

    /** 에이전트 커넥터 전송 실패 — 그 건(또는 그 청크 · 뒤 청크의 건)만 SEND 실패다. */
    public static class TransferSendException extends RuntimeException {
        public TransferSendException(String message) {
            super(message);
        }

        public TransferSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 청크 하나의 결과.
     *
     * @param result SENT · DUPLICATE(멱등 — 이미 받은 순번) · FAILED · NOT_SENT(앞 청크 실패로 보내지 않음)
     */
    public record Chunk(int seq, int records, boolean last, String result, int status, String message,
                        long bytes, long elapsedMs, List<String> keys) {}

    /** 한 번의 전송(flush) 결과. */
    public record Report(String runId, String execId, boolean continued, int fromSeq, long targetCnt,
                         List<Chunk> chunks, int delivered, int failed, boolean closed, String mode, String endpoint) {

        public static Report empty(String execId, String mode, String endpoint) {
            return new Report(null, execId, false, 0, 0, List.of(), 0, 0, false, mode, endpoint);
        }

        public boolean isEmpty() {
            return chunks.isEmpty();
        }

        /** 화면 · 응답용. */
        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", runId);
            m.put("execId", execId);
            m.put("continued", continued);
            m.put("fromSeq", fromSeq);
            m.put("targetCnt", targetCnt);
            m.put("chunkCount", chunks.size());
            m.put("delivered", delivered);
            m.put("failed", failed);
            m.put("closed", closed);
            m.put("mode", mode);
            m.put("endpoint", endpoint);
            m.put("chunks", chunks);
            return m;
        }
    }

    private final AgentConnectorProperties props;
    private final ObjectMapper objectMapper;
    private final AgentConnectorMockReceiver receiver;
    private final TransferRuns runs;
    /** 더미 키 표식(SF) 장애 — 이 레코드를 한 번만 거부한다(그 건만 실패 · 청크는 멈추지 않는다). */
    private final egovframework.unstructured.collector.mock.ScenarioFaults scenarioFaults;
    private final Clock clock;
    private final Deque<Receipt> receipts = new ConcurrentLinkedDeque<>();
    /** 시뮬레이터가 덮어쓴 청크 레코드 수(전송 재처리 시나리오 — 6건을 2건씩 3청크로). null 이면 설정값. */
    private volatile Integer chunkRecordsOverride;
    /** 마지막 전송 결과 — 화면용. */
    private volatile Report lastReport;

    @Autowired
    public AgentConnectorClient(AgentConnectorProperties props, ObjectMapper objectMapper, AgentConnectorMockReceiver receiver,
                       TransferRuns runs, egovframework.unstructured.collector.mock.ScenarioFaults scenarioFaults) {
        this(props, objectMapper, receiver, runs, scenarioFaults, Clock.systemDefaultZone());
    }

    AgentConnectorClient(AgentConnectorProperties props, ObjectMapper objectMapper, AgentConnectorMockReceiver receiver, TransferRuns runs,
                egovframework.unstructured.collector.mock.ScenarioFaults scenarioFaults, Clock clock) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.receiver = receiver;
        this.runs = runs;
        this.scenarioFaults = scenarioFaults;
        this.clock = clock;
        log.info("[Transfer] 전송 모드 {} · {} · gzip={} · 청크 {}건", props.mode(), endpoint(), props.gzip(), chunkRecords());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  설정 · 상태
    // ══════════════════════════════════════════════════════════════════════

    public String mode() {
        return props.mode().name();
    }

    /** 메모리에 남기는 수신증 상한 — 이보다 많이 보낸 배치는 수신증으로 건수를 대조할 수 없다. */
    public int keepLimit() {
        return props.keepReceipts();
    }

    /** 수신 API 전체 주소(REST) — MOCK 이면 표기용 설명. */
    public String endpoint() {
        if (props.mode() == AgentConnectorProperties.Mode.MOCK) {
            return "MOCK(수집기 내장 수신기 — 네트워크 없음 · " + props.transferPath() + " 규약)";
        }
        return (StringUtils.hasText(props.baseUrl()) ? props.baseUrl().replaceAll("/+$", "") : "(base-url 비어 있음)")
                + props.transferPath();
    }

    /** 지금 쓰는 청크 레코드 수. */
    public int chunkRecords() {
        Integer o = chunkRecordsOverride;
        int v = o != null ? o : props.chunkRecords();
        return Math.max(1, Math.min(MAX_CHUNK_RECORDS, v));
    }

    /** 시뮬레이터 — 청크 레코드 수를 덮어쓴다(null 이면 설정값으로 되돌림). */
    public void overrideChunkRecords(Integer records) {
        this.chunkRecordsOverride = records == null || records < 1 ? null : Math.min(MAX_CHUNK_RECORDS, records);
        log.info("[Transfer] 청크 레코드 수 {}", chunkRecordsOverride == null ? "설정값(" + props.chunkRecords() + ")" : chunkRecordsOverride);
    }

    public Report lastReport() {
        return lastReport;
    }

    /** 상태 — 화면 · 헬스용. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode());
        m.put("endpoint", endpoint());
        m.put("transferPath", props.transferPath());
        m.put("gzip", props.gzip());
        m.put("dataTypeCd", props.dataTypeCd());
        m.put("setTypeCd", props.setTypeCd());
        m.put("chunkRecords", chunkRecords());
        m.put("chunkRecordsOverridden", chunkRecordsOverride != null);
        m.put("receiptsKept", receipts.size());
        if (props.mode() == AgentConnectorProperties.Mode.MOCK) {
            m.put("receiver", receiver.status());
        }
        m.put("runsFile", runs.filePath());
        Report r = lastReport;
        if (r != null) {
            m.put("lastTransfer", Map.of("runId", String.valueOf(r.runId()), "execId", String.valueOf(r.execId()),
                    "delivered", r.delivered(), "failed", r.failed(), "closed", r.closed()));
        }
        return m;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  전송 세션 — 배치 1회
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 키 표식(SF) 장애 — 이 레코드가 표식을 달았고 아직 실패한 적이 없으면 거부한다. 그 건만 실패하고 청크는 멈추지 않는다
     * (수신 서버 장애가 아니라 건 하나의 거절을 흉내 낸다 — "표식 건만 정해진 단계에서 실패").
     *
     * @throws TransferSendException 표식 건의 첫 전송
     */
    public void precheck(Record r) {
        if (scenarioFaults.failOnce(egovframework.unstructured.collector.mock.FailureScenario.SEND_FAIL, r.key())) {
            throw new TransferSendException("에이전트 커넥터 HTTP 503 — (MOCK) 수신 거부 [더미 시나리오 SEND_FAIL · 1회]");
        }
    }

    /**
     * 배치 하나의 전송 세션을 연다.
     *
     * @param execId       이 실행 ID
     * @param originExecId 재처리면 원배치 ID — 원배치 런이 마감되지 않았으면 그 런을 이어 받는다(이어달리기)
     */
    public Session openSession(String execId, String originExecId) {
        return new Session(execId, originExecId);
    }

    /** 배치 하나의 전송 — 건마다 {@link #stage} 하고 끝에 {@link #flush} 한 번. 여러 워커가 동시에 쌓아도 된다. */
    public final class Session {
        private final String execId;
        private final String originExecId;
        private final List<Staged> staged = new ArrayList<>();
        private boolean flushed;

        private record Staged(Record record, Consumer<Receipt> onDelivered, Consumer<String> onFailed) {}

        private Session(String execId, String originExecId) {
            this.execId = execId;
            this.originExecId = originExecId;
        }

        /**
         * 레코드 하나를 쌓는다.
         *
         * @param onDelivered 받아들여졌을 때(그 건의 전사 보존물 정리 · 멱등 표식)
         * @param onFailed    그 청크가 실패했거나 앞 청크 실패로 보내지 못했을 때(사유)
         */
        public synchronized void stage(Record record, Consumer<Receipt> onDelivered, Consumer<String> onFailed) {
            if (flushed) {
                throw new IllegalStateException("이미 보낸 세션 — execId=" + execId);
            }
            staged.add(new Staged(record, onDelivered, onFailed));
        }

        public synchronized int size() {
            return staged.size();
        }

        /** 쌓인 레코드를 청크로 나눠 순서대로 보낸다 — 한 청크가 실패하면 멈춘다. */
        public synchronized Report flush() {
            flushed = true;
            if (staged.isEmpty()) {
                return Report.empty(execId, mode(), endpoint());
            }
            boolean continued = originExecId != null && !originExecId.equals(execId) && runs.isOpen(originExecId);
            String runId = continued ? originExecId : execId;
            TransferRuns.RunState before = runs.get(runId);
            int base = before == null ? 0 : before.deliveredSeq;
            long prior = before == null ? 0 : before.deliveredRecords;
            runs.attach(runId, execId);
            long targetCnt = prior + staged.size();
            List<List<Staged>> parts = split(staged);
            if (continued) {
                log.info("[Transfer] 이어달리기 — 원배치 런 {} 의 {}번 청크부터 (앞서 {}건 전송) · 이번 실행 {}", runId, base + 1, prior, execId);
            }
            List<Chunk> out = new ArrayList<>(parts.size());
            int delivered = 0;
            int failed = 0;
            Integer haltedAt = null;
            boolean closed = false;
            for (int i = 0; i < parts.size(); i++) {
                List<Staged> part = parts.get(i);
                int seq = base + 1 + i;
                boolean last = i == parts.size() - 1;
                List<String> keys = part.stream().map(s -> s.record().key()).toList();
                if (haltedAt != null) {
                    String why = "앞 청크(seq " + haltedAt + ") 실패로 미전송 — 전송 재처리가 이어서 보낸다";
                    part.forEach(s -> s.onFailed().accept(why));
                    failed += part.size();
                    out.add(new Chunk(seq, part.size(), last, "NOT_SENT", 0, why, 0L, 0L, keys));
                    continue;
                }
                long t0 = System.currentTimeMillis();
                Sent sent = post(runId, seq, last, targetCnt, part.stream().map(Staged::record).toList());
                long ms = System.currentTimeMillis() - t0;
                if (sent.ok) {
                    String at = LocalDateTime.now(clock).withNano(0).toString();
                    for (Staged s : part) {
                        Record r = s.record();
                        Map<String, Object> meta = new LinkedHashMap<>();
                        meta.put("exec_id", execId);
                        meta.put("type", r.type());
                        meta.put("file_name", r.fileName());
                        meta.put("run_id", runId);
                        meta.put("seq", seq);
                        if (r.metadata() != null) {
                            meta.putAll(r.metadata());
                        }
                        Receipt rc = new Receipt(sent.duplicate ? "DUPLICATE" : "SUCCESS", execId, r.type(), r.managementNo(),
                                r.fileName(), r.rawDataset() == null ? 0 : r.rawDataset().toString().length(), at, mode(),
                                endpoint(), meta, r.preview(), runId, seq);
                        keep(rc);
                        s.onDelivered().accept(rc);
                    }
                    delivered += part.size();
                    runs.delivered(runId, seq, part.size(), last);
                    closed = last;
                    out.add(new Chunk(seq, part.size(), last, sent.duplicate ? "DUPLICATE" : "SENT", sent.status,
                            sent.message, sent.bytes, ms, keys));
                } else {
                    haltedAt = seq;
                    String why = sent.message + " (seq " + seq + ")";
                    part.forEach(s -> s.onFailed().accept(why));
                    failed += part.size();
                    runs.failed(runId, seq, sent.message);
                    out.add(new Chunk(seq, part.size(), last, "FAILED", sent.status, sent.message, sent.bytes, ms, keys));
                    log.warn("[Transfer] 청크 실패 — runId={} seq={} ({}건) · {} → 뒤 청크 {}개는 보내지 않는다", runId, seq,
                            part.size(), sent.message, parts.size() - i - 1);
                }
            }
            Report rep = new Report(runId, execId, continued, base + 1, targetCnt, out, delivered, failed, closed,
                    mode(), endpoint());
            lastReport = rep;
            log.info("[Transfer] 전송 {} — runId={} 청크 {}개(seq {}~{}) · 레코드 {}건 중 {}건 전송 · {}건 실패{}",
                    failed == 0 ? "완료" : (delivered == 0 ? "실패" : "부분 실패"), runId, out.size(), base + 1,
                    base + out.size(), staged.size(), delivered, failed, closed ? " · 런 마감" : "");
            return rep;
        }
    }

    /** 청크로 나눈다 — 레코드 수 상한, 그리고 직렬화 크기 상한(max-payload)을 넘지 않게. */
    private List<List<Session.Staged>> split(List<Session.Staged> all) {
        int per = chunkRecords();
        long maxBytes = Math.max(1024, props.maxPayloadBytes());
        List<List<Session.Staged>> parts = new ArrayList<>();
        List<Session.Staged> cur = new ArrayList<>();
        long curBytes = 0;
        for (Session.Staged s : all) {
            long size = s.record().rawDataset() == null ? 64 : s.record().rawDataset().toString().length() + 64L;
            if (!cur.isEmpty() && (cur.size() >= per || curBytes + size > maxBytes)) {
                parts.add(cur);
                cur = new ArrayList<>();
                curBytes = 0;
            }
            cur.add(s);
            curBytes += size;
        }
        if (!cur.isEmpty()) {
            parts.add(cur);
        }
        return parts;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  청크 하나 보내기
    // ══════════════════════════════════════════════════════════════════════

    private record Sent(boolean ok, boolean duplicate, int status, String message, long bytes) {}

    /** 유실검증 헤더 — data-collector {@code FeatureSetTransferSink.post} 와 같은 이름 · 같은 뜻. */
    public Map<String, String> headers(String runId, int seq, boolean last, long targetCnt, int chunkCnt) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json;charset=UTF-8");
        h.put("X-Run-Id", runId);
        h.put("X-Data-Type", props.dataTypeCd());
        h.put("X-Target-Cnt", Long.toString(targetCnt));
        h.put("X-Chunk-Cnt", Integer.toString(chunkCnt));
        h.put("X-Seq", Integer.toString(seq));
        h.put("X-Is-Last", Boolean.toString(last));
        return h;
    }

    /** 요청 본문 — {@code {header, payload}}. data-collector {@code requestBytes} 와 같은 모양. */
    public byte[] requestBytes(String runId, List<Record> records) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode header = root.putObject("header");
        header.put("runId", runId);
        header.put("dataTypeCd", props.dataTypeCd());
        header.put("collectDtm", nowKst());
        header.put("setTypeCd", props.setTypeCd());
        ArrayNode payload = root.putArray("payload");
        for (Record r : records) {
            ObjectNode p = payload.addObject();
            p.put("managementNo", r.managementNo());
            p.set("rawDataset", r.rawDataset());
        }
        try {
            return objectMapper.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("전송 본문 직렬화 실패", e);
        }
    }

    /**
     * 수집 일시(KST, +09:00) — data-collector 와 같게 UTC 'Z' 를 쓰지 않는다(수신단 -9h 오인 방지).
     * JVM 기본 시간대에 기대지 않고 서울로 못 박는다 — Jenkins(UTC)에서 돈 테스트 · 다른 진입점에서도 같은 값.
     */
    String nowKst() {
        return ZonedDateTime.now(clock).withZoneSameInstant(KST).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");

    private Sent post(String runId, int seq, boolean last, long targetCnt, List<Record> records) {
        byte[] body;
        try {
            body = requestBytes(runId, records);
        } catch (RuntimeException e) {
            return new Sent(false, false, -1, "본문 직렬화 실패 — " + e.getMessage(), 0L);
        }
        Map<String, String> headers = headers(runId, seq, last, targetCnt, records.size());
        if (props.mode() == AgentConnectorProperties.Mode.MOCK) {
            return mock(headers, body);
        }
        return rest(headers, body);
    }

    /** MOCK — 네트워크 없이 내장 수신기에 같은 바이트 · 같은 헤더를 넘긴다. */
    private Sent mock(Map<String, String> headers, byte[] body) {
        byte[] wire = props.gzip() ? gzip(body) : body;
        Map<String, String> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ci.putAll(headers);
        if (props.gzip()) {
            ci.put("Content-Encoding", "gzip");
        }
        AgentConnectorMockReceiver.Ack ack = receiver.receive(ci::get, new ByteArrayInputStream(wire), props.gzip());
        boolean dup = Boolean.TRUE.equals(ack.body().get("duplicate"));
        String msg = ack.ok() ? "MOCK " + ack.status() + (dup ? " duplicate" : "")
                : "에이전트 커넥터 HTTP " + ack.status() + " — " + ack.body().get("message");
        return new Sent(ack.ok(), dup, ack.status(), msg, wire.length);
    }

    /** REST — 전이중 스트리밍(gzip + chunked)으로 보내고 2xx 를 확인한다. */
    private Sent rest(Map<String, String> headers, byte[] body) {
        if (!StringUtils.hasText(props.baseUrl())) {
            return new Sent(false, false, -1, "에이전트 커넥터 주소가 비어 있다 — agent-connector.base-url(AGENT_CONNECTOR_BASE_URL)", 0L);
        }
        URI u;
        try {
            u = URI.create(props.baseUrl().trim());
        } catch (IllegalArgumentException e) {
            return new Sent(false, false, -1, "에이전트 커넥터 주소 형식 오류 — " + props.baseUrl(), 0L);
        }
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
        String base = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
        StreamingHttpClient.Result r = StreamingHttpClient.post(host, port, base + props.transferPath(), headers,
                props.gzip(), out -> out.write(body), props.connectTimeoutMs(), props.readTimeoutMs(), RESP_TAIL_BYTES);
        boolean aborted = r.tail() != null && r.tail().contains("\"aborted\"");
        if (r.ok2xx() && !aborted) {
            boolean dup = r.tail() != null && r.tail().replace(" ", "").contains("\"duplicate\":true");
            return new Sent(true, dup, r.status(), "HTTP " + r.status() + (dup ? " duplicate" : ""), body.length);
        }
        String why = "에이전트 커넥터 HTTP " + r.status()
                + (r.writeError() != null ? " writeErr=" + r.writeError() : "")
                + (r.readError() != null ? " readErr=" + r.readError() : "")
                + (aborted ? " (응답 커밋 후 중단 — summary.aborted)" : "")
                + tailSnippet(r.tail());
        return new Sent(false, false, r.status(), why, body.length);
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length / 8));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException("gzip 압축 실패", e);
        }
        return bos.toByteArray();
    }

    private static String tailSnippet(String tail) {
        if (tail == null || tail.isBlank()) {
            return "";
        }
        String t = tail.replaceAll("\\s+", " ").strip();
        return " — " + (t.length() > 200 ? t.substring(0, 200) + "…" : t);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  수신증
    // ══════════════════════════════════════════════════════════════════════

    private void keep(Receipt r) {
        receipts.addFirst(r);
        int max = Math.max(10, props.keepReceipts());
        while (receipts.size() > max) {
            receipts.pollLast();
        }
    }

    /** 이 배치가 보낸 수신증(최근 것부터) — 메모리에 남아 있는 만큼. */
    public List<Receipt> receipts(String execId) {
        List<Receipt> out = new ArrayList<>();
        for (Receipt r : receipts) {
            if (execId == null || execId.equals(r.execId()) || execId.equals(r.runId())) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * 시험 배치(EXEC_ID 에 TST)의 수신증 · 전송 런 · 내장 수신기 장부를 지운다 — [시뮬레이션 데이터 초기화].
     *
     * @return 지운 수신증 수
     */
    public int clearTestReceipts() {
        int before = receipts.size();
        receipts.removeIf(r -> r.execId() != null && r.execId().toUpperCase().contains("TST"));
        runs.clear("TST");
        receiver.clear("TST");
        return before - receipts.size();
    }
}
