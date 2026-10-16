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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * <b>개발계 클라우드 전송 시뮬레이션</b> — 관리 화면 '파이프라인 운영 &gt; 클라우드 전송 제어' 대기 목록의 다섯 상태
 * (대기 · 실패 · 부분 성공 · 전송중 · 취소)를 <b>상태마다 정한 개수만큼</b> 만든다(2026-10-17). 연속 전송(클라우드 전송 체인 —
 * 누른 배치 이전의 미완료 배치를 오래된 순으로)을 볼 바탕이다.
 *
 * <p>목록은 배치의 T2 SEND 상태를 그대로 읽는다(admin-api {@code cloudSendMapper}). 배치마다 접견 더미(DMY) 원본을 좁은 구간(10분)에
 * 만들고 그 구간만 <b>실제 배치</b>로 돌린다 — T1 · T4 · T5 와 커넥터 보류 장부가 진짜로 생겨야 [클라우드 전송]이 실제로 보낸다.</p>
 *
 * <table>
 *   <tr><th>상태</th><th>만드는 법</th><th>전송 방식 수동(개발계)에서</th></tr>
 *   <tr><td>대기 WAIT</td><td>실제 흐름 — 정상 2건</td><td>커넥터가 수동 확인으로 보류 → SEND WAIT</td></tr>
 *   <tr><td>실패 FAIL</td><td>실제 흐름 — 2건 모두 전송 실패 표식</td><td>커넥터에 닿기 전 실패 → SEND FAIL · 장부 없음</td></tr>
 *   <tr><td>부분 성공 PARTIAL</td><td>재현 — 3건 중 1건 전송 실패 표식(2건 보류) 뒤 전송 단계를 PARTIAL 로</td><td rowspan="3">
 *       비정형은 이 셋이 저절로 남지 않는다(수동 모드는 보류가 이긴다 · 전송중은 순간 · 음성 전송은 중지할 수 없다). 그래서 커넥터 관리자
 *       트리거와 <b>같은 순서</b>(로그 컬렉터 조건부 시작 {@code steps/send/claim} → {@code PATCH steps/{id}} 마감)로 전송 단계만 바꾼다.
 *       보류 장부는 그대로라 [클라우드 전송]이 실제로 보낸다(전송중은 지금 보내는 중으로 보여 건너뛴다)</td></tr>
 *   <tr><td>전송중 RUNNING</td><td>재현 — 정상 2건 보류 뒤 전송 시작만</td></tr>
 *   <tr><td>취소 CANCELED</td><td>재현 — 정상 2건 보류 뒤 시작 → 중지로 마감</td></tr>
 * </table>
 *
 * <p>실행 순서는 상태를 돌아가며(전송중 → 취소 → 부분 성공 → 실패 → 대기 → 전송중 …) 섞는다 — 체인이 여러 상태를 번갈아 만나고, 목록 맨 위
 * (가장 최근)는 대기가 된다. 정리(기본)는 이전 시뮬레이션 묶음을 지운다 — 전송중은 대기로 되돌리고, 장부에 남은 건 먼저 보내 마감한 뒤
 * 로그 컬렉터 정리 API(비정형 · 실행 중 아님 · T4 전부 더미 키)로 지운다.</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class CloudSendSimService {

    /** T1 TRIGGER_BY — 이 API 가 만든 배치. */
    public static final String TRIGGER_BY = "CLOUD-SIM";
    /** 이번 묶음 기록 — ROOT 기준. */
    public static final String FILE = "mock/cloud_send_sim.json";
    static final int DEFAULT_PER_STATE = 2;
    static final int MAX_PER_STATE = 10;
    static final int MAX_TOTAL = 30;
    static final int WINDOW_MIN = 10;
    static final String MSG_BUSY = "다른 배치가 실행 중입니다. 잠시 후 다시 시도해 주세요.";

    /** 대기 목록의 상태 — 건수 · 장애 표식 · 실제 흐름인지. */
    public enum State {
        WAIT("대기", "수동 확인", 2, Map.of(), true, "정상 2건 — 커넥터가 수동 확인으로 보류(실제 흐름)"),
        FAIL("실패", "전송 실패", 2, Map.of(FailureScenario.SEND_FAIL, 2), true,
                "2건 모두 커넥터에 닿기 전 실패(전송 실패 표식 · 실제 흐름) — 보류 장부 없음"),
        PARTIAL("부분 성공", "전송 부분 성공", 3, Map.of(FailureScenario.SEND_FAIL, 1), false,
                "3건 중 1건 전송 실패 · 2건 보류 → 전송 단계를 부분 성공으로(화면 재현)"),
        RUNNING("전송중", "-", 2, Map.of(), false, "정상 2건 보류 → 전송 시작만(화면 재현 · 실제로 보내지 않음)"),
        CANCELED("취소", "전송 중지", 2, Map.of(), false, "정상 2건 보류 → 시작 후 중지로 마감(화면 재현 · 보류분 유지)");

        final String label;
        final String reason;
        final int count;
        final Map<FailureScenario, Integer> failures;
        final boolean natural;
        final String note;

        State(String label, String reason, int count, Map<FailureScenario, Integer> failures, boolean natural, String note) {
            this.label = label;
            this.reason = reason;
            this.count = count;
            this.failures = failures;
            this.natural = natural;
            this.note = note;
        }

        /** 실제 배치가 끝났을 때 있어야 할 전송 단계 — 실패만 FAIL, 나머지는 보류(WAIT) 위에 만든다. */
        String baseSend() {
            return this == FAIL ? "FAIL" : "WAIT";
        }
    }

    /** 돌아가며 섞는 순서 — 마지막이 대기라 목록 맨 위(최근)가 [클라우드 전송]을 누를 만한 행이 된다. */
    static final List<State> ROUND = List.of(State.RUNNING, State.CANCELED, State.PARTIAL, State.FAIL, State.WAIT);

    /** 요청 — 상태별 개수(빠진 상태는 0 · 본문이 없으면 모두 2) · 이전 묶음 정리. */
    public record Request(Map<String, Integer> counts, Boolean cleanup) {}

    private final DummyDataService dummy;
    private final VoiceCollectService voice;
    private final LogCollectorClient logCollector;
    private final TransmitHoldClient holds;
    private final UnstructuredJobRunner runner;
    private final BatchProgress voiceProgress;
    private final ImageCollectService image;
    private final VoiceDirState dirs;
    private final ObjectMapper objectMapper;

    public synchronized Map<String, Object> run(Request req) {
        Map<State, Integer> counts = parseCounts(req == null ? null : req.counts());
        boolean cleanup = req == null || req.cleanup() == null || req.cleanup();
        requireIdle();
        List<State> order = order(counts);

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> asked = new LinkedHashMap<>();
        counts.forEach((k, v) -> asked.put(k.name(), v));
        out.put("counts", asked);
        out.put("cleanup", cleanup ? cleanup() : Map.of("skipped", true));

        LocalDateTime now = LocalDateTime.now().withNano(0);
        List<LocalDateTime[]> windows = windows(order.size(), now, this::busy);
        List<Map<String, Object>> batches = new ArrayList<>();
        List<Map<String, String>> saved = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            State st = order.get(i);
            LocalDateTime[] w = windows.get(i);
            dummy.generate(new DummyDataService.GenerateRequest(DummyTarget.DASHBOARD, List.of(DummyDataType.MEET),
                    w[0].toLocalDate(), st.count, st.failures, true, null), w[0], w[1]);
            VoiceBatchResult v = voice.run(BatchWindow.manual(w[0], w[1]), List.of(VoiceKind.MEET), TRIGGER_BY, false);
            String id = v.execId();
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("order", i + 1);
            b.put("state", st.name());
            b.put("label", st.label);
            b.put("execId", id);
            b.put("window", Map.of("from", w[0].toString(), "to", w[1].toString()));
            b.put("how", st.natural ? "실제 흐름" : "화면 재현");
            b.put("note", st.note);
            b.put("t1", v.execStsCd());
            b.put("counts", Map.of("target", v.targetCnt(), "success", v.successCnt(), "fail", v.failCnt()));
            Step send = sendStep(id);
            b.put("baseSend", send == null ? null : send.sts());
            String problem = shape(st, id, send);
            Step after = sendStep(id);
            b.put("send", after == null ? null : after.sts());
            Set<String> held = holds.holds();
            b.put("held", held != null && held.contains(id));
            b.put("asRequested", problem == null && after != null && st.name().equals(after.sts()));
            if (problem != null) {
                b.put("problem", problem);
            }
            batches.add(b);
            if (id != null) {
                saved.add(Map.of("execId", id, "state", st.name()));
            }
            log.info("[CloudSim] {}/{} {} — {} {} ~ {} · T1 {} · SEND {} → {}{}", i + 1, order.size(), st, id, w[0], w[1],
                    v.execStsCd(), b.get("baseSend"), b.get("send"), problem == null ? "" : " · " + problem);
        }
        save(saved);
        out.put("batches", batches);
        out.put("pick", pick(batches));
        out.put("next", List.of(
                "관리 화면 파이프라인 운영 > 클라우드 전송 제어 — 기간 '오늘' · 상태 '전체'로 조회(마지막 업데이트일시 최신순)",
                "연속 전송은 시뮬레이터 [연속 전송 실행] 또는 admin-api 반영 뒤 관리 화면 [클라우드 전송] — 누른 배치 이전의 미완료 배치를 오래된 순으로",
                "전송중 행은 실제로 보내는 중이 아니다 — [전송중 풀기]로 대기로 되돌린다(관리 화면 [클라우드 전송 중지]는 음성 전송엔 접수되지 않는다)"));
        if (batches.stream().anyMatch(b -> Boolean.FALSE.equals(b.get("asRequested")))) {
            out.put("warning", "요청한 상태로 만들지 못한 배치가 있습니다 — 전송 방식이 자동이면 보류(대기)가 생기지 않아 재현 상태도 만들 수 없습니다");
        }
        return out;
    }

    /** 이번 묶음의 지금 상태 — 배치마다 T1 · 전송 단계 · 보류 장부. */
    public Map<String, Object> status() {
        List<Map<String, String>> saved = load();
        Set<String> held = holds.holds();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, String> s : saved) {
            String id = s.get("execId");
            JsonNode d = logCollector.batchDetail(id);
            JsonNode b = d == null ? null : d.path("batch");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("execId", id);
            r.put("state", s.get("state"));
            State st = parseState(s.get("state"));
            r.put("label", st == null ? s.get("state") : st.label);
            r.put("t1", b == null || b.isMissingNode() ? null : b.path("exec_sts_cd").asText(null));
            Step send = sendStep(d);
            r.put("send", send == null ? null : send.sts());
            r.put("held", held != null && held.contains(id));
            r.put("purged", b != null && "SUCCESS".equals(b.path("purge_sts_cd").asText("")));
            r.put("exists", b != null && b.hasNonNull("exec_id"));
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batches", rows);
        out.put("holdsKnown", held != null);
        out.put("pick", pick(rows));
        return out;
    }

    /** 전송중(재현)을 대기로 되돌린다 — 이번 묶음에서 전송 단계가 RUNNING 이고 보류 장부에 있는 것만. */
    public synchronized Map<String, Object> unstick() {
        Set<String> held = holds.holds();
        List<Map<String, Object>> done = new ArrayList<>();
        for (Map<String, String> s : load()) {
            String id = s.get("execId");
            Step send = sendStep(id);
            if (send == null || !"RUNNING".equals(send.sts())) {
                continue;
            }
            boolean ok = held != null && held.contains(id);
            if (ok) {
                logCollector.finishStep(send.stepLogId(), "WAIT", 0, send.in(), 0L, 0L, null);
            }
            Step after = sendStep(id);
            done.add(Map.of("execId", id, "send", after == null ? "?" : after.sts(),
                    "result", ok ? "대기로 되돌림" : "보류 장부에 없음 — 그대로 둠"));
        }
        log.info("[CloudSim] 전송중 풀기 — {}", done);
        return Map.of("unstuck", done);
    }

    /** 이전 묶음 정리만. */
    public synchronized Map<String, Object> cleanupOnly() {
        requireIdle();
        return cleanup();
    }

    // ── 상태 만들기 ─────────────────────────────────────────────────────────

    /**
     * 실제 배치 위에 요청 상태를 만든다. 문제가 있으면 그 사유(만들지 않음), 됐으면 null.
     * 재현 상태는 커넥터 관리자 트리거와 같은 순서 — 조건부 시작(claim · WAIT → RUNNING · 종료 비움) 뒤 마감(누적이 아니라 마감으로 들어간다).
     */
    String shape(State st, String execId, Step base) {
        if (execId == null) {
            return "배치 실행 ID 없음(로그 컬렉터 미연동)";
        }
        if (base == null) {
            return "전송 단계가 없다 — 전송 전에 끝났다";
        }
        if (!st.baseSend().equals(base.sts())) {
            return "전송 단계가 " + base.sts() + " — " + st.baseSend() + " 이어야 " + st.label + " 를 만들 수 있다"
                    + ("WAIT".equals(st.baseSend()) ? "(전송 방식이 자동이면 보류가 생기지 않는다)" : "");
        }
        if (st.natural) {
            return null;
        }
        String sid = logCollector.claimSendStep(execId);
        if (sid == null) {
            return "전송 시작(claim)을 기록하지 못했다";
        }
        long in = base.in();
        switch (st) {
            case PARTIAL -> logCollector.finishStep(sid, "PARTIAL", 0, in, base.out(), base.err(),
                    "[SEND] " + base.err() + "건 커넥터 전송 실패 · " + base.out() + "건 커넥터 보류(시뮬레이터 재현 — 부분 성공)");
            case CANCELED -> logCollector.finishStep(sid, "CANCELED", 0, in, 0L, in,
                    "[SEND] 관리자 전송 중지 — 보낸 0건 · 남은 " + in + "건 커넥터 보류 유지(시뮬레이터 재현 — 다시 전송하면 이어서)");
            default -> { }   // RUNNING — 시작만
        }
        return null;
    }

    /** 다른 배치가 돌면 안 된다 — 같은 실행기 · 같은 원본을 본다. */
    private void requireIdle() {
        if (runner.isRunning() || voiceProgress.isRunning() || image.isRunning()) {
            throw new E2eDummyService.BusyException(MSG_BUSY);
        }
        if (!logCollector.isEnabled()) {
            throw new IllegalStateException("로그 컬렉터 미연동 — 이력을 남길 수 없어 만들지 않습니다");
        }
    }

    // ── 정리 ───────────────────────────────────────────────────────────────

    /** 이전 묶음 — 전송중은 대기로 되돌리고, 장부에 남은 건 먼저 보내 마감한 뒤 지운다(보내지 못한 건 남긴다). */
    Map<String, Object> cleanup() {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> targets = new LinkedHashSet<>();
        load().forEach(s -> targets.add(s.get("execId")));
        out.put("previous", List.copyOf(targets));
        Set<String> held = holds.holds();
        List<Map<String, Object>> released = new ArrayList<>();
        for (String id : List.copyOf(targets)) {
            Step send = sendStep(id);
            if (send != null && "RUNNING".equals(send.sts())) {
                logCollector.finishStep(send.stepLogId(), "WAIT", 0, send.in(), 0L, 0L, null);
            }
            if (held == null || !held.contains(id)) {
                continue;
            }
            TransmitHoldClient.Release r = holds.release(id, "cloud-sim-cleanup");
            released.add(Map.of("execId", id, "sent", r.sent(), "http", r.httpStatus()));
            if (!r.sent()) {
                targets.remove(id);   // 장부가 남아 있는데 이력을 지우면 장부가 가리킬 곳이 없어진다
            }
        }
        out.put("releasedFirst", released);
        JsonNode del = targets.isEmpty() ? null : logCollector.deleteDummyBatches(List.copyOf(targets));
        out.put("deleted", del == null ? List.of() : del.path("deleted"));
        out.put("skipped", del == null ? List.of() : del.path("skipped"));
        if (!targets.isEmpty() && del == null) {
            out.put("error", "로그 컬렉터 정리 호출 실패");
        }
        if (del != null) {
            save(List.of());
        }
        log.info("[CloudSim] 정리 — 이전 묶음 {} · 먼저 보냄 {} · 지움 {}", out.get("previous"), released,
                del == null ? "-" : del.path("deleted"));
        return out;
    }

    // ── 순서 · 구간 ─────────────────────────────────────────────────────────

    static Map<State, Integer> parseCounts(Map<String, Integer> raw) {
        Map<State, Integer> out = new EnumMap<>(State.class);
        if (raw == null || raw.isEmpty()) {
            for (State s : State.values()) {
                out.put(s, DEFAULT_PER_STATE);
            }
            return out;
        }
        for (State s : State.values()) {
            out.put(s, 0);
        }
        int total = 0;
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            State s = parseState(e.getKey());
            if (s == null) {
                throw new IllegalArgumentException("상태는 WAIT · FAIL · PARTIAL · RUNNING · CANCELED 중에서 고르십시오: " + e.getKey());
            }
            int n = e.getValue() == null ? 0 : e.getValue();
            if (n < 0 || n > MAX_PER_STATE) {
                throw new IllegalArgumentException(s.label + " 개수는 0 ~ " + MAX_PER_STATE + " 입니다: " + n);
            }
            out.put(s, n);
            total += n;
        }
        if (total < 1 || total > MAX_TOTAL) {
            throw new IllegalArgumentException("모두 합쳐 1 ~ " + MAX_TOTAL + "건이어야 합니다: " + total);
        }
        return out;
    }

    static State parseState(String s) {
        if (s == null) {
            return null;
        }
        try {
            return State.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 돌아가며 섞는다 — {@link #ROUND} 순서로 한 바퀴에 상태마다 하나씩, 개수가 다 찬 상태는 건너뛴다. */
    static List<State> order(Map<State, Integer> counts) {
        Map<State, Integer> left = new EnumMap<>(counts);
        List<State> out = new ArrayList<>();
        boolean any = true;
        while (any) {
            any = false;
            for (State s : ROUND) {
                int n = left.getOrDefault(s, 0);
                if (n > 0) {
                    out.add(s);
                    left.put(s, n - 1);
                    any = true;
                }
            }
        }
        return out;
    }

    /**
     * 구간 n개(오래된 것부터) — 마지막이 지금-1분에 끝나는 10분, 앞으로 2분 간격. 처리 안 된 다른 건이 있으면 15분씩 앞으로 비키고,
     * 자정을 걸치면 그날 0시로 끊는다(더미 생성은 하루 안 · 미래 불가).
     *
     * @param busy (시작, 끝) 구간에 아직 처리 안 된 건이 있는가
     */
    static List<LocalDateTime[]> windows(int n, LocalDateTime now, BiPredicate<LocalDateTime, LocalDateTime> busy) {
        List<LocalDateTime[]> out = new ArrayList<>();
        LocalDateTime cursor = now.withSecond(0).withNano(0).minusMinutes(1);
        for (int k = 0; k < n; k++) {
            LocalDateTime[] w = null;
            for (int tries = 0; tries < 60 && w == null; tries++) {
                LocalDateTime s = cursor.minusMinutes(WINDOW_MIN);
                if (!s.toLocalDate().equals(cursor.minusSeconds(1).toLocalDate())) {
                    cursor = cursor.toLocalDate().atStartOfDay();   // 자정을 걸친다 — 0시에 끝나는 구간(전날)으로
                    continue;
                }
                if (busy.test(s, cursor)) {
                    cursor = cursor.minusMinutes(15);
                    continue;
                }
                w = new LocalDateTime[] {s, cursor};
            }
            if (w == null) {
                throw new IllegalStateException("비어 있는 더미 구간을 찾지 못했습니다 — 처리 안 된 더미가 많습니다");
            }
            out.add(0, w);
            cursor = w[0].minusMinutes(2);
        }
        return out;
    }

    private boolean busy(LocalDateTime s, LocalDateTime e) {
        Object pending = voice.pending(BatchWindow.manual(s, e), List.of(VoiceKind.MEET), false).get("pending");
        return pending instanceof Number num && num.longValue() > 0;
    }

    /** 연속 전송에서 누를 배치 — 가장 최근이면서 미완료(대기 · 실패 · 부분 성공 · 취소)인 것. 전송중은 누를 수 없다(409). */
    static String pick(List<Map<String, Object>> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            Object send = rows.get(i).get("send");
            if (send != null && Set.of("WAIT", "FAIL", "PARTIAL", "CANCELED").contains(send.toString())) {
                return String.valueOf(rows.get(i).get("execId"));
            }
        }
        return null;
    }

    // ── 전송 단계 ───────────────────────────────────────────────────────────

    /** T2 SEND 한 행 — 가장 뒤 순번. */
    record Step(String stepLogId, String sts, long in, long out, long err) {}

    Step sendStep(String execId) {
        return execId == null ? null : sendStep(logCollector.batchDetail(execId));
    }

    static Step sendStep(JsonNode detail) {
        if (detail == null || !detail.path("steps").isArray()) {
            return null;
        }
        Step found = null;
        int seq = Integer.MIN_VALUE;
        for (JsonNode st : detail.path("steps")) {
            if (!"SEND".equals(st.path("step_type_cd").asText(""))) {
                continue;
            }
            int q = st.path("step_seq").asInt(0);
            if (found == null || q >= seq) {
                found = new Step(st.path("step_log_id").asText(null), st.path("step_sts_cd").asText(null),
                        st.path("in_cnt").asLong(0), st.path("out_cnt").asLong(0), st.path("err_cnt").asLong(0));
                seq = q;
            }
        }
        return found;
    }

    // ── 묶음 기록 ───────────────────────────────────────────────────────────

    private Path file() {
        return Path.of(dirs.baseDir(), FILE);
    }

    List<Map<String, String>> load() {
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        try {
            JsonNode n = objectMapper.readTree(f.toFile());
            List<Map<String, String>> out = new ArrayList<>();
            n.path("batches").forEach(x -> out.add(Map.of("execId", x.path("execId").asText(), "state", x.path("state").asText())));
            return out;
        } catch (IOException e) {
            log.warn("[CloudSim] 묶음 기록을 읽지 못했다 — {} ({})", f, e.getMessage());
            return List.of();
        }
    }

    private void save(List<Map<String, String>> batches) {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("createdAt", LocalDateTime.now().withNano(0).toString());
            m.put("batches", batches);
            Files.writeString(tmp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(m));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("[CloudSim] 묶음 기록을 남기지 못했다 — {} ({}) · 다음 정리 때 이 묶음은 지워지지 않는다", f, e.getMessage());
        }
    }
}
