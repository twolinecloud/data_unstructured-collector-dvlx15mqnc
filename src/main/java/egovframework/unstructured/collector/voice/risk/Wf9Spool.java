package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceTarget;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * WF9 스풀 — 배치가 <b>전사 비식별 직후</b> 가린 WF9 입력 문서와 작업 정보를 넣고, 작업자가 꺼내 처리한 뒤 지운다.
 *
 * <pre>
 * {spool}/{jobId}/job.json     작업 정보({@link Wf9Job})
 * {spool}/{jobId}/doc.json     가린 WF9 입력 문서({@link Wf9TextDocument} — 구간 글 · 시각 · 운율/음향 not_run)
 * {spool}/.tmp-…               넣는 중(다 쓰고 나서 이름을 바꿔 한 번에 보이게 한다)
 * </pre>
 *
 * <p><b>2026-11-27 음성 사본 폐지</b> — 예전에는 복호화 직후 음성 사본을 넣고 작업자가 제논 전처리({@code audio/process})로 보냈다.
 * 그 길은 원본 음성이 외부망으로 나가므로 걷어냈다. 이제 스풀에는 <b>커넥터가 실제로 가린 전사</b>만 들어간다:
 * 비식별 모드가 {@link #DEIDENT_MODE} 가 아니면(MOCK · BYPASS · 단계 꺼짐) 넣지 않는다 — 가리지 않은 글이 WF9 로 갈 길을 막는다.</p>
 *
 * <p><b>2026-11-27 전송 뒤 호출</b> — 넣는 때는 그대로(비식별 직후)지만, 작업자는 그 배치의 클라우드 전송(T2 SEND)이
 * SUCCESS 가 된 뒤에야 꺼내 부른다({@link RiskWf9Properties#afterSend()}). 그동안 가린 문서는 스풀에 남는다.</p>
 *
 * <p><b>배치를 멈추지 않는다</b> — {@link #offer} 는 예외를 던지지 않고, 넣지 못하면 경고만 남긴다.
 * 문서는 배치 산출물과 같은 PVC · 권한에 두고, 성공 · 포기 즉시 지운다.</p>
 */
@Log4j2
@Component
public class Wf9Spool {

    static final String JOB_FILE = "job.json";
    static final String DOC_FILE = "doc.json";
    static final String TMP_PREFIX = ".tmp-";
    /** 이 모드로 가린 전사만 넣는다 — 커넥터 응답 {@code mode}({@code VoiceDeidentClient.Result#mode}). */
    public static final String DEIDENT_MODE = "DEIDENT";
    /** 넣다 만 폴더(재기동 등)를 지우는 나이. */
    static final long TMP_STALE_MS = 60L * 60 * 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 스풀의 작업 하나 — job.json 을 읽지 못했으면 {@code job} 이 null. */
    public record Entry(Path dir, Wf9Job job) {}

    private final RiskWf9Properties props;
    private final Supplier<Path> root;
    private final Supplier<String> blocker;
    private final Clock clock;
    /** 비식별을 거치지 않은 전사를 처음 만났을 때 한 번만 경고한다(설정 문제 — 건마다 쌓지 않는다). */
    private final AtomicBoolean notDeidentWarned = new AtomicBoolean();

    @Autowired
    public Wf9Spool(RiskWf9Properties props, VoiceDirState dirs, RiskWf9Pipeline pipeline) {
        this(props, () -> defaultRoot(props, dirs), pipeline::blocker, Clock.systemUTC());
    }

    Wf9Spool(RiskWf9Properties props, Supplier<Path> root, Supplier<String> blocker, Clock clock) {
        this.props = props;
        this.root = root;
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
     * 가린 전사로 WF9 입력 문서를 만들어 넣는다 — <b>예외를 던지지 않는다</b>(배치 결과에 영향 없음).
     *
     * @param masked    본 배치 DEIDENT 단계가 돌려준 전사(가린 전문 · 구간)
     * @param deidMode  그 비식별의 모드 — {@link #DEIDENT_MODE} 일 때만 넣는다
     * @return 넣었으면 true
     */
    public boolean offer(String execId, VoiceTarget target, SttResult masked, String deidMode, boolean testRun) {
        if (!props.enabled() || target == null) {
            return false;
        }
        Path tmp = null;
        try {
            if (testRun && props.skipTestRuns()) {
                return false;
            }
            if (!DEIDENT_MODE.equals(deidMode)) {
                if (notDeidentWarned.compareAndSet(false, true)) {
                    log.warn("[WF9] 스풀 건너뜀 — 커넥터 비식별을 거치지 않은 전사(모드 {}) · 가린 글만 WF9 로 보낸다"
                            + "(agent-connector.voice-deident · 전송 모드 확인) · 같은 사유는 다시 적지 않는다", deidMode);
                }
                return false;
            }
            String blocked = blocker.get();
            if (blocked != null) {
                log.debug("[WF9] 스풀 건너뜀 — {} · {}", target.shortId(), blocked);
                return false;
            }
            if (isBlank(target.idempotencyKey()) || isBlank(target.corrNo()) || target.occurredAt() == null
                    || masked == null || masked.isEmpty()) {
                log.warn("[WF9] 스풀 건너뜀 — {} · 녹음 ID · 교정번호 · 일시 · 가린 전사 중 빈 것이 있다", target.shortId());
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
            String contactId = "C-" + UUID.randomUUID().toString().replace("-", "");
            Wf9TextDocument.Built built = Wf9TextDocument.of(JSON, contactId, contactTypeOf(target),
                    target.occurredAt().withNano(0).atZone(Wf9Job.KST).toOffsetDateTime().toString(), masked,
                    props.analyzeMaxSec());
            if (built.segments() == 0) {
                log.warn("[WF9] 스풀 건너뜀 — {} · 분석 길이(analyze-max-sec {}초) 안에 실을 구간이 없다", target.shortId(),
                        props.analyzeMaxSec());
                return false;
            }
            byte[] doc = JSON.writeValueAsBytes(built.document());
            long[] usage = usage(base);
            if (usage[0] >= props.maxPending() || usage[1] + doc.length > props.maxSpoolMb() * 1024 * 1024) {
                log.warn("[WF9] 스풀 가득 참(작업 {}개 · {}MB · 상한 {}개 · {}MB) — {} 넣지 않음", usage[0],
                        usage[1] / (1024 * 1024), props.maxPending(), props.maxSpoolMb(), target.shortId());
                return false;
            }
            tmp = base.resolve(TMP_PREFIX + jobId + "-" + UUID.randomUUID().toString().substring(0, 8));
            Files.createDirectories(tmp);
            Files.write(tmp.resolve(DOC_FILE), doc);
            Wf9Job job = new Wf9Job(jobId, target.idempotencyKey().strip(), target.corrNo().strip(), target.kind().name(),
                    target.occurredAt().withNano(0).toString(), execId, contactId, DOC_FILE, built.cutSec(), clock.millis(),
                    0, 0L, null, 0L);
            Files.writeString(tmp.resolve(JOB_FILE), JSON.writeValueAsString(job), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException e) {
                log.debug("[WF9] 이미 대기 중(동시에 넣음) — {}", target.shortId());
                return false;
            }
            tmp = null;
            log.info("[WF9] 스풀 적재 — {} · 구간 {}개{}", target.shortId(), built.segments(),
                    built.cutSec() == null ? "" : " · 앞 " + Math.round(built.cutSec()) + "초만(D9)");
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

    /** 제논 {@code contact_type} — {@link Wf9Job#contactType()} 와 같은 규칙. */
    private static String contactTypeOf(VoiceTarget t) {
        return "PHONE".equals(t.kind().name()) ? "전화" : "접견";
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

    /** 작업 정보 — 예전 음성 사본 작업(audioFile)은 docFile 이 없어 못 읽은 것으로 본다(작업자가 지운다). */
    private static Wf9Job read(Path dir) {
        try {
            Wf9Job j = JSON.readValue(Files.readString(dir.resolve(JOB_FILE), StandardCharsets.UTF_8), Wf9Job.class);
            boolean ok = j != null && !isBlank(j.recFileId()) && !isBlank(j.corrNo()) && !isBlank(j.occurredAt())
                    && !isBlank(j.contactId()) && !isBlank(j.docFile()) && !j.docFile().contains("/")
                    && !j.docFile().contains("\\") && Files.isRegularFile(dir.resolve(j.docFile()));
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

    /** 작업 폴더를 통째로 지운다(가린 문서 포함). */
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
