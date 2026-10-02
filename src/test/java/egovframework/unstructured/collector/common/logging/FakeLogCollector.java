package egovframework.unstructured.collector.common.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 테스트용 <b>가짜 로그 컬렉터</b> — 실제 HTTP 로 수집기의 T1 · T2 · T4 호출을 받아 H2 표에 넣는다.
 *
 * <p>진짜 로그 컬렉터는 PostgreSQL(kcais) 전용이라 로컬 · CI 에서 띄울 수 없다. 경로 · 요청 본문 · 응답 봉투
 * ({@code {success, code, result}})는 로그 컬렉터 {@code origin/dev} 와 같게 맞췄고, 표 이름 · 컬럼도 kcais 스키마를 따른다.
 * <b>T4 에는 {@code step_type_cd} 가 있다</b> — 로그 컬렉터 V16(2026-10-02)과 같다.
 * 컬럼이 생긴 뒤의 모습을 미리 보는 것이고, 실패 단계는 컬럼과 상관없이 {@code err_stack} 의 {@code [단계]} 로도 남는다.</p>
 *
 * <p>채번은 로그 컬렉터 규칙을 흉내 낸다 — {@code yyyyMMdd + (TEST_BATCH 면 TST, 아니면 UNS) + 회차 3자리} (컬렉터 {@code DataTypeCd.UNSTRUCTURED} — 2026-10-02 VOC → UNS).</p>
 */
public final class FakeLogCollector implements AutoCloseable {

    private static final Pattern BATCH = Pattern.compile(".*/api/v1/logs/batches/([^/]+)$");
    private static final Pattern STEPS = Pattern.compile(".*/api/v1/logs/batches/([^/]+)/steps$");
    private static final Pattern FILES = Pattern.compile(".*/api/v1/logs/batches/([^/]+)/file-procs$");
    private static final Pattern STEP = Pattern.compile(".*/api/v1/logs/steps/([^/]+)$");

    private final HttpServer server;
    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();
    private final AtomicInteger seq = new AtomicInteger();

    private FakeLogCollector(HttpServer server, JdbcTemplate jdbc) {
        this.server = server;
        this.jdbc = jdbc;
    }

    public static FakeLogCollector start() throws IOException {
        return start(0);
    }

    /**
     * 혼자 띄운다 — 로컬 실검증용(수집기 {@code --log-collector.enabled=true --log-collector.base-url=http://127.0.0.1:포트/logc}).
     * 쌓인 행은 {@code GET /logc/__rows/{표}} 로 본다(tb_batch_exec_log · tb_batch_step_log · tb_file_proc_log).
     */
    public static void main(String[] args) throws Exception {
        FakeLogCollector f = start(args.length > 0 ? Integer.parseInt(args[0]) : 18096);
        System.out.println("[fake-logc] " + f.baseUrl() + " (H2) — 표 조회 GET " + f.baseUrl() + "/__rows/{표}");
        Thread.currentThread().join();
    }

    public static FakeLogCollector start(int port) throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:fakelogc-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE tb_batch_exec_log (exec_id VARCHAR(30) PRIMARY KEY, job_id VARCHAR(50), job_nm VARCHAR(100),"
                + " data_type_cd VARCHAR(30), exec_type_cd VARCHAR(20), trigger_by VARCHAR(200), target_from_dtm VARCHAR(30),"
                + " target_to_dtm VARCHAR(30), exec_sts_cd VARCHAR(20), target_cnt BIGINT, success_cnt BIGINT, fail_cnt BIGINT,"
                + " err_msg VARCHAR(1000))");
        jdbc.execute("CREATE TABLE tb_batch_step_log (step_log_id VARCHAR(40) PRIMARY KEY, exec_id VARCHAR(30) NOT NULL,"
                + " step_seq SMALLINT, step_type_cd VARCHAR(20), step_sts_cd VARCHAR(20), in_cnt BIGINT, out_cnt BIGINT,"
                + " err_cnt BIGINT, err_stack VARCHAR(1000))");
        jdbc.execute("CREATE TABLE tb_file_proc_log (file_proc_id VARCHAR(40) PRIMARY KEY, exec_id VARCHAR(30) NOT NULL,"
                + " rec_file_id VARCHAR(50) NOT NULL, file_path VARCHAR(500), file_nm VARCHAR(200), inmate_pid VARCHAR(50),"
                + " file_size BIGINT, proc_sts_cd VARCHAR(20) NOT NULL, err_stack VARCHAR(1000), step_type_cd VARCHAR(20))");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        FakeLogCollector f = new FakeLogCollector(server, jdbc);
        server.createContext("/", f::handle);
        server.start();
        return f;
    }

    /** {@code log-collector.base-url} 에 넣을 주소(context-path {@code /logc} 포함). */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/logc";
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** 행을 소문자 키 맵으로. */
    public List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, args)) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(k.toLowerCase(Locale.ROOT), v));
            out.add(m);
        }
        return out;
    }

    public void clear() {
        jdbc.update("DELETE FROM tb_file_proc_log");
        jdbc.update("DELETE FROM tb_batch_step_log");
        jdbc.update("DELETE FROM tb_batch_exec_log");
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ── 처리 ──────────────────────────────────────────────────────────────

    private synchronized void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        byte[] in = ex.getRequestBody().readAllBytes();
        JsonNode body = in.length == 0 ? om.nullNode() : om.readTree(in);
        Object result;
        Matcher m;
        try {
            if ("GET".equals(method) && path.contains("/__rows/")) {
                String table = path.substring(path.indexOf("/__rows/") + 8);
                if (!List.of("tb_batch_exec_log", "tb_batch_step_log", "tb_file_proc_log").contains(table)) {
                    throw new IllegalArgumentException("모르는 표: " + table);
                }
                result = rows("SELECT * FROM " + table);
            } else if ("POST".equals(method) && path.endsWith("/api/v1/logs/batches")) {
                result = createBatch(body);
            } else if ("GET".equals(method) && path.endsWith("/api/v1/logs/batches/watermark")) {
                ObjectNode w = om.createObjectNode();
                w.putNull("watermark");
                result = w;
            } else if ("POST".equals(method) && (m = STEPS.matcher(path)).matches()) {
                result = createStep(m.group(1), body);
            } else if ("POST".equals(method) && (m = FILES.matcher(path)).matches()) {
                result = createFileProcs(m.group(1), body);
            } else if ("PATCH".equals(method) && (m = STEP.matcher(path)).matches()) {
                jdbc.update("UPDATE tb_batch_step_log SET step_sts_cd=?, in_cnt=?, out_cnt=?, err_cnt=?, err_stack=? WHERE step_log_id=?",
                        text(body, "stepStsCd"), num(body, "inCnt"), num(body, "outCnt"), num(body, "errCnt"),
                        text(body, "errStack"), m.group(1));
                result = Map.of("stepLogId", m.group(1));
            } else if ("PATCH".equals(method) && (m = BATCH.matcher(path)).matches()) {
                jdbc.update("UPDATE tb_batch_exec_log SET exec_sts_cd=?, target_cnt=?, success_cnt=?, fail_cnt=?, err_msg=? WHERE exec_id=?",
                        text(body, "execStsCd"), num(body, "targetCnt"), num(body, "successCnt"), num(body, "failCnt"),
                        text(body, "errMsg"), m.group(1));
                result = Map.of("execId", m.group(1));
            } else if ("GET".equals(method) && (m = BATCH.matcher(path)).matches()) {
                List<Map<String, Object>> b = rows("SELECT * FROM tb_batch_exec_log WHERE exec_id = ?", m.group(1));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("batch", b.isEmpty() ? null : b.get(0));
                d.put("steps", rows("SELECT * FROM tb_batch_step_log WHERE exec_id = ? ORDER BY step_seq", m.group(1)));
                result = d;
            } else {
                result = Map.of();
            }
            respond(ex, 200, Map.of("success", true, "code", 0, "result", result));
        } catch (Exception e) {
            respond(ex, 500, Map.of("success", false, "code", 500, "error_message", String.valueOf(e.getMessage())));
        }
    }

    private Map<String, Object> createBatch(JsonNode b) {
        String code = "TEST_BATCH".equalsIgnoreCase(text(b, "jobId")) ? "TST" : "UNS";
        String execId = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + code + "%03d".formatted(seq.incrementAndGet());
        String jobNm = text(b, "jobNm") == null ? text(b, "jobId") : text(b, "jobNm");
        jdbc.update("INSERT INTO tb_batch_exec_log (exec_id, job_id, job_nm, data_type_cd, exec_type_cd, trigger_by,"
                        + " target_from_dtm, target_to_dtm, exec_sts_cd) VALUES (?,?,?,?,?,?,?,?, 'RUNNING')",
                execId, text(b, "jobId"), jobNm, text(b, "dataTypeCd"), text(b, "execTypeCd"), text(b, "triggerBy"),
                text(b, "targetFromDtm"), text(b, "targetToDtm"));
        return Map.of("execId", execId);
    }

    private Map<String, Object> createStep(String execId, JsonNode b) {
        String id = execId + "-S" + text(b, "stepTypeCd");
        jdbc.update("INSERT INTO tb_batch_step_log (step_log_id, exec_id, step_seq, step_type_cd, step_sts_cd) VALUES (?,?,?,?, 'RUNNING')",
                id, execId, num(b, "stepSeq"), text(b, "stepTypeCd"));
        return Map.of("stepLogId", id);
    }

    private Map<String, Object> createFileProcs(String execId, JsonNode list) {
        List<String> ids = new ArrayList<>();
        for (JsonNode r : list) {
            String id = execId + "-F" + "%04d".formatted(ids.size() + 1);
            jdbc.update("INSERT INTO tb_file_proc_log (file_proc_id, exec_id, rec_file_id, file_path, file_nm, inmate_pid, file_size,"
                            + " proc_sts_cd, err_stack, step_type_cd) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id, execId, text(r, "recFileId"), text(r, "filePath"), text(r, "fileNm"), text(r, "inmatePid"),
                    num(r, "fileSize"), text(r, "procStsCd"), text(r, "errStack"), text(r, "stepTypeCd"));
            ids.add(id);
        }
        return Map.of("count", ids.size(), "ids", ids);
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Long num(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asLong();
    }

    private void respond(HttpExchange ex, int status, Object body) throws IOException {
        byte[] out = om.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }
}
