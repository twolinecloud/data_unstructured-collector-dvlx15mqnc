package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.batch.UnstructuredJobRunner;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.transfer.TransmitHoldClient;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.voice.batch.BatchProgress;
import egovframework.unstructured.collector.voice.batch.VoiceBatchResult;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>개발계 E2E 데이터 주입</b> — 버튼 하나로 '어제(FAIL) → 오늘 오전(WAIT) → 방금(PARTIAL)' 같은 배치 묶음을 만든다(2026-10-16).
 *
 * <p>로그(T1 · T2 · T4)를 DB 에 직접 꽂지 않는다. <b>더미 원본을 좁은 구간에 만들고 그 구간만 실제 배치로 돌려</b> 진짜 이력 · 커넥터 보류 장부 ·
 * 재처리 보존물이 생기게 한다 — WAIT 은 커넥터 장부에 있어야 재처리 체인이 그 보류분을 보낼 수 있고, FAIL 은 원본과 복호화 오디오가 있어야
 * 다시 돈다. 배치는 요청 순서대로 돌아 실행 시각이 그 순서다(재처리 체인 = 오래된 순). 이력 화면 맨 위에 그 순서 그대로 뜬다.</p>
 *
 * <ul>
 *   <li>상태별 더미(접견 · DMY): FAIL = 2건 모두 전송 실패 표식 · WAIT = 정상 2건(전송 방식 수동이면 커넥터가 보류) ·
 *       PARTIAL = 3건 중 1건 분석 실패 표식(나머지 2건 보류)</li>
 *   <li>구간: 첫 배치 '어제'(지금-1일 · 10분) · 가운데 '오늘 오전'(10:00 부터 20분 간격 · 10분) · 마지막 '방금'(지금-11분 ~ 지금-1분).
 *       구간 안에 아직 처리 안 된 다른 건이 있으면 15분씩 앞으로 비켜 잡는다(그 배치가 남의 건을 집지 않게)</li>
 *   <li>정리(기본): 이전 E2E 묶음 + <b>재처리로 풀 수 없는 옛 더미 실패 배치</b>(음성: 실패 건 원본이 수집 구간에 하나도 없음 ·
 *       이미지: 실패 건이 DMYIMG 이고 날짜가 더미 보존 기간보다 오래됨) + 요청의 {@code extraExecIds} 를 지운다. 커넥터 장부에 남은 건
 *       먼저 보내 마감한다(장부가 가리키는 이력이 사라지지 않게). 실제 삭제는 로그 컬렉터가 안전 조건(비정형 · 실행 중 아님 · T4 전부 더미 키)을
 *       SQL 안에서 다시 건다</li>
 * </ul>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class E2eDummyService {

    /** T1 TRIGGER_BY — 이 API 가 만든 배치. 재처리하면 바뀌므로 묶음 기록은 PV 파일로 따로 한다. */
    public static final String TRIGGER_BY = "E2E-DUMMY";
    /** 이전 E2E 묶음 기록 — ROOT 기준. */
    public static final String FILE = "mock/e2e_dummy.json";
    static final int MAX_STATUSES = 6;
    static final String MSG_BUSY = "다른 배치가 실행 중입니다. 잠시 후 다시 시도해 주세요.";

    /** 만들 수 있는 배치 상태 — 건수 · 장애 표식. */
    public enum Status {
        FAIL(2, Map.of(FailureScenario.SEND_FAIL, 2), "2건 모두 전송 실패 표식(커넥터에 닿기 전 실패)"),
        WAIT(2, Map.of(), "정상 2건 — 전송 방식이 수동이면 커넥터가 보류(전송 대기)"),
        PARTIAL(3, Map.of(FailureScenario.ANALYZE_FAIL, 1), "3건 중 1건 분석 실패 표식 · 나머지 2건 보류");

        final int count;
        final Map<FailureScenario, Integer> failures;
        final String note;

        Status(int count, Map<FailureScenario, Integer> failures, String note) {
            this.count = count;
            this.failures = failures;
            this.note = note;
        }
    }

    /** 요청 — 상태 순서(오래된 것부터) · 정리 여부 · 함께 지울 배치. */
    public record Request(List<String> statuses, Boolean cleanup, List<String> extraExecIds) {}

    private final DummyDataService dummy;
    private final VoiceCollectService voice;
    private final LogCollectorClient logCollector;
    private final TransmitHoldClient holds;
    private final UnstructuredJobRunner runner;
    private final BatchProgress voiceProgress;
    private final ImageCollectService image;
    private final MockDataProperties mockProps;
    private final VoiceDirState dirs;
    private final ObjectMapper objectMapper;

    public synchronized Map<String, Object> run(Request req) {
        List<Status> statuses = parse(req == null ? null : req.statuses());
        boolean cleanup = req == null || req.cleanup() == null || req.cleanup();
        if (runner.isRunning() || voiceProgress.isRunning() || image.isRunning()) {
            throw new BusyException(MSG_BUSY);
        }
        if (!logCollector.isEnabled()) {
            throw new IllegalStateException("로그 컬렉터 미연동 — 이력을 남길 수 없어 E2E 데이터를 만들지 않습니다");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statuses", statuses.stream().map(Enum::name).toList());
        out.put("cleanup", cleanup ? cleanup(req == null ? List.of() : req.extraExecIds()) : Map.of("skipped", true));

        LocalDateTime now = LocalDateTime.now().withNano(0);
        List<Map<String, Object>> batches = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        LocalDateTime prevEnd = null;
        for (int i = 0; i < statuses.size(); i++) {
            Status st = statuses.get(i);
            String label = labelOf(i, statuses.size());
            LocalDateTime[] w = freeWindow(plannedEnd(i, statuses.size(), now), prevEnd);
            prevEnd = w[1];
            Map<String, Object> g = dummy.generate(new DummyDataService.GenerateRequest(DummyTarget.DASHBOARD, List.of(DummyDataType.MEET),
                    w[0].toLocalDate(), st.count, st.failures, true, null), w[0], w[1]);
            VoiceBatchResult v = voice.run(BatchWindow.manual(w[0], w[1]), List.of(VoiceKind.MEET), TRIGGER_BY, false);
            ids.add(v.execId());
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("order", i + 1);
            b.put("label", label);
            b.put("requested", st.name());
            b.put("execId", v.execId());
            b.put("window", Map.of("from", w[0].toString(), "to", w[1].toString()));
            b.put("dummy", st.note);
            b.put("keys", keysOf(g));
            b.put("t1", v.execStsCd());
            b.put("send", sendStatus(v.execId()));
            b.put("counts", Map.of("target", v.targetCnt(), "success", v.successCnt(), "fail", v.failCnt(), "skipped", v.skippedCnt()));
            b.put("asRequested", matches(st, v.execStsCd(), (String) b.get("send")));
            batches.add(b);
            log.info("[E2E] {} {} — {} {} ~ {} · T1 {} · SEND {}", i + 1, label, v.execId(), w[0], w[1], v.execStsCd(), b.get("send"));
        }
        save(ids);
        out.put("batches", batches);
        out.put("holds", holds.holds());
        out.put("next", List.of(
                "관리 화면 배치 수행 이력 — 맨 위 " + ids.size() + "건(위가 최신 '" + labelOf(ids.size() - 1, ids.size()) + "')",
                "맨 위 배치의 [긴급 재처리] → 체인이 오래된 순(" + String.join(" → ", ids) + ")으로 처리",
                "확인: GET /internal/batch/status 의 job.result · 로그 컬렉터 GET /logc/api/v1/logs/batches/{execId}"));
        if (batches.stream().anyMatch(b -> Boolean.FALSE.equals(b.get("asRequested")))) {
            out.put("warning", "요청한 상태와 다르게 끝난 배치가 있습니다 — 전송 방식이 자동이면 WAIT(전송 대기)가 생기지 않습니다");
        }
        return out;
    }

    // ── 정리 ───────────────────────────────────────────────────────────────

    /** 이전 E2E 묶음 + 재처리로 풀 수 없는 옛 더미 실패 배치 + 요청 배치를 지운다(장부에 남은 건 먼저 보내 마감). */
    Map<String, Object> cleanup(List<String> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> previous = new LinkedHashSet<>(load());
        Map<String, String> garbage = findUnresolvable();
        Set<String> targets = new LinkedHashSet<>(previous);
        targets.addAll(garbage.keySet());
        if (extra != null) {
            extra.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).forEach(targets::add);
        }
        out.put("previousE2e", previous);
        out.put("unresolvable", garbage);
        out.put("extra", extra == null ? List.of() : extra);
        Set<String> held = holds.holds();
        List<Map<String, Object>> released = new ArrayList<>();
        if (held != null) {
            for (String id : List.copyOf(targets)) {
                if (!held.contains(id)) {
                    continue;
                }
                TransmitHoldClient.Release r = holds.release(id, "e2e-cleanup");
                released.add(Map.of("execId", id, "sent", r.sent(), "http", r.httpStatus()));
                if (!r.sent()) {
                    targets.remove(id);   // 장부가 남아 있는데 이력을 지우면 장부가 가리킬 곳이 없어진다
                }
            }
        }
        out.put("releasedFirst", released);
        JsonNode del = targets.isEmpty() ? null : logCollector.deleteDummyBatches(List.copyOf(targets));
        out.put("deleted", del == null ? List.of() : del.path("deleted"));
        out.put("skipped", del == null ? List.of() : del.path("skipped"));
        out.put("rows", del == null ? Map.of() : del.path("rows"));
        if (!targets.isEmpty() && del == null) {
            out.put("error", "로그 컬렉터 정리 호출 실패");
        }
        log.info("[E2E] 정리 — 이전 묶음 {} · 풀 수 없는 옛 배치 {} · 요청 {} · 지움 {}", previous, garbage.keySet(), extra,
                del == null ? "-" : del.path("deleted"));
        return out;
    }

    /**
     * 재처리로 풀 수 없는 비정형 더미 실패 배치 — 미완료(FAIL · PARTIAL · CANCELED) 중 실패 건이 있고 전부 더미 키인데,
     * 음성은 실패 건 원본이 수집 구간에 하나도 없고, 이미지는 실패 건(DMYIMG) 날짜가 더미 보존 기간보다 오래된 것.
     *
     * @return 실행 ID → 판정 사유
     */
    Map<String, String> findUnresolvable() {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode rows = logCollector.listBatches("UNSTRUCTURED", "FAIL,PARTIAL,CANCELED", 200);
        if (rows == null) {
            return out;
        }
        int keep = mockProps.daily().retainDays() > 0 ? mockProps.daily().retainDays() : 7;
        LocalDate cut = LocalDate.now().minusDays(keep);
        for (JsonNode r : rows) {
            String id = r.path("execId").asText("");
            String job = r.path("jobId").asText("");
            boolean isVoice = "VOICE_ANALYSIS".equals(job);
            if (id.isBlank() || !(isVoice || "IMAGE_COLLECT".equals(job))) {
                continue;
            }
            JsonNode t4 = logCollector.fileProcs(id, 5000);
            if (t4 == null || !t4.path("rows").isArray() || t4.path("rows").isEmpty()) {
                continue;
            }
            Set<String> failed = new LinkedHashSet<>();
            boolean allDummy = true;
            for (JsonNode f : t4.path("rows")) {
                String key = f.path("rec_file_id").asText("");
                allDummy &= isDummyKey(key);
                if ("FAIL".equals(f.path("proc_sts_cd").asText(""))) {
                    failed.add(key);
                }
            }
            if (failed.isEmpty() || !allDummy) {
                continue;
            }
            if (isVoice) {
                LocalDateTime from = dtm(r.path("targetFromDtm"));
                LocalDateTime to = dtm(r.path("targetToDtm"));
                if (from != null && to != null && from.isBefore(to)
                        && voice.noReopenTargets(BatchWindow.manual(from, to), failed, false)) {
                    out.put(id, "음성 실패 " + failed.size() + "건의 원본이 수집 구간에 없음");
                }
            } else if (failed.stream().allMatch(k -> k.startsWith("DMYIMG")
                    && DummyKeys.dashboardDateOf(k).map(d -> d.isBefore(cut)).orElse(false))) {
                out.put(id, "이미지 실패 " + failed.size() + "건이 더미 보존 기간(" + keep + "일) 이전");
            }
        }
        return out;
    }

    static boolean isDummyKey(String key) {
        return key.startsWith("DMY-") || key.startsWith("SIM-") || key.startsWith("DMYIMG") || key.startsWith("SIMIMG");
    }

    // ── 구간 ───────────────────────────────────────────────────────────────

    static String labelOf(int i, int n) {
        if (i == n - 1) {
            return "방금";
        }
        return i == 0 ? "어제" : "오늘 오전";
    }

    /** i 번째 배치 구간의 끝(계획) — 첫 배치 지금-1일 · 마지막 지금-1분 · 가운데 오늘 10:00 부터 20분 간격(너무 늦으면 지금 앞으로). */
    static LocalDateTime plannedEnd(int i, int n, LocalDateTime now) {
        LocalDateTime t = now.withSecond(0);
        if (i == n - 1) {
            return t.minusMinutes(1);
        }
        if (i == 0 && n > 1) {
            return t.minusDays(1);
        }
        LocalDateTime end = t.toLocalDate().atTime(10, 0).plusMinutes(20L * (i - 1));
        LocalDateTime latest = t.minusMinutes(25L + 15L * (n - 2 - i));
        return end.isAfter(latest) ? latest : end;
    }

    /** 구간 10분 — 아직 처리 안 된 다른 건이 있거나 앞 구간과 겹치면 15분씩 앞으로 비킨다. 뒤 배치가 앞 배치보다 늦게 실행되므로 순서는 실행 시각이 정한다. */
    LocalDateTime[] freeWindow(LocalDateTime end, LocalDateTime prevEnd) {
        LocalDateTime e = end;
        for (int k = 0; k < 12; k++) {
            LocalDateTime s = e.minusMinutes(10);
            boolean overlaps = prevEnd != null && s.isBefore(prevEnd) && e.isAfter(prevEnd.minusMinutes(10));
            Object pending = voice.pending(BatchWindow.manual(s, e), List.of(VoiceKind.MEET), false).get("pending");
            boolean busy = pending instanceof Number n && n.longValue() > 0;
            if (!overlaps && !busy && s.toLocalDate().equals(e.minusSeconds(1).toLocalDate())) {
                return new LocalDateTime[] {s, e};
            }
            e = e.minusMinutes(15);
        }
        throw new IllegalStateException("비어 있는 더미 구간을 찾지 못했습니다(" + end + " 앞 3시간) — 처리 안 된 더미가 많습니다");
    }

    // ── 기타 ───────────────────────────────────────────────────────────────

    static List<Status> parse(List<String> raw) {
        List<String> in = raw == null || raw.isEmpty() ? List.of("FAIL", "WAIT", "PARTIAL") : raw;
        if (in.size() > MAX_STATUSES) {
            throw new IllegalArgumentException("상태는 " + MAX_STATUSES + "개까지입니다: " + in.size());
        }
        List<Status> out = new ArrayList<>();
        for (String s : in) {
            try {
                out.add(Status.valueOf(s == null ? "" : s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("상태는 FAIL · WAIT · PARTIAL 중에서 고르십시오: " + s);
            }
        }
        return out;
    }

    private String sendStatus(String execId) {
        JsonNode d = logCollector.batchDetail(execId);
        if (d == null || !d.path("steps").isArray()) {
            return null;
        }
        String sts = null;
        for (JsonNode st : d.path("steps")) {
            if ("SEND".equals(st.path("step_type_cd").asText(""))) {
                sts = st.path("step_sts_cd").asText(null);
            }
        }
        return sts;
    }

    static boolean matches(Status st, String t1, String send) {
        return switch (st) {
            case FAIL -> "FAIL".equals(t1);
            case WAIT -> "SUCCESS".equals(t1) && "WAIT".equals(send);
            case PARTIAL -> "PARTIAL".equals(t1);
        };
    }

    @SuppressWarnings("unchecked")
    private static List<String> keysOf(Map<String, Object> generated) {
        List<String> keys = new ArrayList<>();
        Object types = generated.get("types");
        if (types instanceof Map<?, ?> m && m.get("MEET") instanceof Map<?, ?> meet && meet.get("keys") instanceof List<?> l) {
            l.forEach(k -> keys.add(String.valueOf(k)));
        }
        return keys;
    }

    private static LocalDateTime dtm(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(v.asText().trim().replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Path file() {
        return Path.of(dirs.baseDir(), FILE);
    }

    List<String> load() {
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        try {
            JsonNode n = objectMapper.readTree(f.toFile());
            List<String> ids = new ArrayList<>();
            n.path("execIds").forEach(x -> ids.add(x.asText()));
            return ids;
        } catch (IOException e) {
            log.warn("[E2E] 이전 묶음 기록을 읽지 못했다 — {} ({})", f, e.getMessage());
            return List.of();
        }
    }

    private void save(List<String> ids) {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("createdAt", LocalDateTime.now().withNano(0).toString());
            m.put("execIds", ids);
            Files.writeString(tmp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(m));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("[E2E] 묶음 기록을 남기지 못했다 — {} ({}) · 다음 정리 때 이 묶음은 지워지지 않는다", f, e.getMessage());
        }
    }

    /** 다른 배치가 도는 중 — 컨트롤러가 409. */
    public static class BusyException extends RuntimeException {
        public BusyException(String message) {
            super(message);
        }
    }
}
