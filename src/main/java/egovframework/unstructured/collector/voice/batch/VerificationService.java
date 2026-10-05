package egovframework.unstructured.collector.voice.batch;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.common.config.DeployEnvPreset;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.voice.stt.SttTempStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 장애·재처리 검증 패널 — <b>DB 에 무엇이 남았고 디스크에 무엇이 남았는가</b>를 한 장으로.
 *
 * <p>배치 응답만 보면 "성공 9 · 실패 1" 까지는 알지만, 그 결과가 <b>실제로</b> 로그 테이블과
 * PV 에 어떻게 남았는지는 따로 DB 툴과 터미널을 열어야 확인할 수 있었다. 재처리 시나리오의
 * 요점이 "실패한 건의 중간 산출물이 남아 있다가 재처리 후 사라진다" 인데, 그걸 눈으로 보려면
 * 세 군데를 오가야 했다.</p>
 *
 * <p>여기서 네 가지를 같이 돌려준다.</p>
 * <ol>
 *   <li><b>DB</b> — 로그 컬렉터 {@code GET /api/v1/logs/batches/{execId}} 를 통해 T1·T2(3단계 COLLECT·ANALYZE·SEND)·T4,
 *       {@code …/file-procs} 로 <b>T4 단계별 이력</b>(파일마다 끝난 단계 {@code STEP_TYPE_CD}).
 *       이 서비스는 로그 DB 에 직접 붙지 않는다(적재도 조회도 컬렉터 API 로만). 클라우드 전송·비식별화가 빠져
 *       (2026-10-01) T5(비식별·전송 로그)는 더 남지 않는다.</li>
 *   <li><b>PV 파일</b> — 복호화 보존물·전사 보존물. 성공한 건은 제논 전송 뒤 지워지므로(Purge) 실패한 건만 남는다.</li>
 *   <li><b>제논</b> — 이 배치가 보낸 수신증(메모리).</li>
 *   <li><b>손으로 확인할 명령</b> — 위를 조회한 것과 같은 SQL 과 {@code ls}/{@code cat} 명령.
 *       화면 숫자를 믿지 못하겠으면 그대로 복사해 DB 툴·터미널에서 돌려 보면 된다.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class VerificationService {

    /** 개발계 파드를 가리키는 kubectl 접두 — 차트의 배포 이름과 같다. */
    private static final String KUBECTL = "kubectl -n data-pipeline exec deploy/unstructured-collector-dvlx15mqnc -- ";
    /** 파일 목록의 처음·끝에서 보일 개수. */
    private static final int EDGE = 2;
    /** T4 단계별 이력에 싣는 행 상한 — 단계별 건수는 컬렉터 집계라 상한과 무관하게 정확하다. */
    static final int T4_ROWS = 300;
    /** {@code [코드] [단계] 사유} — 수집기가 ERR_STACK 에 남기는 모양(로그 컬렉터 정규화 뒤에도 같다). */
    private static final java.util.regex.Pattern ERR_STEP =
            java.util.regex.Pattern.compile("^(?:\\[[A-Z_]+\\]\\s*)?\\[(COLLECT|ANALYZE|SEND)\\]");
    private static final List<String> STEP_ORDER = List.of("COLLECT", "ANALYZE", "SEND");

    private final VoiceDirState dirs;
    private final LogCollectorClient logCollector;
    private final DeployEnvPreset deployEnv;
    private final SttTempStore sttTemp;
    private final egovframework.unstructured.collector.common.transfer.ZenonClient zenon;

    public Map<String, Object> verify(String execId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", execId);
        out.put("env", deployEnv.kind().name());
        out.put("db", db(execId));
        out.put("files", files(execId));
        out.put("zenon", zenon(execId));
        out.put("sql", sql(execId));
        out.put("cli", cli(execId));
        return out;
    }

    // ── DB ────────────────────────────────────────────────────────────────

    /** DB(T1·T2·T4) 대조만 — 성능 시험이 끝난 뒤 정합성 점검에서도 쓴다. */
    public Map<String, Object> db(String execId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!logCollector.isEnabled()) {
            m.put("available", false);
            m.put("reason", "로그 컬렉터 미연동 — 이력이 DB 에 남지 않는 구성입니다(log-collector.enabled=false)");
            return m;
        }
        if (!StringUtils.hasText(execId)) {
            m.put("available", false);
            m.put("reason", "EXEC_ID 가 없습니다 — 배치를 먼저 실행하십시오");
            return m;
        }
        JsonNode d = logCollector.batchDetail(execId);
        if (d == null || d.isNull() || d.path("batch").isMissingNode()) {
            m.put("available", false);
            m.put("reason", "컬렉터에서 이 EXEC_ID 를 찾지 못했습니다 — 로컬 임시 ID 이거나(컬렉터 호출 실패 시) 이미 초기화됐습니다");
            return m;
        }
        m.put("available", true);

        JsonNode b = d.path("batch");
        Map<String, Object> t1 = new LinkedHashMap<>();
        t1.put("execStsCd", text(b, "exec_sts_cd"));
        t1.put("execTypeCd", text(b, "exec_type_cd"));
        t1.put("dataTypeCd", text(b, "data_type_cd"));
        t1.put("targetCnt", num(b, "target_cnt"));
        t1.put("successCnt", num(b, "success_cnt"));
        t1.put("failCnt", num(b, "fail_cnt"));
        t1.put("startDtm", text(b, "start_dtm"));
        t1.put("endDtm", text(b, "end_dtm"));
        m.put("t1", t1);

        List<Map<String, Object>> t2 = new ArrayList<>();
        for (JsonNode s : d.path("steps")) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("stepSeq", num(s, "step_seq"));
            one.put("stepTypeCd", text(s, "step_type_cd"));
            one.put("stepStsCd", text(s, "step_sts_cd"));
            one.put("inCnt", num(s, "in_cnt"));
            one.put("outCnt", num(s, "out_cnt"));
            one.put("errCnt", num(s, "err_cnt"));
            t2.add(one);
        }
        m.put("t2", t2);

        JsonNode rc = d.path("reconcile");
        JsonNode ss = d.path("statusSummary");
        Map<String, Object> t4 = new LinkedHashMap<>();
        t4.put("total", num(rc, "file_cnt"));
        t4.put("byStatus", counts(ss.path("fileByStatus")));
        m.put("t4", t4);
        // 컬렉터가 옛 버전이면 상태별 집계가 없다 — 건수만 보이고, 화면이 그 사실을 말한다
        m.put("statusSummarySupported", !ss.isMissingNode());

        // [바로 실행] 워터마크 — 이 배치와 같은 작업(job_id)의 SUCCESS 배치 MAX(target_to_dtm).
        String jobId = text(b, "job_id");
        LogCollectorClient.Watermark w = logCollector.watermark(text(b, "data_type_cd"), jobId);
        Map<String, Object> wm = new LinkedHashMap<>();
        wm.put("jobId", jobId);
        wm.put("at", w == null ? null : w.at().toString());
        wm.put("execId", w == null ? null : w.execId());
        wm.put("targetToDtm", text(b, "target_to_dtm"));
        m.put("watermark", wm);
        m.put("t4Steps", t4Steps(execId));
        return m;
    }

    /**
     * T4 단계별 이력 — 로그 컬렉터 T4 행의 {@code STEP_TYPE_CD}(V16)를 그대로 읽는다.
     *
     * <p>DB 에 V16 이 아직 없으면(컬렉터가 {@code stepColumn=false}) 실패 행은 ERR_STACK 의 {@code [단계]}, 성공 행은 마지막 단계
     * {@code SEND} 로 <b>추정</b>해 채우고 {@code source=ERR_STACK} 으로 표시한다. T4 조회 API 가 없는 옛 컬렉터면 {@code available=false}.</p>
     */
    public Map<String, Object> t4Steps(String execId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!logCollector.isEnabled() || !StringUtils.hasText(execId)) {
            m.put("available", false);
            m.put("reason", !logCollector.isEnabled() ? "로그 컬렉터 미연동(log-collector.enabled=false)" : "EXEC_ID 가 없습니다");
            return m;
        }
        JsonNode r = logCollector.fileProcs(execId, T4_ROWS);
        if (r == null || r.isNull() || !r.path("rows").isArray()) {
            m.put("available", false);
            m.put("reason", "로그 컬렉터에서 T4 행을 읽지 못했습니다 — 이 EXEC_ID 가 없거나(로컬 임시 ID · 초기화됨) "
                    + "로그 컬렉터가 T4 조회 API(GET …/file-procs · 2026-10-03) 이전 버전입니다");
            return m;
        }
        boolean column = r.path("stepColumn").asBoolean(false);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Long>> derived = new LinkedHashMap<>();
        for (JsonNode x : r.path("rows")) {
            String sts = text(x, "proc_sts_cd");
            String step = text(x, "step_type_cd");
            String source = step != null ? "COLUMN" : null;
            if (step == null && !column) {
                step = "SUCCESS".equals(sts) ? "SEND" : stepOf(text(x, "err_stack"));
                source = step == null ? null : "ERR_STACK";
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("fileProcId", text(x, "file_proc_id"));
            one.put("recFileId", text(x, "rec_file_id"));
            one.put("fileNm", text(x, "file_nm"));
            one.put("procStsCd", sts);
            one.put("stepTypeCd", step);
            one.put("stepTypeNm", text(x, "step_type_nm"));
            one.put("stepSource", source);
            String err = text(x, "err_stack");
            one.put("errStack", err != null && err.length() > 300 ? err.substring(0, 300) + "…" : err);
            rows.add(one);
            derived.computeIfAbsent(step == null ? "(없음)" : step, k -> new LinkedHashMap<>()).merge(String.valueOf(sts), 1L, Long::sum);
        }
        Map<String, Map<String, Long>> byStep = new LinkedHashMap<>();
        if (column && r.path("byStep").isObject()) {
            r.path("byStep").fields().forEachRemaining(e -> byStep.put(e.getKey(), counts(e.getValue())));
        } else {
            byStep.putAll(derived);
        }
        m.put("available", true);
        m.put("stepColumn", column);
        m.put("source", column ? "COLUMN" : "ERR_STACK");
        m.put("byStep", ordered(byStep));
        m.put("byStatus", counts(r.path("byStatus")));
        m.put("truncated", r.path("truncated").asBoolean(false));
        m.put("rows", rows);
        return m;
    }

    /** ERR_STACK 의 {@code [단계]} — 없으면 null. */
    static String stepOf(String errStack) {
        if (errStack == null) {
            return null;
        }
        java.util.regex.Matcher mt = ERR_STEP.matcher(errStack.trim());
        return mt.find() ? mt.group(1) : null;
    }

    /** 단계 순서 — COLLECT · ANALYZE · SEND 다음 나머지(이름순). */
    private static Map<String, Map<String, Long>> ordered(Map<String, Map<String, Long>> in) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        STEP_ORDER.forEach(s -> { if (in.containsKey(s)) out.put(s, in.get(s)); });
        in.keySet().stream().filter(k -> !STEP_ORDER.contains(k)).sorted().forEach(k -> out.put(k, in.get(k)));
        return out;
    }

    private static Map<String, Long> counts(JsonNode n) {
        Map<String, Long> m = new LinkedHashMap<>();
        if (n != null && n.isObject()) {
            n.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asLong()));
        }
        return m;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Long num(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asLong();
    }

    // ── PV 파일 ───────────────────────────────────────────────────────────

    private Map<String, Object> files(String execId) {
        Map<String, Object> m = new LinkedHashMap<>();
        // 복호화 보존물 — 실패한 건의 STT 직전 오디오(ANALYZE 재처리용). 같은 폴더의 다른 파일은 세지 않는다
        m.put("decoding", listing(Path.of(dirs.work()), "decrypted_"));
        // 전사 보존물 — SEND(제논 전송) 재처리용. 배치 폴더 단위로 남는다
        m.put("sttTemp", listing(sttTemp.dir(execId), null));
        m.put("sttTempAll", sttTemp.status());
        return m;
    }

    /** 제논 수신증 — 이 배치가 보낸 것(메모리에 남은 만큼). 결과는 PV 에 남기지 않는다. */
    private Map<String, Object> zenon(String execId) {
        Map<String, Object> m = new LinkedHashMap<>(zenon.status());
        List<egovframework.unstructured.collector.common.transfer.ZenonClient.Receipt> r = zenon.receipts(execId);
        Map<String, Long> byKind = new LinkedHashMap<>();
        for (var one : r) {
            Object k = one.metadata() == null ? null : one.metadata().get("kind");
            byKind.merge(String.valueOf(k), 1L, Long::sum);
        }
        m.put("count", r.size());
        m.put("byKind", byKind);
        m.put("sample", r.stream().limit(EDGE).toList());
        return m;
    }

    private static Map<String, Object> listing(Path dir, String prefix) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", dir.toString().replace('\\', '/'));
        boolean exists = Files.isDirectory(dir);
        m.put("exists", exists);
        List<String> head = new ArrayList<>(EDGE);
        java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>(EDGE);
        int count = 0;
        if (exists) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                    String n = p.getFileName().toString();
                    if (prefix != null && !n.startsWith(prefix)) {
                        continue;
                    }
                    count++;
                    if (head.size() < EDGE) {
                        head.add(n);
                        continue;
                    }
                    if (tail.size() == EDGE) {
                        tail.removeFirst();
                    }
                    tail.addLast(n);
                }
            } catch (IOException ignored) {
                // 목록을 못 읽어도 검증 화면이 에러를 내면 안 된다 — 0건으로 보인다
            }
        }
        m.put("count", count);
        // 300건이면 파일이 600개다 — 전부 늘어놓지 않고 처음·끝만 보인다(건마다 .json·.txt 한 쌍이라 둘씩)
        m.put("head", head);
        m.put("tail", List.copyOf(tail));
        return m;
    }

    // ── 손으로 확인할 명령 ─────────────────────────────────────────────────

    private static List<Map<String, String>> sql(String execId) {
        String id = StringUtils.hasText(execId) ? execId.replace("'", "''") : "{EXEC_ID}";
        List<Map<String, String>> l = new ArrayList<>();
        l.add(cmd("T1 배치 실행 이력 (kcais.tb_batch_exec_log)",
                "SELECT exec_id, job_id, data_type_cd, exec_type_cd, exec_sts_cd,\n"
                        + "       target_from_dtm, target_to_dtm,\n"
                        + "       target_cnt, success_cnt, fail_cnt, start_dtm, end_dtm\n"
                        + "  FROM kcais.tb_batch_exec_log\n"
                        + " WHERE exec_id = '" + id + "';"));
        l.add(cmd("T2 단계 이력 (kcais.tb_batch_step_log)",
                "SELECT step_seq, step_type_cd, step_sts_cd, in_cnt, out_cnt, err_cnt, elapsed_sec\n"
                        + "  FROM kcais.tb_batch_step_log\n"
                        + " WHERE exec_id = '" + id + "'\n"
                        + " ORDER BY step_seq;"));
        l.add(cmd("T4 파일 처리 이력 — 상태별 (kcais.tb_file_proc_log)",
                "SELECT proc_sts_cd, COUNT(*)\n"
                        + "  FROM kcais.tb_file_proc_log\n"
                        + " WHERE exec_id = '" + id + "'\n"
                        + " GROUP BY proc_sts_cd;"));
        l.add(cmd("T4 파일 처리 이력 — 단계별 (STEP_TYPE_CD · V16)",
                "SELECT step_type_cd, proc_sts_cd, COUNT(*)\n"
                        + "  FROM kcais.tb_file_proc_log\n"
                        + " WHERE exec_id = '" + id + "'\n"
                        + " GROUP BY step_type_cd, proc_sts_cd\n"
                        + " ORDER BY step_type_cd, proc_sts_cd;"));
        l.add(cmd("T4 파일 처리 이력 — 건별 (단계 · 실패 사유 포함)",
                "SELECT file_proc_id, rec_file_id, proc_sts_cd, step_type_cd, LEFT(err_stack, 200) AS err\n"
                        + "  FROM kcais.tb_file_proc_log\n"
                        + " WHERE exec_id = '" + id + "'\n"
                        + " ORDER BY file_proc_id;"));
        l.add(cmd("[바로 실행] 워터마크 — SUCCESS 배치의 MAX(target_to_dtm)",
                "SELECT MAX(target_to_dtm) AS watermark\n"
                        + "  FROM kcais.tb_batch_exec_log\n"
                        + " WHERE exec_sts_cd = 'SUCCESS'\n"
                        + "   AND data_type_cd = 'UNSTRUCTURED'\n"
                        + "   AND job_id = (SELECT job_id FROM kcais.tb_batch_exec_log WHERE exec_id = '" + id + "');"));
        return l;
    }

    private List<Map<String, String>> cli(String execId) {
        String id = StringUtils.hasText(execId) ? execId : "{EXEC_ID}";
        boolean k8s = deployEnv.kind() == DeployEnvPreset.Kind.K8S;
        // 로컬은 Git Bash 기준(ls/cat). 경로는 이 서버가 실제로 쓰는 값 그대로다
        String pre = k8s ? KUBECTL : "";
        String work = slash(dirs.work());
        String temp = slash(sttTemp.dir(id).toString());

        List<Map<String, String>> l = new ArrayList<>();
        l.add(cmd("복호화 보존물 (ANALYZE 재처리용)", pre + "ls -la " + q(work, k8s)));
        l.add(cmd("전사 보존물 (SEND 재처리용 — 성공한 건은 전송 뒤 지워진다)", pre + "ls -la " + q(temp, k8s)));
        l.add(cmd("제논 전송 수신증 (이 배치)",
                "curl -s '" + (k8s ? "https://<admin-fe>/voice" : "http://localhost:8085")
                        + "/api/v1/mock/zenon/receipts?execId=" + id + "'"));
        l.add(cmd("제논 수신 장부 (이 배치 런 — 순번 · 빈 순번 · 상태)",
                "curl -s '" + (k8s ? "https://<admin-fe>/voice" : "http://localhost:8085")
                        + "/api/v1/mock/zenon/ledger?runId=" + id + "'"));
        l.add(cmd("제논 목 서버가 받은 청크 (REST 모드 · tools/zenon-mock)", "ls -la ./mock_received_files/" + id));
        return l;
    }

    private static Map<String, String> cmd(String label, String text) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("text", text);
        return m;
    }

    private static String slash(String p) {
        return p.replace('\\', '/');
    }

    /** 로컬 경로엔 공백이 있을 수 있어 따옴표로 감싼다. 파드 안 경로는 그대로 둔다. */
    private static String q(String p, boolean k8s) {
        return k8s ? p : "\"" + p + "\"";
    }
}
