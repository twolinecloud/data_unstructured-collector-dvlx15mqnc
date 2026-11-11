package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.voice.stt.genon.AudioInspector;
import egovframework.unstructured.collector.voice.stt.genon.AudioSampleGenerator;
import egovframework.unstructured.collector.voice.stt.genon.GenonCall;
import egovframework.unstructured.collector.voice.stt.genon.GenonSttClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 음성 STT 시뮬레이션(11번 탭) — 제논 호출 · 사전 검증 · 경계 샘플. dev · local 전용.
 *
 * <p><b>샘플은 PV 에 만든다</b>({@code {ROOT}/mock/stt_samples}) — 20MB 짜리를 레포에 담지 않는다. 합성 낭독 22초만
 * 레포 자산을 복사해 온다. 업로드 파일은 {@code {ROOT}/mock/stt_upload} 에 잠깐 두고 호출이 끝나면 지운다.</p>
 *
 * <p><b>원래 파일 이름은 화면에만 돌려준다</b> — 로그에도, 제논에도 남기지 않는다(수용자 이름이 들어 있을 수 있다).</p>
 */
@Log4j2
@Service
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class SttSimService {

    public static final String SAMPLE_DIR = "mock/stt_samples";
    public static final String UPLOAD_DIR = "mock/stt_upload";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final GenonSttClient client;
    private final VoiceDirState dirs;
    private final VoiceModeState modes;

    // ---------------------------------------------------------------- 상태 · 모드

    /** 제논 상태 + 배치 STT 모드. {@code probe} 면 {@code GET /health} 로 연결 · 토큰을 실제로 확인한다. */
    public Map<String, Object> status(boolean probe) {
        Map<String, Object> m = new LinkedHashMap<>(client.status());
        m.put("batchSttMode", modes.stt().name());
        m.put("sampleDir", sampleDir().toString());
        if (probe) {
            m.put("probe", client.probe());
        }
        return m;
    }

    public Map<String, Object> setMode(String value) {
        String before = client.setCallMode(value);
        Map<String, Object> m = status(false);
        m.put("before", before);
        return m;
    }

    // ---------------------------------------------------------------- 샘플

    /**
     * 샘플을 만든다.
     *
     * @param kind 비우거나 ALL 이면 전부 · UNSUPPORTED 면 OGG 와 텍스트 둘 · 그 밖은 종류 이름(WAV_3S · OVER_SIZE …)
     */
    public Map<String, Object> generate(String kind) throws IOException {
        List<AudioSampleGenerator.Kind> kinds = kindsOf(kind);
        long t0 = System.currentTimeMillis();
        List<String> made = new ArrayList<>();
        for (AudioSampleGenerator.Kind k : kinds) {
            AudioSampleGenerator.write(k, sampleDir());
            made.add(k.fileName());
        }
        log.info("[STT시뮬] 샘플 {}개 생성 — {} · {}ms", made.size(), made, System.currentTimeMillis() - t0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generated", made);
        m.put("elapsedMs", System.currentTimeMillis() - t0);
        m.put("samples", samples());
        return m;
    }

    private static List<AudioSampleGenerator.Kind> kindsOf(String kind) {
        String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (k.isEmpty() || "ALL".equals(k)) {
            return List.of(AudioSampleGenerator.Kind.values());
        }
        if ("UNSUPPORTED".equals(k)) {
            return List.of(AudioSampleGenerator.Kind.UNSUPPORTED_OGG, AudioSampleGenerator.Kind.UNSUPPORTED_TXT);
        }
        try {
            return List.of(AudioSampleGenerator.Kind.valueOf(k));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("모르는 샘플 종류: " + kind
                    + " (ALL · UNSUPPORTED · " + String.join(" · ", names()) + ")");
        }
    }

    private static List<String> names() {
        List<String> n = new ArrayList<>();
        for (AudioSampleGenerator.Kind k : AudioSampleGenerator.Kind.values()) {
            n.add(k.name());
        }
        return n;
    }

    /** 샘플 목록 — 만들어진 것은 사전 검증 결과를 붙인다(헤더만 읽으므로 20MB 짜리도 금방이다). */
    public List<Map<String, Object>> samples() {
        Path dir = sampleDir();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AudioSampleGenerator.Kind k : AudioSampleGenerator.Kind.values()) {
            Path p = dir.resolve(k.fileName());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", k.fileName());
            m.put("kind", k.name());
            m.put("label", k.label());
            m.put("expectPass", k.expectPass());
            boolean exists = Files.isRegularFile(p);
            m.put("exists", exists);
            if (exists) {
                m.put("audio", view(client.inspect(p, k.fileName(), mimeOf(k.fileName()))));
            }
            out.add(m);
        }
        return out;
    }

    /** 샘플 파일 — 생성기가 만드는 이름만 받는다(경로 조작으로 다른 파일을 꺼내 가지 않게). */
    public Path samplePath(String name) {
        AudioSampleGenerator.Kind k = name == null ? null : AudioSampleGenerator.Kind.byFileName(name.trim());
        if (k == null) {
            throw new IllegalArgumentException("샘플 이름이 아니다: " + name);
        }
        Path p = sampleDir().resolve(k.fileName());
        if (!Files.isRegularFile(p)) {
            throw new NoSuchElementException("샘플이 아직 없다 — 먼저 생성: " + k.fileName());
        }
        return p;
    }

    public int deleteSamples() throws IOException {
        int n = 0;
        for (AudioSampleGenerator.Kind k : AudioSampleGenerator.Kind.values()) {
            n += Files.deleteIfExists(sampleDir().resolve(k.fileName())) ? 1 : 0;
            Files.deleteIfExists(sampleDir().resolve(k.fileName() + ".tmp"));
        }
        log.info("[STT시뮬] 샘플 {}개 삭제", n);
        return n;
    }

    // ---------------------------------------------------------------- 호출

    /**
     * 제논을 부른다 — 업로드 파일 또는 생성 샘플 하나.
     *
     * @param api            transcribe · process
     * @param occurredAt     process 의 발생 시각. {@code 2026-11-06T10:00}(KST 로 봄) · 오프셋 붙은 ISO 둘 다 받는다. 비면 지금
     * @param skipValidation 사전 검증을 건너뛰고 보낸다 — 제논이 직접 거절하는지 볼 때만
     * @throws egovframework.unstructured.collector.voice.stt.genon.AudioValidationException 사전 검증 위반(보내지 않음)
     * @throws egovframework.unstructured.collector.voice.stt.genon.GenonSttException         제논 호출 실패
     */
    public Map<String, Object> call(MultipartFile file, String sample, String api, String contactId,
                                    String contactType, String occurredAt, boolean skipValidation) throws IOException {
        boolean process = isProcess(api);
        boolean hasSample = sample != null && !sample.isBlank();
        if ((file != null) == hasSample) {
            throw new IllegalArgumentException("file(업로드) 과 sample(생성 샘플) 중 하나만 보낸다");
        }
        Instant at = process ? occurredAt(occurredAt) : null;

        Path path;
        String name;
        String mime;
        boolean temp = file != null;
        if (temp) {
            name = baseName(file.getOriginalFilename());
            mime = file.getContentType();
            String ext = extensionOf(name);
            Files.createDirectories(uploadDir());
            path = uploadDir().resolve(UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext));
            file.transferTo(path);
        } else {
            path = samplePath(sample);
            name = path.getFileName().toString();
            mime = mimeOf(name);
        }
        try {
            GenonCall call;
            if (skipValidation) {
                call = client.sendUnchecked(process, path.toFile(), name, mime, contactId, contactType, at);
            } else if (process) {
                call = client.processContactAudio(path.toFile(), name, mime, contactId, contactType, at);
            } else {
                call = client.transcribe(path.toFile(), name, mime);
            }
            return view(call, process, name, skipValidation);
        } finally {
            if (temp) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static boolean isProcess(String api) {
        String a = api == null ? "" : api.trim().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "", "transcribe" -> false;
            case "process" -> true;
            default -> throw new IllegalArgumentException("api 는 transcribe · process 중 하나: " + api);
        };
    }

    static Instant occurredAt(String s) {
        if (s == null || s.isBlank()) {
            return Instant.now();
        }
        String v = s.trim();
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignore) {
            // 오프셋 없는 값(화면 datetime-local)은 KST 로 본다
        }
        try {
            return LocalDateTime.parse(v).atZone(KST).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("발생 시각 형식 — 2026-11-06T10:00 또는 2026-11-06T10:00:00+09:00: " + s);
        }
    }

    // ---------------------------------------------------------------- 화면용 모양

    private static Map<String, Object> view(GenonCall call, boolean process, String name, boolean skipped) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("stage", "GENON");
        m.put("mode", call.mode());
        m.put("api", process ? "process" : "transcribe");
        m.put("path", call.path());
        m.put("httpStatus", call.status());
        m.put("elapsedMs", call.elapsedMs());
        m.put("queuedMs", call.queuedMs());
        m.put("attempts", call.attempts());
        m.put("validationSkipped", skipped);
        m.put("file", name);
        m.put("audio", view(call.audio()));
        SttResult r = GenonSttClient.toSttResult(call);
        JsonNode segs = call.body().path("segments");
        if (!segs.isArray()) {
            segs = call.body().path("input").path("speech_results").path(0).path("segments");
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("text", r.text());
        t.put("charCount", r.charCount());
        t.put("segments", segs.isArray() ? segs : List.of());
        m.put("transcript", t);
        m.put("response", call.body());
        return m;
    }

    /** 사전 검증 결과를 화면 모양으로. */
    public static Map<String, Object> view(AudioInspector.Check c) {
        if (c == null) {
            return null;
        }
        AudioInspector.Info i = c.info();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", c.ok());
        m.put("format", i.format());
        m.put("extension", i.extension());
        m.put("mime", i.mime());
        m.put("sizeBytes", i.sizeBytes());
        m.put("size", AudioInspector.human(i.sizeBytes()));
        m.put("durationSec", i.durationSec());
        m.put("durationFrom", i.durationFrom());
        m.put("sampleRate", i.sampleRate());
        m.put("channels", i.channels());
        m.put("codec", i.codec());
        m.put("uploadName", c.uploadName());
        m.put("violations", c.violations());
        m.put("warnings", c.warnings());
        m.put("maxBytes", c.maxBytes());
        m.put("maxDurationSec", c.maxDurationSec());
        return m;
    }

    // ---------------------------------------------------------------- 공통

    private Path sampleDir() {
        return Path.of(dirs.baseDir(), SAMPLE_DIR);
    }

    private Path uploadDir() {
        return Path.of(dirs.baseDir(), UPLOAD_DIR);
    }

    /** 브라우저가 붙이는 MIME 흉내 — 샘플은 업로드가 아니라서 확장자로 정한다. */
    static String mimeOf(String name) {
        return switch (extensionOf(name)) {
            case "wav" -> "audio/wav";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/x-m4a";
            case "ogg" -> "audio/ogg";
            case "txt" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    /** 임시 파일에 붙일 확장자 — 영숫자 5자까지만(경로 조작 · 이상한 이름 방지). */
    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,5}") ? ext : "";
    }

    private static String baseName(String original) {
        if (original == null || original.isBlank()) {
            return "upload";
        }
        String n = original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        return n.isEmpty() ? "upload" : (n.length() > 120 ? n.substring(n.length() - 120) : n);
    }
}
