package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 대시보드 더미 <b>일 단위 자동 생성</b> — 매일 일배치 전에 <b>어제 날짜</b>로 DMY 를 추가하고, 보존 기간이 지난 DMY 를 지운다.
 *
 * <p>dev/local 프로필이면 빈은 늘 생긴다 — 켜고 끄는 것은 실행 중 스위치다(2026-10-05, 시뮬레이터 상단 패널).
 * 처음 값은 {@code unstructured.mock.daily.enabled}(기본 끔). 화면에서 바꾸면 PV 파일 {@code {ROOT}/mock/auto_generator.json} 에
 * 남겨 재배포 · 재기동 뒤에도 그대로 간다. 설정값과 같은 쪽으로 되돌리면 파일을 지운다(설정이 다시 정한다).</p>
 *
 * <p>cron 은 늘 돌고, 꺼져 있으면 그 자리에서 건너뛴다. 놓친 날을 따라잡지 않는다(catch-up 없음) — 수집기가 내려가 있던 날은 비어 있다.
 * 실패해도 예외를 밖으로 던지지 않는다(다음 날 다시 돈다). 즉시 실행 · N일 경과 시뮬레이션은 스위치와 상관없이 돈다 —
 * 한 번에 하나만(겹치면 {@link BusyException}).</p>
 */
@Log4j2
@Component
@Profile({"dev", "local"})
public class MockDataScheduler {

    /** 화면에서 바꾼 ON/OFF — ROOT 기준 상대 경로. */
    public static final String FILE = "mock/auto_generator.json";
    /** N일 경과 시뮬레이션 상한 — 하루에 유형별 건수만큼 만들므로 개발계 공용 DB 에 너무 많이 쌓이지 않게. */
    public static final int MAX_SIM_DAYS = 14;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DummyDataService dummy;
    private final MockDataProperties props;
    /** 스위치 파일 — null 이면 메모리만(단위 테스트). */
    private final Supplier<Path> file;
    private final AtomicBoolean enabled = new AtomicBoolean();
    /** 지금 값이 어디서 왔나 — CONFIG(설정) · UI(화면에서 바꿈 · 파일). */
    private volatile String source = "CONFIG";
    private volatile String changedAt;
    private final ReentrantLock running = new ReentrantLock();
    private volatile String runningWhat;
    private volatile Map<String, Object> lastRun;

    /** 이미 다른 생성(스케줄 · 즉시 실행 · 시뮬레이션)이 돌고 있다 — 409. */
    public static class BusyException extends RuntimeException {
        public BusyException(String message) {
            super(message);
        }
    }

    @Autowired
    public MockDataScheduler(DummyDataService dummy, MockDataProperties props, VoiceDirState dirs) {
        this(dummy, props, () -> Path.of(dirs.baseDir(), FILE));
    }

    /** 파일 없이 — 설정값으로 시작하고 메모리에서만 바꾼다(단위 테스트용). */
    public MockDataScheduler(DummyDataService dummy, MockDataProperties props) {
        this(dummy, props, (Supplier<Path>) null);
    }

    /** 이 파일에 스위치를 남긴다 — 재기동 흉내 단위 테스트용. */
    MockDataScheduler(DummyDataService dummy, MockDataProperties props, Supplier<Path> file) {
        this.dummy = dummy;
        this.props = props;
        this.file = file;
        this.enabled.set(props.daily().enabled());
        load();
        log.info("[Dummy:daily] 일 단위 자동 생성 — {} ({}) · cron {} · 보존 {}일", enabled.get() ? "켜짐" : "꺼짐",
                "UI".equals(source) ? "화면에서 바꾼 값 · " + path() : "설정 unstructured.mock.daily.enabled",
                props.daily().cron(), props.daily().retainDays());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  스케줄
    // ══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "${unstructured.mock.daily.cron:0 30 1 * * *}", zone = "Asia/Seoul")
    public void daily() {
        if (!enabled.get()) {
            log.debug("[Dummy:daily] 꺼져 있어 건너뛴다");
            return;
        }
        if (!running.tryLock()) {
            log.warn("[Dummy:daily] {} 이(가) 돌고 있어 이번 회차는 건너뛴다", runningWhat);
            return;
        }
        try {
            runningWhat = "스케줄";
            LocalDate today = LocalDate.now(ZONE);
            remember("SCHEDULER", today, runOnce(today));
        } finally {
            runningWhat = null;
            running.unlock();
        }
    }

    /**
     * 한 번 — 실행일({@code today})의 <b>어제</b>로 추가 · 실행일 기준 보존 기간 정리. 스위치는 보지 않는다.
     *
     * @return 생성 · 정리 결과 요약(실패면 사유)
     */
    public Map<String, Object> runOnce(LocalDate today) {
        MockDataProperties.Daily d = props.daily();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runDate", today.toString());
        out.put("targetDate", today.minusDays(1).toString());
        out.put("purgeBefore", d.retainDays() > 0 ? today.minusDays(d.retainDays()).toString() : null);
        if (!props.dashboard().enabled()) {
            log.warn("[Dummy:daily] 대시보드 더미가 꺼져 있어 건너뛴다 — unstructured.mock.dashboard.enabled=false");
            out.put("skipped", "dashboard disabled");
            return out;
        }
        try {
            Map<FailureScenario, Integer> failures = new EnumMap<>(FailureScenario.class);
            failures.put(FailureScenario.COLLECT_FAIL, d.collectFail());
            failures.put(FailureScenario.ANALYZE_FAIL, d.analyzeFail());
            failures.put(FailureScenario.SEND_FAIL, d.sendFail());
            Map<String, Object> g = dummy.generate(new DummyDataService.GenerateRequest(DummyTarget.DASHBOARD, d.types(),
                    today.minusDays(1), d.count(), failures, true, null));
            out.put("generated", g.get("totals"));
            log.info("[Dummy:daily] {} 대시보드 더미 추가 — {}", today.minusDays(1), g.get("totals"));
        } catch (Exception e) {
            out.put("generateError", e.getMessage());
            log.error("[Dummy:daily] 생성 실패 — {}", e.getMessage(), e);
        }
        if (d.retainDays() > 0) {
            try {
                Map<String, Object> p = dummy.purgeDashboardBefore(today.minusDays(d.retainDays()));
                out.put("purged", p.get("counts"));
            } catch (Exception e) {
                out.put("purgeError", e.getMessage());
                log.error("[Dummy:daily] 보존 기간 정리 실패 — {}", e.getMessage(), e);
            }
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  상태 · 스위치
    // ══════════════════════════════════════════════════════════════════════

    public boolean isEnabled() {
        return enabled.get();
    }

    /** 지금 상태 · 설정 · 다음 실행 · 마지막 실행. */
    public Map<String, Object> status() {
        MockDataProperties.Daily d = props.daily();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled.get());
        out.put("source", source);
        out.put("sourceLabel", "UI".equals(source) ? "화면에서 바꾼 값(PV 파일)" : "설정값(unstructured.mock.daily.enabled)");
        out.put("configEnabled", d.enabled());
        out.put("changedAt", changedAt);
        Path f = path();
        out.put("stateFile", f == null ? null : f.toString());
        out.put("stateFileExists", f != null && Files.isRegularFile(f));
        out.put("cron", d.cron());
        out.put("zone", ZONE.getId());
        out.put("nextRun", enabled.get() ? nextRun(d.cron()) : null);
        out.put("nextFire", nextRun(d.cron()));
        out.put("types", d.types().stream().map(Enum::name).toList());
        out.put("count", d.count());
        Map<String, Integer> failures = new LinkedHashMap<>();
        failures.put(FailureScenario.COLLECT_FAIL.name(), d.collectFail());
        failures.put(FailureScenario.ANALYZE_FAIL.name(), d.analyzeFail());
        failures.put(FailureScenario.SEND_FAIL.name(), d.sendFail());
        out.put("failures", failures);
        out.put("retainDays", d.retainDays());
        out.put("dashboardEnabled", props.dashboard().enabled());
        out.put("running", running.isLocked());
        out.put("runningWhat", runningWhat);
        out.put("lastRun", lastRun);
        out.put("maxSimDays", MAX_SIM_DAYS);
        return out;
    }

    /**
     * 켜고 끈다. 설정값과 다르면 PV 파일에 남기고, 설정값으로 되돌리면 파일을 지운다.
     *
     * @return 바꾼 뒤 상태 + {@code changed}(실제로 바뀌었나)
     */
    public synchronized Map<String, Object> setEnabled(boolean on) {
        boolean before = enabled.getAndSet(on);
        changedAt = LocalDateTime.now(ZONE).withNano(0).toString();
        source = on == props.daily().enabled() ? "CONFIG" : "UI";
        save(on);
        log.info("[Dummy:daily] 일 단위 자동 생성 {} → {} (화면)", before ? "ON" : "OFF", on ? "ON" : "OFF");
        Map<String, Object> out = new LinkedHashMap<>(status());
        out.put("changed", before != on);
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  즉시 실행 · N일 경과 시뮬레이션
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 즉시 1회 — {@code targetDate}(비우면 어제) 치를 만들고, 그 다음 날을 실행일로 보고 보존 기간을 정리한다.
     * 새벽 스케줄이 하는 일과 같다(스케줄은 실행일 = 오늘, 대상일 = 어제).
     *
     * @throws IllegalArgumentException 대상일이 오늘보다 뒤
     * @throws BusyException            다른 생성이 돌고 있다
     */
    public Map<String, Object> trigger(LocalDate targetDate) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate target = targetDate == null ? today.minusDays(1) : targetDate;
        if (target.isAfter(today)) {
            throw new IllegalArgumentException("대상일은 오늘 이전이어야 합니다: " + target);
        }
        requireDashboard();
        return exclusive("즉시 실행", () -> {
            Map<String, Object> r = runOnce(target.plusDays(1));
            remember("MANUAL", target.plusDays(1), r);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("trigger", "MANUAL");
            out.putAll(r);
            out.put("ok", !r.containsKey("generateError") && !r.containsKey("purgeError") && !r.containsKey("skipped"));
            out.put("days", dummy.dashboardDays(target.minusDays(Math.max(props.daily().retainDays(), 0) + 1L), target));
            return out;
        });
    }

    /**
     * N일 경과 시뮬레이션 — 가상 실행일을 N일 앞당겨 시작해 하루씩 N+1번(0일째 + N일 경과) 돌린다.
     *
     * <pre>
     *  실행일 V = 오늘-N … 오늘 → 대상일 V-1 = 오늘-N-1 … 어제 를 만들고, 매번 V-보존일 이전을 지운다.
     *  N = 보존일(7)이면 첫날 만든 오늘-8 치는 마지막 회차(V=오늘)의 정리(오늘-7 이전)에서 지워지고,
     *  오늘-7 … 어제 7일 치만 남아야 한다 — 끝나고 날짜별 건수를 세어 맞는지 본다.
     * </pre>
     *
     * <p>대상일은 늘 오늘 이전이다(생성 API 가 미래를 막는다). 보존일보다 앞선 기존 DMY 도 같이 지워진다 — 새벽 스케줄과 같다.</p>
     *
     * @param days 경과 일수(1~{@value #MAX_SIM_DAYS}) — 비우면 보존일(0 이하면 7)
     */
    public Map<String, Object> simulate(Integer days) {
        MockDataProperties.Daily d = props.daily();
        int n = days == null ? (d.retainDays() > 0 ? d.retainDays() : 7) : days;
        if (n < 1 || n > MAX_SIM_DAYS) {
            throw new IllegalArgumentException("경과 일수는 1~" + MAX_SIM_DAYS + " 입니다: " + n);
        }
        requireDashboard();
        return exclusive(n + "일 경과 시뮬레이션", () -> {
            LocalDate today = LocalDate.now(ZONE);
            LocalDate firstTarget = today.minusDays(n + 1L);
            LocalDate lastTarget = today.minusDays(1);
            Map<String, Map<String, Integer>> before = dummy.dashboardDays(firstTarget, lastTarget);
            List<Map<String, Object>> runs = new ArrayList<>();
            boolean errors = false;
            for (int i = 0; i <= n; i++) {
                LocalDate v = today.minusDays(n - i);
                runningWhat = n + "일 경과 시뮬레이션 (" + i + "/" + n + "일째)";
                Map<String, Object> r = runOnce(v);
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("day", i);
                step.putAll(r);
                runs.add(step);
                errors |= r.containsKey("generateError") || r.containsKey("purgeError") || r.containsKey("skipped");
            }
            Map<String, Map<String, Integer>> after = dummy.dashboardDays(firstTarget, lastTarget);

            // 판정 — 보존일 이전 대상일은 0건, 그 뒤는 유형별로 하루 치 이상
            LocalDate keepFrom = d.retainDays() > 0 ? today.minusDays(d.retainDays()) : null;
            List<Map<String, Object>> checks = new ArrayList<>();
            boolean pass = !errors;
            int purgedDays = 0;
            for (Map.Entry<String, Map<String, Integer>> e : after.entrySet()) {
                LocalDate day = LocalDate.parse(e.getKey());
                boolean expectPurged = keepFrom != null && day.isBefore(keepFrom);
                boolean ok = true;
                for (DummyDataType t : d.types()) {
                    int c = e.getValue().getOrDefault(key(t), -1);
                    ok &= expectPurged ? c == 0 : c >= d.count();
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("targetDate", e.getKey());
                c.put("expect", expectPurged ? "PURGED" : "KEPT");
                // 이 날짜를 만든 회차 · 처음으로 정리 범위에 든 회차 — 전 · 후 건수만으로는 '만들었다 지웠다' 가 안 보인다
                for (Map<String, Object> run : runs) {
                    if (e.getKey().equals(run.get("targetDate"))) {
                        c.put("generatedOnDay", run.get("day"));
                        c.put("generated", run.get("generated"));
                    }
                    Object pb = run.get("purgeBefore");
                    if (!c.containsKey("purgedOnDay") && pb != null && day.isBefore(LocalDate.parse(pb.toString()))) {
                        c.put("purgedOnDay", run.get("day"));
                    }
                }
                c.put("before", before.get(e.getKey()));
                c.put("after", e.getValue());
                c.put("ok", ok);
                checks.add(c);
                pass &= ok;
                purgedDays += expectPurged ? 1 : 0;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("days", n);
            out.put("retainDays", d.retainDays());
            out.put("runFrom", today.minusDays(n).toString());
            out.put("runTo", today.toString());
            out.put("generatedFrom", firstTarget.toString());
            out.put("generatedTo", lastTarget.toString());
            out.put("keepFrom", keepFrom == null ? null : keepFrom.toString());
            out.put("expectedPurgedDays", purgedDays);
            out.put("runs", runs);
            out.put("checks", checks);
            out.put("verdict", keepFrom == null ? "NO_PURGE" : pass ? "PASS" : "FAIL");
            out.put("message", keepFrom == null
                    ? "보존 기간(retain-days)이 0 이하라 지우지 않습니다 — 만들기만 했습니다"
                    : (pass ? "통과" : "불일치") + " — 가상 실행일 " + today.minusDays(n) + " ~ " + today + " (" + (n + 1) + "회) · 대상일 "
                    + firstTarget + " ~ " + lastTarget + " 생성 · " + keepFrom + " 이전 " + purgedDays + "일 치는 지워지고 이후 "
                    + (after.size() - purgedDays) + "일 치는 남아야 합니다");
            remember("SIMULATE", today, Map.of("days", n, "verdict", out.get("verdict"), "generatedFrom", firstTarget.toString(),
                    "generatedTo", lastTarget.toString()));
            log.info("[Dummy:daily] {}일 경과 시뮬레이션 — {} · {}", n, out.get("verdict"), out.get("message"));
            return out;
        });
    }

    // ══════════════════════════════════════════════════════════════════════

    private static String key(DummyDataType t) {
        return switch (t) {
            case MEET -> "meet";
            case PHONE -> "phone";
            case IMAGE -> "image";
        };
    }

    private void requireDashboard() {
        if (!props.dashboard().enabled()) {
            throw new DummyDataService.DashboardDisabledException(
                    "대시보드 더미 생성이 꺼져 있습니다 — unstructured.mock.dashboard.enabled=false");
        }
    }

    private Map<String, Object> exclusive(String what, Supplier<Map<String, Object>> job) {
        if (!running.tryLock()) {
            throw new BusyException(runningWhat + " 이(가) 돌고 있습니다 — 끝난 뒤 다시 누르십시오");
        }
        try {
            runningWhat = what;
            return job.get();
        } finally {
            runningWhat = null;
            running.unlock();
        }
    }

    private void remember(String trigger, LocalDate runDate, Map<String, Object> result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trigger", trigger);
        m.put("at", LocalDateTime.now(ZONE).withNano(0).toString());
        m.put("runDate", runDate.toString());
        m.put("result", result);
        lastRun = m;
    }

    private static String nextRun(String cron) {
        try {
            ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.now(ZONE));
            return next == null ? null : next.toLocalDateTime().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private Path path() {
        try {
            return file == null ? null : file.get();
        } catch (Exception e) {
            return null;
        }
    }

    /** 기동 시 — 화면에서 바꾼 값이 파일에 있으면 그것을 쓴다. 못 읽으면 설정값으로(경고만). */
    private void load() {
        Path f = path();
        if (f == null || !Files.isRegularFile(f)) {
            return;
        }
        try {
            Map<?, ?> m = JSON.readValue(f.toFile(), Map.class);
            if (m.get("enabled") instanceof Boolean b) {
                enabled.set(b);
                source = "UI";
                changedAt = m.get("changedAt") == null ? null : String.valueOf(m.get("changedAt"));
            }
        } catch (IOException e) {
            log.warn("[Dummy:daily] 스위치 파일을 읽지 못해 설정값으로 간다 — {} ({})", f, e.getMessage());
        }
    }

    /** 설정값과 같으면 파일을 지우고, 다르면 임시 파일에 써서 바꿔 끼운다. 실패해도 메모리 값은 그대로(경고만). */
    private void save(boolean on) {
        Path f = path();
        if (f == null) {
            return;
        }
        try {
            if ("CONFIG".equals(source)) {
                Files.deleteIfExists(f);
                return;
            }
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", on);
            m.put("changedAt", changedAt);
            m.put("note", "시뮬레이터 [일 단위 자동 생성 스케줄러] 에서 바꾼 값 — 설정값(unstructured.mock.daily.enabled)으로 되돌리면 이 파일은 지워진다");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), m);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("[Dummy:daily] 스위치 파일을 쓰지 못했다(메모리 값으로 계속) — {} ({})", f, e.getMessage());
        }
    }
}
