package egovframework.unstructured.collector.mock;

import egovframework.unstructured.collector.batch.UnstructuredJobRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 장시간 배치 점유 시뮬레이션 — 관리 화면 [바로 실행] · [긴급 재처리]가 "이미 실행 중" 으로 거절되는 상황을 재현한다. dev · local 전용.
 *
 * <p>진짜 배치와 <b>같은 실행기 · 같은 잠금</b>({@link UnstructuredJobRunner#submit} — 프로세스 안 잠금 + 파드 간 DB 잠금)을
 * 잡고 정해진 시간 동안 기다리기만 한다. 그래서 점유 중에는 바로 실행 호환 경로가 실제 배치가 돌 때와 똑같이
 * 200 {@code {accepted:false, reason:"이미 실행 중"}}, 본문 기반 경로 · 긴급 재처리 · 클라우드 전송이 409 로 거절된다.
 * 시뮬레이터가 직접 부르는 수동 배치(/api/v1/voice/batches/*)는 원래 이 실행기를 거치지 않으므로 막지 않는다.
 */
@Log4j2
@Service
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class SimHoldService {

    static final int DEFAULT_SEC = 30;
    static final int MAX_SEC = 600;

    private final UnstructuredJobRunner runner;
    private final AtomicReference<Hold> current = new AtomicReference<>();

    /** 점유 시작 — 다른 작업이 돌고 있으면 실행기가 {@link UnstructuredJobRunner.AlreadyRunningException} 을 던진다(409). */
    public Map<String, Object> hold(Integer durationSec, String triggerBy) {
        int sec = durationSec == null ? DEFAULT_SEC : durationSec;
        if (sec < 1 || sec > MAX_SEC) {
            throw new IllegalArgumentException("점유 시간은 1 ~ " + MAX_SEC + "초로 입력해 주세요.");
        }
        Hold h = new Hold(sec);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("simulation", "장시간 배치 점유");
        detail.put("durationSec", sec);
        detail.put("until", h.until.withNano(0).toString());
        if (!current.compareAndSet(null, h)) {   // 태스크가 끝나며 비우므로 잡기 전에 걸어 둔다
            throw new IllegalStateException("이미 가상 배치를 점유하고 있습니다.");
        }
        UnstructuredJobRunner.Job job;
        try {
            job = runner.submit(UnstructuredJobRunner.Kind.SIM_HOLD,
                triggerBy == null || triggerBy.isBlank() ? "SIM-HOLD" : triggerBy, detail, sink -> {
                    try {
                        boolean released = h.release.await(sec, TimeUnit.SECONDS);
                        return released ? "점유 강제 해제 — " + h.elapsedSec() + "초 점유" : "점유 종료 — " + sec + "초";
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "점유 중단(인터럽트)";
                    } finally {
                        current.compareAndSet(h, null);
                    }
                });
        } catch (RuntimeException e) {
            current.compareAndSet(h, null);
            throw e;
        }
        h.handle = job.handle();
        log.info("[SimHold] 배치 점유 시작 — {}초 · handle={} · 끝 {}", sec, h.handle, h.until.withNano(0));
        return status();
    }

    /** 점유 강제 해제 — 기다리던 태스크를 깨우면 실행기가 잠금을 푼다(파드 간 DB 잠금 포함). */
    public Map<String, Object> release() {
        Hold h = current.get();
        Map<String, Object> m = new LinkedHashMap<>();
        if (h == null) {
            m.put("released", false);
            m.put("message", "점유 중인 가상 배치가 없습니다.");
        } else {
            h.release.countDown();
            m.put("released", true);
            m.put("handle", h.handle);
            m.put("heldSec", h.elapsedSec());
            log.info("[SimHold] 점유 강제 해제 — handle={} · {}초 점유", h.handle, h.elapsedSec());
        }
        m.putAll(statusAfterRelease(h));
        return m;
    }

    /** 지금 점유 상태 — 시뮬레이터가 남은 초를 그린다. 실행기 상태(실제 배치 포함)도 함께. */
    public Map<String, Object> status() {
        Hold h = current.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("holding", h != null);
        if (h != null) {
            m.put("handle", h.handle);
            m.put("durationSec", h.durationSec);
            m.put("startedAt", h.startedAt.withNano(0).toString());
            m.put("until", h.until.withNano(0).toString());
            m.put("remainingSec", h.remainingSec());
        }
        m.put("runner", runner.status());
        return m;
    }

    private Map<String, Object> statusAfterRelease(Hold h) {
        if (h == null) {
            return status();
        }
        // 깨운 태스크가 잠금을 푸는 데 잠깐 걸린다 — 응답에서 '아직 점유 중' 으로 보이지 않게 조금 기다린다
        try {
            for (int i = 0; i < 20 && current.get() == h; i++) {
                Thread.sleep(25);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return status();
    }

    static final class Hold {
        final int durationSec;
        final LocalDateTime startedAt = LocalDateTime.now();
        final LocalDateTime until;
        final CountDownLatch release = new CountDownLatch(1);
        volatile String handle;

        Hold(int durationSec) {
            this.durationSec = durationSec;
            this.until = startedAt.plusSeconds(durationSec);
        }

        long remainingSec() {
            return Math.max(0, Duration.between(LocalDateTime.now(), until).toSeconds());
        }

        long elapsedSec() {
            return Duration.between(startedAt, LocalDateTime.now()).toSeconds();
        }
    }
}
