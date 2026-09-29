package egovframework.unstructured.collector.voice.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * STT 중간 산출물 보관소 — {@code {ROOT}/stt_temp/{execId}/}.
 *
 * <p><b>왜 따로 두나</b>: SEND(최종 저장)가 깨졌을 때 STT 를 다시 돌리는 것은 낭비다. NPU 호출이
 * 이 파이프라인에서 가장 비싼 구간인데, 디스크가 찼다는 이유로 1,300건을 다시 인식시킬 이유가 없다.
 * ANALYZE 를 통과한 결과를 여기 적어 두면 SEND 만 이어서 재시도할 수 있다.</p>
 *
 * <p><b>성공하면 지운다.</b> 최종 저장까지 끝난 건의 중간 산출물은 원본이 두 벌 남는 것이고,
 * 그 안에는 성명·주민등록번호가 그대로 들어 있다. 실패했을 때만 남긴다.</p>
 *
 * <p><b>배치 폴더로 가르되 찾을 때는 가로지른다.</b> 재처리는 새 EXEC_ID 로 도는데 보존물은
 * 실패한 배치의 EXEC_ID 아래 있다. 그래서 키로 찾을 때는 {@code stt_temp} 전체를 훑어
 * <b>가장 최근 것</b>을 쓴다 — 어느 배치에서 끊겼는지 사람이 기억해 입력하지 않아도 되게.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class SttTempStore {

    private static final String ROOT = "stt_temp";

    private final VoiceDirState dirs;
    private final ObjectMapper objectMapper;

    /** 이 배치의 보관 폴더. */
    public Path dir(String execId) {
        return Path.of(dirs.baseDir(), ROOT, safe(execId));
    }

    public Path root() {
        return Path.of(dirs.baseDir(), ROOT);
    }

    // ── 쓰기 ──────────────────────────────────────────────────────────────

    /**
     * ANALYZE 통과 직후 — 전사 결과를 남긴다(Whisper 모양 그대로).
     *
     * <p>쓰기에 실패해도 <b>배치를 실패시키지 않는다</b>. 이것은 재시도를 빠르게 하려는 보조 수단이지
     * 산출물이 아니다. 없으면 재처리가 STT 부터 다시 하면 된다.</p>
     */
    public Optional<Path> save(String execId, VoiceTarget target, SttResult stt, long audioBytes) {
        Path file = dir(execId).resolve(safe(target.shortId()) + ".json");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("execId", execId);
        m.put("kind", target.kind().name());
        m.put("key", target.idempotencyKey());
        m.put("srcFileName", target.srcFileName());
        m.put("audioBytes", audioBytes);
        m.put("engine", stt.engine());
        m.put("fromSource", stt.fromSource());
        m.put("savedAt", java.time.LocalDateTime.now().withNano(0).toString());
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("text", stt.text() == null ? "" : stt.text());
        t.put("language", stt.language());
        t.put("duration", stt.duration());
        List<Map<String, Object>> segs = new ArrayList<>();
        for (SttResult.Segment seg : stt.segments()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", seg.id());
            one.put("start", seg.start());
            one.put("end", seg.end());
            one.put("text", seg.text());
            segs.add(one);
        }
        t.put("segments", segs);
        m.put("transcript", t);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(m));
            return Optional.of(file);
        } catch (IOException e) {
            log.warn("[SttTemp] 중간 산출물 저장 실패 — {} ({}) · 재처리는 STT 부터 다시 한다",
                    file, e.getMessage());
            return Optional.empty();
        }
    }

    // ── 읽기 ──────────────────────────────────────────────────────────────

    /**
     * 이 대상의 보존된 전사 결과를 찾는다.
     *
     * @param fromExecId 특정 배치에서만 찾으려면 지정. 비면 {@code stt_temp} 전체에서 가장 최근 것
     */
    public Optional<SttResult> find(VoiceTarget target, String fromExecId) {
        String name = safe(target.shortId()) + ".json";
        Optional<Path> hit = (fromExecId == null || fromExecId.isBlank())
                ? newestAcross(name)
                : exists(dir(fromExecId).resolve(name));
        return hit.flatMap(this::read);
    }

    private Optional<Path> exists(Path p) {
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    /** stt_temp 아래 모든 배치 폴더에서 같은 이름을 찾아 가장 최근 것을 고른다. */
    private Optional<Path> newestAcross(String name) {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .map(d -> d.resolve(name))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<SttResult> read(Path file) {
        try {
            var node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            var t = node.path("transcript");
            List<SttResult.Segment> segs = new ArrayList<>();
            for (var seg : t.path("segments")) {
                segs.add(new SttResult.Segment(seg.path("id").asInt(segs.size()),
                        seg.path("start").asDouble(0d), seg.path("end").asDouble(0d),
                        seg.path("text").asText("")));
            }
            String text = t.path("text").asText("");
            if (text.isBlank()) {
                log.warn("[SttTemp] 보존물에 전문이 없다 — {} · 없는 것으로 본다", file);
                return Optional.empty();
            }
            // engine 에 표식을 남긴다 — 이 건이 재처리에서 STT 를 건너뛴 것임을 이력에서 알 수 있게
            return Optional.of(new SttResult(text, node.path("engine").asText("UNKNOWN") + "(보존물)",
                    t.path("duration").asDouble(0d), node.path("fromSource").asBoolean(false),
                    t.hasNonNull("language") ? t.path("language").asText(null) : null, segs));
        } catch (Exception e) {
            log.warn("[SttTemp] 보존물을 읽지 못했다 — {} ({}) · 없는 것으로 본다", file, e.getMessage());
            return Optional.empty();
        }
    }

    // ── 정리 ──────────────────────────────────────────────────────────────

    /** 이 건이 최종 저장까지 끝났다 — 중간 산출물을 지운다. */
    public void discard(String execId, VoiceTarget target) {
        Path file = dir(execId).resolve(safe(target.shortId()) + ".json");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("[SttTemp] 삭제 실패 — {} ({})", file, e.getMessage());
        }
    }

    /** 재처리로 소비한 보존물을 지운다 — 어느 배치 폴더에 있든. */
    public void discardAnywhere(VoiceTarget target) {
        String name = safe(target.shortId()) + ".json";
        Path root = root();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> s = Files.list(root)) {
            for (Path d : s.filter(Files::isDirectory).toList()) {
                Files.deleteIfExists(d.resolve(name));
            }
        } catch (IOException e) {
            log.debug("[SttTemp] 정리 실패 — {} ({})", root, e.getMessage());
        }
    }

    /** 빈 배치 폴더를 치운다 — 남겨 두면 "여기 뭔가 있나" 를 매번 확인하게 된다. */
    public void pruneEmptyDirs() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> s = Files.list(root)) {
            for (Path d : s.filter(Files::isDirectory).toList()) {
                try (Stream<Path> inner = Files.list(d)) {
                    if (inner.findAny().isEmpty()) {
                        Files.deleteIfExists(d);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("[SttTemp] 빈 폴더 정리 실패 — {} ({})", root, e.getMessage());
        }
    }

    /** 화면·상태용 — 지금 몇 건이 어느 배치에 남아 있는지. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", root().toString().replace('\\', '/'));
        Map<String, Integer> byExec = new LinkedHashMap<>();
        int total = 0;
        Path root = root();
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.list(root)) {
                for (Path d : s.filter(Files::isDirectory).sorted().toList()) {
                    try (Stream<Path> inner = Files.list(d)) {
                        int n = (int) inner.filter(Files::isRegularFile).count();
                        if (n > 0) {
                            byExec.put(d.getFileName().toString(), n);
                            total += n;
                        }
                    }
                }
            } catch (IOException ignored) {
                // 목록을 못 읽어도 상태 조회가 실패를 만들면 안 된다
            }
        }
        out.put("total", total);
        out.put("byExecId", byExec);
        return out;
    }

    /** 전부 지운다 — [시뮬레이션 데이터 초기화] 가 부른다. */
    public int clearAll() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> all = s.sorted(Comparator.reverseOrder()).toList();
            for (Path p : all) {
                if (p.equals(root)) {
                    continue;
                }
                if (Files.isRegularFile(p)) {
                    n++;
                }
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("[SttTemp] 전체 삭제 실패 — {} ({})", root, e.getMessage());
        }
        return n;
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9._가-힣-]", "_");
    }
}
