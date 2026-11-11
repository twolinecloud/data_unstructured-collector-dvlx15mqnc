package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.StepType;
import egovframework.unstructured.collector.common.util.InmatePidGenerator;
import egovframework.unstructured.collector.voice.stt.genon.AudioSampleGenerator;
import egovframework.unstructured.collector.voice.stt.genon.GenonSttClient;
import egovframework.unstructured.collector.voice.stt.genon.PcmWav;
import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 음성 STT · 비식별 검증 파이프라인(시뮬레이터 11번 탭 · 일 단위 자동 생성) — dev · local 전용.
 *
 * <pre>
 *  ① 음원 생성(COLLECT)  합성 낭독 22초(16kHz · 모노 · PCM 16bit)를 이어 붙여 20초 ~ 30분 WAV
 *  ② STT(ANALYZE)       제논 /audio/transcribe — 600초를 넘으면 조각내 차례로 부르고(GenonSttClient#transcribeLong)
 *  ③ 병합                조각 전사를 순서대로 잇는다(구간 시각은 원래 음원 기준) · 선택하면 가상 개인정보 문장을 끝에 덧붙인다
 *  ④ 비식별(DEIDENT)     에이전트 커넥터 /api/v1/deid/run(persist=false) — 토큰 · 별표로 가린 글
 *  ⑤ 적재(STORE · REAL)  가린 글만 {ROOT}/mock/stt_deid/results/{execId}/ 에 JSON 으로 — 원문 전사는 쓰지 않는다
 * </pre>
 *
 * <p><b>SIM</b> — 결과를 메모리(최근 {@code keep-runs}건)와 화면에만 둔다. 로그 컬렉터를 부르지 않는다(대시보드 무변화).
 * <b>REAL</b> — 실제 배치처럼 T1(작업 {@code VOICE_STT_DEID} · 비정형 · 대상 구간 없음 → 워터마크에 안 섞임) · T2(COLLECT ·
 * ANALYZE · DEIDENT · STORE) · T4(건마다)를 남긴다. 대시보드 '처리 건수' 는 STORE 통과 건수다(SEND 가 없을 때의 규칙).
 * 전송(SEND)은 하지 않는다 — 이 파이프라인은 클라우드로 아무것도 보내지 않는다.</p>
 *
 * <p>한 번에 하나씩 돈다(단일 작업 스레드 · 제논 동시 1건). 화면 실행은 돌고 있으면 거절하고(409), 일 단위 실행은 줄을 선다.
 * 단계는 단계 단위로 돈다(모든 건 생성 → 모든 건 STT → …) — T2 단계 시각 · 건수가 실제와 맞게.</p>
 */
@Log4j2
@Service
@Profile({"dev", "local"})
public class SttDeidSimService {

    public enum Mode { SIM, REAL }

    /** 11번 탭 프리셋 — 길이와 뜻. */
    public enum Preset {
        S20(20, "20초 — 짧은 음성 · 기본 동작"),
        M5(300, "5분 — 일반 대화"),
        M10(600, "10분 — 제논 한 번 호출 한도"),
        M20(1200, "20분 — 2회 분할(10분 × 2)"),
        M30(1800, "30분 — 법정 표준(10분 × 3 분할)");

        private final int seconds;
        private final String label;

        Preset(int seconds, String label) {
            this.seconds = seconds;
            this.label = label;
        }

        public int seconds() {
            return seconds;
        }

        public String label() {
            return label;
        }
    }

    public enum Kind {
        MEET("접견"), PHONE("전화");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 화면 단계 — 표시 순서. */
    public enum Stage { QUEUED, GENERATE, STT, MERGE, DEID, STORE, DONE }

    /** 비식별 적재 폴더 — ROOT 기준. */
    public static final String RESULT_DIR = "mock/stt_deid/results";
    /** 화면 실행 길이 상한(초). */
    static final int MAX_SECONDS = 3600;

    /**
     * 가상 개인정보 문장 — 합성 낭독에는 개인정보가 없어 실제 전사만으로는 가릴 것이 없다. 이름 · 번호는 예시용으로 지어낸 것이다
     * (커넥터 API 문서 예시와 같은 값). 전사 끝에 줄을 바꿔 덧붙이고, 화면은 이 줄을 STT 결과와 구분해 보여 준다.
     */
    static final List<String> SEED_LINES = List.of(
            "보호자 김철수 씨 연락처는 010-1234-5678 입니다.",
            "영치금은 이영희 이름으로 110-222-333444 계좌에 넣어 주세요.");

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter EXEC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter EXEC_TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    /** T4 ERR_STACK 상한(로그 컬렉터 상세 1000자) 안쪽. */
    private static final int REASON_CAP = 900;

    private final GenonSttClient genon;
    private final TranscriptDeidRunClient deid;
    private final LogCollectorClient logCollector;
    private final InmatePidGenerator pids;
    private final VoiceDirState dirs;
    private final VoiceProperties voiceProps;
    private final SttDeidProperties props;
    private final ObjectMapper om;
    private final Path workRoot;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stt-deid-sim");
        t.setDaemon(true);
        return t;
    });
    /** 최근 실행 — 새것이 앞. */
    private final Deque<Run> runs = new ConcurrentLinkedDeque<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger seq = new AtomicInteger();
    private volatile Run active;
    private volatile Map<String, Object> lastDaily;

    @Autowired
    public SttDeidSimService(GenonSttClient genon, TranscriptDeidRunClient deid, LogCollectorClient logCollector,
                             InmatePidGenerator pids, VoiceDirState dirs, VoiceProperties voiceProps,
                             SttDeidProperties props, ObjectMapper objectMapper) {
        this(genon, deid, logCollector, pids, dirs, voiceProps, props, objectMapper,
                Path.of(System.getProperty("java.io.tmpdir"), "stt-deid-sim"));
    }

    /** 작업 폴더(생성 음원 · 조각)를 정해서 — 단위 테스트용. */
    SttDeidSimService(GenonSttClient genon, TranscriptDeidRunClient deid, LogCollectorClient logCollector,
                      InmatePidGenerator pids, VoiceDirState dirs, VoiceProperties voiceProps,
                      SttDeidProperties props, ObjectMapper objectMapper, Path workRoot) {
        this.genon = genon;
        this.deid = deid;
        this.logCollector = logCollector;
        this.pids = pids;
        this.dirs = dirs;
        this.voiceProps = voiceProps;
        this.props = props;
        this.om = objectMapper;
        this.workRoot = workRoot;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  접수
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 화면 [음성 전사 및 비식별 실행] — 한 건. 돌고 있거나 줄 선 실행이 있으면 거절한다.
     *
     * @param preset  S20 · M5 · M10 · M20 · M30
     * @param seconds 길이를 직접 줄 때(1~3600초) — 주면 프리셋 길이 대신 쓴다
     * @param kind    MEET(접견) · PHONE(전화)
     * @param mode    SIM(기본 · 기록 안 함) · REAL(T1~T4)
     * @param seedPii 가상 개인정보 문장 덧붙이기 — null 이면 설정값
     * @throws IllegalArgumentException 값이 틀림
     * @throws IllegalStateException    이미 돌고 있음(409)
     */
    public Map<String, Object> submit(String preset, Integer seconds, String kind, String mode, Boolean seedPii) {
        Preset p = parse(Preset.class, preset == null || preset.isBlank() ? "S20" : preset, "preset");
        Kind k = parse(Kind.class, kind == null || kind.isBlank() ? "MEET" : kind, "kind");
        Mode m = parse(Mode.class, mode == null || mode.isBlank() ? "SIM" : mode, "mode");
        int sec = seconds == null ? p.seconds() : seconds;
        if (sec < 1 || sec > MAX_SECONDS) {
            throw new IllegalArgumentException("길이는 1~" + MAX_SECONDS + "초입니다: " + sec);
        }
        if (pending.get() > 0) {
            Run a = active;
            throw new IllegalStateException("STT·비식별 실행이 이미 " + (a != null ? "돌고 있습니다(" + a.id + ")" : "줄 서 있습니다")
                    + " — 끝난 뒤 다시 누르십시오");
        }
        Run run = newRun(m, "MANUAL", "SIMULATOR", seedPii == null ? props.seedPii() : seedPii,
                List.of(new Spec(k, sec, seconds == null ? p.name() : null)));
        enqueue(run);
        log.info("[STT-DEID] 접수 {} — {} · {} · {}초 · 가상 개인정보 {}", run.id, m, k.label(), sec, run.seedPii ? "덧붙임" : "없음");
        return detail(run);
    }

    /**
     * 일 단위 — 접견 · 전화 3~5건(20~300초 무작위). 앞 실행이 있으면 줄을 선다.
     *
     * @param mode      REAL(새벽 스케줄 · 대시보드용 즉시 실행) · SIM(시뮬레이터용 즉시 실행)
     * @param triggerBy SCHEDULER(→ T1 SCHEDULED) · 그 밖(→ MANUAL)
     * @return 접수 요약(건수 · 길이) — 꺼져 있으면 {@code skipped}
     */
    public Map<String, Object> submitDaily(Mode mode, String triggerBy) {
        SttDeidProperties.Daily d = props.daily();
        Map<String, Object> out = new LinkedHashMap<>();
        if (!d.enabled()) {
            out.put("skipped", "unstructured.mock.stt-deid.daily.enabled=false");
            return out;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int lo = Math.max(1, Math.min(d.minItems(), d.maxItems()));
        int hi = Math.max(lo, d.maxItems());
        int sLo = Math.max(1, Math.min(d.minSec(), d.maxSec()));
        int sHi = Math.min(MAX_SECONDS, Math.max(sLo, d.maxSec()));
        int n = rnd.nextInt(lo, hi + 1);
        List<Spec> specs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            specs.add(new Spec(rnd.nextBoolean() ? Kind.MEET : Kind.PHONE, rnd.nextInt(sLo, sHi + 1), null));
        }
        Run run = newRun(mode == null ? Mode.REAL : mode, "DAILY", triggerBy == null ? "SIMULATOR" : triggerBy,
                props.seedPii(), specs);
        int ahead = pending.get();
        enqueue(run);
        out.put("runId", run.id);
        out.put("mode", run.mode.name());
        out.put("triggerBy", run.triggerBy);
        out.put("execType", execTypeOf(run.triggerBy));
        out.put("items", specs.stream().map(s -> s.kind().label() + " " + s.seconds() + "초").toList());
        out.put("queued", true);
        out.put("ahead", ahead);
        out.put("note", run.mode == Mode.REAL
                ? "백그라운드에서 수집 → STT → 비식별 → 적재 — 끝나면 대시보드 배치 수행 이력(비정형)에 " + props.jobNm() + " 로 남는다"
                : "백그라운드에서 수집 → STT → 비식별 — 결과는 11번 탭 '최근 실행' 에만(기록 안 함)");
        lastDaily = new LinkedHashMap<>(out);
        log.info("[STT-DEID] 일 단위 접수 {} — {} · {}건 {} · 앞 {}건", run.id, run.mode, n, out.get("items"), ahead);
        return out;
    }

    private void enqueue(Run run) {
        runs.addFirst(run);
        trimRuns();
        pending.incrementAndGet();
        try {
            worker.submit(() -> {
                try {
                    active = run;
                    execute(run);
                } catch (Throwable t) {
                    log.error("[STT-DEID] {} 처리 중 예기치 못한 오류 — {}", run.id, t.toString(), t);
                    run.status = "FAIL";
                    run.error = "예기치 못한 오류 — " + t.getClass().getSimpleName();
                } finally {
                    active = null;
                    pending.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            pending.decrementAndGet();
            runs.remove(run);
            throw new IllegalStateException("작업 스레드가 접수를 거절했습니다 — " + e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  실행
    // ══════════════════════════════════════════════════════════════════════

    private void execute(Run run) {
        long t0 = System.currentTimeMillis();
        run.startedAt = LocalDateTime.now(ZONE).withNano(0);
        run.status = "RUNNING";
        Path dir = workRoot.resolve(run.id);
        List<Item> alive = new ArrayList<>(run.items);
        try {
            Files.createDirectories(dir);
            openBatch(run);

            // ① COLLECT — 가상 음원
            run.stage = Stage.GENERATE;
            long p0 = System.currentTimeMillis();
            String step = openStep(run, StepType.COLLECT);
            for (Item it : alive) {
                generate(it, dir);
            }
            alive.removeIf(Item::failed);
            run.generateMs = since(p0);
            closeStep(step, run.items.size(), alive.size(), run.generateMs, firstErrStack(run, StepType.COLLECT));

            // ② ③ ANALYZE — 제논 STT(분할 · 병합)
            if (!alive.isEmpty()) {
                run.stage = Stage.STT;
                p0 = System.currentTimeMillis();
                step = openStep(run, StepType.ANALYZE);
                int in = alive.size();
                for (Item it : alive) {
                    transcribe(run, it, dir);
                }
                alive.removeIf(Item::failed);
                run.sttMs = since(p0);
                run.mergeMs = run.items.stream().mapToLong(i -> i.mergeMs).sum();
                closeStep(step, in, alive.size(), run.sttMs, firstErrStack(run, StepType.ANALYZE));
            }

            // ④ DEIDENT — 커넥터 비식별
            if (!alive.isEmpty()) {
                run.stage = Stage.DEID;
                p0 = System.currentTimeMillis();
                step = openStep(run, StepType.DEIDENT);
                int in = alive.size();
                for (Item it : alive) {
                    deidentify(run, it);
                }
                alive.removeIf(Item::failed);
                run.deidMs = since(p0);
                closeStep(step, in, alive.size(), run.deidMs, firstErrStack(run, StepType.DEIDENT));
            }

            // ⑤ STORE — REAL 만(가린 글만 적재)
            if (!alive.isEmpty() && run.mode == Mode.REAL) {
                run.stage = Stage.STORE;
                p0 = System.currentTimeMillis();
                step = openStep(run, StepType.STORE);
                int in = alive.size();
                for (Item it : alive) {
                    store(run, it);
                }
                alive.removeIf(Item::failed);
                run.storeMs = since(p0);
                closeStep(step, in, alive.size(), run.storeMs, firstErrStack(run, StepType.STORE));
                pruneResults();
            }
            for (Item it : alive) {
                it.status = "SUCCESS";
                it.stage = Stage.DONE;
            }
        } catch (Exception e) {
            // 파이프라인 밖 오류(작업 폴더 · 자산 등) — 아직 안 끝난 건을 그 단계 실패로
            String why = "처리 중단 — " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            log.error("[STT-DEID] {} {}", run.id, why, e);
            for (Item it : alive) {
                if (!it.failed()) {
                    it.fail(stepOf(it.stage), why);
                }
            }
        } finally {
            deleteTree(dir);
            run.totalMs = since(t0);
            finishBatch(run);
            run.stage = Stage.DONE;
            run.endedAt = LocalDateTime.now(ZONE).withNano(0);
        }
    }

    /** ① 합성 낭독을 이어 붙여 그 길이의 WAV 를 만든다. */
    private void generate(Item it, Path dir) {
        it.stage = Stage.GENERATE;
        it.status = "RUNNING";
        long t0 = System.currentTimeMillis();
        Path wav = dir.resolve(it.fileNm);
        try (InputStream in = SttDeidSimService.class.getClassLoader().getResourceAsStream(AudioSampleGenerator.SPEECH_ASSET)) {
            if (in == null) {
                throw new IOException("합성 낭독 자산이 없다 — " + AudioSampleGenerator.SPEECH_ASSET);
            }
            PcmWav.Info info = PcmWav.tile(in, wav, it.seconds);
            it.wav = wav;
            it.fileSize = Files.size(wav);
            it.audio = "%dHz · %s · 16bit · %.1f초".formatted(info.rate(), info.channels() == 1 ? "모노" : info.channels() + "채널",
                    info.durationSec());
        } catch (Exception e) {
            deleteQuietly(wav);
            it.fail(StepType.COLLECT, "음원 생성 실패 — " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        it.generateMs = since(t0);
    }

    /** ② 제논 STT(600초 넘으면 조각) · ③ 병합 — 끝나면 음원은 지운다. */
    private void transcribe(Run run, Item it, Path dir) {
        it.stage = Stage.STT;
        long t0 = System.currentTimeMillis();
        try {
            GenonSttClient.LongTranscript lt = genon.transcribeLong(it.wav, dir, new GenonSttClient.ChunkListener() {
                @Override
                public void sending(int no, int total, double startSec, double endSec) {
                    it.chunkNo = no;
                    it.chunkTotal = total;
                }

                @Override
                public void done(GenonSttClient.ChunkCall call, int total) {
                    it.chunks.add(chunkView(call));
                    it.chunkTotal = total;
                }
            });
            it.sttMs = since(t0);
            it.stage = Stage.MERGE;
            long m0 = System.currentTimeMillis();
            SttResult r = lt.result();
            it.sttEngine = r.engine();
            it.segments = r.segments().size();
            it.transcript = r.text();
            if (r.isEmpty()) {
                throw new IllegalStateException("STT 결과가 비어 있음");
            }
            it.seeded = run.seedPii ? SEED_LINES : List.of();
            it.deidInput = run.seedPii ? r.text() + "\n" + String.join("\n", SEED_LINES) : r.text();
            it.mergeMs = since(m0);
        } catch (Exception e) {
            if (it.sttMs == 0) {
                it.sttMs = since(t0);
            }
            it.fail(StepType.ANALYZE, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            deleteQuietly(it.wav);
        }
    }

    /** ④ 커넥터 비식별 — REAL 이면 실행 ID 를 함께 보낸다(커넥터 로그 추적용). */
    private void deidentify(Run run, Item it) {
        it.stage = Stage.DEID;
        long t0 = System.currentTimeMillis();
        try {
            it.deid = deid.deidentify(run.mode == Mode.REAL ? run.execId : null, it.sessionId, it.deidInput);
        } catch (TranscriptDeidRunClient.DeidRunException e) {
            it.deidHttpStatus = e.httpStatus();
            it.deidCode = e.code();
            it.fail(StepType.DEIDENT, e.getMessage());
        } catch (Exception e) {
            it.fail(StepType.DEIDENT, "커넥터 비식별 실패 — " + e.getClass().getSimpleName());
        }
        it.deidMs = since(t0);
    }

    /** ⑤ 가린 글만 적재 — 원문 전사 · 가상 문장 원문은 쓰지 않는다. */
    private void store(Run run, Item it) {
        it.stage = Stage.STORE;
        long t0 = System.currentTimeMillis();
        try {
            Path d = resultRoot().resolve(run.execId);
            Files.createDirectories(d);
            Path f = d.resolve(it.recFileId + ".json");
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("execId", run.execId);
            doc.put("recFileId", it.recFileId);
            doc.put("inmatePid", it.inmatePid);
            doc.put("kind", it.kind.label());
            doc.put("durationSec", it.seconds);
            doc.put("sttEngine", it.sttEngine);
            doc.put("sttChunks", it.chunks.size());
            doc.put("deidMode", it.deid.mode());
            doc.put("tokens", it.deid.tokens());
            doc.put("tokenTypes", it.deid.tokenTypes());
            doc.put("masks", it.deid.masks());
            doc.put("seededPii", !it.seeded.isEmpty());
            doc.put("deidentifiedText", it.deid.text());
            doc.put("storedAt", LocalDateTime.now(ZONE).withNano(0).toString());
            om.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), doc);
            it.storedAt = f.toString();
        } catch (Exception e) {
            it.fail(StepType.STORE, "비식별 결과 적재 실패 — " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        it.storeMs = since(t0);
    }

    // ── 이력(REAL) ─────────────────────────────────────────────────────────

    /** REAL 이면 T1 을 연다. 로그 컬렉터가 꺼져 있으면 로컬 번호로 돌고 남기지 않는다. */
    private void openBatch(Run run) {
        if (run.mode != Mode.REAL) {
            run.notes.add("SIM — 로그 컬렉터에 남기지 않습니다(대시보드 무변화 · 결과는 이 화면에만)");
            return;
        }
        String id = logCollector.createBatch(props.jobId(), props.jobNm(), voiceProps.batch().dataTypeCd(),
                execTypeOf(run.triggerBy), run.triggerBy, null, null);
        run.logged = id != null;
        if (id == null) {
            LocalDateTime now = LocalDateTime.now(ZONE);
            id = now.format(EXEC_DATE) + "UNS" + now.format(EXEC_TIME);
            run.notes.add("로그 컬렉터 미연동(log-collector.enabled · base-url 확인) — 대시보드에 남지 않습니다 · 실행 ID 는 로컬 번호");
            log.warn("[STT-DEID] {} — 로그 컬렉터 미연동, 로컬 번호 {} 로 진행(이력 없음)", run.id, id);
        }
        run.execId = id;
    }

    private String openStep(Run run, StepType t) {
        return run.logged ? logCollector.createStep(run.execId, t.seq(), t.name()) : null;
    }

    /** T2 마감 — 들어온 건 · 통과한 건으로 상태를 정한다(전부 통과 SUCCESS · 일부 PARTIAL · 없음 FAIL). */
    private void closeStep(String stepId, long in, long out, long ms, String err) {
        if (stepId == null) {
            return;
        }
        long errCnt = Math.max(0, in - out);
        String sts = errCnt == 0 ? "SUCCESS" : out > 0 ? "PARTIAL" : "FAIL";
        logCollector.finishStep(stepId, sts, secs(ms), in, out, errCnt, errCnt == 0 ? null : err);
    }

    /** 건 상태 · T4 · T1 마감. SIM 이면 화면 상태만. */
    private void finishBatch(Run run) {
        long ok = run.items.stream().filter(i -> !i.failed()).count();
        long ng = run.items.size() - ok;
        run.status = ng == 0 ? "SUCCESS" : ok > 0 ? "PARTIAL" : "FAIL";
        if (!run.logged) {
            log.info("[STT-DEID] {} 끝 — {} · {} · 성공 {} · 실패 {} · {}ms", run.id, run.mode, run.status, ok, ng, run.totalMs);
            return;
        }
        List<LogCollectorClient.FileProcReq> rows = new ArrayList<>();
        for (Item it : run.items) {
            rows.add(new LogCollectorClient.FileProcReq(it.recFileId, "sim/stt-deid/" + it.fileNm, it.fileNm, it.inmatePid,
                    it.fileSize, it.failed() ? "FAIL" : "SUCCESS", it.errStack,
                    it.failed() ? it.failedStep.name() : StepType.STORE.name()));
        }
        logCollector.createFileProcs(run.execId, rows);
        String top = run.items.stream().filter(Item::failed).map(i -> i.errStack).findFirst().orElse(null);
        logCollector.finishBatch(run.execId, run.status, secs(run.totalMs), (long) run.items.size(), ok, ng, top);
        log.info("[STT-DEID] {} 끝 — REAL {} · {} · 성공 {} · 실패 {} · {}ms", run.id, run.execId, run.status, ok, ng, run.totalMs);
    }

    /** T1 EXEC_TYPE_CD — 스케줄러만 SCHEDULED(이미지 · 음성 배치와 같은 규칙). */
    static String execTypeOf(String triggerBy) {
        return triggerBy != null && triggerBy.toUpperCase(Locale.ROOT).startsWith("SCHEDULE") ? "SCHEDULED" : "MANUAL";
    }

    private static String firstErrStack(Run run, StepType step) {
        return run.items.stream().filter(i -> i.failedStep == step).map(i -> i.errStack).findFirst().orElse(null);
    }

    private static StepType stepOf(Stage s) {
        return switch (s) {
            case STT, MERGE -> StepType.ANALYZE;
            case DEID -> StepType.DEIDENT;
            case STORE, DONE -> StepType.STORE;
            default -> StepType.COLLECT;
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    //  조회
    // ══════════════════════════════════════════════════════════════════════

    /** 탭 상단 — 프리셋 · 엔진 · 커넥터 · 이력 연동 · 진행 중 · 일 단위 설정. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> presets = new ArrayList<>();
        for (Preset p : Preset.values()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", p.name());
            v.put("seconds", p.seconds());
            v.put("label", p.label());
            presets.add(v);
        }
        m.put("presets", presets);
        Map<String, Object> stt = new LinkedHashMap<>();
        Map<String, Object> gs = genon.status();
        stt.put("callMode", gs.get("mode"));
        stt.put("baseUrl", gs.get("baseUrl"));
        stt.put("apiKeySet", gs.get("apiKeySet"));
        stt.put("apiKeyMasked", gs.get("apiKeyMasked"));
        stt.put("limits", gs.get("limits"));
        stt.put("timeoutSec", gs.get("timeoutSec"));
        m.put("stt", stt);
        m.put("deid", deid.status());
        Map<String, Object> lc = new LinkedHashMap<>();
        lc.put("enabled", logCollector.isEnabled());
        lc.put("baseUrl", logCollector.baseUrl());
        lc.put("jobId", props.jobId());
        lc.put("jobNm", props.jobNm());
        lc.put("dataTypeCd", voiceProps.batch().dataTypeCd());
        lc.put("steps", List.of("COLLECT", "ANALYZE", "DEIDENT", "STORE"));
        m.put("logCollector", lc);
        m.put("seedPiiDefault", props.seedPii());
        m.put("seedLines", SEED_LINES);
        m.put("daily", props.daily());
        m.put("lastDaily", lastDaily);
        m.put("resultDir", resultRoot().toString());
        Run a = active;
        m.put("busy", pending.get() > 0);
        m.put("pending", pending.get());
        m.put("active", a == null ? null : summary(a));
        return m;
    }

    /** 최근 실행 — 새것부터(글 없이). */
    public List<Map<String, Object>> runs() {
        return runs.stream().map(this::summary).toList();
    }

    /** 실행 하나 — 건마다 전사 · 가린 글까지. */
    public Map<String, Object> run(String id) {
        return runs.stream().filter(r -> r.id.equals(id)).findFirst().map(this::detail)
                .orElseThrow(() -> new NoSuchElementException("실행이 없습니다(최근 " + props.keepRuns() + "건만 남깁니다): " + id));
    }

    private Map<String, Object> summary(Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.id);
        m.put("mode", r.mode.name());
        m.put("trigger", r.trigger);
        m.put("triggerBy", r.triggerBy);
        m.put("execType", execTypeOf(r.triggerBy));
        m.put("status", r.status);
        m.put("stage", r.stage.name());
        m.put("execId", r.execId);
        m.put("logged", r.logged);
        m.put("seedPii", r.seedPii);
        m.put("requestedAt", r.requestedAt.toString());
        m.put("startedAt", r.startedAt == null ? null : r.startedAt.toString());
        m.put("endedAt", r.endedAt == null ? null : r.endedAt.toString());
        m.put("itemCount", r.items.size());
        m.put("successCount", r.items.stream().filter(i -> "SUCCESS".equals(i.status)).count());
        m.put("failCount", r.items.stream().filter(Item::failed).count());
        m.put("totalSeconds", r.items.stream().mapToInt(i -> i.seconds).sum());
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("generateMs", r.generateMs);
        t.put("sttMs", r.sttMs);
        t.put("mergeMs", r.mergeMs);
        t.put("deidMs", r.deidMs);
        t.put("storeMs", r.storeMs);
        t.put("totalMs", r.totalMs);
        m.put("timings", t);
        m.put("notes", List.copyOf(r.notes));
        m.put("error", r.error);
        return m;
    }

    private Map<String, Object> detail(Run r) {
        Map<String, Object> m = summary(r);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Item it : r.items) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("no", it.no);
            v.put("kind", it.kind.name());
            v.put("kindLabel", it.kind.label());
            v.put("preset", it.preset);
            v.put("seconds", it.seconds);
            v.put("recFileId", it.recFileId);
            v.put("fileNm", it.fileNm);
            v.put("inmatePid", it.inmatePid);
            v.put("sttSessionId", it.sessionId);
            v.put("audio", it.audio);
            v.put("fileSize", it.fileSize);
            v.put("stage", it.stage.name());
            v.put("status", it.status);
            v.put("failedStep", it.failedStep == null ? null : it.failedStep.name());
            v.put("error", it.error);
            v.put("errStack", it.errStack);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("generateMs", it.generateMs);
            t.put("sttMs", it.sttMs);
            t.put("mergeMs", it.mergeMs);
            t.put("deidMs", it.deidMs);
            t.put("storeMs", it.storeMs);
            v.put("timings", t);
            Map<String, Object> stt = new LinkedHashMap<>();
            stt.put("engine", it.sttEngine);
            stt.put("chunkNo", it.chunkNo);
            stt.put("chunkTotal", it.chunkTotal);
            stt.put("chunks", List.copyOf(it.chunks));
            stt.put("segments", it.segments);
            stt.put("chars", it.transcript == null ? 0 : it.transcript.length());
            stt.put("text", it.transcript);
            stt.put("seededLines", it.seeded);
            v.put("stt", stt);
            Map<String, Object> d = new LinkedHashMap<>();
            TranscriptDeidRunClient.Result res = it.deid;
            d.put("mode", res != null ? res.mode() : deid.mode());
            d.put("endpoint", res != null ? res.endpoint() : deid.endpoint());
            d.put("httpStatus", res != null ? res.httpStatus() : it.deidHttpStatus);
            d.put("code", it.deidCode);
            d.put("elapsedMs", res != null ? res.elapsedMs() : it.deidMs);
            d.put("inputChars", it.deidInput == null ? 0 : it.deidInput.length());
            d.put("chars", res == null ? 0 : res.text().length());
            d.put("tokens", res == null ? 0 : res.tokens());
            d.put("uniqueTokens", res == null ? 0 : res.uniqueTokens());
            d.put("tokenTypes", res == null ? Map.of() : res.tokenTypes());
            d.put("masks", res == null ? 0 : res.masks());
            d.put("connectorExecId", res == null ? null : res.connectorExecId());
            d.put("text", res == null ? null : res.text());
            v.put("deid", d);
            v.put("storedAt", it.storedAt);
            items.add(v);
        }
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> chunkView(GenonSttClient.ChunkCall c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("no", c.no());
        m.put("startSec", c.startSec());
        m.put("endSec", c.endSec());
        m.put("mode", c.mode());
        m.put("httpStatus", c.httpStatus());
        m.put("elapsedMs", c.elapsedMs());
        m.put("queuedMs", c.queuedMs());
        m.put("attempts", c.attempts());
        m.put("chars", c.chars());
        m.put("resplit", c.resplit());
        return m;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  내부
    // ══════════════════════════════════════════════════════════════════════

    /** 만들 건 하나 — 유형 · 길이 · 프리셋(일 단위는 null). */
    record Spec(Kind kind, int seconds, String preset) {
    }

    private Run newRun(Mode mode, String trigger, String triggerBy, boolean seedPii, List<Spec> specs) {
        LocalDateTime now = LocalDateTime.now(ZONE).withNano(0);
        String id = "SD" + now.format(ID_TIME) + "-" + String.format("%02d", seq.incrementAndGet() % 100);
        List<Item> items = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            int no = i + 1;
            String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
            String fileNm = "%s_%s_%02d_%ds.wav".formatted(s.kind().name(), now.format(FILE_TIME), no, s.seconds());
            // 가상 교정번호 — PID 를 만드는 데만 쓰고 어디에도 남기지 않는다
            String inmatePid = pids.of("STTDEID-" + tag);
            items.add(new Item(no, s.kind(), s.seconds(), s.preset(), "STTDEID-" + now.format(ID_TIME) + "-" + tag,
                    fileNm, inmatePid, "STTSIM-" + tag));
        }
        return new Run(id, mode, trigger, triggerBy, seedPii, now, items);
    }

    private void trimRuns() {
        int keep = Math.max(1, props.keepRuns());
        while (runs.size() > keep) {
            Run oldest = runs.peekLast();
            if (oldest == null || "QUEUED".equals(oldest.status) || "RUNNING".equals(oldest.status)) {
                return;
            }
            runs.pollLast();
        }
    }

    private Path resultRoot() {
        return Path.of(dirs.baseDir(), RESULT_DIR);
    }

    /** 적재 폴더 — 최근 keep-runs 개(실행 ID 폴더)만 남긴다. */
    private void pruneResults() {
        Path root = resultRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> s = Files.list(root)) {
            List<Path> dirsByAge = s.filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(SttDeidSimService::modified).reversed()).toList();
            for (int i = Math.max(1, props.keepRuns()); i < dirsByAge.size(); i++) {
                deleteTree(dirsByAge.get(i));
            }
        } catch (IOException e) {
            log.warn("[STT-DEID] 적재 폴더 정리 실패 — {}", e.getMessage());
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(SttDeidSimService::deleteQuietly);
        } catch (IOException e) {
            log.warn("[STT-DEID] 폴더 정리 실패 — {} ({})", dir, e.getMessage());
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("[STT-DEID] 지우지 못함 — {} ({})", p, e.getMessage());
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            StringBuilder ok = new StringBuilder();
            for (E c : type.getEnumConstants()) {
                ok.append(ok.length() == 0 ? "" : " · ").append(c.name());
            }
            throw new IllegalArgumentException(name + " 은(는) " + ok + " 중 하나입니다: " + value);
        }
    }

    private static long since(long t0) {
        return System.currentTimeMillis() - t0;
    }

    /** 초 — 0.x 초도 1초로(이력의 0초 표시가 '안 돌았다' 로 읽히지 않게). */
    private static int secs(long ms) {
        return (int) Math.ceil(ms / 1000d);
    }

    /** 실행 하나. 작업 스레드가 쓰고 화면 스레드가 읽는다(volatile). */
    static final class Run {
        final String id;
        final Mode mode;
        final String trigger;
        final String triggerBy;
        final boolean seedPii;
        final LocalDateTime requestedAt;
        final List<Item> items;
        final List<String> notes = new CopyOnWriteArrayList<>();
        volatile String status = "QUEUED";
        volatile Stage stage = Stage.QUEUED;
        volatile String execId;
        volatile boolean logged;
        volatile LocalDateTime startedAt;
        volatile LocalDateTime endedAt;
        volatile long generateMs;
        volatile long sttMs;
        volatile long mergeMs;
        volatile long deidMs;
        volatile long storeMs;
        volatile long totalMs;
        volatile String error;

        Run(String id, Mode mode, String trigger, String triggerBy, boolean seedPii, LocalDateTime requestedAt,
            List<Item> items) {
            this.id = id;
            this.mode = mode;
            this.trigger = trigger;
            this.triggerBy = triggerBy;
            this.seedPii = seedPii;
            this.requestedAt = requestedAt;
            this.items = List.copyOf(items);
        }
    }

    /** 음원 하나. */
    static final class Item {
        final int no;
        final Kind kind;
        final int seconds;
        final String preset;
        final String recFileId;
        final String fileNm;
        final String inmatePid;
        final String sessionId;
        final List<Map<String, Object>> chunks = new CopyOnWriteArrayList<>();
        volatile Stage stage = Stage.QUEUED;
        volatile String status = "WAIT";
        volatile StepType failedStep;
        volatile String error;
        volatile String errStack;
        volatile Path wav;
        volatile long fileSize;
        volatile String audio;
        volatile long generateMs;
        volatile long sttMs;
        volatile long mergeMs;
        volatile long deidMs;
        volatile long storeMs;
        volatile int chunkNo;
        volatile int chunkTotal;
        volatile String sttEngine;
        volatile int segments;
        volatile String transcript;
        volatile List<String> seeded = List.of();
        volatile String deidInput;
        volatile TranscriptDeidRunClient.Result deid;
        volatile int deidHttpStatus;
        volatile String deidCode;
        volatile String storedAt;

        Item(int no, Kind kind, int seconds, String preset, String recFileId, String fileNm, String inmatePid,
             String sessionId) {
            this.no = no;
            this.kind = kind;
            this.seconds = seconds;
            this.preset = preset;
            this.recFileId = recFileId;
            this.fileNm = fileNm;
            this.inmatePid = inmatePid;
            this.sessionId = sessionId;
        }

        boolean failed() {
            return failedStep != null;
        }

        /** 실패 — 사유는 원문을 담지 않는 한 줄. T4 ERR_STACK 은 {@code [코드] [단계] 상세}. */
        void fail(StepType step, String reason) {
            String r = reason == null || reason.isBlank() ? "사유 미상" : reason.strip();
            if (r.length() > REASON_CAP) {
                r = r.substring(0, REASON_CAP) + "…";
            }
            failedStep = step;
            error = r;
            errStack = LogCollectorClient.FileProcReq.errStackOf(step, r);
            status = "FAIL";
        }
    }
}
