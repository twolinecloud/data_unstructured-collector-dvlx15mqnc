package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.meet;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF9 전송 확인 문(2026-11-27) — 그 배치의 클라우드 전송(T2 SEND)이 SUCCESS 가 된 뒤에만 WF9 를 부르고, 그래야 T1 폐기 완료도 전송 뒤에 적힌다.
 * 수동 전송: STT → 비식별 → (대기) → (전송) → WF9 → 폐기 · 자동 전송: 배치 안에서 SEND 가 끝나 바로 WF9 → 폐기.
 */
class RiskWf9AfterSendTest {

    @TempDir
    Path tmp;

    private final Wf9TestSupport.MutableClock clock = new Wf9TestSupport.MutableClock(Instant.parse("2026-11-27T03:00:00Z"));
    private final AtomicBoolean batchRunning = new AtomicBoolean();
    /** 배치별 SEND 상태 — 없으면 null(읽지 못함). */
    private final Map<String, String> send = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();
    private Wf9Spool spool;
    private Path root;

    @BeforeEach
    void setUp() {
        root = tmp.resolve("spool");
    }

    private RiskWf9Worker worker(Wf9TestSupport.Props p) {
        RiskWf9Properties props = p.build();
        spool = new Wf9Spool(props, () -> root, () -> null, clock);
        return new RiskWf9Worker(props, spool, () -> null, (job, file) -> {
            ran.add(job.recFileId());
            return new RiskWf9Pipeline.Outcome(1, 0, 1, 0, 10, true);
        }, batchRunning::get, execId -> {
            asked.add(execId);
            return send.get(execId);
        }, clock);
    }

    private void put(String execId, String recFileId) {
        assertThat(spool.offer(execId, meet(recFileId), Wf9TestSupport.masked(), Wf9Spool.DEIDENT_MODE, false)).isTrue();
    }

    @Test
    @DisplayName("수동 전송 — SEND WAIT(보류) 동안은 WF9 를 부르지 않고 가린 문서를 둔다 · 시도 횟수를 쓰지 않는다")
    void manualWaitsWhileHeld() throws Exception {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        put("E-MANUAL", "R1");
        send.put("E-MANUAL", "WAIT");

        RiskWf9Worker.Report r = w.drain();

        assertThat(r.waitingSend()).isEqualTo(1);
        assertThat(r.succeeded()).isZero();
        assertThat(r.retried()).isZero();
        assertThat(ran).as("전송 전에는 WF9 · 폐기 완료가 없다").isEmpty();
        Wf9Job job = spool.list().get(0).job();
        assertThat(job.attempts()).isZero();
        assertThat(job.sendConfirmedAtMs()).isZero();
    }

    @Test
    @DisplayName("수동 전송 — [클라우드 전송]으로 SEND SUCCESS 가 되면 다음 순회에 WF9 를 부르고 스풀을 비운다")
    void manualRunsAfterSend() throws Exception {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        put("E-MANUAL", "R1");
        send.put("E-MANUAL", "WAIT");
        w.drain();

        send.put("E-MANUAL", "SUCCESS");
        clock.advanceSec(3600);
        RiskWf9Worker.Report r = w.drain();

        assertThat(r.succeeded()).isEqualTo(1);
        assertThat(r.waitingSend()).isZero();
        assertThat(ran).containsExactly("R1");
        assertThat(spool.list()).isEmpty();
    }

    @Test
    @DisplayName("자동 전송 — 배치 안에서 SEND SUCCESS 로 끝났으면 배치가 끝난 첫 순회에 바로 부른다")
    void autoRunsRightAway() {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        put("E-AUTO", "R1");
        send.put("E-AUTO", "SUCCESS");

        batchRunning.set(true);
        assertThat(w.drain().yielded()).as("배치가 돌 땐 양보 · 전송 상태도 묻지 않는다").isTrue();
        assertThat(asked).isEmpty();

        batchRunning.set(false);
        assertThat(w.drain().succeeded()).isEqualTo(1);
        assertThat(ran).containsExactly("R1");
    }

    @Test
    @DisplayName("SUCCESS 가 아닌 상태(RUNNING · FAIL · PARTIAL · CANCELED · SEND 없음 · 못 읽음)는 모두 기다린다")
    void otherStatesWait() {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        String[][] cases = {{"E1", "RUNNING"}, {"E2", "FAIL"}, {"E3", "PARTIAL"}, {"E4", "CANCELED"}, {"E5", null}};
        int i = 0;
        for (String[] c : cases) {
            put(c[0], "R" + (++i));
            clock.advanceSec(1);
            if (c[1] != null) {
                send.put(c[0], c[1]);
            }
        }

        RiskWf9Worker.Report r = w.drain();

        assertThat(r.waitingSend()).isEqualTo(5);
        assertThat(ran).isEmpty();
    }

    @Test
    @DisplayName("같은 배치의 여러 녹음은 한 번 훑는 동안 전송 상태를 한 번만 묻는다")
    void asksOncePerBatch() {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        put("E-MANUAL", "R1");
        clock.advanceSec(1);
        put("E-MANUAL", "R2");
        clock.advanceSec(1);
        put("E-OTHER", "R3");
        send.put("E-MANUAL", "WAIT");
        send.put("E-OTHER", "SUCCESS");

        RiskWf9Worker.Report r = w.drain();

        assertThat(asked).containsExactly("E-MANUAL", "E-OTHER");
        assertThat(r.waitingSend()).isEqualTo(2);
        assertThat(ran).containsExactly("R3");
    }

    @Test
    @DisplayName("기다리는 건은 하루 상한을 쓰지 않는다 — 보낸 배치 건이 상한에 막히지 않는다")
    void waitingDoesNotTakeDailySlot() {
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.dailyLimit = 1;
        RiskWf9Worker w = worker(p);
        put("E-MANUAL", "R1");
        clock.advanceSec(1);
        put("E-AUTO", "R2");
        send.put("E-MANUAL", "WAIT");
        send.put("E-AUTO", "SUCCESS");

        assertThat(w.drain().succeeded()).isEqualTo(1);
        assertThat(ran).containsExactly("R2");
    }

    @Test
    @DisplayName("전송 대기 상한(send-wait-max-hours)을 넘기면 포기하고 가린 문서를 지운다 — 48시간 기한과 따로 센다")
    void sendWaitExpires() throws Exception {
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.sendWaitMaxHours = 72;
        RiskWf9Worker w = worker(p);
        put("E-MANUAL", "R1");
        send.put("E-MANUAL", "WAIT");

        clock.advanceSec(60L * 3600);   // 48시간(max-age) 넘음 · 72시간 전
        assertThat(w.drain().waitingSend()).as("전송 대기 중엔 max-age 로 지우지 않는다").isEqualTo(1);

        clock.advanceSec(13L * 3600);   // 73시간
        RiskWf9Worker.Report r = w.drain();
        assertThat(r.gaveUp()).isEqualTo(1);
        assertThat(spool.list()).isEmpty();
        assertThat(ran).isEmpty();
    }

    @Test
    @DisplayName("전송 확인 뒤엔 max-age 를 확인 시각부터 센다 — 오래 기다린 건도 보낸 뒤 재시도할 시간이 있다")
    void maxAgeCountsFromConfirm() throws Exception {
        RiskWf9Worker w = worker(new Wf9TestSupport.Props());
        put("E-MANUAL", "R1");
        send.put("E-MANUAL", "WAIT");
        clock.advanceSec(100L * 3600);   // 100시간 기다림
        w.drain();
        send.put("E-MANUAL", "SUCCESS");

        RiskWf9Worker failing = new RiskWf9Worker(new Wf9TestSupport.Props().build(), spool, () -> null, (job, file) -> {
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "503");
        }, batchRunning::get, send::get, clock);
        assertThat(failing.drain().retried()).as("넣은 지 100시간이지만 전송 확인 직후라 포기하지 않는다").isEqualTo(1);
        Wf9Job job = spool.list().get(0).job();
        assertThat(job.sendConfirmedAtMs()).isEqualTo(clock.millis());
        assertThat(job.attempts()).isEqualTo(1);

        clock.advanceSec(49L * 3600);
        assertThat(failing.drain().gaveUp()).as("확인 뒤 48시간이 지나면 포기").isEqualTo(1);
    }

    @Test
    @DisplayName("after-send=false — 예전처럼 비식별 직후(전송 상태를 묻지 않고) 부른다")
    void offRunsWithoutAsking() {
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.afterSend = false;
        RiskWf9Worker w = worker(p);
        put("E-MANUAL", "R1");
        send.put("E-MANUAL", "WAIT");

        assertThat(w.drain().succeeded()).isEqualTo(1);
        assertThat(asked).isEmpty();
    }

    @Test
    @DisplayName("예전 job.json(sendConfirmedAtMs 없음)도 읽고 전송 대기로 본다")
    void oldJobJsonReadsAsUnconfirmed() throws Exception {
        String old = "{\"jobId\":\"j\",\"recFileId\":\"R1\",\"corrNo\":\"C\",\"kind\":\"MEET\",\"occurredAt\":\"2026-11-27T10:00:00\","
                + "\"execId\":\"E\",\"contactId\":\"C-1\",\"docFile\":\"doc.json\",\"cutSec\":null,\"createdAtMs\":1,"
                + "\"attempts\":0,\"nextAttemptAtMs\":0,\"lastError\":null}";
        Wf9Job j = new ObjectMapper().readValue(old, Wf9Job.class);
        assertThat(j.sendConfirmedAtMs()).isZero();
    }

    // ── 배치 상세 → SEND 마지막 행 ──────────────────────────────────────

    private static JsonNode json(String s) throws Exception {
        return new ObjectMapper().readTree(s);
    }

    @Test
    @DisplayName("배치 상세에서 SEND 가장 뒤 순번의 상태를 읽는다(재처리로 SEND 가 두 줄이면 뒤 것)")
    void lastSendStatus() throws Exception {
        assertThat(Wf9SendGate.lastSendStatus(json("{\"steps\":["
                + "{\"step_type_cd\":\"COLLECT\",\"step_seq\":1,\"step_sts_cd\":\"SUCCESS\"},"
                + "{\"step_type_cd\":\"SEND\",\"step_seq\":4,\"step_sts_cd\":\"WAIT\"},"
                + "{\"step_type_cd\":\"SEND\",\"step_seq\":8,\"step_sts_cd\":\"SUCCESS\"}]}"))).isEqualTo("SUCCESS");
        assertThat(Wf9SendGate.lastSendStatus(json("{\"steps\":["
                + "{\"step_type_cd\":\"SEND\",\"step_seq\":8,\"step_sts_cd\":\"WAIT\"},"
                + "{\"step_type_cd\":\"SEND\",\"step_seq\":4,\"step_sts_cd\":\"SUCCESS\"}]}"))).isEqualTo("WAIT");
        assertThat(Wf9SendGate.lastSendStatus(json("{\"result\":{\"steps\":["
                + "{\"step_type_cd\":\"SEND\",\"step_seq\":4,\"step_sts_cd\":\"SUCCESS\"}]}}"))).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("SEND 행이 없거나 · 상세가 없으면 null(기다린다)")
    void noSendStep() throws Exception {
        assertThat(Wf9SendGate.lastSendStatus(json("{\"steps\":[{\"step_type_cd\":\"DEIDENT\",\"step_seq\":3,\"step_sts_cd\":\"SUCCESS\"}]}")))
                .isNull();
        assertThat(Wf9SendGate.lastSendStatus(json("{}"))).isNull();
        assertThat(Wf9SendGate.lastSendStatus(null)).isNull();
    }
}
