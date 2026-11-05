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
import egovframework.unstructured.collector.voice.batch.StageFaultState;
import egovframework.unstructured.collector.voice.batch.VerificationService;
import egovframework.unstructured.collector.voice.batch.VoiceBatchResult;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import egovframework.unstructured.collector.voice.transfer.VoiceDeidentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>비식별 실패 배치 만들기</b> — 어드민 배치 상세의 '실패 상태만 보기'(기본 체크)를 비정형 비식별화 단계에서 확인하려고 만든다(2026-11-05).
 *
 * <p>개발계 비정형 배치는 비식별 단계에서 실패하는 일이 없어, 상세 모달의 비식별화 목록이 늘 '실패 내역이 없습니다.' 였다.
 * 3번 탭 단계 장애 스위치(DEIDENT)로도 만들 수 있지만 그건 전역이라 켜 둔 동안 스케줄 배치까지 떨어진다. 여기서는
 * <b>더미 키에 비식별 표식(DF)을 박아</b> 그 건만 한 번 실패시킨다 — {@link E2eDummyService} 와 같은 방식(접견 DMY 원본을 10분 구간에 만들고
 * 그 구간만 실제 배치 · EXEC_ID UNS 로 돈다).</p>
 *
 * <ul>
 *   <li>결과: T1 PARTIAL(일부 실패) 또는 FAIL(전부 실패) · T2 DEIDENT 부분 성공/실패 · T4 표식 건 {@code PROC_STS_CD=FAIL · STEP_TYPE_CD=DEIDENT}.
 *       어드민 목록은 T4 를 T5 비식별 결과로 덮어 보여주는데 실패 건은 T5 가 없어 T4 의 FAIL 이 그대로 뜬다</li>
 *   <li>표식은 한 번만 실패한다 — 어드민 [긴급 재처리]가 같은 배치를 다시 열면 보존된 전사로 비식별부터 다시 해 성공한다</li>
 *   <li>정리(기본): 이 API 가 이전에 만든 배치를 지운다(커넥터 장부에 남은 건 먼저 보내 마감). 다른 배치는 건드리지 않는다</li>
 * </ul>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class DeidentFailSimService {

    /** T1 TRIGGER_BY — 이 API 가 만든 배치. */
    public static final String TRIGGER_BY = "DEIDENT-FAIL-SIM";
    /** 이전 묶음 기록 — ROOT 기준. */
    public static final String FILE = "mock/deident_fail_sim.json";
    static final int MAX_COUNT = 10;
    static final int DEFAULT_COUNT = 3;
    static final int DEFAULT_FAIL = 2;

    /** 요청 — 총 건수 · 그중 비식별 실패 건수 · 이전 묶음 정리 여부. */
    public record Request(Integer count, Integer deidentFail, Boolean cleanup) {}

    private final DummyDataService dummy;
    private final E2eDummyService e2e;
    private final VoiceCollectService voice;
    private final VerificationService verification;
    private final LogCollectorClient logCollector;
    private final TransmitHoldClient holds;
    private final UnstructuredJobRunner runner;
    private final BatchProgress voiceProgress;
    private final ImageCollectService image;
    private final StageFaultState stageFault;
    private final VoiceDeidentClient voiceDeident;
    private final ScenarioFaults scenarioFaults;
    private final VoiceDirState dirs;
    private final ObjectMapper objectMapper;

    public synchronized Map<String, Object> run(Request req) {
        int count = req == null || req.count() == null ? DEFAULT_COUNT : req.count();
        int fail = req == null || req.deidentFail() == null ? Math.min(DEFAULT_FAIL, count) : req.deidentFail();
        boolean cleanup = req == null || req.cleanup() == null || req.cleanup();
        validate(count, fail);
        if (runner.isRunning() || voiceProgress.isRunning() || image.isRunning()) {
            throw new E2eDummyService.BusyException(E2eDummyService.MSG_BUSY);
        }
        precheck();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleanup", cleanup ? cleanup() : Map.of("skipped", true));

        LocalDateTime now = LocalDateTime.now().withNano(0).withSecond(0);
        LocalDateTime[] w = e2e.freeWindow(now.minusMinutes(1), null);
        Map<String, Object> g = dummy.generate(new DummyDataService.GenerateRequest(DummyTarget.DASHBOARD, List.of(DummyDataType.MEET),
                w[0].toLocalDate(), count, Map.of(FailureScenario.DEIDENT_FAIL, fail), true, null), w[0], w[1]);
        VoiceBatchResult v = voice.run(BatchWindow.manual(w[0], w[1]), List.of(VoiceKind.MEET), TRIGGER_BY, false);
        save(v.execId());

        long deidentFail = deidentFailCount(verification.t4Steps(v.execId()));
        out.put("execId", v.execId());
        out.put("window", Map.of("from", w[0].toString(), "to", w[1].toString()));
        out.put("keys", g.get("types") instanceof Map<?, ?> t && t.get("MEET") instanceof Map<?, ?> m ? m.get("keys") : List.of());
        out.put("t1", v.execStsCd());
        out.put("counts", Map.of("target", v.targetCnt(), "success", v.successCnt(), "fail", v.failCnt(), "skipped", v.skippedCnt()));
        out.put("requestedDeidentFail", fail);
        out.put("deidentFail", deidentFail);
        out.put("asRequested", deidentFail == fail);
        out.put("next", List.of(
                "관리 화면 배치 수행 이력 — 맨 위 " + v.execId() + " 상세",
                "단계별 처리 타임라인 '비식별화' 카드 → '실패 상태만 보기'(기본 체크)에 " + fail + "건",
                "같은 배치 [긴급 재처리] → 표식은 한 번만 실패하므로 비식별부터 다시 해 성공"));
        if (deidentFail != fail) {
            out.put("warning", "비식별 실패가 " + fail + "건이어야 하는데 " + deidentFail + "건입니다 — 수집 · 분석 단계 장애(브로커 주소 등)가 걸려 있는지 보십시오");
        }
        log.info("[DeidentFailSim] {} {} ~ {} · T1 {} · 비식별 실패 {}/{}", v.execId(), w[0], w[1], v.execStsCd(), deidentFail, fail);
        return out;
    }

    static void validate(int count, int fail) {
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("총 건수는 1~" + MAX_COUNT + " 입니다: " + count);
        }
        if (fail < 1 || fail > count) {
            throw new IllegalArgumentException("비식별 실패 건수는 1~총 건수(" + count + ") 입니다: " + fail);
        }
    }

    /** 표식이 실제로 실패하게 될 환경인지 — 아니면 '실패 없는 배치' 만 쌓인다. */
    private void precheck() {
        if (!logCollector.isEnabled()) {
            throw new IllegalStateException("로그 컬렉터 미연동 — 이력을 남길 수 없어 배치를 만들지 않습니다");
        }
        if (!voiceDeident.enabled()) {
            throw new IllegalStateException("비식별 단계가 꺼져 있습니다(VOICE_DEIDENT_ENABLED=false) — 비식별 실패를 만들 수 없습니다");
        }
        if (!scenarioFaults.isActive()) {
            throw new IllegalStateException("더미 장애 표식이 꺼져 있는 환경입니다 — 표식 건이 실패하지 않습니다");
        }
        if (stageFault.anyOn()) {
            throw new IllegalStateException("3번 탭 단계별 장애가 걸려 있습니다 — 앞 단계에서 먼저 떨어져 비식별까지 가지 않습니다. [전체 해제] 후 다시 하십시오");
        }
    }

    static long deidentFailCount(Map<String, Object> t4) {
        if (t4 != null && t4.get("byStep") instanceof Map<?, ?> byStep
                && byStep.get("DEIDENT") instanceof Map<?, ?> d && d.get("FAIL") instanceof Number n) {
            return n.longValue();
        }
        return 0;
    }

    // ── 정리 ───────────────────────────────────────────────────────────────

    /** 이 API 가 이전에 만든 배치만 지운다 — 장부에 남은 건 먼저 보내 마감하고, 못 보낸 건 지우지 않는다. */
    Map<String, Object> cleanup() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> targets = new ArrayList<>(load());
        out.put("previous", List.copyOf(targets));
        Set<String> held = holds.holds();
        List<Map<String, Object>> released = new ArrayList<>();
        if (held != null) {
            for (String id : List.copyOf(targets)) {
                if (!held.contains(id)) {
                    continue;
                }
                TransmitHoldClient.Release r = holds.release(id, "deident-fail-sim-cleanup");
                released.add(Map.of("execId", id, "sent", r.sent(), "http", r.httpStatus()));
                if (!r.sent()) {
                    targets.remove(id);   // 장부가 남아 있는데 이력을 지우면 장부가 가리킬 곳이 없어진다
                }
            }
        }
        out.put("releasedFirst", released);
        JsonNode del = targets.isEmpty() ? null : logCollector.deleteDummyBatches(targets);
        out.put("deleted", del == null ? List.of() : del.path("deleted"));
        out.put("skipped", del == null ? List.of() : del.path("skipped"));
        if (!targets.isEmpty() && del == null) {
            out.put("error", "로그 컬렉터 정리 호출 실패");
        }
        return out;
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
            List<String> ids = new ArrayList<>();
            objectMapper.readTree(f.toFile()).path("execIds").forEach(x -> ids.add(x.asText()));
            return ids;
        } catch (IOException e) {
            log.warn("[DeidentFailSim] 이전 묶음 기록을 읽지 못했다 — {} ({})", f, e.getMessage());
            return List.of();
        }
    }

    private void save(String execId) {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("createdAt", LocalDateTime.now().withNano(0).toString());
            m.put("execIds", List.of(execId));
            Files.writeString(tmp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(m));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("[DeidentFailSim] 묶음 기록을 남기지 못했다 — {} ({}) · 다음 정리 때 이 배치는 지워지지 않는다", f, e.getMessage());
        }
    }
}
