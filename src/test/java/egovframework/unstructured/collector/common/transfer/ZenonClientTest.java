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
 * 제논(Zenon) 전송 — data-collector 와 같은 양식(gzip · chunked · 유실검증 헤더 · 2xx) · 순서대로 · 실패하면 멈춤 ·
 * 재처리 이어달리기, 그리고 내장 수신기(MOCK)의 장부 · 멱등 · 헤더 누락 · 해제 상한 · 유실 판정.
 */
class ZenonClientTest {

    private final ObjectMapper om = new ObjectMapper();

    static ZenonProperties props(ZenonProperties.Mode mode, String baseUrl, int chunkRecords, long maxDecompressed) {
        return new ZenonProperties(mode, baseUrl, "/api/v1/learn/transfer", true, "UNSTRUCTURED", "VOICE",
                chunkRecords, 50L * 1024 * 1024, 1000, 5000, 100, maxDecompressed, 60);
    }

    private ZenonClient client(ZenonProperties p, ZenonMockReceiver receiver, ZenonTransferRuns runs, ScenarioFaults sf) {
        return new ZenonClient(p, om, receiver, runs, sf, java.time.Clock.systemDefaultZone());
    }

    private ZenonClient.Record rec(String execId, int i) {
        ObjectNode raw = om.createObjectNode();
        raw.put("recFileId", "SIM-MEET-" + String.format("%03d", i));
        raw.put("kind", "MEET");
        raw.put("inmatePid", "PID" + i);
        raw.put("charCount", 10 + i);
        raw.putObject("transcript").put("text", "전사 " + i);
        return new ZenonClient.Record("VOICE", execId, "SIM-MEET-" + String.format("%03d", i),
                "SIM0000000000000" + i, "meet-SIM-MEET-" + i + ".json", raw, Map.of("kind", "MEET"), "전사 " + i);
    }

    /** 레코드 n 건을 쌓고 보낸다 — 받아들여진 키 · 실패 사유를 모은다. */
    private ZenonClient.Report sendAll(ZenonClient c, String execId, String origin, int n, List<String> ok,
                                       Map<String, String> failed) {
        ZenonClient.Session s = c.openSession(execId, origin);
        for (int i = 1; i <= n; i++) {
            ZenonClient.Record r = rec(execId, i);
            s.stage(r, rc -> ok.add(r.key()), why -> failed.put(r.key(), why));
        }
        return s.flush();
    }

    @Test
    @DisplayName("MOCK — 6건을 2건씩 3청크(seq 1~3, 마지막만 last) 로 보내고 수신 장부가 COMPLETE 로 마감된다")
    void mockSendsChunksInOrderAndCloses() {
        ZenonMockReceiver recv = new ZenonMockReceiver(props(ZenonProperties.Mode.MOCK, "", 2, 512L << 20), om);
        ZenonTransferRuns runs = ZenonTransferRuns.inMemory();
        ZenonClient c = client(props(ZenonProperties.Mode.MOCK, "", 2, 512L << 20), recv, runs, ScenarioFaults.inactive());
        List<String> ok = new ArrayList<>();
        Map<String, String> failed = new TreeMap<>();

        ZenonClient.Report rep = sendAll(c, "20261005TST001", null, 6, ok, failed);

        assertThat(rep.runId()).isEqualTo("20261005TST001");
        assertThat(rep.chunks()).extracting(ZenonClient.Chunk::seq).containsExactly(1, 2, 3);
        assertThat(rep.chunks()).extracting(ZenonClient.Chunk::last).containsExactly(false, false, true);
        assertThat(rep.chunks()).extracting(ZenonClient.Chunk::result).containsOnly("SENT");
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
            assertThat(r.location()).startsWith("zenon:mock/20261005TST001#");
        });
    }

    @Test
    @DisplayName("3번 청크에서 503 → 1·2번만 받아들여지고 멈춘다(PARTIAL) · 정상화 뒤 재처리가 원배치 런을 3번부터 이어 마감한다")
    void partialFailureThenResumeContinuesOriginRun() {
        ZenonMockReceiver recv = new ZenonMockReceiver(props(ZenonProperties.Mode.MOCK, "", 2, 512L << 20), om);
        ZenonTransferRuns runs = ZenonTransferRuns.inMemory();
        ZenonClient c = client(props(ZenonProperties.Mode.MOCK, "", 2, 512L << 20), recv, runs, ScenarioFaults.inactive());
        recv.setFault(ZenonMockReceiver.FaultMode.FAIL_FROM_SEQ, 3);
        List<String> ok = new ArrayList<>();
        Map<String, String> failed = new TreeMap<>();

        ZenonClient.Report first = sendAll(c, "20261005UNS001", null, 6, ok, failed);

        assertThat(first.chunks()).extracting(ZenonClient.Chunk::result).containsExactly("SENT", "SENT", "FAILED");
        assertThat(first.delivered()).isEqualTo(4);
        assertThat(failed).hasSize(2).allSatisfy((k, v) -> assertThat(v).contains("503").contains("seq 3"));
        assertThat(runs.isOpen("20261005UNS001")).isTrue();
        assertThat(runs.get("20261005UNS001").deliveredSeq).isEqualTo(2);
        assertThat(recv.ledger("20261005UNS001").get("state")).isEqualTo("RECEIVING");

        // 정상화 → 재처리(새 실행 ID)는 실패한 2건만 다시 쌓는다 — 원배치 런을 3번부터 이어 받는다
        recv.setFault(ZenonMockReceiver.FaultMode.UP, 0);
        ZenonClient.Session s = c.openSession("20261005UNS002", "20261005UNS001");
        List<String> ok2 = new ArrayList<>();
        for (int i = 5; i <= 6; i++) {
            ZenonClient.Record r = rec("20261005UNS002", i);
            s.stage(r, rc -> ok2.add(r.key()), why -> { });
        }
        ZenonClient.Report again = s.flush();

        assertThat(again.continued()).isTrue();
        assertThat(again.runId()).isEqualTo("20261005UNS001");
        assertThat(again.chunks()).extracting(ZenonClient.Chunk::seq).containsExactly(3);
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
        ZenonMockReceiver recv = new ZenonMockReceiver(props(ZenonProperties.Mode.MOCK, "", 2, 512L << 20), om);
        ZenonTransferRuns runs = ZenonTransferRuns.inMemory();
        ZenonClient c = client(props(ZenonProperties.Mode.MOCK, "", 2, 512L << 20), recv, runs, ScenarioFaults.inactive());
        recv.setFault(ZenonMockReceiver.FaultMode.DOWN, 0);
        Map<String, String> failed = new TreeMap<>();

        ZenonClient.Report first = sendAll(c, "20261005UNS003", null, 6, new ArrayList<>(), failed);

        assertThat(first.chunks()).extracting(ZenonClient.Chunk::result).containsExactly("FAILED", "NOT_SENT", "NOT_SENT");
        assertThat(first.delivered()).isZero();
        assertThat(failed).hasSize(6);
        assertThat(failed.values()).filteredOn(v -> v.contains("앞 청크(seq 1) 실패로 미전송")).hasSize(4);

        recv.setFault(ZenonMockReceiver.FaultMode.UP, 0);
        ZenonClient.Report again = sendAll(c, "20261005UNS004", "20261005UNS003", 6, new ArrayList<>(), new TreeMap<>());
        assertThat(again.continued()).isTrue();
        assertThat(again.chunks()).extracting(ZenonClient.Chunk::seq).containsExactly(1, 2, 3);
        assertThat(recv.ledger("20261005UNS003").get("state")).isEqualTo("COMPLETE");
    }

    @Test
    @DisplayName("마감된 원배치 런은 이어 받지 않는다 — 재처리 실행 ID 로 새 런")
    void closedOriginRunStartsNewRun() {
        ZenonMockReceiver recv = new ZenonMockReceiver(props(ZenonProperties.Mode.MOCK, "", 50, 512L << 20), om);
        ZenonTransferRuns runs = ZenonTransferRuns.inMemory();
        ZenonClient c = client(props(ZenonProperties.Mode.MOCK, "", 50, 512L << 20), recv, runs, ScenarioFaults.inactive());
        sendAll(c, "20261005UNS005", null, 2, new ArrayList<>(), new TreeMap<>());

        ZenonClient.Report again = sendAll(c, "20261005UNS006", "20261005UNS005", 1, new ArrayList<>(), new TreeMap<>());

        assertThat(again.continued()).isFalse();
        assertThat(again.runId()).isEqualTo("20261005UNS006");
        assertThat(again.fromSeq()).isEqualTo(1);
    }

    @Test
    @DisplayName("키 표식(SF) — 그 레코드만 한 번 거부(precheck) · 두 번째는 통과")
    void scenarioMarkerRejectsOnce() {
        ZenonMockReceiver recv = new ZenonMockReceiver(props(ZenonProperties.Mode.MOCK, "", 50, 512L << 20), om);
        ZenonClient c = client(props(ZenonProperties.Mode.MOCK, "", 50, 512L << 20), recv, ZenonTransferRuns.inMemory(),
                ScenarioFaults.activeForTest());
        ZenonClient.Record sf = new ZenonClient.Record("VOICE", "x", "DMY-MEET-20261005-SF-0001", "DMY1", "a.json",
                om.createObjectNode(), Map.of(), "");

        assertThatThrownBy(() -> c.precheck(sf)).isInstanceOf(ZenonClient.ZenonSendException.class)
                .hasMessageContaining("SEND_FAIL");
        c.precheck(sf);   // 두 번째는 통과
        c.precheck(rec("x", 1));   // 표식 없는 키는 늘 통과
    }

    // ── 내장 수신기 ──────────────────────────────────────────────────────

    private ZenonMockReceiver.Ack post(ZenonMockReceiver recv, Map<String, String> h, byte[] body, boolean gz) {
        Map<String, String> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ci.putAll(h);
        return recv.receive(ci::get, new ByteArrayInputStream(gz ? ZenonClient.gzip(body) : body), gz);
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
        ZenonMockReceiver recv = new ZenonMockReceiver(props(ZenonProperties.Mode.MOCK, "", 2, 512L << 20), om);

        assertThat(post(recv, Map.of("X-Seq", "1"), body("R1", 1), true).status()).isEqualTo(400);
        assertThat(post(recv, h("R1", 1, false, 6, 3), body("R1", 2), true).status()).isEqualTo(400);

        assertThat(post(recv, h("R1", 1, false, 6, 2), body("R1", 2), true).status()).isEqualTo(200);
        assertThat(post(recv, h("R1", 2, false, 6, 2), body("R1", 2), true).status()).isEqualTo(200);
        // ⏸ 강제 중단 — 3번을 보내지 않았다
        assertThat(recv.ledger("R1").get("state")).isEqualTo("RECEIVING");
        ZenonMockReceiver.Ack dup = post(recv, h("R1", 2, false, 6, 2), body("R1", 2), true);
        assertThat(dup.status()).isEqualTo(200);
        assertThat(dup.body().get("duplicate")).isEqualTo(true);
        // 같은 런으로 3번(마지막) 재전송 → 마감 · 중복 아님
        ZenonMockReceiver.Ack r3 = post(recv, h("R1", 3, true, 6, 2), body("R1", 2), true);
        assertThat(r3.body().get("duplicate")).isEqualTo(false);
        assertThat(recv.ledger("R1").get("state")).isEqualTo("COMPLETE");
    }

    @Test
    @DisplayName("수신기 — 2번이 빠진 채 1 · 3(last) 를 받으면 GAP_SUSPECT → 스윕으로 INGEST-GAP")
    void receiverGapSweep() {
        ZenonMockReceiver recv = new ZenonMockReceiver(props(ZenonProperties.Mode.MOCK, "", 2, 512L << 20), om);
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
        ZenonMockReceiver recv = new ZenonMockReceiver(props(ZenonProperties.Mode.MOCK, "", 2, 1024 * 1024), om);
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

        ZenonMockReceiver.Ack a = recv.receive(ci::get, new ByteArrayInputStream(bos.toByteArray()), true);

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
            ZenonProperties p = props(ZenonProperties.Mode.REST, base, 2, 512L << 20);
            ZenonClient c = client(p, new ZenonMockReceiver(p, om), ZenonTransferRuns.inMemory(), ScenarioFaults.inactive());
            Map<String, String> failed = new TreeMap<>();

            ZenonClient.Report rep = sendAll(c, "20261005UNS009", null, 5, new ArrayList<>(), failed);

            assertThat(rep.chunks()).extracting(ZenonClient.Chunk::result).containsExactly("SENT", "FAILED", "NOT_SENT");
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
            assertThat(failed.values()).anyMatch(v -> v.contains("제논 HTTP 503"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("REST 인데 주소가 비면 보내지 않고 그 런의 레코드 전부 실패")
    void restWithoutBaseUrlFails() {
        ZenonProperties p = props(ZenonProperties.Mode.REST, "", 50, 512L << 20);
        ZenonClient c = client(p, new ZenonMockReceiver(p, om), ZenonTransferRuns.inMemory(), ScenarioFaults.inactive());
        Map<String, String> failed = new TreeMap<>();

        ZenonClient.Report rep = sendAll(c, "20261005UNS010", null, 2, new ArrayList<>(), failed);

        assertThat(rep.delivered()).isZero();
        assertThat(failed.values()).allMatch(v -> v.contains("base-url"));
    }

    @Test
    @DisplayName("전송 런 장부는 PV 파일에 남아 재기동 뒤에도 이어달리기 지점을 안다")
    void transferRunsPersist(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path f = dir.resolve("zenon/transfer_runs.json");
        ZenonTransferRuns a = new ZenonTransferRuns(() -> f);
        a.attach("20261005UNS011", "20261005UNS011");
        a.delivered("20261005UNS011", 1, 2, false);
        a.delivered("20261005UNS011", 2, 2, false);
        a.failed("20261005UNS011", 3, "503");

        ZenonTransferRuns b = new ZenonTransferRuns(() -> f);   // 재기동 흉내

        assertThat(b.isOpen("20261005UNS011")).isTrue();
        assertThat(b.get("20261005UNS011").deliveredSeq).isEqualTo(2);
        assertThat(b.get("20261005UNS011").deliveredRecords).isEqualTo(4);
        assertThat(b.get("20261005UNS011").failedSeq).isEqualTo(3);
    }
}
