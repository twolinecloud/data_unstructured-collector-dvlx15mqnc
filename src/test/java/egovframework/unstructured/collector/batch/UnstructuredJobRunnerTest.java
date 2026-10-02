package egovframework.unstructured.collector.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 비정형 실행기 — 접수 즉시 반환 · 실제 execId 대기 · 동시 1건(409) · 바깥 배치 거절 · 상태. */
class UnstructuredJobRunnerTest {

    @Test
    @DisplayName("접수 직후 배치가 알린 로그 컬렉터 execId 를 응답에 싣는다(COLLECTOR)")
    void awaitsCollectorExecId() {
        UnstructuredJobRunner r = new UnstructuredJobRunner(() -> null);
        CountDownLatch hold = new CountDownLatch(1);
        UnstructuredJobRunner.Job job = r.submitAndAwaitExecId(UnstructuredJobRunner.Kind.RUN, "ADMIN", Map.of("k", "v"), sink -> {
            sink.publish("20261002VOC001", true);
            block(hold);
            return "done";
        }, 2000);
        assertThat(job.currentExecId()).isEqualTo("20261002VOC001");
        assertThat(job.execIdSource()).isEqualTo("COLLECTOR");
        assertThat(r.status("20261002VOC001").get("status")).isEqualTo("RUNNING");
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
            sink.publish("20261002VOC002", true);
            block(hold);
            return "ok";
        }, 2000);
        assertThatThrownBy(() -> r.submit(UnstructuredJobRunner.Kind.REPROCESS, "ADMIN", null, sink -> "x"))
                .isInstanceOfSatisfying(UnstructuredJobRunner.AlreadyRunningException.class, e -> {
                    assertThat(e.kind()).isEqualTo("SCHEDULE");
                    assertThat(e.execId()).isEqualTo("20261002VOC002");
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
            sink.publish("20261002VOC101530123", false);
            return "ok";
        }, 2000);
        assertThat(job.execIdSource()).isEqualTo("LOCAL");
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
