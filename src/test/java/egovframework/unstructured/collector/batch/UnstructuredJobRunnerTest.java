package egovframework.unstructured.collector.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 비정형 실행기 — 접수 즉시 반환 · 실제 execId 대기 · 동시 1건(409) · 바깥 배치 거절 · 파드 간 잠금 · 상태. */
class UnstructuredJobRunnerTest {

    @Test
    @DisplayName("접수 직후 배치가 알린 로그 컬렉터 execId 를 응답에 싣는다(COLLECTOR)")
    void awaitsCollectorExecId() {
        UnstructuredJobRunner r = new UnstructuredJobRunner(() -> null);
        CountDownLatch hold = new CountDownLatch(1);
        UnstructuredJobRunner.Job job = r.submitAndAwaitExecId(UnstructuredJobRunner.Kind.RUN, "ADMIN", Map.of("k", "v"), sink -> {
            sink.publish("20261002UNS001", true);
            block(hold);
            return "done";
        }, 2000);
        assertThat(job.currentExecId()).isEqualTo("20261002UNS001");
        assertThat(job.execIdSource()).isEqualTo("COLLECTOR");
        assertThat(r.status("20261002UNS001").get("status")).isEqualTo("RUNNING");
        hold.countDown();
        await().atMost(3, TimeUnit.SECONDS).until(() -> "DONE".equals(r.status().get("status")));
        assertThat(r.status(job.handle()).get("result")).isEqualTo("done");
    }

    @Test
    @DisplayName("실행 중 재요청 — AlreadyRunningException(→409) 에 실행 중 execId")
    void conflictWhileRunning() {
        UnstructuredJobRunner r = new UnstructuredJobRunner(() -> null);
        CountDownLatch hold = new CountDownLatch(1);
        r.submitAndAwaitExecId(UnstructuredJobRunner.Kind.SCHEDULE, "SCHEDULER", null, sink -> {
            sink.publish("20261002UNS002", true);
            block(hold);
            return "ok";
        }, 2000);
        assertThatThrownBy(() -> r.submit(UnstructuredJobRunner.Kind.REPROCESS, "ADMIN", null, sink -> "x"))
                .isInstanceOfSatisfying(UnstructuredJobRunner.AlreadyRunningException.class, e -> {
                    assertThat(e.kind()).isEqualTo("SCHEDULE");
                    assertThat(e.execId()).isEqualTo("20261002UNS002");
                });
        hold.countDown();
        await().atMost(3, TimeUnit.SECONDS).until(() -> !r.isRunning());
        // 끝나면 다시 받는다
        assertThat(r.submit(UnstructuredJobRunner.Kind.RUN, "ADMIN", null, sink -> "again")).isNotNull();
    }

    @Test
    @DisplayName("실행기 밖의 배치(시뮬레이터·수동 API)가 돌고 있으면 거절 — kind=OTHER")
    void otherBatchRunning() {
        UnstructuredJobRunner r = new UnstructuredJobRunner(() -> "음성 배치(20261002TST001)");
        assertThatThrownBy(() -> r.submit(UnstructuredJobRunner.Kind.RUN, "ADMIN", null, sink -> "x"))
                .isInstanceOfSatisfying(UnstructuredJobRunner.AlreadyRunningException.class, e -> {
                    assertThat(e.kind()).isEqualTo("OTHER");
                    assertThat(e.getMessage()).contains("20261002TST001");
                });
    }

    @Test
    @DisplayName("기다려도 execId 가 안 오면 접수 handle(HANDLE) · 실패는 FAILED 와 사유")
    void handleFallbackAndFailure() {
        UnstructuredJobRunner r = new UnstructuredJobRunner(() -> null);
        UnstructuredJobRunner.Job job = r.submitAndAwaitExecId(UnstructuredJobRunner.Kind.RUN, "ADMIN", null, sink -> {
            sleep(400);
            throw new IllegalStateException("보라미 연결 실패");
        }, 50);
        assertThat(job.execIdSource()).isEqualTo("HANDLE");
        assertThat(job.currentExecId()).isEqualTo(job.handle()).startsWith("UNS-RUN-");
        await().atMost(3, TimeUnit.SECONDS).until(() -> "FAILED".equals(r.status(job.handle()).get("status")));
        assertThat(r.status(job.handle()).get("error")).asString().contains("보라미 연결 실패");
        assertThat(r.status("NOPE").get("status")).isEqualTo("NONE");
    }

    @Test
    @DisplayName("로그 컬렉터 미연동이면 로컬 임시 execId(LOCAL)")
    void localExecId() {
        UnstructuredJobRunner r = new UnstructuredJobRunner(() -> null);
        UnstructuredJobRunner.Job job = r.submitAndAwaitExecId(UnstructuredJobRunner.Kind.RUN, "ADMIN", null, sink -> {
            sink.publish("20261002UNS101530123", false);
            return "ok";
        }, 2000);
        assertThat(job.execIdSource()).isEqualTo("LOCAL");
    }

    /** 파드 간 잠금 대역 — 잡힘 / 다른 파드 / 오류를 바꿔 가며. */
    static final class FakeLock implements BatchLock {
        volatile String mode = "FREE";
        final AtomicInteger acquired = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();

        @Override
        public Held tryAcquire() {
            if ("OTHER_POD".equals(mode)) {
                return null;
            }
            if ("ERROR".equals(mode)) {
                throw new IllegalStateException("Admin DB 연결 실패");
            }
            acquired.incrementAndGet();
            return released::incrementAndGet;
        }

        @Override
        public Map<String, Object> describe() {
            return Map.of("enabled", true, "key", 42120002L);
        }
    }

    @Test
    @DisplayName("파드 간 잠금 — 다른 파드가 잡고 있으면 OTHER_POD · 잠금 확인 실패면 LOCK 로 거절(돌리지 않는다)")
    void rejectsWhenLockHeldElsewhere() {
        FakeLock lock = new FakeLock();
        UnstructuredJobRunner r = new UnstructuredJobRunner(() -> null, lock);
        lock.mode = "OTHER_POD";
        assertThatThrownBy(() -> r.submit(UnstructuredJobRunner.Kind.SCHEDULE, "SCHEDULER", null, sink -> "x"))
                .isInstanceOfSatisfying(UnstructuredJobRunner.AlreadyRunningException.class,
                        e -> assertThat(e.kind()).isEqualTo("OTHER_POD"));
        lock.mode = "ERROR";
        assertThatThrownBy(() -> r.submit(UnstructuredJobRunner.Kind.RUN, "ADMIN", null, sink -> "x"))
                .isInstanceOfSatisfying(UnstructuredJobRunner.AlreadyRunningException.class, e -> {
                    assertThat(e.kind()).isEqualTo("LOCK");
                    assertThat(e.getMessage()).contains("Admin DB 연결 실패");
                });
        assertThat(r.status().get("status")).isEqualTo("NONE");
        assertThat(r.lockInfo()).containsEntry("key", 42120002L);
    }

    @Test
    @DisplayName("파드 간 잠금 — 접수할 때 잡고, 배치가 끝나면(성공 · 실패 모두) 푼 뒤 done() 이 완료된다")
    void releasesLockAfterJob() throws Exception {
        FakeLock lock = new FakeLock();
        UnstructuredJobRunner r = new UnstructuredJobRunner(() -> null, lock);
        CountDownLatch hold = new CountDownLatch(1);
        UnstructuredJobRunner.Job job = r.submit(UnstructuredJobRunner.Kind.SCHEDULE, "SCHEDULER", null, sink -> {
            block(hold);
            return "ok";
        });
        assertThat(lock.acquired.get()).isEqualTo(1);
        assertThat(lock.released.get()).as("도는 동안은 쥐고 있다").isZero();
        assertThat(job.done()).isNotDone();
        hold.countDown();
        job.done().get(3, TimeUnit.SECONDS);
        assertThat(lock.released.get()).isEqualTo(1);

        UnstructuredJobRunner.Job failed = r.submit(UnstructuredJobRunner.Kind.RUN, "ADMIN", null, sink -> {
            throw new IllegalStateException("보라미 연결 실패");
        });
        failed.done().get(3, TimeUnit.SECONDS);
        assertThat(lock.released.get()).as("실패해도 푼다").isEqualTo(2);
        assertThat(r.status(failed.handle()).get("status")).isEqualTo("FAILED");
    }

    private static void block(CountDownLatch l) {
        try {
            l.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
