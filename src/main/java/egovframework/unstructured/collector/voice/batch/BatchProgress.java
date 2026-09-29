package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.model.ProcStatus;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 지금 도는 배치가 <b>몇 건 중 몇 번째를 처리하고 있는지</b>. 화면 진행률 바가 이것을 폴링한다.
 *
 * <p><b>왜 따로 두나</b>: 배치 REST 는 동기다 — 한 번 호출하면 전건이 끝나야 응답이 온다.
 * 1,300건이면 수 분 동안 화면이 멈춰 있고, 사용자는 도는 중인지 죽은 것인지 알 수 없다.
 * 응답을 스트리밍으로 바꾸면 결과 JSON 계약이 흔들리므로, 진행 상태만 밖에서 읽게 뺐다.</p>
 *
 * <p><b>한 번에 하나만 센다</b>: 배치는 {@code VoiceBatchScheduler} 의 가드로 동시에 하나만 돈다.
 * 겹쳐 시작하면 나중 것이 이깁(덮어씁)니다 — 진행률은 참고 표시이지 정합성 근거가 아니다.
 * 모든 필드가 {@code volatile} 이다(배치 스레드가 쓰고 API 스레드가 읽는다).</p>
 */
@Log4j2
@Component
public class BatchProgress {

    private volatile boolean running;
    private volatile String execId;
    private volatile String window;
    private volatile int total;
    private volatile String currentFile;
    private volatile String currentKind;
    private volatile long startedAt;
    private volatile long finishedAt;
    /**
     * 중단 요청 — 배치 루프가 건과 건 사이에서 본다.
     *
     * <p><b>처리 중인 건은 끝까지 간다.</b> 브로커 왕복이나 STT 호출 한가운데서 스레드를 끊으면
     * 복호화 원본이 디스크에 남거나 반쯤 쓴 산출물이 생긴다. 다음 건으로 넘어가기 직전에만 멈춘다.</p>
     */
    private volatile boolean cancelRequested;

    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger success = new AtomicInteger();
    private final AtomicInteger fail = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    /** 지금 처리 중인 건 수 — 동시 처리(워커 N개)일 때 '처리 중 N건' 으로 보인다. 순차면 0 또는 1. */
    private final AtomicInteger active = new AtomicInteger();
    private volatile int concurrency = 1;
    /** 워커 구성 — 확보·STT 를 나눠 돌 때(생산자-소비자) 화면이 구역별로 보여 준다. */
    private volatile int acquireWorkers = 1;
    private volatile boolean pipeline;
    /** 구역별 건수 — 확보 중 · STT 대기(확보 끝, 빈 STT 워커를 기다림) · STT 처리 중. 순차면 쓰지 않는다. */
    private final Map<Zone, AtomicInteger> zones = new java.util.EnumMap<>(Zone.class);

    /** 생산자-소비자에서 한 건이 머무는 구역. */
    public enum Zone { ACQUIRE, QUEUE, STT }

    public BatchProgress() {
        for (Zone z : Zone.values()) {
            zones.put(z, new AtomicInteger());
        }
    }

    /** 대상 수가 확정된 직후 — 총 건수를 알아야 진행률이 나온다. */
    public void begin(String execId, String window, int total) {
        begin(execId, window, total, 1);
    }

    /** 위와 같되, 동시에 처리하는 워커 수를 함께 적는다. */
    public void begin(String execId, String window, int total, int concurrency) {
        begin(execId, window, total, new Workers(1, concurrency), false);
    }

    /** 위와 같되, 확보·STT 워커를 나눠 적는다. {@code pipeline} 이면 구역별 건수를 센다. */
    public void begin(String execId, String window, int total, Workers workers, boolean pipeline) {
        this.concurrency = workers.stt();
        this.acquireWorkers = workers.acquire();
        this.pipeline = pipeline;
        zones.values().forEach(z -> z.set(0));
        active.set(0);
        this.execId = execId;
        this.window = window;
        this.total = total;
        this.currentFile = null;
        this.currentKind = null;
        this.startedAt = System.currentTimeMillis();
        this.finishedAt = 0L;
        done.set(0);
        success.set(0);
        fail.set(0);
        skipped.set(0);
        this.cancelRequested = false;
        this.running = true;
    }

    /** 이 건을 시작한다 — 화면에 "처리 중: mock_phone_003.wav" 로 뜬다. */
    public void startFile(VoiceTarget target) {
        if (!running) {
            return;
        }
        active.incrementAndGet();
        this.currentKind = target.kind().name();
        this.currentFile = target.srcFileName() == null ? target.shortId() : target.srcFileName();
    }

    /** 이 건이 끝났다 — 성공/실패/건너뜀을 나눠 센다. */
    public void finishFile(ProcStatus status) {
        if (!running) {
            return;
        }
        active.updateAndGet(v -> v > 0 ? v - 1 : 0);
        done.incrementAndGet();
        switch (status) {
            case SUCCESS -> success.incrementAndGet();
            case SKIPPED -> skipped.incrementAndGet();
            default -> fail.incrementAndGet();
        }
    }

    /**
     * 시작하지 않은 건을 건너뜀으로 센다 — 동시 처리 중 중단됐을 때. {@link #finishFile} 과 달리
     * '처리 중' 건수를 건드리지 않는다(다른 워커가 아직 처리 중이다).
     */
    public void skipFile() {
        if (!running) {
            return;
        }
        done.incrementAndGet();
        skipped.incrementAndGet();
    }

    /**
     * 한 건이 구역을 옮긴다 — {@code from} 에서 빼고 {@code to} 에 넣는다. 어느 쪽이든 null 이면 그쪽은 건드리지 않는다.
     * 확보 시작(null → ACQUIRE) · 확보 끝(ACQUIRE → QUEUE) · STT 워커가 집음(QUEUE → STT) · 끝(STT → null).
     */
    public void move(Zone from, Zone to) {
        if (!running) {
            return;
        }
        if (from != null) {
            zones.get(from).updateAndGet(v -> v > 0 ? v - 1 : 0);
        }
        if (to != null) {
            zones.get(to).incrementAndGet();
        }
    }

    /** 배치가 돌고 있는가 — 성능 테스트가 겹쳐 시작하지 않게 본다. */
    public boolean isRunning() {
        return running;
    }

    /**
     * 배치가 끝났다. <b>마지막 상태를 지우지 않는다</b> — 화면이 폴링을 멈추기 전에 100% 를 한 번은
     * 봐야 하고, 끝난 뒤에도 직전 배치 요약을 읽을 수 있어야 한다.
     */
    public void end() {
        this.running = false;
        this.currentFile = null;
        active.set(0);
        zones.values().forEach(z -> z.set(0));
        this.finishedAt = System.currentTimeMillis();
    }

    /** 중단을 요청한다. 돌고 있지 않으면 아무 일도 하지 않는다. */
    public boolean cancel() {
        if (!running) {
            return false;
        }
        cancelRequested = true;
        log.info("[Progress] 중단 요청 — execId={} ({}/{}건 처리 후)", execId, done.get(), total);
        return true;
    }

    /** 배치 루프가 건과 건 사이에서 묻는다. */
    public boolean isCancelRequested() {
        return cancelRequested;
    }

    /** 화면이 읽어 가는 한 장. 배치가 한 번도 안 돌았으면 {@code total=0} 이다. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        int t = total;
        int d = done.get();
        m.put("running", running);
        m.put("execId", execId);
        m.put("window", window);
        m.put("total", t);
        m.put("done", d);
        m.put("percent", t <= 0 ? (running ? 0 : 100) : (int) Math.round(d * 100.0 / t));
        m.put("success", success.get());
        m.put("fail", fail.get());
        m.put("skipped", skipped.get());
        m.put("currentFile", currentFile);
        m.put("currentKind", currentKind);
        m.put("active", active.get());
        m.put("concurrency", concurrency);
        m.put("acquireWorkers", acquireWorkers);
        m.put("sttWorkers", concurrency);
        m.put("pipeline", pipeline);
        if (pipeline) {
            m.put("acquiring", zones.get(Zone.ACQUIRE).get());
            m.put("queued", zones.get(Zone.QUEUE).get());
            m.put("processing", zones.get(Zone.STT).get());
        }
        m.put("canceled", cancelRequested);
        m.put("elapsedMs", startedAt == 0 ? 0 : (running ? System.currentTimeMillis() : finishedAt) - startedAt);
        return m;
    }
}
