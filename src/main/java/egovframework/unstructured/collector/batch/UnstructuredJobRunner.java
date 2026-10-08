package egovframework.unstructured.collector.batch;

import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.image.perf.ImagePerfService;
import egovframework.unstructured.collector.voice.batch.BatchProgress;
import egovframework.unstructured.collector.voice.perf.PerfRunService;
import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 비정형 배치 실행기 — <b>스케줄 · 바로 실행 · 긴급 재처리가 같은 단일 스레드와 같은 잠금을 쓴다.</b>
 *
 * <p>data-collector {@code AsyncBatchJobRunner} · {@code ReprocessJobRunner} 와 같은 생각이다.
 * 배치(수 분~수십 분)를 HTTP 요청 스레드에서 돌리면 admin-api 호출이 타임아웃(기본 3초)난다 → 접수 즉시 돌려주고
 * 백그라운드에서 돌린다. 동시에 한 건만 — 실행 중에 또 오면 {@link AlreadyRunningException}(컨트롤러가 409).</p>
 *
 * <p><b>실행기 밖의 배치도 본다</b>: 시뮬레이터·기존 수동 API({@code /api/v1/voice/**} · {@code /api/v1/image/**})·성능 시험은
 * 요청 스레드에서 동기로 돈다. 그것이 돌고 있으면 같은 대상·같은 수신 폴더를 동시에 건드리게 되므로 역시 거절한다.</p>
 *
 * <p><b>파드 간 잠금</b>({@link BatchLock}, 2026-10-08): 프로세스 안 잠금({@code synchronized})에 더해 접수할 때
 * Admin DB advisory lock 을 잡고 배치가 끝나면 푼다(data-collector 와 같은 방식). 롤링 배포 중 옛 파드가 돌리고 있으면
 * 새 파드는 잠금을 못 잡아 거절된다({@code kind=OTHER_POD}). 실행기 밖의 동기 API(시뮬레이터 · 수동 API · 성능 시험)는
 * 이 잠금을 쓰지 않는다 — 사람이 직접 부르는 시험용이다.</p>
 */
@Log4j2
@Component
public class UnstructuredJobRunner {

    /** 실행 종류 — 상태 표기·로그용. 잠금은 종류와 무관하게 하나다. */
    public enum Kind { SCHEDULE, RUN, REPROCESS }

    /** 배치가 log-collector 채번 execId 를 받자마자 알려 준다 — 접수 응답·상태에 실린다. */
    @FunctionalInterface
    public interface ExecIdSink {
        void publish(String execId, boolean fromCollector);
    }

    /** 실제 배치 — 결과 요약 문자열을 돌려준다. */
    @FunctionalInterface
    public interface Task {
        String run(ExecIdSink sink);
    }

    /** 실행기 밖에서 도는 배치 — 있으면 무엇인지 설명, 없으면 null. */
    @FunctionalInterface
    public interface OtherBatchProbe {
        String running();
    }

    private static final DateTimeFormatter HANDLE = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final int HISTORY_MAX = 50;

    private final OtherBatchProbe others;
    private final BatchLock lock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "unstructured-batch");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Job> current = new AtomicReference<>();
    /** 접수 handle · 발행 execId → Job. 최근 것만(오래된 것부터 버린다). */
    private final Map<String, Job> history = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Job> e) {
            return size() > HISTORY_MAX;
        }
    });

    @Autowired
    public UnstructuredJobRunner(BatchProgress voiceProgress, ImageCollectService image,
                                 ObjectProvider<PerfRunService> voicePerf, ObjectProvider<ImagePerfService> imagePerf,
                                 BatchLock lock) {
        this(() -> {
            if (voiceProgress.isRunning()) {
                return "음성 배치(" + voiceProgress.snapshot().get("execId") + ")";
            }
            if (image.isRunning()) {
                return "수용자 이미지 수집";
            }
            PerfRunService vp = voicePerf.getIfAvailable();
            if (vp != null && vp.isActive()) {
                return "음성 성능 시험";
            }
            ImagePerfService ip = imagePerf.getIfAvailable();
            if (ip != null && ip.isActive()) {
                return "수용자 이미지 검증";
            }
            return null;
        }, lock);
    }

    /** 바깥 배치 판정을 직접 준다 — 테스트(다른 패키지의 스케줄러 테스트 포함)용. 파드 간 잠금 없음. */
    public UnstructuredJobRunner(OtherBatchProbe others) {
        this(others, BatchLock.NONE);
    }

    public UnstructuredJobRunner(OtherBatchProbe others, BatchLock lock) {
        this.others = others;
        this.lock = lock;
    }

    /**
     * 접수 — 실행 중(이 실행기든 바깥이든 · 다른 파드든)이면 {@link AlreadyRunningException}. 아니면 백그라운드로 넘기고 즉시 돌아온다.
     * 파드 간 잠금은 여기서 잡고, 배치가 끝나면(성공 · 실패 모두) 백그라운드 스레드가 푼다.
     *
     * @param detail 상태에 함께 보일 값(창 · 원배치 · 재처리 단계 등)
     */
    public synchronized Job submit(Kind kind, String triggerBy, Map<String, Object> detail, Task task) {
        Job cur = current.get();
        if (cur != null && cur.status == Status.RUNNING) {
            throw new AlreadyRunningException(cur.kind.name(), cur.currentExecId(), "이미 실행 중 — " + cur.kind + " " + cur.currentExecId());
        }
        String other = others.running();
        if (other != null) {
            throw new AlreadyRunningException("OTHER", null, "다른 배치가 실행 중 — " + other);
        }
        BatchLock.Held held;
        try {
            held = lock.tryAcquire();
        } catch (IllegalStateException e) {
            throw new AlreadyRunningException("LOCK", null, "실행 잠금을 확인하지 못해 돌리지 않는다 — " + e.getMessage());
        }
        if (held == null) {
            throw new AlreadyRunningException("OTHER_POD", null, "다른 파드에서 비정형 배치가 실행 중(DB 잠금)");
        }
        String handle = "UNS-" + kind.name() + "-" + LocalDateTime.now().format(HANDLE);
        Job job = new Job(handle, kind, triggerBy, detail);
        current.set(job);
        history.put(handle, job);
        try {
            executor.submit(() -> {
                try {
                    execute(job, task);
                } finally {
                    held.close();
                    job.done.complete(job);   // 잠금을 푼 뒤에 알린다 — 다음 회차가 곧바로 잠금을 잡을 수 있게
                }
            });
        } catch (RuntimeException e) {
            held.close();
            job.fail("접수 실패 — " + e.getMessage());
            job.done.complete(job);
            throw e;
        }
        log.info("[Unstructured] 접수 kind={} handle={} triggerBy={} {}", kind, handle, triggerBy, detail);
        return job;
    }

    /** 파드 간 잠금 상태 — 상태 화면용. */
    public Map<String, Object> lockInfo() {
        return lock.describe();
    }

    /**
     * 접수 뒤 실제 execId(로그 컬렉터 채번) 발행을 {@code awaitMs} 까지 기다린다 — data-collector {@code submitAwaitExecId} 와 같다.
     * 배치는 시작하자마자 T1 을 열어 execId 를 받으므로 보통 수십~수백 ms 면 온다. 못 받으면 handle 로 응답한다.
     */
    public Job submitAndAwaitExecId(Kind kind, String triggerBy, Map<String, Object> detail, Task task, long awaitMs) {
        Job job = submit(kind, triggerBy, detail, task);
        try {
            job.execIdLatch.await(Math.max(0, awaitMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return job;
    }

    private void execute(Job job, Task task) {
        try {
            String r = task.run((id, fromCollector) -> {
                job.publish(id, fromCollector);
                history.put(id, job);   // 발행 execId 로도 상태를 찾을 수 있게
            });
            job.finish(r);
            log.info("[Unstructured] 완료 kind={} execId={} {}ms — {}", job.kind, job.currentExecId(), job.elapsedMs(), r);
        } catch (RuntimeException | Error e) {
            job.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("[Unstructured] 실패 kind={} execId={} — {}", job.kind, job.currentExecId(), e.getMessage(), e);
        }
    }

    /** 이 실행기에서 지금 도는 작업이 있는가. */
    public boolean isRunning() {
        Job j = current.get();
        return j != null && j.status == Status.RUNNING;
    }

    /** 현재(또는 마지막) 작업. 없으면 status=NONE. */
    public Map<String, Object> status() {
        Job j = current.get();
        return j == null ? none(null) : j.snapshot();
    }

    /** handle 또는 execId 로 최근 작업 하나. 비우면 {@link #status()}. 없으면 status=NONE. */
    public Map<String, Object> status(String id) {
        if (id == null || id.isBlank()) {
            return status();
        }
        Job j = history.get(id.trim());
        return j == null ? none(id.trim()) : j.snapshot();
    }

    private static Map<String, Object> none(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "NONE");
        m.put("execId", id);
        return m;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    enum Status { RUNNING, DONE, FAILED }

    /** 작업 한 건. */
    public static final class Job {
        final String handle;
        final Kind kind;
        final String triggerBy;
        final Map<String, Object> detail;
        final LocalDateTime startedAt = LocalDateTime.now();
        final CountDownLatch execIdLatch = new CountDownLatch(1);
        final CompletableFuture<Job> done = new CompletableFuture<>();
        volatile Status status = Status.RUNNING;
        volatile String execId;
        volatile Boolean execIdFromCollector;
        volatile LocalDateTime finishedAt;
        volatile String result;
        volatile String error;

        Job(String handle, Kind kind, String triggerBy, Map<String, Object> detail) {
            this.handle = handle;
            this.kind = kind;
            this.triggerBy = triggerBy;
            this.detail = detail == null ? Map.of() : new LinkedHashMap<>(detail);
        }

        void publish(String id, boolean fromCollector) {
            if (execId == null) {   // 음성 → 이미지 순차여도 대표 execId 는 음성 배치(로그 컬렉터 채번) 하나
                execId = id;
                execIdFromCollector = fromCollector;
            }
            execIdLatch.countDown();
        }

        void finish(String r) {
            result = r;
            finishedAt = LocalDateTime.now();
            status = Status.DONE;
            execIdLatch.countDown();   // 발행 없이 끝나도 대기는 풀어 준다
        }

        void fail(String e) {
            error = e;
            finishedAt = LocalDateTime.now();
            status = Status.FAILED;
            execIdLatch.countDown();
        }

        public String handle() {
            return handle;
        }

        /** 끝나면(성공 · 실패 모두, 파드 간 잠금을 푼 뒤) 완료된다 — 스케줄러가 '끝난 시각 + 주기' 로 다음 회차를 건다. */
        public CompletableFuture<Job> done() {
            return done;
        }

        /** 응답·상태에 보일 execId — 로그 컬렉터 채번 값, 아직이면 접수 handle. */
        public String currentExecId() {
            return execId != null ? execId : handle;
        }

        /**
         * execId 출처 — {@code COLLECTOR}(로그 컬렉터 채번 · T1 적재됨) · {@code LOCAL}(컬렉터 미연동 — 로컬 임시 ID)
         * · {@code HANDLE}(아직 발행 전 — 접수 handle).
         */
        public String execIdSource() {
            if (execId == null) {
                return "HANDLE";
            }
            return Boolean.TRUE.equals(execIdFromCollector) ? "COLLECTOR" : "LOCAL";
        }

        public boolean running() {
            return status == Status.RUNNING;
        }

        long elapsedMs() {
            return Duration.between(startedAt, finishedAt != null ? finishedAt : LocalDateTime.now()).toMillis();
        }

        public Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind.name());
            m.put("status", status.name());
            m.put("execId", currentExecId());
            m.put("execIdSource", execIdSource());
            m.put("handle", handle);
            m.put("triggerBy", triggerBy);
            m.put("startedAt", startedAt.withNano(0).toString());
            m.put("finishedAt", finishedAt == null ? null : finishedAt.withNano(0).toString());
            m.put("elapsedMs", elapsedMs());
            m.put("detail", detail);
            m.put("result", result);
            m.put("error", error);
            return m;
        }
    }

    /** 실행 중 재요청 — 컨트롤러가 409 로 바꾼다. */
    public static class AlreadyRunningException extends RuntimeException {
        private final String kind;
        private final String execId;

        AlreadyRunningException(String kind, String execId, String message) {
            super(message);
            this.kind = kind;
            this.execId = execId;
        }

        /** 실행 중인 것 — {@code SCHEDULE/RUN/REPROCESS}, 실행기 밖이면 {@code OTHER}. */
        public String kind() {
            return kind;
        }

        /** 실행 중인 execId — 실행기 밖의 배치면 null 일 수 있다. */
        public String execId() {
            return execId;
        }
    }
}
