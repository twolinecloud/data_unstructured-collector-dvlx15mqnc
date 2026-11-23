package egovframework.unstructured.collector.voice.risk;

import egovframework.unstructured.collector.voice.batch.BatchProgress;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * WF9 작업자 — <b>하나</b>. 음성 배치가 돌지 않을 때만 스풀을 오래된 것부터 비운다(제논 키 '동시 1건'을 배치 STT 에 양보).
 *
 * <ul>
 *   <li>전용 스레드에서 돈다 — 공용 스케줄러(배치 트리거가 쓰는 스레드)를 붙잡지 않는다</li>
 *   <li>건마다 배치가 시작했는지 다시 본다. 이미 보낸 제논 호출은 공정 세마포어 차례대로 끝난다</li>
 *   <li>성공 → 스풀 삭제(음성 사본 포함) · 다시 해도 같은 실패(400 · 422 · 사전 검증) → 바로 포기 ·
 *       그 밖의 실패 → {@code retry-base-sec} 부터 두 배씩 기다려 {@code max-retries} 번까지 · 넣은 지 {@code max-age-hours} 가
 *       지나면 포기. 포기하면 사본을 지우고 ERROR 로그</li>
 *   <li>끝까지 갈 수 없는 설정이면(커넥터 MOCK · 로그 컬렉터 미연동 · 포털 계정 없음) 돌지 않는다 — 재시도를 태우지 않고,
 *       기한이 지난 사본만 지운다</li>
 * </ul>
 */
@Log4j2
@Component
public class RiskWf9Worker {

    /** 한 건 처리 — 시험이 바꿔 끼운다. */
    interface Runner {
        RiskWf9Pipeline.Outcome run(Wf9Job job, Path audio);
    }

    /** 한 번 순회한 결과. */
    public record Report(int succeeded, int retried, int gaveUp, boolean yielded, String blocked) {}

    private final RiskWf9Properties props;
    private final Wf9Spool spool;
    private final Supplier<String> blocker;
    private final Runner runner;
    private final BooleanSupplier batchRunning;
    private final Clock clock;
    private final Object drainLock = new Object();
    private ScheduledExecutorService exec;
    private volatile boolean stopping;
    private volatile String lastBlocker;
    private LocalDate day;
    private int dayCount;
    private boolean dayLimitLogged;

    @Autowired
    public RiskWf9Worker(RiskWf9Properties props, Wf9Spool spool, RiskWf9Pipeline pipeline, BatchProgress progress) {
        this(props, spool, pipeline::blocker, pipeline::run, progress::isRunning, Clock.systemUTC());
    }

    RiskWf9Worker(RiskWf9Properties props, Wf9Spool spool, Supplier<String> blocker, Runner runner,
                  BooleanSupplier batchRunning, Clock clock) {
        this.props = props;
        this.spool = spool;
        this.blocker = blocker;
        this.runner = runner;
        this.batchRunning = batchRunning;
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        if (!props.enabled()) {
            log.info("[WF9] 접견·전화 위험 신호(제논 WF9) 꺼짐 — voice.risk-wf9.enabled=false");
            return;
        }
        String why = blocker.get();
        log.info("[WF9] 켜짐 — 포털 {} · 앞 {}초 분석 · 재시도 {}회 · 기한 {}시간 · {}", props.portalMode(),
                props.analyzeMaxSec(), props.maxRetries(), props.maxAgeHours(), why == null ? "준비됨" : "멈춤: " + why);
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wf9-worker");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::tick, Math.max(0, props.initialDelaySec()), Math.max(1, props.pollSec()),
                TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        stopping = true;
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    void tick() {
        try {
            drain();
        } catch (RuntimeException e) {
            // 메시지에 내용이 섞일 수 있어 예외 이름만
            log.error("[WF9] 작업자 순회 실패 — {}", e.getClass().getSimpleName());
        }
    }

    /** 스풀을 한 번 훑는다(작업자 하나 — 겹쳐 돌지 않는다). */
    public Report drain() {
        synchronized (drainLock) {
            return drainLocked();
        }
    }

    private Report drainLocked() {
        int ok = 0;
        int retried = 0;
        int gaveUp = 0;
        boolean yielded = false;
        String blocked = blocker.get();
        if (blocked == null && lastBlocker != null) {
            log.info("[WF9] 작업자 다시 돈다 — 막힘 풀림");
        } else if (blocked != null && !blocked.equals(lastBlocker)) {
            log.warn("[WF9] 작업자 멈춤 — {}(스풀은 두고 기한 지난 사본만 지운다)", blocked);
        }
        lastBlocker = blocked;
        try {
            spool.sweepTemp();
        } catch (IOException e) {
            log.warn("[WF9] 넣다 만 폴더 정리 실패 — {}", e.getClass().getSimpleName());
        }
        List<Wf9Spool.Entry> entries;
        try {
            entries = spool.list();
        } catch (IOException e) {
            log.warn("[WF9] 스풀을 읽지 못함 — {}", e.getClass().getSimpleName());
            return new Report(0, 0, 0, false, blocked);
        }
        for (Wf9Spool.Entry e : entries) {
            if (stopping || Thread.currentThread().isInterrupted()) {
                break;
            }
            Wf9Job job = e.job();
            if (job == null) {
                spool.delete(e);
                log.error("[WF9] 포기 — {} · 작업 정보 · 음성 사본을 읽지 못함 · 폴더 지움", e.dir().getFileName());
                gaveUp++;
                continue;
            }
            if (clock.millis() - job.createdAtMs() > props.maxAgeHours() * 3_600_000L) {
                giveUp(e, "넣은 지 " + props.maxAgeHours() + "시간 지남"
                        + (job.lastError() == null ? "" : " · 마지막 실패 " + job.lastError()));
                gaveUp++;
                continue;
            }
            if (blocked != null || job.nextAttemptAtMs() > clock.millis()) {
                continue;
            }
            if (batchRunning.getAsBoolean()) {
                log.debug("[WF9] 음성 배치가 돌고 있어 양보한다");
                yielded = true;
                break;
            }
            if (!takeDailySlot()) {
                break;
            }
            switch (processOne(e)) {
                case 1 -> ok++;
                case 2 -> retried++;
                case 3 -> gaveUp++;
                default -> {
                    // 종료 중 — 다음 기동 때 다시
                }
            }
        }
        return new Report(ok, retried, gaveUp, yielded, blocked);
    }

    /** @return 1 성공 · 2 다시 하기로 · 3 포기 · 0 종료 중이라 그대로 둠 */
    private int processOne(Wf9Spool.Entry e) {
        Wf9Job job = e.job();
        try {
            RiskWf9Pipeline.Outcome o = runner.run(job, e.dir().resolve(job.audioFile()));
            spool.delete(e);
            log.info("[WF9] 완료 — {} · 발견 {} · 검토 {} · 적재 {}행{} · 토큰 {} · {}ms · 음성 사본 지움", job.shortId(),
                    o.findings(), o.reviews(), o.inserted(), o.skipped() > 0 ? " · 빈 내용 " + o.skipped() + "개 뺌" : "",
                    o.tokens(), o.elapsedMs());
            return 1;
        } catch (Wf9Failure f) {
            if (stopping) {
                log.info("[WF9] 종료 중 — {} 는 다음 기동 때 다시", job.shortId());
                return 0;
            }
            String why = "[" + f.stage() + "] " + f.getMessage();
            if (!f.retryable()) {
                giveUp(e, "다시 해도 같은 결과 — " + why);
                return 3;
            }
            return retryLater(e, why);
        } catch (RuntimeException ex) {
            if (stopping) {
                return 0;
            }
            return retryLater(e, "예상 밖 오류 — " + ex.getClass().getSimpleName());
        }
    }

    private int retryLater(Wf9Spool.Entry e, String why) {
        Wf9Job job = e.job();
        int attempts = job.attempts() + 1;
        if (attempts > props.maxRetries()) {
            giveUp(e, "재시도 " + props.maxRetries() + "회를 다 씀 — " + why);
            return 3;
        }
        long waitMs = Math.max(1, props.retryBaseSec()) * 1000L << Math.min(attempts - 1, 20);
        try {
            spool.save(e, job.failed(clock.millis() + waitMs, why));
        } catch (IOException io) {
            log.warn("[WF9] 실패 횟수를 적지 못함 — {} · {}", job.shortId(), io.getClass().getSimpleName());
        }
        log.warn("[WF9] 실패 — {} · {} · {}초 뒤 다시({}/{})", job.shortId(), why, waitMs / 1000, attempts, props.maxRetries());
        return 2;
    }

    private void giveUp(Wf9Spool.Entry e, String why) {
        spool.delete(e);
        log.error("[WF9] 포기 — {} · {} · 음성 사본 지움", e.job().shortId(), why);
    }

    /** 하루(KST) 상한 — 0 이면 없음. */
    private boolean takeDailySlot() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), Wf9Job.KST);
        if (!today.equals(day)) {
            day = today;
            dayCount = 0;
            dayLimitLogged = false;
        }
        if (props.dailyLimit() > 0 && dayCount >= props.dailyLimit()) {
            if (!dayLimitLogged) {
                log.warn("[WF9] 오늘 처리 상한 {}건에 닿음 — 남은 작업은 내일", props.dailyLimit());
                dayLimitLogged = true;
            }
            return false;
        }
        dayCount++;
        return true;
    }
}
