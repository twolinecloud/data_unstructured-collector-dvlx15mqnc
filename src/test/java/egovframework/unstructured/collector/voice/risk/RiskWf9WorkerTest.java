package egovframework.unstructured.collector.voice.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.meet;
import static org.assertj.core.api.Assertions.assertThat;

/** WF9 작업자 — 성공 · 포기 · 다시 하기(간격 두 배) · 기한 · 배치 양보 · 막힘 · 하루 상한. 처리는 바꿔 끼운 가짜다. */
class RiskWf9WorkerTest {

    @TempDir
    Path tmp;

    private Path root;
    private final Wf9TestSupport.MutableClock clock = new Wf9TestSupport.MutableClock(Instant.parse("2026-11-16T03:00:00Z"));
    private final AtomicReference<String> blocked = new AtomicReference<>();
    private final AtomicBoolean batchRunning = new AtomicBoolean();
    private final List<String> ran = new ArrayList<>();
    private final Deque<Supplier<RiskWf9Pipeline.Outcome>> script = new ArrayDeque<>();
    private Wf9Spool spool;

    @BeforeEach
    void setUp() throws Exception {
        root = tmp.resolve("spool");
    }

    private RiskWf9Worker worker(Wf9TestSupport.Props p) {
        p.afterSend = false;   // 전송 확인 문은 RiskWf9AfterSendTest 가 본다 — 여기는 기한 · 재시도 · 양보만
        RiskWf9Properties props = p.build();
        spool = new Wf9Spool(props, () -> root, () -> null, clock);
        return new RiskWf9Worker(props, spool, blocked::get, (job, file) -> {
            ran.add(job.recFileId());
            assertThat(file).exists();
            Supplier<RiskWf9Pipeline.Outcome> next = script.poll();
            return next == null ? ok() : next.get();
        }, batchRunning::get, clock);
    }

    private static RiskWf9Pipeline.Outcome ok() {
        return new RiskWf9Pipeline.Outcome(1, 0, 1, 0, 10, true);
    }

    private void put(String recFileId) {
        assertThat(spool.offer("E1", meet(recFileId), Wf9TestSupport.masked(), Wf9Spool.DEIDENT_MODE, false)).isTrue();
    }

    private Wf9Job only() throws Exception {
        List<Wf9Spool.Entry> l = spool.list();
        assertThat(l).hasSize(1);
        return l.get(0).job();
    }

    @Test
    @DisplayName("성공 → 스풀 삭제(가린 문서 포함)")
    void success() throws Exception {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        put("R1");

        RiskWf9Worker.Report r = w.drain();

        assertThat(r.succeeded()).isEqualTo(1);
        assertThat(ran).containsExactly("R1");
        assertThat(spool.list()).isEmpty();
    }

    @Test
    @DisplayName("다시 해도 같은 실패(422 등) → 바로 포기하고 지운다")
    void permanentGivesUp() throws Exception {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        put("R1");
        script.add(() -> {
            throw Wf9Failure.permanent(Wf9Failure.Stage.INPUT, "WF9 입력 규격 위반 — speech_results[0].segments[0].speaker INMATE · OTHER · UNKNOWN 이 아니다");
        });

        RiskWf9Worker.Report r = w.drain();

        assertThat(r.gaveUp()).isEqualTo(1);
        assertThat(spool.list()).isEmpty();
    }

    @Test
    @DisplayName("다시 하기 — 5분 · 10분 · 20분 뒤, 재시도 3회를 다 쓰면 포기(첫 시도 포함 4번)")
    void retriesWithBackoff() throws Exception {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        put("R1");
        for (int i = 0; i < 4; i++) {
            script.add(() -> {
                throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 WF9 HTTP 503");
            });
        }

        assertThat(w.drain().retried()).isEqualTo(1);
        Wf9Job j = only();
        assertThat(j.attempts()).isEqualTo(1);
        assertThat(j.nextAttemptAtMs()).isEqualTo(clock.millis() + 300_000);
        assertThat(j.lastError()).isEqualTo("[PORTAL] 제논 WF9 HTTP 503");

        assertThat(w.drain().retried()).as("때가 안 됐다").isZero();
        assertThat(ran).hasSize(1);

        clock.advanceSec(300);
        w.drain();
        assertThat(only().attempts()).isEqualTo(2);
        assertThat(only().nextAttemptAtMs()).isEqualTo(clock.millis() + 600_000);

        clock.advanceSec(600);
        w.drain();
        assertThat(only().nextAttemptAtMs()).isEqualTo(clock.millis() + 1_200_000);

        clock.advanceSec(1_200);
        RiskWf9Worker.Report last = w.drain();
        assertThat(last.gaveUp()).isEqualTo(1);
        assertThat(ran).hasSize(4);
        assertThat(spool.list()).isEmpty();
    }

    @Test
    @DisplayName("예상 밖 예외는 다시 하기 — 남기는 사유는 예외 이름만(메시지의 내용을 버린다)")
    void unexpectedException() throws Exception {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        put("R1");
        script.add(() -> {
            throw new IllegalStateException("홍길동 처리 중 깨짐");
        });

        assertThat(w.drain().retried()).isEqualTo(1);
        assertThat(only().lastError()).isEqualTo("예상 밖 오류 — IllegalStateException");
    }

    @Test
    @DisplayName("기한(48시간)이 지나면 막혀 있어도 포기하고 지운다 · 막혀 있으면 처리하지 않는다")
    void expiryAndBlocked() throws Exception {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        put("R1");
        blocked.set("로그 컬렉터 미연동");

        RiskWf9Worker.Report r = w.drain();
        assertThat(r.blocked()).isEqualTo("로그 컬렉터 미연동");
        assertThat(ran).isEmpty();
        assertThat(spool.list()).hasSize(1);

        clock.advanceSec(48 * 3600 + 1);
        assertThat(w.drain().gaveUp()).isEqualTo(1);
        assertThat(ran).isEmpty();
        assertThat(spool.list()).isEmpty();
    }

    @Test
    @DisplayName("음성 배치가 돌면 양보한다 — 끝나면 이어서")
    void yieldsToBatch() throws Exception {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        put("R1");
        batchRunning.set(true);

        RiskWf9Worker.Report r = w.drain();
        assertThat(r.yielded()).isTrue();
        assertThat(ran).isEmpty();

        batchRunning.set(false);
        assertThat(w.drain().succeeded()).isEqualTo(1);
    }

    @Test
    @DisplayName("하루(KST) 상한 — 넘으면 다음 날로")
    void dailyLimit() throws Exception {
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.dailyLimit = 1;
        RiskWf9Worker w = worker(p);
        put("R1");
        clock.advanceSec(1);
        put("R2");

        assertThat(w.drain().succeeded()).isEqualTo(1);
        assertThat(w.drain().succeeded()).as("오늘은 다 썼다").isZero();
        assertThat(ran).containsExactly("R1");

        clock.advanceSec(24 * 3600);
        assertThat(w.drain().succeeded()).isEqualTo(1);
        assertThat(ran).containsExactly("R1", "R2");
    }

    @Test
    @DisplayName("못 읽는 작업 폴더는 지운다 · 여러 건은 넣은 순서대로")
    void corruptAndOrder() throws Exception {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        put("R2");
        clock.advanceSec(-10);
        put("R1");
        clock.advanceSec(10);
        Files.writeString(Files.createDirectories(root.resolve("meet-broken")).resolve(Wf9Spool.JOB_FILE), "{");

        RiskWf9Worker.Report r = w.drain();

        assertThat(r.gaveUp()).isEqualTo(1);
        assertThat(r.succeeded()).isEqualTo(2);
        assertThat(ran).containsExactly("R1", "R2");
        assertThat(spool.list()).isEmpty();
    }
}
