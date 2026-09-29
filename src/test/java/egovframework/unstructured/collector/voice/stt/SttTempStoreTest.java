package egovframework.unstructured.collector.voice.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * STT 중간 산출물 보관소 — <b>SEND 가 깨져도 STT 를 다시 돌리지 않게 한다.</b>
 *
 * <p>여기서 보는 것은 셋이다 — 남기는가, 재처리가 찾아 쓰는가, 성공하면 지우는가.
 * 특히 "새 EXEC_ID 로 도는 재처리가 옛 배치의 보존물을 찾는가" 가 핵심이다.</p>
 */
class SttTempStoreTest {

    @TempDir
    Path root;

    private SttTempStore store;

    private static VoiceTarget target(String key) {
        return new VoiceTarget(VoiceKind.MEET, "SIM00000000000001", key, "DOC1", "KEY1",
                true, null, "/src", "mock_meet_001.m4a", null, LocalDateTime.now());
    }

    private static SttResult stt(String text) {
        return new SttResult(text, "MOCK", 7.5, false, "ko",
                List.of(new SttResult.Segment(0, 0.0, 3.5, "앞"),
                        new SttResult.Segment(1, 3.5, 7.5, "뒤")));
    }

    @BeforeEach
    void setUp() {
        VoiceDirState dirs = mock(VoiceDirState.class);
        when(dirs.baseDir()).thenReturn(root.toString());
        store = new SttTempStore(dirs, new ObjectMapper());
    }

    @Test
    @DisplayName("남긴다 — {ROOT}/stt_temp/{execId}/ 아래 전사 구조 그대로")
    void savesTranscript() {
        VoiceTarget t = target("SIM-MEET-001");

        Optional<Path> saved = store.save("20260922TST001", t, stt("여보세요"), 1234L);

        assertThat(saved).isPresent();
        assertThat(saved.get()).exists();
        assertThat(saved.get().getParent()).isEqualTo(root.resolve("stt_temp").resolve("20260922TST001"));
    }

    @Test
    @DisplayName("재처리가 찾아 쓴다 — 새 EXEC_ID 로 돌아도 옛 배치의 보존물을 집는다")
    void findsAcrossExecIds() {
        VoiceTarget t = target("SIM-MEET-001");
        store.save("20260922TST001", t, stt("원래 전사"), 1234L);

        // 재처리는 배치 번호를 모른다 — 비워 두고 찾는다
        Optional<SttResult> found = store.find(t, null);

        assertThat(found).isPresent();
        assertThat(found.get().text()).isEqualTo("원래 전사");
        assertThat(found.get().segments()).hasSize(2);
        assertThat(found.get().language()).isEqualTo("ko");
        assertThat(found.get().duration()).isEqualTo(7.5);
        // 이 건이 STT 를 건너뛴 것임이 이력에 남아야 한다
        assertThat(found.get().engine()).contains("보존물");
    }

    @Test
    @DisplayName("배치를 지정하면 그 배치에서만 찾는다")
    void findsWithinGivenExecId() {
        VoiceTarget t = target("SIM-MEET-001");
        store.save("20260922TST001", t, stt("A 배치"), 1L);

        assertThat(store.find(t, "20260922TST001")).isPresent();
        assertThat(store.find(t, "20260922TST999")).as("없는 배치").isEmpty();
    }

    @Test
    @DisplayName("보존물이 없으면 비어 있다 — 재처리는 앞 단계로 내려가면 된다")
    void emptyWhenNothingPreserved() {
        assertThat(store.find(target("SIM-MEET-404"), null)).isEmpty();
    }

    @Test
    @DisplayName("성공하면 지운다 — 어느 배치 폴더에 있든")
    void discardsAnywhere() throws Exception {
        VoiceTarget t = target("SIM-MEET-001");
        store.save("20260922TST001", t, stt("x"), 1L);
        store.save("20260922TST002", t, stt("y"), 1L);

        store.discardAnywhere(t);

        assertThat(store.find(t, null)).isEmpty();
        store.pruneEmptyDirs();
        try (var s = Files.list(root.resolve("stt_temp"))) {
            assertThat(s).as("빈 배치 폴더는 치운다").isEmpty();
        }
    }

    @Test
    @DisplayName("현황 — 어느 배치에 몇 건 남았는지")
    void reportsStatus() {
        VoiceTarget a = target("SIM-MEET-001");
        VoiceTarget b = target("SIM-MEET-002");
        store.save("20260922TST001", a, stt("1"), 1L);
        store.save("20260922TST001", b, stt("2"), 1L);

        var st = store.status();

        assertThat(st).containsEntry("total", 2);
        @SuppressWarnings("unchecked")
        var byExec = (java.util.Map<String, Integer>) st.get("byExecId");
        assertThat(byExec).containsEntry("20260922TST001", 2);
    }

    @Test
    @DisplayName("전체 삭제 — 초기화가 부른다")
    void clearsAll() {
        store.save("20260922TST001", target("SIM-MEET-001"), stt("1"), 1L);
        store.save("20260922TST002", target("SIM-MEET-002"), stt("2"), 1L);

        assertThat(store.clearAll()).isEqualTo(2);
        assertThat(store.status()).containsEntry("total", 0);
    }

    @Test
    @DisplayName("전문이 빈 보존물은 없는 것으로 본다 — 빈 전사로 SEND 를 통과시키면 안 된다")
    void ignoresEmptyTranscript() throws Exception {
        VoiceTarget t = target("SIM-MEET-001");
        Path f = root.resolve("stt_temp").resolve("20260922TST001").resolve("meet-SIM-MEET-001.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"transcript\":{\"text\":\"\",\"segments\":[]}}");

        assertThat(store.find(t, null)).isEmpty();
    }
}
