package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.model.BatchWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 배치 스케줄러 — 일배치와 주기배치.
 *
 * <p>2026-09-11 회의 {@code [00:18:42]}~{@code [00:19:08]} 의 합의를 그대로 옮겼다.</p>
 * <ul>
 *   <li><b>일배치</b> — 새벽에 돌면서 "전날 00시부터 오늘 00시 전까지" 하루치</li>
 *   <li><b>주기배치</b> — 10분 단위로 돌되 "앞에서 못 돌린 것까지 다 하고 20분 전 것까지"</li>
 * </ul>
 *
 * <p><b>기본은 꺼져 있다</b>({@code voice.batch.schedule-enabled=false}).
 * 개발·시연 중에 원치 않는 시점에 배치가 돌아 대상을 소진해 버리면 시연을 다시 준비해야 한다.
 * 운영 배포에서만 켠다.</p>
 *
 * <p><b>중복 실행 방지</b>: 이전 실행이 끝나지 않았으면 이번 주기는 건너뛴다. 10분 주기인데
 * 한 번 실행이 10분을 넘기면 실행이 겹쳐 같은 대상을 동시에 집게 된다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class VoiceBatchScheduler {

    private final VoiceProperties props;
    private final VoiceCollectService service;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${voice.batch.daily-cron}")
    public void daily() {
        if (!props.batch().scheduleEnabled()) {
            return;
        }
        runGuarded(BatchWindow.daily(LocalDateTime.now()));
    }

    @Scheduled(cron = "${voice.batch.periodic-cron}")
    public void periodic() {
        if (!props.batch().scheduleEnabled()) {
            return;
        }
        runGuarded(BatchWindow.periodic(LocalDateTime.now(), props.batch().periodicLagMin()));
    }

    private void runGuarded(BatchWindow window) {
        if (!running.compareAndSet(false, true)) {
            log.warn("[Scheduler] 이전 실행이 아직 진행 중 — {} 건너뜀", window);
            return;
        }
        try {
            service.run(window, null, "SCHEDULER");
        } catch (Exception e) {
            // 스케줄러 스레드로 예외가 올라가면 이후 주기가 멈출 수 있다. 여기서 막는다.
            log.error("[Scheduler] 배치 실행 실패 — {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    /** 현재 실행 중인지(상태 조회용). */
    public boolean isRunning() {
        return running.get();
    }
}
