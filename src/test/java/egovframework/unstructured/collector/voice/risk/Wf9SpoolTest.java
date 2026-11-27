package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.OM;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.masked;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.meet;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.phone;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF9 스풀(2026-11-27 텍스트 전용) — 가린 전사로 만든 문서만 넣는다 · 비식별 모드 관문 · 중복 · 상한 · 구간 기준 D9 · 꺼내기 · 정리.
 * 음성 파일은 어디에도 없다. 전사는 지어낸 가린 글이다.
 */
class Wf9SpoolTest {

    @TempDir
    Path tmp;

    private Path root;
    private final AtomicReference<String> blocked = new AtomicReference<>();
    private final Wf9TestSupport.MutableClock clock = new Wf9TestSupport.MutableClock(Instant.parse("2026-11-16T03:00:00Z"));

    @BeforeEach
    void setUp() {
        root = tmp.resolve("xvram/wf9-spool");
    }

    private Wf9Spool spool(Wf9TestSupport.Props p) {
        return new Wf9Spool(p.build(), () -> root, blocked::get, clock);
    }

    private static boolean offer(Wf9Spool s, String execId, VoiceTarget t) {
        return s.offer(execId, t, masked(), Wf9Spool.DEIDENT_MODE, false);
    }

    @Test
    @DisplayName("넣기 — job.json(대체 ID · 일시 · 구분) + doc.json(가린 구간 · UNKNOWN · not_run) · 음성 파일 없음 · 같은 녹음은 대기 중 한 번만")
    void offerWritesJobAndDoc() throws Exception {
        Wf9Spool s = spool(new Wf9TestSupport.Props());

        assertThat(offer(s, "E1", meet("TARE0000000001"))).isTrue();
        assertThat(offer(s, "E2", meet("TARE0000000001"))).as("대기 중 중복").isFalse();
        assertThat(offer(s, "E1", phone("VRFC0000000001"))).isTrue();

        List<Wf9Spool.Entry> list = s.list();
        assertThat(list).hasSize(2);
        Wf9Spool.Entry me = list.stream().filter(e -> "MEET".equals(e.job().kind())).findFirst().orElseThrow();
        Wf9Job j = me.job();
        assertThat(j.recFileId()).isEqualTo("TARE0000000001");
        assertThat(j.corrNo()).isEqualTo("2026000123");
        assertThat(j.execId()).isEqualTo("E1");
        assertThat(j.occurredLocal()).hasToString("2026-11-16T10:30");
        assertThat(j.contactType()).isEqualTo("접견");
        assertThat(j.contactSeCd()).isEqualTo("VISIT");
        assertThat(j.contactId()).startsWith("C-").hasSize(34).doesNotContain("TARE").doesNotContain("2026000123");
        assertThat(j.docFile()).isEqualTo(Wf9Spool.DOC_FILE);
        assertThat(j.cutSec()).isNull();
        assertThat(j.attempts()).isZero();
        assertThat(j.createdAtMs()).isEqualTo(clock.millis());

        try (var files = Files.list(me.dir())) {
            assertThat(files.map(f -> f.getFileName().toString()).toList())
                    .as("음성 사본이 없다").containsExactlyInAnyOrder(Wf9Spool.JOB_FILE, Wf9Spool.DOC_FILE);
        }
        String raw = Files.readString(me.dir().resolve(Wf9Spool.DOC_FILE));
        assertThat(raw).as("문서엔 녹음 ID · 교정번호가 없다").doesNotContain("TARE0000000001").doesNotContain("2026000123");
        JsonNode doc = OM.readTree(raw);
        assertThat(doc.path("function").asText()).isEqualTo("contact_risk_analysis");
        JsonNode rec = doc.path("input").path("contact_records").path(0);
        assertThat(rec.path("contact_id").asText()).isEqualTo(j.contactId());
        assertThat(rec.path("contact_type").asText()).isEqualTo("접견");
        assertThat(rec.path("occurred_at").asText()).isEqualTo("2026-11-16T10:30+09:00");
        JsonNode sr = doc.path("input").path("speech_results").path(0);
        assertThat(sr.path("segments")).hasSize(2);
        assertThat(sr.path("segments").path(0).path("text").asText()).isEqualTo(Wf9TestSupport.MASKED_1);
        assertThat(sr.path("prosody_status").asText()).isEqualTo("not_run");
        assertThat(sr.path("audio_status").asText()).isEqualTo("not_run");

        Wf9Job p = list.stream().map(Wf9Spool.Entry::job).filter(x -> "PHONE".equals(x.kind())).findFirst().orElseThrow();
        assertThat(p.contactType()).isEqualTo("전화");
        assertThat(p.contactSeCd()).isEqualTo("CALL");
        assertThat(p.occurredLocal()).hasToString("2026-11-16T14:05:07");
        assertThat(Files.readString(me.dir().resolve(Wf9Spool.JOB_FILE))).as("job.json 에 계정 · 토큰 같은 것은 없다")
                .doesNotContain("password").doesNotContain("token");
    }

    @Test
    @DisplayName("비식별 관문 — 커넥터가 실제로 가린 건(mode=DEIDENT)만. MOCK · BYPASS · OFF · null 은 원문일 수 있어 넣지 않는다")
    void onlyDeidentedTranscripts() throws Exception {
        Wf9Spool s = spool(new Wf9TestSupport.Props());
        for (String mode : new String[]{"MOCK", "BYPASS", "OFF", null, "deident"}) {
            assertThat(s.offer("E", meet("M-" + mode), masked(), mode, false)).as("mode " + mode).isFalse();
        }
        assertThat(s.list()).isEmpty();
        assertThat(s.offer("E", meet("M-OK"), masked(), "DEIDENT", false)).isTrue();
    }

    @Test
    @DisplayName("넣지 않는 경우 — 꺼짐 · 시험 실행 · 막힘 · 빈 값 · 빈 전사 · 위치를 못 정함(예외 없이 false)")
    void offerSkips() throws Exception {
        Wf9TestSupport.Props off = new Wf9TestSupport.Props();
        off.enabled = false;
        assertThat(offer(spool(off), "E", meet("A1"))).isFalse();
        assertThat(Files.exists(root)).isFalse();

        Wf9Spool s = spool(new Wf9TestSupport.Props());
        assertThat(s.offer("E", meet("A2"), masked(), Wf9Spool.DEIDENT_MODE, true)).as("시험 실행").isFalse();
        blocked.set("로그 컬렉터 미연동");
        assertThat(offer(s, "E", meet("A3"))).as("막힘").isFalse();
        blocked.set(null);
        VoiceTarget noCorr = new VoiceTarget(meet("A4").kind(), " ", "A4", null, null, false, null, null, null, null,
                meet("A4").occurredAt());
        assertThat(offer(s, "E", noCorr)).isFalse();
        VoiceTarget noTime = new VoiceTarget(meet("A5").kind(), "2026000123", "A5", null, null, false, null, null, null, null, null);
        assertThat(offer(s, "E", noTime)).isFalse();
        assertThat(s.offer("E", meet("A6"), new SttResult(" ", "MOCK", 3, false), Wf9Spool.DEIDENT_MODE, false)).isFalse();
        assertThat(s.offer("E", meet("A6"), null, Wf9Spool.DEIDENT_MODE, false)).isFalse();
        assertThat(s.list()).isEmpty();

        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.skipTestRuns = false;
        assertThat(spool(p).offer("TST", meet("A7"), masked(), Wf9Spool.DEIDENT_MODE, true)).as("시험 실행도 넣게 설정").isTrue();

        Wf9Spool broken = new Wf9Spool(new Wf9TestSupport.Props().build(), () -> {
            throw new IllegalStateException("작업 폴더 없음");
        }, () -> null, clock);
        assertThat(offer(broken, "E", meet("A8"))).as("예외를 던지지 않는다").isFalse();
    }

    @Test
    @DisplayName("상한 — 작업 수 · 디스크(MB)를 넘으면 넣지 않는다")
    void capacity() throws Exception {
        Wf9TestSupport.Props one = new Wf9TestSupport.Props();
        one.maxPending = 1;
        Wf9Spool s = spool(one);
        assertThat(offer(s, "E", meet("B1"))).isTrue();
        assertThat(offer(s, "E", meet("B2"))).isFalse();

        root = tmp.resolve("other");
        Wf9TestSupport.Props zero = new Wf9TestSupport.Props();
        zero.maxSpoolMb = 0;
        assertThat(offer(spool(zero), "E", meet("B3"))).isFalse();
    }

    @Test
    @DisplayName("D9 — analyze-max-sec 뒤에 시작하는 구간은 싣지 않고 cutSec 를 적는다 · 실을 구간이 없으면 넣지 않는다")
    void analyzeMaxSecCutsSegments() throws Exception {
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.analyzeMaxSec = 2;
        Wf9Spool s = spool(p);

        assertThat(offer(s, "E", meet("C1"))).isTrue();
        Wf9Spool.Entry e = s.list().get(0);
        assertThat(e.job().cutSec()).isEqualTo(2.0);
        JsonNode segs = OM.readTree(e.dir().resolve(Wf9Spool.DOC_FILE).toFile())
                .path("input").path("speech_results").path(0).path("segments");
        assertThat(segs).hasSize(1);
        assertThat(segs.path(0).path("text").asText()).isEqualTo(Wf9TestSupport.MASKED_1);

        SttResult late = new SttResult("늦은 구간", "MOCK", 10, false, "ko",
                List.of(new SttResult.Segment(0, 5.0, 6.0, "늦은 구간")));
        assertThat(s.offer("E", meet("C2"), late, Wf9Spool.DEIDENT_MODE, false)).as("2초 안에 시작하는 구간 없음").isFalse();
        assertThat(s.list()).hasSize(1);
    }

    @Test
    @DisplayName("꺼내기 — 넣은 순서 · 못 읽는 작업(깨진 job · 예전 음성 사본 작업)은 job=null · 고쳐 쓰기 · 지우기 · 넣다 만 폴더 정리")
    void listSaveDeleteSweep() throws Exception {
        Wf9Spool s = spool(new Wf9TestSupport.Props());
        offer(s, "E", meet("F2"));
        clock.advanceSec(-60);
        offer(s, "E", meet("F1"));
        clock.advanceSec(60);
        Path junk = Files.createDirectories(root.resolve("meet-junk"));
        Files.writeString(junk.resolve(Wf9Spool.JOB_FILE), "{깨짐");
        // 예전(11-26 까지) 음성 사본 작업 — audioFile 만 있고 docFile 이 없다
        Path legacy = Files.createDirectories(root.resolve("meet-legacy"));
        Files.write(legacy.resolve("audio.dat"), new byte[]{1, 2, 3});
        Files.writeString(legacy.resolve(Wf9Spool.JOB_FILE), "{\"jobId\":\"meet-legacy\",\"recFileId\":\"L1\",\"corrNo\":\"2026000123\","
                + "\"kind\":\"MEET\",\"occurredAt\":\"2026-11-16T10:30\",\"execId\":\"E\",\"contactId\":\"C-1\",\"audioFile\":\"audio.dat\","
                + "\"createdAtMs\":1}");
        Path oldTmp = Files.createDirectories(root.resolve(Wf9Spool.TMP_PREFIX + "old"));
        Files.setLastModifiedTime(oldTmp, FileTime.fromMillis(clock.millis() - Wf9Spool.TMP_STALE_MS - 1000));
        Path newTmp = Files.createDirectories(root.resolve(Wf9Spool.TMP_PREFIX + "new"));
        Files.setLastModifiedTime(newTmp, FileTime.fromMillis(clock.millis()));

        List<Wf9Spool.Entry> list = s.list();
        assertThat(list).hasSize(4);
        assertThat(list.get(0).job()).as("못 읽는 것이 맨 앞").isNull();
        assertThat(list.get(1).job()).as("예전 음성 사본 작업도 못 읽는 것").isNull();
        assertThat(list.get(2).job().recFileId()).isEqualTo("F1");
        assertThat(list.get(3).job().recFileId()).isEqualTo("F2");

        Wf9Spool.Entry f1 = list.get(2);
        s.save(f1, f1.job().failed(clock.millis() + 1000, "[PORTAL] 제논 WF9 HTTP 503"));
        Wf9Job again = s.list().get(2).job();
        assertThat(again.attempts()).isEqualTo(1);
        assertThat(again.nextAttemptAtMs()).isEqualTo(clock.millis() + 1000);
        assertThat(again.lastError()).isEqualTo("[PORTAL] 제논 WF9 HTTP 503");
        assertThat(again.contactId()).isEqualTo(f1.job().contactId());

        assertThat(s.delete(list.get(0))).isTrue();
        assertThat(s.delete(list.get(1))).isTrue();
        assertThat(Files.exists(legacy)).as("예전 음성 사본도 지워진다").isFalse();
        assertThat(s.sweepTemp()).isEqualTo(1);
        assertThat(Files.exists(oldTmp)).isFalse();
        assertThat(Files.exists(newTmp)).isTrue();
        assertThat(s.list()).hasSize(2);
    }

    @Test
    @DisplayName("폴더 이름 — 같은 녹음은 같은 이름 · 위험한 글자는 다듬고 해시로 겹치지 않게 · 접견/전화는 다른 이름")
    void jobIds() {
        assertThat(Wf9Spool.jobIdOf(meet("TARE01"))).isEqualTo(Wf9Spool.jobIdOf(meet("TARE01")));
        assertThat(Wf9Spool.jobIdOf(meet("TARE01"))).isNotEqualTo(Wf9Spool.jobIdOf(phone("TARE01")));
        String odd = Wf9Spool.jobIdOf(meet("../a b/c"));
        assertThat(odd).matches("meet-[A-Za-z0-9_.-]+-[0-9a-f]{10}").doesNotContain("/");
        assertThat(Wf9Spool.jobIdOf(meet("a/b"))).isNotEqualTo(Wf9Spool.jobIdOf(meet("a_b")));
    }
}
