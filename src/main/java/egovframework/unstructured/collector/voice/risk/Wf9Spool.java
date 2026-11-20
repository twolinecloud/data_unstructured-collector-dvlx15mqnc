package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.voice.stt.genon.AudioInspector;
import egovframework.unstructured.collector.voice.stt.genon.GenonSttProperties;
import egovframework.unstructured.collector.voice.stt.genon.PcmWav;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * WF9 스풀 — 배치가 복호화 직후 음성 사본과 작업 정보를 넣고, 작업자가 꺼내 처리한 뒤 지운다.
 *
 * <pre>
 * {spool}/{jobId}/job.json     작업 정보({@link Wf9Job})
 * {spool}/{jobId}/audio.dat    음성 사본(analyze-max-sec · 기본 1800초를 넘는 PCM WAV 는 앞부분만 — D9)
 * {spool}/.tmp-…               넣는 중(다 쓰고 나서 이름을 바꿔 한 번에 보이게 한다)
 * </pre>
 *
 * <p><b>배치를 멈추지 않는다</b> — {@link #offer} 는 예외를 던지지 않고, 넣지 못하면 경고만 남긴다.
 * 사본은 복호화 산출물과 같은 PVC · 권한에 두고, 성공 · 포기 즉시 지운다.</p>
 */
@Log4j2
@Component
public class Wf9Spool {

    static final String JOB_FILE = "job.json";
    static final String AUDIO_FILE = "audio.dat";
    static final String TMP_PREFIX = ".tmp-";
    /** 넣다 만 폴더(재기동 등)를 지우는 나이. */
    static final long TMP_STALE_MS = 60L * 60 * 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 스풀의 작업 하나 — job.json 을 읽지 못했으면 {@code job} 이 null. */
    public record Entry(Path dir, Wf9Job job) {}

    private final RiskWf9Properties props;
    private final Supplier<Path> root;
    private final GenonSttProperties genon;
    private final Supplier<String> blocker;
    private final Clock clock;

    @Autowired
    public Wf9Spool(RiskWf9Properties props, VoiceDirState dirs, GenonSttProperties genon, RiskWf9Pipeline pipeline) {
        this(props, () -> defaultRoot(props, dirs), genon, pipeline::blocker, Clock.systemUTC());
    }

    Wf9Spool(RiskWf9Properties props, Supplier<Path> root, GenonSttProperties genon, Supplier<String> blocker, Clock clock) {
        this.props = props;
        this.root = root;
        this.genon = genon;
        this.blocker = blocker;
        this.clock = clock;
    }

    private static Path defaultRoot(RiskWf9Properties props, VoiceDirState dirs) {
        if (!props.spoolDir().isEmpty()) {
            return Path.of(props.spoolDir());
        }
        String work = dirs.work();
        if (work == null || work.isBlank()) {
            throw new IllegalStateException("복호화 작업 폴더(voice dirs.work)가 비어 스풀 위치를 정할 수 없다");
        }
        return Path.of(work).resolveSibling("wf9-spool");
    }

    public Path root() {
        return root.get();
    }

    // ------------------------------------------------------------------ 넣기(배치 스레드)

    /**
     * 음성 없이 끝나는 건(보라미 STT 재사용 — 복호화 · STT 생략) — WF9 는 음성에서 시작하므로 넣지 못한다.
     * 켜져 있을 때만 한 줄 남긴다(분석에서 빠진 건수를 셀 수 있게). 예외를 던지지 않는다.
     */
    public void noteNoAudio(VoiceTarget target, boolean testRun) {
        if (props.enabled() && target != null && !(testRun && props.skipTestRuns())) {
            log.info("[WF9] 대상 아님 — {} · 음성 없음(보라미 STT 재사용 · 복호화 생략)", target.shortId());
        }
    }

    /**
     * 복호화한 음성의 사본을 넣는다 — <b>예외를 던지지 않는다</b>(배치 결과에 영향 없음).
     *
     * @param audio 복호화한 음성(배치가 곧 지운다 — 그래서 사본을 만든다)
     * @return 넣었으면 true
     */
    public boolean offer(String execId, VoiceTarget target, Path audio, boolean testRun) {
        if (!props.enabled() || target == null) {
            return false;
        }
        Path tmp = null;
        try {
            if (testRun && props.skipTestRuns()) {
                return false;
            }
            String blocked = blocker.get();
            if (blocked != null) {
                log.debug("[WF9] 스풀 건너뜀 — {} · {}", target.shortId(), blocked);
                return false;
            }
            if (isBlank(target.idempotencyKey()) || isBlank(target.corrNo()) || target.occurredAt() == null
                    || audio == null || !Files.isRegularFile(audio)) {
                log.warn("[WF9] 스풀 건너뜀 — {} · 녹음 ID · 교정번호 · 일시 · 음성 중 빈 것이 있다", target.shortId());
                return false;
            }
            Path base = root.get();
            Files.createDirectories(base);
            String jobId = jobIdOf(target);
            Path dir = base.resolve(jobId);
            if (Files.exists(dir)) {
                log.debug("[WF9] 이미 대기 중 — {}", target.shortId());
                return false;
            }
            long[] usage = usage(base);
            long size = Files.size(audio);
            if (usage[0] >= props.maxPending() || usage[1] + Math.min(size, genon.maxBytes()) > props.maxSpoolMb() * 1024 * 1024) {
                log.warn("[WF9] 스풀 가득 참(작업 {}개 · {}MB · 상한 {}개 · {}MB) — {} 넣지 않음", usage[0],
                        usage[1] / (1024 * 1024), props.maxPending(), props.maxSpoolMb(), target.shortId());
                return false;
            }
            tmp = base.resolve(TMP_PREFIX + jobId + "-" + UUID.randomUUID().toString().substring(0, 8));
            Files.createDirectories(tmp);
            Double cut = copyHead(audio, tmp.resolve(AUDIO_FILE), target);
            if (cut != null && cut < 0) {
                return false;   // 제논 한도 밖이고 자를 수 없는 형식 — copyHead 가 경고를 남겼다
            }
            Wf9Job job = new Wf9Job(jobId, target.idempotencyKey().strip(), target.corrNo().strip(), target.kind().name(),
                    target.occurredAt().withNano(0).toString(), execId, "C-" + UUID.randomUUID().toString().replace("-", ""),
                    AUDIO_FILE, cut, clock.millis(), 0, 0L, null);
            Files.writeString(tmp.resolve(JOB_FILE), JSON.writeValueAsString(job), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException e) {
                log.debug("[WF9] 이미 대기 중(동시에 넣음) — {}", target.shortId());
                return false;
            }
            tmp = null;
            log.info("[WF9] 스풀 적재 — {}{}", target.shortId(), cut == null ? "" : " · 앞 " + Math.round(cut) + "초만(D9)");
            return true;
        } catch (Exception e) {
            log.warn("[WF9] 스풀 넣기 실패(배치는 계속) — {} · {}", target.shortId(), e.getClass().getSimpleName());
            return false;
        } finally {
            if (tmp != null) {
                deleteQuietly(tmp);
            }
        }
    }

    /**
     * 사본을 만든다. 제논 한도(길이 · 크기)를 넘는 PCM 16bit WAV 는 앞부분만 담는다(D9).
     *
     * @return 잘랐으면 담은 길이(초), 통째면 null, 한도 밖인데 자를 수 없는 형식이면 -1(사본 없음)
     */
    Double copyHead(Path src, Path dst, VoiceTarget target) throws IOException {
        int limitSec = Math.max(1, Math.min(props.analyzeMaxSec(), genon.maxDurationSec()));
        PcmWav.Info info;
        try {
            info = PcmWav.read(src);
        } catch (IllegalArgumentException notPcm) {
            AudioInspector.Check c = AudioInspector.check(src, null, null, genon.maxBytes(), genon.maxDurationSec());
            if (!c.ok()) {
                log.warn("[WF9] 스풀 건너뜀 — {} · 제논 한도 밖이고 자를 수 없는 형식({} · {})", target.shortId(),
                        c.violationText(), c.summary());
                return -1d;
            }
            Files.copy(src, dst);
            return null;
        }
        long align = info.blockAlign();
        long bySize = Math.max(0, genon.maxBytes() - 44) / align * align;
        long byTime = (long) limitSec * info.bytesPerSec();
        long keep = Math.min(info.dataLen(), Math.min(bySize, byTime));
        keep -= keep % align;
        if (keep >= info.dataLen() && Files.size(src) <= genon.maxBytes()) {
            Files.copy(src, dst);
            return null;
        }
        double sec = (double) keep / info.bytesPerSec();
        PcmWav.write(src, info, new PcmWav.Piece(0, 0, keep, 0, sec), dst);
        return sec;
    }

    // ------------------------------------------------------------------ 꺼내기(작업자)

    /** 대기 중인 작업 — 넣은 순서(오래된 것부터). 넣는 중인 폴더는 빼고, job.json 을 못 읽은 폴더는 job=null 로. */
    public List<Entry> list() throws IOException {
        Path base = root.get();
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(base)) {
            for (Path d : s.filter(Files::isDirectory).toList()) {
                if (d.getFileName().toString().startsWith(".")) {
                    continue;
                }
                out.add(new Entry(d, read(d)));
            }
        }
        out.sort(Comparator.comparingLong((Entry e) -> e.job() == null ? Long.MIN_VALUE : e.job().createdAtMs())
                .thenComparing(e -> e.dir().getFileName().toString()));
        return out;
    }

    private static Wf9Job read(Path dir) {
        try {
            Wf9Job j = JSON.readValue(Files.readString(dir.resolve(JOB_FILE), StandardCharsets.UTF_8), Wf9Job.class);
            boolean ok = j != null && !isBlank(j.recFileId()) && !isBlank(j.corrNo()) && !isBlank(j.occurredAt())
                    && !isBlank(j.contactId()) && !isBlank(j.audioFile()) && !j.audioFile().contains("/")
                    && !j.audioFile().contains("\\") && Files.isRegularFile(dir.resolve(j.audioFile()));
            if (ok) {
                j.occurredLocal();   // 모양 확인
            }
            return ok ? j : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 작업 정보를 바꿔 쓴다(실패 횟수 · 다음 시도) — 임시 파일에 쓰고 이름을 바꾼다. */
    public void save(Entry e, Wf9Job job) throws IOException {
        Path t = e.dir().resolve(JOB_FILE + ".tmp");
        Files.writeString(t, JSON.writeValueAsString(job), StandardCharsets.UTF_8);
        Files.move(t, e.dir().resolve(JOB_FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 작업 폴더를 통째로 지운다(음성 사본 포함). */
    public boolean delete(Entry e) {
        return deleteQuietly(e.dir());
    }

    /** 넣다 만 폴더(재기동 등)를 지운다 — 한 시간 넘은 것만. */
    public int sweepTemp() throws IOException {
        Path base = root.get();
        if (!Files.isDirectory(base)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> s = Files.list(base)) {
            for (Path d : s.filter(p -> p.getFileName().toString().startsWith(TMP_PREFIX)).toList()) {
                if (clock.millis() - Files.getLastModifiedTime(d).toMillis() > TMP_STALE_MS && deleteQuietly(d)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** [작업 수, 바이트] — 넣는 중인 폴더도 센다(디스크는 이미 쓰고 있다). */
    long[] usage(Path base) throws IOException {
        long[] u = {0, 0};
        if (!Files.isDirectory(base)) {
            return u;
        }
        try (Stream<Path> s = Files.list(base)) {
            for (Path d : s.filter(Files::isDirectory).toList()) {
                if (!d.getFileName().toString().startsWith(".")) {
                    u[0]++;
                }
                try (Stream<Path> f = Files.list(d)) {
                    for (Path p : f.filter(Files::isRegularFile).toList()) {
                        u[1] += Files.size(p);
                    }
                }
            }
        }
        return u;
    }

    /** 같은 녹음 = 같은 폴더 이름. 글자는 안전한 것만 남기고, 다듬다 겹치지 않게 원래 값의 해시를 붙인다. */
    static String jobIdOf(VoiceTarget t) {
        String raw = t.kind().name() + ":" + t.idempotencyKey().strip();
        String safe = t.idempotencyKey().strip().replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        return t.kind().name().toLowerCase() + "-" + safe + "-" + sha256(raw).substring(0, 10);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean deleteQuietly(Path p) {
        try {
            return FileSystemUtils.deleteRecursively(p);
        } catch (IOException e) {
            log.warn("[WF9] 스풀 폴더를 지우지 못함 — {} · {}", p.getFileName(), e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
