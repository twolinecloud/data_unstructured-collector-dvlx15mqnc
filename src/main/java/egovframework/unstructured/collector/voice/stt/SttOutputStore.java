package egovframework.unstructured.collector.voice.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * STT 결과를 <b>배치 단위 폴더</b>에 남긴다 — {@code {output}/{execId}/{건ID}.txt} + {@code .json}.
 *
 * <p><b>왜 파일인가</b>: 이 서비스는 STT 텍스트를 외부로 보내지 않는다(커넥터 연동 제외). 대신 하류가
 * 읽어 갈 수 있게 PV 의 정해진 자리({@code {base-dir}/xenon/voice|phone/{execId}/})에 놓는다.
 * 텍스트({@code .txt})와 처리 메타({@code .json} — 건 키·엔진·글자 수·처리 시각)를 나란히 두어
 * 폴더만 봐도 어느 배치가 무엇을 만들었는지 알 수 있게 한다.</p>
 *
 * <p><b>배치 격리</b>: 재처리하면 새 EXEC_ID 폴더가 생긴다. 시험 배치(TST)는
 * {@link #deleteTestOutputs()} 가 폴더째 지운다 — 운영 배치(VOC) 폴더는 건드리지 않는다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class SttOutputStore {

    private final VoiceDirState dirs;
    private final ObjectMapper objectMapper;

    /** 저장 결과. {@code textFile} 이 T4·배치 결과의 {@code sttPath} 로 나간다. */
    public record Saved(Path textFile, Path metaFile, long textBytes) {}

    /**
     * 한 건의 STT 텍스트와 메타를 쓴다. 같은 건을 같은 배치에서 다시 쓰면 덮어쓴다.
     *
     * @throws IOException 디렉터리 생성·쓰기 실패 — 호출자가 그 건을 실패로 기록한다
     */
    public Saved save(String execId, VoiceTarget target, SttResult stt, long audioBytes) throws IOException {
        Path dir = dirs.outputDir(target.kind(), execId);
        Files.createDirectories(dir);
        String base = safeName(target.shortId());
        Path text = dir.resolve(base + ".txt");
        Path meta = dir.resolve(base + ".json");

        byte[] body = (stt.text() == null ? "" : stt.text()).getBytes(StandardCharsets.UTF_8);
        Files.write(text, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("execId", execId);
        m.put("kind", target.kind().name());
        m.put("key", target.idempotencyKey());
        m.put("srcFileName", target.srcFileName());
        m.put("srcFilePath", target.srcFilePath());
        m.put("engine", stt.engine());
        m.put("fromSource", stt.fromSource());
        m.put("durationSec", stt.durationSec());
        m.put("charCount", stt.charCount());
        m.put("audioBytes", audioBytes);
        m.put("textFile", text.getFileName().toString());
        m.put("processedAt", LocalDateTime.now().withNano(0).toString());

        // ── transcript — 전사 구조(Whisper 표준 모양) ───────────────────────────
        //   위의 처리 메타(execId·key·engine·durationSec…)는 <b>그대로 둔다</b>. 하류가 이미 그 이름으로
        //   읽고 있어, 이름을 바꾸거나 자리를 옮기면 읽는 쪽이 조용히 깨진다. 전사 구조는 새 키 아래에만
        //   담아 <b>더하기만</b> 한다 — 예전 산출물을 읽던 코드는 이 키를 모른 채 그대로 동작한다.
        //   구간을 주지 않는 엔진·보라미 기존 STT 재사용(Q1)이면 segments 가 빈 목록이다.
        Map<String, Object> transcript = new LinkedHashMap<>();
        transcript.put("text", stt.text() == null ? "" : stt.text());
        transcript.put("language", stt.language());
        transcript.put("duration", stt.duration());
        List<Map<String, Object>> segs = new ArrayList<>();
        for (SttResult.Segment seg : stt.segments()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", seg.id());
            one.put("start", seg.start());
            one.put("end", seg.end());
            one.put("text", seg.text());
            segs.add(one);
        }
        transcript.put("segments", segs);
        m.put("transcript", transcript);
        Files.write(meta, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(m),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        log.info("[Output] STT 저장 — {} ({}자) → {}", target.shortId(), stt.charCount(), text);
        return new Saved(text, meta, body.length);
    }

    /**
     * 한 배치가 만든 출력 목록(메타 + 텍스트). 시뮬레이터 표시용 — 텍스트는 그대로 싣는다.
     * {@code /api/v1/mock/**} 뒤에서만 부른다(운영은 차단).
     */
    public List<Map<String, Object>> list(VoiceKind kind, String execId, int maxTextChars) {
        Path dir = dirs.outputDir(kind, execId);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return rows;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(meta -> {
                Map<String, Object> row = new LinkedHashMap<>();
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = objectMapper.readValue(meta.toFile(), Map.class);
                    row.putAll(m);
                    Path text = dir.resolve(String.valueOf(m.get("textFile")));
                    row.put("textPath", text.toString().replace('\\', '/'));
                    if (Files.isRegularFile(text)) {
                        String t = Files.readString(text, StandardCharsets.UTF_8);
                        row.put("text", t.length() > maxTextChars ? t.substring(0, maxTextChars) + "…" : t);
                        row.put("textBytes", Files.size(text));
                    }
                } catch (IOException e) {
                    row.put("error", e.getMessage());
                }
                rows.add(row);
            });
        } catch (IOException e) {
            log.warn("[Output] 출력 목록 조회 실패 — {} ({})", dir, e.getMessage());
        }
        return rows;
    }

    /**
     * 시험 배치(EXEC_ID 에 {@code TST})의 출력 폴더를 통째로 지운다. 운영 폴더는 이름에 TST 가 없어 걸리지 않는다.
     *
     * @return 지운 배치 폴더 수
     */
    public int deleteTestOutputs() {
        int removed = 0;
        for (VoiceKind kind : VoiceKind.values()) {
            Path root = dirs.outputRoot(kind);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> s = Files.list(root)) {
                for (Path d : s.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().toUpperCase().contains("TST")).toList()) {
                    deleteTree(d);
                    removed++;
                }
            } catch (IOException e) {
                log.warn("[Output] 시험 출력 삭제 실패 — {} ({})", root, e.getMessage());
            }
        }
        if (removed > 0) {
            log.info("[Output] 시험 배치 출력 폴더 {}개 삭제", removed);
        }
        return removed;
    }

    /** 배치 폴더 하나만 지운다(깊이 1 — 우리가 만든 .txt/.json 뿐이다). */
    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.toList()) {
                if (Files.isRegularFile(f)) {
                    Files.deleteIfExists(f);
                }
            }
        }
        Files.deleteIfExists(dir);
    }

    private static String safeName(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
