package egovframework.unstructured.collector.common.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import egovframework.unstructured.collector.mock.ScenarioFaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 에이전트 커넥터 전송 — data-collector 와 같은 양식(gzip · chunked · 유실검증 헤더 · 2xx) · 순서대로 · 실패하면 멈춤 ·
 * 재처리 이어달리기, 그리고 내장 수신기(MOCK)의 장부 · 멱등 · 헤더 누락 · 해제 상한 · 유실 판정.
 */
class AgentConnectorClientTest {

    private final ObjectMapper om = new ObjectMapper();

    static AgentConnectorProperties props(AgentConnectorProperties.Mode mode, String baseUrl, int chunkRecords, long maxDecompressed) {
        return new AgentConnectorProperties(mode, baseUrl, "/api/v1/learn/transfer", true, "UNSTRUCTURED", "VOICE",
                chunkRecords, 50L * 1024 * 1024, 1000, 5000, 100, maxDecompressed, 60);
    }

    private AgentConnectorClient client(AgentConnectorProperties p, AgentConnectorMockReceiver receiver, TransferRuns runs, ScenarioFaults sf) {
        return new AgentConnectorClient(p, om, receiver, runs, sf, java.time.Clock.systemDefaultZone());
    }

    private AgentConnectorClient.Record rec(String execId, int i) {
        ObjectNode raw = om.createObjectNode();
        raw.put("recFileId", "SIM-MEET-" + String.format("%03d", i));
        raw.put("kind", "MEET");
        raw.put("inmatePid", "PID" + i);
        raw.put("charCount", 10 + i);
        raw.putObject("transcript").put("text", "전사 " + i);
        return new AgentConnectorClient.Record("VOICE", execId, "SIM-MEET-" + String.format("%03d", i),
                "SIM0000000000000" + i, "meet-SIM-MEET-" + i + ".json", raw, Map.of("kind", "MEET"), "전사 " + i);
    }

    /** 레코드 n 건을 쌓고 보낸다 — 받아들여진 키 · 실패 사유를 모은다. */
    private AgentConnectorClient.Report sendAll(AgentConnectorClient c, String execId, String origin, int n, List<String> ok,
                                       Map<String, String> failed) {
        AgentConnectorClient.Session s = c.openSession(execId, origin);
        for (int i = 1; i <= n; i++) {
            AgentConnectorClient.Record r = rec(execId, i);
            s.stage(r, rc -> ok.add(r.key()), why -> failed.put(r.key(), why));
        }
        return s.flush();
    }

    @Test
    @DisplayName("MOCK — 6건을 2건씩 3청크(seq 1~3, 마지막만 last) 로 보내고 수신 장부가 COMPLETE 로 마감된다")
    void mockSendsChunksInOrderAndCloses() {
        AgentConnectorMockReceiver recv = new AgentConnectorMockReceiver(props(AgentConnectorProperties.Mode.MOCK, "", 2, 512L << 20), om);
        TransferRuns runs = TransferRuns.inMemory();
        AgentConnectorClient c = client(props(AgentConnectorProperties.Mode.MOCK, "", 2, 512L << 20), recv, runs, ScenarioFaults.inactive());
        List<String> ok = new ArrayList<>();
        Map<String, String> failed = new TreeMap<>();

        AgentConnectorClient.Report rep = sendAll(c, "20261005TST001", null, 6, ok, failed);

        assertThat(rep.runId()).isEqualTo("20261005TST001");
        assertThat(rep.chunks()).extracting(AgentConnectorClient.Chunk::seq).containsExactly(1, 2, 3);
        assertThat(rep.chunks()).extracting(AgentConnectorClient.Chunk::last).containsExactly(false, false, true);
        assertThat(rep.chunks()).extracting(AgentConnectorClient.Chunk::result).containsOnly("SENT");
        assertThat(rep.delivered()).isEqualTo(6);
        assertThat(rep.closed()).isTrue();
        assertThat(ok).hasSize(6);
        assertThat(failed).isEmpty();

        Map<String, Object> led = recv.ledger("20261005TST001");
        assertThat(led.get("state")).isEqualTo("COMPLETE");
        assertThat(led.get("records")).isEqualTo(6L);
        assertThat(led.get("targetCnt")).isEqualTo(6L);
        assertThat(led.get("setTypeCd")).isEqualTo("VOICE");
        assertThat(led.get("dataType")).isEqualTo("UNSTRUCTURED");
        assertThat(runs.isOpen("20261005TST001")).isFalse();
        assertThat(c.receipts("20261005TST001")).hasSize(6).allSatisfy(r -> {
            assertThat(r.runId()).isEqualTo("20261005TST001");
            assertThat(r.location()).startsWith("agent-connector:mock/20261005TST001#");
        });
    }

    @Test
    @DisplayName("3번 청크에서 503 → 1·2번만 받아들여지고 멈춘다(PARTIAL) · 정상화 뒤 재처리가 원배치 런을 3번부터 이어 마감한다")
    void partialFailureThenResumeContinuesOriginRun() {
        AgentConnectorMockReceiver recv = new AgentConnectorMockReceiver(props(AgentConnectorProperties.Mode.MOCK, "", 2, 512L << 20), om);
        TransferRuns runs = TransferRuns.inMemory();
        AgentConnectorClient c = client(props(AgentConnectorProperties.Mode.MOCK, "", 2, 512L << 20), recv, runs, ScenarioFaults.inactive());
        recv.setFault(AgentConnectorMockReceiver.FaultMode.FAIL_FROM_SEQ, 3);
        List<String> ok = new ArrayList<>();
        Map<String, String> failed = new TreeMap<>();

        AgentConnectorClient.Report first = sendAll(c, "20261005UNS001", null, 6, ok, failed);

        assertThat(first.chunks()).extracting(AgentConnectorClient.Chunk::result).containsExactly("SENT", "SENT", "FAILED");
        assertThat(first.delivered()).isEqualTo(4);
        assertThat(failed).hasSize(2).allSatisfy((k, v) -> assertThat(v).contains("503").contains("seq 3"));
        assertThat(runs.isOpen("20261005UNS001")).isTrue();
        assertThat(runs.get("20261005UNS001").deliveredSeq).isEqualTo(2);
        assertThat(recv.ledger("20261005UNS001").get("state")).isEqualTo("RECEIVING");

        // 정상화 → 재처리(새 실행 ID)는 실패한 2건만 다시 쌓는다 — 원배치 런을 3번부터 이어 받는다
        recv.setFault(AgentConnectorMockReceiver.FaultMode.UP, 0);
        AgentConnectorClient.Session s = c.openSession("20261005UNS002", "20261005UNS001");
        List<String> ok2 = new ArrayList<>();
        for (int i = 5; i <= 6; i++) {
            AgentConnectorClient.Record r = rec("20261005UNS002", i);
            s.stage(r, rc -> ok2.add(r.key()), why -> { });
        }
        AgentConnectorClient.Report again = s.flush();

        assertThat(again.continued()).isTrue();
        assertThat(again.runId()).isEqualTo("20261005UNS001");
        assertThat(again.chunks()).extracting(AgentConnectorClient.Chunk::seq).containsExactly(3);
        assertThat(again.chunks().get(0).last()).isTrue();
        assertThat(again.targetCnt()).isEqualTo(6);
        assertThat(ok2).hasSize(2);
        Map<String, Object> led = recv.ledger("20261005UNS001");
        assertThat(led.get("state")).isEqualTo("COMPLETE");
        assertThat(led.get("receivedSeqs")).isEqualTo(List.of(1, 2, 3));
        assertThat(runs.isOpen("20261005UNS001")).isFalse();
        assertThat(c.receipts("20261005UNS002")).allSatisfy(r -> assertThat(r.runId()).isEqualTo("20261005UNS001"));
    }

    @Test
    @DisplayName("수신 서버 다운 → 1번부터 실패 · 뒤 청크는 NOT_SENT(FAIL) · 재처리는 같은 런을 1번부터 다시 보낸다")
    void downFailsAllThenResumeFromFirst() {
        AgentConnectorMockReceiver recv = new AgentConnectorMockReceiver(props(AgentConnectorProperties.Mode.MOCK, "", 2, 512L << 20), om);
        TransferRuns runs = TransferRuns.inMemory();
        AgentConnectorClient c = client(props(AgentConnectorProperties.Mode.MOCK, "", 2, 512L << 20), recv, runs, ScenarioFaults.inactive());
        recv.setFault(AgentConnectorMockReceiver.FaultMode.DOWN, 0);
        Map<String, String> failed = new TreeMap<>();

        AgentConnectorClient.Report first = sendAll(c, "20261005UNS003", null, 6, new ArrayList<>(), failed);

        assertThat(first.chunks()).extracting(AgentConnectorClient.Chunk::result).containsExactly("FAILED", "NOT_SENT", "NOT_SENT");
        assertThat(first.delivered()).isZero();
        assertThat(failed).hasSize(6);
        assertThat(failed.values()).filteredOn(v -> v.contains("앞 청크(seq 1) 실패로 미전송")).hasSize(4);

        recv.setFault(AgentConnectorMockReceiver.FaultMode.UP, 0);
        AgentConnectorClient.Report again = sendAll(c, "20261005UNS004", "20261005UNS003", 6, new ArrayList<>(), new TreeMap<>());
        assertThat(again.continued()).isTrue();
        assertThat(again.chunks()).extracting(AgentConnectorClient.Chunk::seq).containsExactly(1, 2, 3);
        assertThat(recv.ledger("20261005UNS003").get("state")).isEqualTo("COMPLETE");
    }

    @Test
    @DisplayName("마감된 원배치 런은 이어 받지 않는다 — 재처리 실행 ID 로 새 런")
    void closedOriginRunStartsNewRun() {
        AgentConnectorMockReceiver recv = new AgentConnectorMockReceiver(props(AgentConnectorProperties.Mode.MOCK, "", 50, 512L << 20), om);
        TransferRuns runs = TransferRuns.inMemory();
        AgentConnectorClient c = client(props(AgentConnectorProperties.Mode.MOCK, "", 50, 512L << 20), recv, runs, ScenarioFaults.inactive());
        sendAll(c, "20261005UNS005", null, 2, new ArrayList<>(), new TreeMap<>());

        AgentConnectorClient.Report again = sendAll(c, "20261005UNS006", "20261005UNS005", 1, new ArrayList<>(), new TreeMap<>());

        assertThat(again.continued()).isFalse();
        assertThat(again.runId()).isEqualTo("20261005UNS006");
        assertThat(again.fromSeq()).isEqualTo(1);
    }

    @Test
    @DisplayName("키 표식(SF) — 그 레코드만 한 번 거부(precheck) · 두 번째는 통과")
    void scenarioMarkerRejectsOnce() {
        AgentConnectorMockReceiver recv = new AgentConnectorMockReceiver(props(AgentConnectorProperties.Mode.MOCK, "", 50, 512L << 20), om);
        AgentConnectorClient c = client(props(AgentConnectorProperties.Mode.MOCK, "", 50, 512L << 20), recv, TransferRuns.inMemory(),
                ScenarioFaults.activeForTest());
        AgentConnectorClient.Record sf = new AgentConnectorClient.Record("VOICE", "x", "DMY-MEET-20261005-SF-0001", "DMY1", "a.json",
                om.createObjectNode(), Map.of(), "");

        assertThatThrownBy(() -> c.precheck(sf)).isInstanceOf(AgentConnectorClient.TransferSendException.class)
                .hasMessageContaining("SEND_FAIL");
        c.precheck(sf);   // 두 번째는 통과
        c.precheck(rec("x", 1));   // 표식 없는 키는 늘 통과
    }

    // ── 내장 수신기 ──────────────────────────────────────────────────────

    private AgentConnectorMockReceiver.Ack post(AgentConnectorMockReceiver recv, Map<String, String> h, byte[] body, boolean gz) {
        Map<String, String> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ci.putAll(h);
        return recv.receive(ci::get, new ByteArrayInputStream(gz ? AgentConnectorClient.gzip(body) : body), gz);
    }

    private byte[] body(String runId, int records) {
        StringBuilder sb = new StringBuilder("{\"header\":{\"runId\":\"" + runId + "\",\"setTypeCd\":\"VOICE\"},\"payload\":[");
        for (int i = 0; i < records; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"managementNo\":\"M").append(i).append("\",\"rawDataset\":{\"recFileId\":\"K")
                    .append(i).append("\"}}");
        }
        return sb.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> h(String runId, int seq, boolean last, int target, int chunk) {
        return Map.of("X-Run-Id", runId, "X-Seq", String.valueOf(seq), "X-Is-Last", String.valueOf(last),
                "X-Target-Cnt", String.valueOf(target), "X-Chunk-Cnt", String.valueOf(chunk), "X-Data-Type", "UNSTRUCTURED");
    }

    @Test
    @DisplayName("수신기 — 헤더 누락 400 · 레코드 수 불일치 400 · 중복 청크 200 duplicate · 강제 중단 뒤 같은 런 재전송으로 마감")
    void receiverHeadersDuplicateAndResend() {
        AgentConnectorMockReceiver recv = new AgentConnectorMockReceiver(props(AgentConnectorProperties.Mode.MOCK, "", 2, 512L << 20), om);

        assertThat(post(recv, Map.of("X-Seq", "1"), body("R1", 1), true).status()).isEqualTo(400);
        assertThat(post(recv, h("R1", 1, false, 6, 3), body("R1", 2), true).status()).isEqualTo(400);

        assertThat(post(recv, h("R1", 1, false, 6, 2), body("R1", 2), true).status()).isEqualTo(200);
        assertThat(post(recv, h("R1", 2, false, 6, 2), body("R1", 2), true).status()).isEqualTo(200);
        // ⏸ 강제 중단 — 3번을 보내지 않았다
        assertThat(recv.ledger("R1").get("state")).isEqualTo("RECEIVING");
        AgentConnectorMockReceiver.Ack dup = post(recv, h("R1", 2, false, 6, 2), body("R1", 2), true);
        assertThat(dup.status()).isEqualTo(200);
        assertThat(dup.body().get("duplicate")).isEqualTo(true);
        // 같은 런으로 3번(마지막) 재전송 → 마감 · 중복 아님
        AgentConnectorMockReceiver.Ack r3 = post(recv, h("R1", 3, true, 6, 2), body("R1", 2), true);
        assertThat(r3.body().get("duplicate")).isEqualTo(false);
        assertThat(recv.ledger("R1").get("state")).isEqualTo("COMPLETE");
    }

    @Test
    @DisplayName("수신기 — 2번이 빠진 채 1 · 3(last) 를 받으면 GAP_SUSPECT → 스윕으로 INGEST-GAP")
    void receiverGapSweep() {
        AgentConnectorMockReceiver recv = new AgentConnectorMockReceiver(props(AgentConnectorProperties.Mode.MOCK, "", 2, 512L << 20), om);
        post(recv, h("R2", 1, false, 6, 2), body("R2", 2), true);
        post(recv, h("R2", 3, true, 6, 2), body("R2", 2), true);
        assertThat(recv.ledger("R2").get("state")).isEqualTo("GAP_SUSPECT");
        assertThat(recv.ledger("R2").get("missingSeqs")).isEqualTo(List.of(2));

        Map<String, Object> sw = recv.sweep(0);

        assertThat(sw.get("sweptCount")).isEqualTo(1);
        assertThat(recv.ledger("R2").get("state")).isEqualTo("INGEST-GAP");
    }

    @Test
    @DisplayName("수신기 — 해제 상한을 넘는 압축 폭탄은 끝까지 풀지 않고 413")
    void receiverBombIs413() throws Exception {
        AgentConnectorMockReceiver recv = new AgentConnectorMockReceiver(props(AgentConnectorProperties.Mode.MOCK, "", 2, 1024 * 1024), om);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write("{\"header\":{\"runId\":\"R3\"},\"payload\":[".getBytes(StandardCharsets.UTF_8));
            byte[] spaces = new byte[64 * 1024];
            java.util.Arrays.fill(spaces, (byte) ' ');
            for (int i = 0; i < 64; i++) {   // 4MB 공백 — 상한 1MB
                gz.write(spaces);
            }
            gz.write("]}".getBytes(StandardCharsets.UTF_8));
        }
        Map<String, String> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ci.putAll(h("R3", 1, true, 0, 0));

        AgentConnectorMockReceiver.Ack a = recv.receive(ci::get, new ByteArrayInputStream(bos.toByteArray()), true);

        assertThat(a.status()).isEqualTo(413);
        assertThat(bos.size()).isLessThan(64 * 1024);   // 보낸 것은 작다(압축비)
        assertThat(recv.ledger("R3")).as("거절한 요청은 장부를 만들지 않는다").isNull();
    }

    // ── REST(실제 소켓) ──────────────────────────────────────────────────

    @Test
    @DisplayName("REST — gzip + chunked 로 헤더 6종 · 본문(header · payload)을 보내고 2xx 면 성공, 503 이면 그 청크에서 멈춘다")
    void restStreamsGzipChunkedWithHeaders() throws Exception {
        List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/zen/api/v1/learn/transfer", ex -> {
            Map<String, String> hs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            ex.getRequestHeaders().forEach((k, v) -> hs.put(k, v.get(0)));
            seen.add(hs);
            byte[] raw;
            try (InputStream in = new GZIPInputStream(ex.getRequestBody())) {
                raw = in.readAllBytes();
            }
            bodies.add(om.readTree(raw));
            int seq = Integer.parseInt(hs.get("X-Seq"));
            byte[] resp = (seq >= 2 ? "{\"code\":\"UNAVAILABLE\"}" : "{\"code\":\"SUCCESS\",\"duplicate\":false}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(seq >= 2 ? 503 : 200, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/zen";
            AgentConnectorProperties p = props(AgentConnectorProperties.Mode.REST, base, 2, 512L << 20);
            AgentConnectorClient c = client(p, new AgentConnectorMockReceiver(p, om), TransferRuns.inMemory(), ScenarioFaults.inactive());
            Map<String, String> failed = new TreeMap<>();

            AgentConnectorClient.Report rep = sendAll(c, "20261005UNS009", null, 5, new ArrayList<>(), failed);

            assertThat(rep.chunks()).extracting(AgentConnectorClient.Chunk::result).containsExactly("SENT", "FAILED", "NOT_SENT");
            assertThat(seen).hasSize(2);
            Map<String, String> h1 = seen.get(0);
            assertThat(h1.get("X-Run-Id")).isEqualTo("20261005UNS009");
            assertThat(h1.get("X-Seq")).isEqualTo("1");
            assertThat(h1.get("X-Is-Last")).isEqualTo("false");
            assertThat(h1.get("X-Target-Cnt")).isEqualTo("5");
            assertThat(h1.get("X-Chunk-Cnt")).isEqualTo("2");
            assertThat(h1.get("X-Data-Type")).isEqualTo("UNSTRUCTURED");
            assertThat(h1.get("Content-Encoding")).isEqualTo("gzip");
            assertThat(h1.get("Transfer-encoding")).isEqualToIgnoringCase("chunked");
            JsonNode b1 = bodies.get(0);
            assertThat(b1.path("header").path("runId").asText()).isEqualTo("20261005UNS009");
            assertThat(b1.path("header").path("collectDtm").asText()).endsWith("+09:00");
            assertThat(b1.path("header").path("setTypeCd").asText()).isEqualTo("VOICE");
            assertThat(b1.path("payload")).hasSize(2);
            assertThat(b1.path("payload").get(0).path("managementNo").asText()).startsWith("SIM");
            assertThat(failed).hasSize(3);
            assertThat(failed.values()).anyMatch(v -> v.contains("에이전트 커넥터 HTTP 503"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("REST — 에이전트 커넥터 bypass API(LearnTransferController) 규약 그대로: /api/v1/learn/transfer · JSON · 본문은 풀지 않고 "
            + "· 응답 {runId, seq, duplicate, receivedChunks, last} · 이미 받은 순번은 duplicate 로 받아들인다")
    void restAgainstConnectorBypassContract() throws Exception {
        // 커넥터 origin/dev(ed46d09) LearnTransferController 와 같은 판정 — X-Run-Id · X-Seq 필수(없으면 400), (runId+seq) 멱등, 본문은 세기만
        Map<String, java.util.Set<Integer>> tallies = new java.util.concurrent.ConcurrentHashMap<>();
        tallies.computeIfAbsent("20261006UNS001", k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(1);  // 1번은 이미 받음
        List<String> paths = new CopyOnWriteArrayList<>();
        List<String> types = new CopyOnWriteArrayList<>();
        List<Long> drained = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/learn/transfer", ex -> {
            paths.add(ex.getRequestURI().getPath());
            types.add(ex.getRequestHeaders().getFirst("Content-Type"));
            String runId = ex.getRequestHeaders().getFirst("X-Run-Id");
            String seqText = ex.getRequestHeaders().getFirst("X-Seq");
            byte[] resp;
            int status;
            if (runId == null || seqText == null) {
                status = 400;
                resp = "{\"status\":400,\"error\":\"Bad Request\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                int seq = Integer.parseInt(seqText);
                boolean last = Boolean.parseBoolean(ex.getRequestHeaders().getFirst("X-Is-Last"));
                java.util.Set<Integer> seqs = tallies.computeIfAbsent(runId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
                boolean duplicate = !seqs.add(seq);
                drained.add((long) ex.getRequestBody().readAllBytes().length);   // 풀지 않고 소비만
                status = 200;
                resp = om.writeValueAsBytes(Map.of("runId", runId, "seq", seq, "duplicate", duplicate,
                        "receivedChunks", seqs.size(), "last", last));
            }
            ex.sendResponseHeaders(status, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AgentConnectorProperties p = props(AgentConnectorProperties.Mode.REST, base, 2, 512L << 20);
            AgentConnectorClient c = client(p, new AgentConnectorMockReceiver(p, om), TransferRuns.inMemory(), ScenarioFaults.inactive());
            List<String> ok = new ArrayList<>();
            Map<String, String> failed = new TreeMap<>();

            AgentConnectorClient.Report rep = sendAll(c, "20261006UNS001", null, 5, ok, failed);

            assertThat(rep.chunks()).extracting(AgentConnectorClient.Chunk::result).containsExactly("DUPLICATE", "SENT", "SENT");
            assertThat(rep.delivered()).isEqualTo(5);
            assertThat(rep.closed()).isTrue();
            assertThat(failed).isEmpty();
            assertThat(paths).containsOnly("/api/v1/learn/transfer");
            assertThat(types).allMatch(t -> t.startsWith("application/json"));   // 커넥터는 consumes = application/json
            assertThat(drained).allMatch(n -> n > 0);
            assertThat(tallies.get("20261006UNS001")).containsExactlyInAnyOrder(1, 2, 3);
            assertThat(c.receipts("20261006UNS001")).extracting(AgentConnectorClient.Receipt::location)
                    .allMatch(l -> l.startsWith("agent-connector:" + base + "/api/v1/learn/transfer/20261006UNS001#"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("전송 런 장부 — 새 위치(transfer/)에 파일이 없으면 옛 위치(zenon/)를 읽어 이어달리기를 잇고, 다음 저장부터 새 위치에 쓴다")
    void transferRunsReadLegacyFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path legacy = dir.resolve(TransferRuns.LEGACY_FILE);
        java.nio.file.Path now = dir.resolve(TransferRuns.FILE);
        TransferRuns old = new TransferRuns(() -> legacy);   // 10-05 배포가 남긴 장부
        old.attach("20261005UNS020", "20261005UNS020");
        old.delivered("20261005UNS020", 1, 2, false);
        old.failed("20261005UNS020", 2, "503");
        assertThat(legacy).isRegularFile();

        TransferRuns b = new TransferRuns(() -> now);   // 새 배포 기동

        assertThat(b.isOpen("20261005UNS020")).isTrue();
        assertThat(b.get("20261005UNS020").deliveredSeq).isEqualTo(1);
        assertThat(now).doesNotExist();
        b.delivered("20261005UNS020", 2, 2, true);
        assertThat(now).isRegularFile();
        assertThat(new TransferRuns(() -> now).isOpen("20261005UNS020")).isFalse();
    }

    @Test
    @DisplayName("REST 인데 주소가 비면 보내지 않고 그 런의 레코드 전부 실패")
    void restWithoutBaseUrlFails() {
        AgentConnectorProperties p = props(AgentConnectorProperties.Mode.REST, "", 50, 512L << 20);
        AgentConnectorClient c = client(p, new AgentConnectorMockReceiver(p, om), TransferRuns.inMemory(), ScenarioFaults.inactive());
        Map<String, String> failed = new TreeMap<>();

        AgentConnectorClient.Report rep = sendAll(c, "20261005UNS010", null, 2, new ArrayList<>(), failed);

        assertThat(rep.delivered()).isZero();
        assertThat(failed.values()).allMatch(v -> v.contains("base-url"));
    }

    @Test
    @DisplayName("전송 런 장부는 PV 파일에 남아 재기동 뒤에도 이어달리기 지점을 안다")
    void transferRunsPersist(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path f = dir.resolve("transfer/transfer_runs.json");
        TransferRuns a = new TransferRuns(() -> f);
        a.attach("20261005UNS011", "20261005UNS011");
        a.delivered("20261005UNS011", 1, 2, false);
        a.delivered("20261005UNS011", 2, 2, false);
        a.failed("20261005UNS011", 3, "503");

        TransferRuns b = new TransferRuns(() -> f);   // 재기동 흉내

        assertThat(b.isOpen("20261005UNS011")).isTrue();
        assertThat(b.get("20261005UNS011").deliveredSeq).isEqualTo(2);
        assertThat(b.get("20261005UNS011").deliveredRecords).isEqualTo(4);
        assertThat(b.get("20261005UNS011").failedSeq).isEqualTo(3);
    }
}
