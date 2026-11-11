package egovframework.unstructured.collector.voice.stt.genon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceFile;
import egovframework.unstructured.collector.voice.stt.SttClient;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 제논(GenOS) 음성 STT — 외부망 호출 가이드(2026-11-05) 연동. 배치 STT 모드 {@code GENON} 의 구현이다.
 *
 * <p><b>스위치가 두 겹이다</b> — 배치가 어느 STT 를 쓰는지는 {@code voice.stt.mode}(MOCK · NPU · GENON)가,
 * GENON 이 실제로 외부망을 부르는지는 이 클래스의 호출 모드(MOCK · REST, 기동값 {@code genon.stt.mode})가 정한다.
 * 둘 다 시뮬레이터가 재시작 없이 바꾼다.</p>
 *
 * <p><b>동시 1건</b> — 키 한도가 1이라 공정(fair) 세마포어로 줄을 세운다. 그래도 같은 키를 쓰는 다른 곳(다른 파드 ·
 * 사람)과 겹치면 제논이 <b>HTTP 200 본문</b>에 '동시 진행 한도' 오류(13040001 · retry_after_ms)를 실어 준다
 * (2026-11-06 실측). 상태 코드만 보면 성공으로 읽으므로 본문을 보고, 알려 준 시간만큼 기다렸다 다시 보낸다.</p>
 *
 * <p><b>내보내지 않는 것</b> — 원래 파일 이름(수용자 이름이 들어 있을 수 있다)은 보내지 않고 {@code audio.wav} 처럼
 * 내용 기준 이름만 붙인다. 전사 텍스트 · 키는 로그에 남기지 않는다(글자 수만). 주소는 기동 설정으로만 정한다 —
 * 런타임에 바꿀 수 있으면 토큰을 엉뚱한 곳으로 보낼 수 있다. 같은 까닭으로 리다이렉트도 따라가지 않는다.</p>
 *
 * <p>multipart 는 JDK HttpClient 로 직접 짠다 — 파일은 {@code ofFile} 로 흘려보내 메모리에 올리지 않고,
 * 길이를 알므로 Content-Length 를 붙여 보낸다.</p>
 */
@Log4j2
@Component
public class GenonSttClient implements SttClient {

    static final String TRANSCRIBE = "/audio/transcribe";
    static final String PROCESS = "/audio/process";
    static final String HEALTH = "/health";
    /** 제논 '동시 진행 한도' 오류 코드 — HTTP 200 본문에 실려 온다. */
    static final String BUSY_CODE = "13040001";
    /** 가이드의 contact_type 값. */
    public static final Set<String> CONTACT_TYPES = Set.of("접견", "전화");
    private static final Pattern CONTACT_ID = Pattern.compile("[A-Za-z0-9가-힣_.:\\-]{1,64}");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 가이드 예시 모양 그대로 — {@code 2026-11-06T10:00:00+09:00}(초까지 · 소수 없음). */
    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    /** 오류 본문은 앞부분만 싣는다. */
    private static final int BODY_CAP = 2000;
    /** 이 위반만 있으면 나눠 보낼 수 있다(PCM 16bit WAV 일 때). 형식 · 빈 파일은 나눠도 안 된다. */
    private static final Set<String> SPLIT_CODES = Set.of("DURATION_LIMIT_EXCEEDED", "SIZE_LIMIT_EXCEEDED");
    /** 제논이 길이 한도로 거절한 조각을 반으로 다시 나누는 하한 — 이보다 짧은 조각이 거절되면 그대로 실패다. */
    static final double RESPLIT_MIN_SEC = 60d;

    /** MOCK 대본 — 가공된 시험 문장이다. MockSttClient 와 같은 까닭으로 개인정보 모양(가짜 번호)을 하나 섞는다. */
    private static final List<String> MOCK_LINES = List.of(
            "여보세요. 제논 STT 모의 응답입니다.",
            "실제 음성을 듣지 않고 정해진 문장을 돌려줍니다.",
            "연락처는 010-1234-5678 로 남겨 주세요.",
            "다음 주 화요일에 면회 가겠습니다.");

    private final GenonSttProperties props;
    private final ObjectMapper om;
    private final HttpClient http;
    private final Semaphore permits;
    private final String baseUrl;
    private volatile GenonSttProperties.Mode callMode;
    /** 마지막 호출 요약 — 화면 표시용. 경로 · 상태 · 시간만 담는다(본문 없음). */
    private volatile Map<String, Object> lastCall = Map.of();

    public GenonSttClient(GenonSttProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.om = objectMapper;
        String b = props.baseUrl() == null ? "" : props.baseUrl().trim();
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.callMode = props.mode() == null ? GenonSttProperties.Mode.MOCK : props.mode();
        this.permits = new Semaphore(Math.max(1, props.maxConcurrency()), true);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.connectTimeoutSec())))
                .followRedirects(HttpClient.Redirect.NEVER)
                // -Dhttps.proxyHost 가 있으면 따른다(없으면 직접). 클러스터 외부망 경로가 아직 확인 전이다
                .proxy(ProxySelector.getDefault())
                .build();
        log.info("[STT:GENON] 호출 모드 {} · {} · 키 {} · 한도 {} · {}초 · 동시 {}건", callMode, baseUrl,
                hasKey() ? "있음" : "없음(REST 불가)", AudioInspector.human(props.maxBytes()),
                props.maxDurationSec(), Math.max(1, props.maxConcurrency()));
    }

    // ---------------------------------------------------------------- 가이드 API

    /** 전사 — {@code POST /audio/transcribe}. 파일 이름의 확장자로도 검사한다. */
    public GenonCall transcribe(File audioFile) {
        return transcribe(audioFile, audioFile.getName(), null);
    }

    /**
     * 전사 — 업로드처럼 원래 이름 · MIME 이 따로 있을 때.
     *
     * @throws AudioValidationException 사전 검증 위반 — 보내지 않았다
     * @throws GenonSttException        호출 실패
     */
    public GenonCall transcribe(File audioFile, String fileName, String declaredMime) {
        AudioInspector.Check check = validate(audioFile.toPath(), fileName, declaredMime);
        return send(TRANSCRIBE, audioFile.toPath(), check, Map.of());
    }

    /**
     * 접견 · 전화 전처리 — {@code POST /audio/process}.
     *
     * @param contactType 접견 · 전화
     * @param occurredAt  발생 시각 — KST 오프셋을 붙여 보낸다({@code 2026-11-06T10:00:00+09:00})
     */
    public GenonCall processContactAudio(File audioFile, String contactId, String contactType, Instant occurredAt) {
        return processContactAudio(audioFile, audioFile.getName(), null, contactId, contactType, occurredAt);
    }

    public GenonCall processContactAudio(File audioFile, String fileName, String declaredMime,
                                         String contactId, String contactType, Instant occurredAt) {
        Map<String, String> fields = contactFields(contactId, contactType, occurredAt);
        AudioInspector.Check check = validate(audioFile.toPath(), fileName, declaredMime);
        return send(PROCESS, audioFile.toPath(), check, fields);
    }

    /**
     * 사전 검증을 건너뛰고 보낸다 — <b>시뮬레이터가 '제논이 직접 거절하는지' 볼 때만</b> 쓴다.
     * 검사 결과는 위반이 있어도 그대로 싣는다.
     */
    public GenonCall sendUnchecked(boolean process, File audioFile, String fileName, String declaredMime,
                                   String contactId, String contactType, Instant occurredAt) {
        Map<String, String> fields = process ? contactFields(contactId, contactType, occurredAt) : Map.of();
        AudioInspector.Check check = inspect(audioFile.toPath(), fileName, declaredMime);
        return send(process ? PROCESS : TRANSCRIBE, audioFile.toPath(), check, fields);
    }

    /** 이 클라이언트의 한도로 검사만 한다(보내지 않음). */
    public AudioInspector.Check inspect(Path file, String fileName, String declaredMime) {
        return AudioInspector.check(file, fileName, declaredMime, props.maxBytes(), props.maxDurationSec());
    }

    // ---------------------------------------------------------------- 긴 음원 — 나눠 보내고 잇기

    /**
     * 조각 하나의 호출 — 화면 진행 표시용. 전사 글은 싣지 않는다(글자 수만).
     *
     * @param no      보낸 순서(1 부터)
     * @param resplit 제논이 길이 한도로 거절해 반으로 다시 나눈 조각인지
     */
    public record ChunkCall(int no, double startSec, double endSec, String mode, int httpStatus, long elapsedMs,
                            long queuedMs, int attempts, int chars, boolean resplit) {
    }

    /**
     * 나눠 보낸 전사.
     *
     * @param result      조각 전사를 순서대로 이은 결과 — 글은 조각마다 줄을 바꿔 잇고, 구간 시각은 원래 음원 기준이다
     * @param chunks      조각별 호출(나누지 않았으면 하나)
     * @param durationSec 원래 음원 길이(초)
     * @param elapsedMs   나누기 · 조각 쓰기 · 보내기 · 잇기 전부
     */
    public record LongTranscript(SttResult result, List<ChunkCall> chunks, double durationSec, long elapsedMs) {

        public boolean split() {
            return chunks.size() > 1;
        }
    }

    /** 진행 알림 — 시뮬레이터가 단계 표시에 쓴다. 기본은 아무것도 하지 않는다. */
    public interface ChunkListener {

        ChunkListener NONE = new ChunkListener() {
        };

        /** 조각을 보내기 직전 — {@code total} 은 지금까지 정해진 조각 수(길이 거절로 다시 나누면 는다). */
        default void sending(int no, int total, double startSec, double endSec) {
        }

        /** 조각 하나를 받았다. */
        default void done(ChunkCall call, int total) {
        }
    }

    /** 보낼 조각 — 다시 나눈 것인지 함께 든다. */
    private record Todo(PcmWav.Piece piece, boolean resplit) {
    }

    /**
     * 긴 음원 전사 — {@code /audio/transcribe} 를 조각마다 차례로 불러 잇는다. 한도 안이면 한 번만 부른다.
     *
     * <p><b>나누는 것은 PCM 16bit WAV 뿐</b>이고 위반이 길이 · 크기 한도뿐일 때만이다. 조각은 {@link PcmWav#plan} 이 정한다
     * (개수 최소 · 길이 고르게 · 경계는 가장 조용한 20ms). 조각 파일은 {@code workDir} 에 잠깐 썼다가 받자마자 지운다.
     * 원래 이름은 이번에도 보내지 않는다(조각마다 {@code audio.wav}).</p>
     *
     * <p><b>딱 600초 조각은 실측한 적이 없다</b>(570초 통과 · 605초 거절, 2026-11-09). 제논이 한 조각을 길이 한도로 거절하면
     * 그 조각만 반으로 나눠 다시 보낸다 — {@value #RESPLIT_MIN_SEC}초보다 짧은 조각이 거절되면 그대로 실패다.</p>
     *
     * @param wav      음원 — 이름은 보지 않고 내용으로 검사한다
     * @param workDir  조각을 잠깐 쓸 폴더(없으면 만든다)
     * @param listener 진행 알림 — null 이면 알리지 않는다
     * @throws AudioValidationException 나눌 수 없는 위반(형식 · 빈 파일 · PCM 16bit 가 아닌 WAV) — 보내지 않았다
     * @throws GenonSttException        호출 실패 — 나눴으면 메시지 앞에 {@code 조각 i/n} 이 붙는다(종류 · 코드는 그대로)
     */
    public LongTranscript transcribeLong(Path wav, Path workDir, ChunkListener listener) throws IOException {
        ChunkListener l = listener == null ? ChunkListener.NONE : listener;
        long t0 = System.nanoTime();
        AudioInspector.Check whole = inspect(wav, null, null);
        if (whole.ok()) {
            double sec = round3(whole.info().durationSec() == null ? 0d : whole.info().durationSec());
            l.sending(1, 1, 0d, sec);
            GenonCall call = send(TRANSCRIBE, wav, whole, Map.of());
            SttResult r = toSttResult(call);
            ChunkCall c = chunkCall(1, 0d, sec, call, r, false);
            l.done(c, 1);
            return new LongTranscript(r, List.of(c), r.duration(), ms(t0));
        }
        if (!splittable(whole)) {
            throw rejected(whole);
        }
        PcmWav.Info info;
        try {
            info = PcmWav.read(wav);
        } catch (IllegalArgumentException e) {
            log.warn("[STT:GENON] 나눌 수 없는 WAV — {}", e.getMessage());
            throw rejected(whole);
        }
        List<PcmWav.Piece> plan = PcmWav.plan(wav, info, chunkMaxSec(info), props.chunkSearchSec());
        log.info("[STT:GENON] 긴 음원 {}초 — 조각 {}개({})로 나눠 보냄", round1(info.durationSec()), plan.size(),
                String.join(" · ", plan.stream().map(p -> round1(p.durationSec()) + "초").toList()));
        Files.createDirectories(workDir);

        Deque<Todo> todo = new ArrayDeque<>();
        plan.forEach(p -> todo.add(new Todo(p, false)));
        int total = plan.size();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        List<ChunkCall> calls = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<SttResult.Segment> segments = new ArrayList<>();
        String engine = null;
        while (!todo.isEmpty()) {
            Todo t = todo.pollFirst();
            PcmWav.Piece p = t.piece();
            int no = calls.size() + 1;
            Path part = workDir.resolve("genon-part-" + tag + "-" + no + ".wav");
            GenonCall call;
            try {
                PcmWav.write(wav, info, p, part);
                AudioInspector.Check check = validate(part, null, null);
                l.sending(no, total, p.startSec(), p.endSec());
                call = send(TRANSCRIBE, part, check, Map.of());
            } catch (GenonSttException e) {
                if (rejectedForDuration(e) && p.durationSec() >= RESPLIT_MIN_SEC) {
                    List<PcmWav.Piece> two = PcmWav.halve(wav, info, p, props.chunkSearchSec());
                    todo.addFirst(new Todo(two.get(1), true));
                    todo.addFirst(new Todo(two.get(0), true));
                    total++;
                    log.warn("[STT:GENON] 조각 {}/{}({}초)이 길이 한도로 거절됨 — 반으로 나눠 다시 보냄", no, total - 1,
                            round1(p.durationSec()));
                    continue;
                }
                throw inChunk(e, no, total);
            } finally {
                Files.deleteIfExists(part);
            }
            SttResult r = toSttResult(call);
            engine = engine == null ? r.engine() : engine;
            if (!r.text().isBlank()) {
                texts.add(r.text().strip());
            }
            for (SttResult.Segment s : r.segments()) {
                segments.add(new SttResult.Segment(segments.size(), round3(s.start() + p.startSec()),
                        round3(s.end() + p.startSec()), s.text()));
            }
            ChunkCall c = chunkCall(no, p.startSec(), p.endSec(), call, r, t.resplit());
            calls.add(c);
            l.done(c, total);
        }
        SttResult merged = new SttResult(String.join("\n", texts), engine, round1(info.durationSec()), false, null,
                segments);
        log.info("[STT:GENON] 긴 음원 전사 — 조각 {}개 · {}자 · 구간 {}개 · {}ms", calls.size(), merged.charCount(),
                segments.size(), ms(t0));
        return new LongTranscript(merged, List.copyOf(calls), merged.duration(), ms(t0));
    }

    /** 나눠 보낼 수 있는 위반인지 — 길이 · 크기 한도뿐이고 내용이 WAV(PCM 16bit 인지는 머리를 읽어 본다). */
    static boolean splittable(AudioInspector.Check check) {
        return !check.ok() && "wav".equals(check.info().format())
                && check.violations().stream().allMatch(v -> SPLIT_CODES.contains(v.code()));
    }

    /** 조각 상한(초) — 설정 · 길이 한도 · 크기 한도(머리 44B 를 뺀 표본 바이트) 중 가장 작은 것. */
    double chunkMaxSec(PcmWav.Info info) {
        double bySize = (double) (props.maxBytes() - 44) / info.bytesPerSec();
        double sec = Math.min(Math.min(props.chunkMaxSec(), props.maxDurationSec()), bySize);
        if (!(sec >= 1d)) {
            throw new GenonSttException(GenonSttException.Kind.CONFIG,
                    "제논 STT 분할 설정 오류 — 조각 상한이 1초 미만(genon.stt.chunk-max-sec · max-bytes 확인)");
        }
        return sec;
    }

    private static boolean rejectedForDuration(GenonSttException e) {
        return e.kind() == GenonSttException.Kind.REJECTED && e.errorCode() != null
                && e.errorCode().contains("duration");
    }

    /** 조각 실패 — 몇 번째 조각인지 앞에 붙인다. 종류 · 상태 · 코드는 그대로다(분류 낱말도 원래 메시지에 남는다). */
    private static GenonSttException inChunk(GenonSttException e, int no, int total) {
        if (total <= 1) {
            return e;
        }
        return new GenonSttException(e.kind(), "조각 %d/%d — %s".formatted(no, total, e.getMessage()),
                e.httpStatus(), e.errorCode(), e.upstreamBody(), e.elapsedMs(), e.attempts(), e);
    }

    private static ChunkCall chunkCall(int no, double startSec, double endSec, GenonCall call, SttResult r,
                                       boolean resplit) {
        return new ChunkCall(no, startSec, endSec, call.mode(), call.status(), call.elapsedMs(), call.queuedMs(),
                call.attempts(), r.charCount(), resplit);
    }

    // ---------------------------------------------------------------- 배치(SttClient)

    /**
     * 배치 ANALYZE — 파일 이름은 믿지 않는다(ESB 파일은 {@code .DAT}). 내용(매직 넘버)으로만 검사하고 보낸다.
     * 위반이면 {@link AudioValidationException} — 그 건의 ANALYZE 실패 사유가 된다.
     */
    @Override
    public SttResult transcribe(VoiceFile file) {
        AudioInspector.Check check = validate(file.path(), null, null);
        GenonCall call = send(TRANSCRIBE, file.path(), check, Map.of());
        SttResult r = toSttResult(call);
        log.info("[STT:GENON] {} — {}자 · {}초 · 구간 {}개 · {}ms{}", file.path().getFileName(), r.charCount(),
                r.duration(), r.segments().size(), call.elapsedMs(), "MOCK".equals(call.mode()) ? " (MOCK)" : "");
        return r;
    }

    @Override
    public String mode() {
        return "GENON";
    }

    /**
     * 제논 응답 → 배치 STT 결과. {@code start_ms · end_ms}(실수 · 밀리초)를 초로 바꾼다.
     * 전처리 응답이면 {@code input.speech_results[0].segments} 를 읽는다. 전문이 비고 구간만 있으면 구간을 잇는다.
     */
    public static SttResult toSttResult(GenonCall call) {
        JsonNode body = call.body();
        JsonNode arr = body.path("segments");
        if (!arr.isArray()) {
            arr = body.path("input").path("speech_results").path(0).path("segments");
        }
        List<SttResult.Segment> segments = new ArrayList<>();
        if (arr.isArray()) {
            int i = 0;
            for (JsonNode s : arr) {
                segments.add(new SttResult.Segment(i++, round3(s.path("start_ms").asDouble(0d) / 1000d),
                        round3(s.path("end_ms").asDouble(0d) / 1000d), s.path("text").asText("")));
            }
        }
        String text = body.path("text").asText("");
        if (text.isBlank() && !segments.isEmpty()) {
            text = String.join("\n", segments.stream().map(s -> s.text().strip()).filter(t -> !t.isEmpty()).toList());
        }
        Double measured = call.audio() == null ? null : call.audio().info().durationSec();
        double duration = measured != null ? measured
                : (segments.isEmpty() ? 0d : segments.get(segments.size() - 1).end());
        String engine = "MOCK".equals(call.mode()) ? "GENON(MOCK)" : "GENON";
        return new SttResult(text, engine, round1(duration), false, null, segments);
    }

    // ---------------------------------------------------------------- 모드 · 상태

    public GenonSttProperties.Mode callMode() {
        return callMode;
    }

    /**
     * 호출 모드를 바꾼다(MOCK · REST). 키가 없으면 REST 로 바꾸지 않는다 — 바꿔 놓고 전건 401 을 보는 것보다
     * 여기서 이유를 말하는 편이 낫다.
     *
     * @return 바뀌기 전 값
     */
    public String setCallMode(String value) {
        GenonSttProperties.Mode m;
        try {
            m = GenonSttProperties.Mode.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("제논 호출 모드는 MOCK · REST 중 하나다: " + value);
        }
        if (m == GenonSttProperties.Mode.REST && !hasKey()) {
            throw new IllegalStateException("GENON_STT_API_KEY 가 비어 있어 REST 로 바꿀 수 없다 — 배포 Secret 에 키를 넣고 다시 띄울 것");
        }
        String before = callMode.name();
        callMode = m;
        log.info("[STT:GENON] 호출 모드 {} → {}", before, m);
        return before;
    }

    /** 배치 화면의 STT 단계 주소 표시. */
    public String endpoint() {
        return callMode == GenonSttProperties.Mode.MOCK
                ? "제논 STT — 호출 MOCK(외부망 안 부름 · 제논 모양 응답)"
                : "제논 STT — " + baseUrl + TRANSCRIBE + (hasKey() ? "" : " (키 없음)");
    }

    public boolean hasKey() {
        return props.apiKey() != null && !props.apiKey().isBlank();
    }

    /** 화면 표시용 — 앞 4 · 뒤 4 글자만. 짧으면 전부 가린다. */
    public String maskedKey() {
        if (!hasKey()) {
            return "";
        }
        String k = props.apiKey().trim();
        return k.length() < 16 ? "*".repeat(k.length())
                : k.substring(0, 4) + "*".repeat(k.length() - 8) + k.substring(k.length() - 4);
    }

    /** 상태 — 화면 상단 · 시뮬레이터 status 가 쓴다. 키 원문은 없다. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", callMode.name());
        m.put("configuredMode", props.mode() == null ? "MOCK" : props.mode().name());
        m.put("baseUrl", baseUrl);
        m.put("apiKeySet", hasKey());
        m.put("apiKeyMasked", maskedKey());
        String exp = props.keyExpiresOn() == null ? "" : props.keyExpiresOn().trim();
        m.put("keyExpiresOn", exp);
        if (!exp.isEmpty()) {
            try {
                m.put("keyDaysLeft", ChronoUnit.DAYS.between(LocalDate.now(KST), LocalDate.parse(exp)));
            } catch (DateTimeParseException e) {
                m.put("keyDaysLeft", null);
            }
        }
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxBytes", props.maxBytes());
        limits.put("maxDurationSec", props.maxDurationSec());
        limits.put("maxConcurrency", Math.max(1, props.maxConcurrency()));
        limits.put("formats", List.of("WAV", "MP3", "M4A"));
        limits.put("chunkMaxSec", props.chunkMaxSec());
        limits.put("chunkSearchSec", props.chunkSearchSec());
        m.put("limits", limits);
        m.put("timeoutSec", props.timeoutSec());
        m.put("queueTimeoutSec", props.queueTimeoutSec());
        m.put("busyRetries", props.busyRetries());
        m.put("inFlight", Math.max(1, props.maxConcurrency()) - permits.availablePermits());
        m.put("waiting", permits.getQueueLength());
        m.put("lastCall", lastCall);
        return m;
    }

    /** 연결 확인 결과. */
    public record Probe(Boolean reachable, Integer httpStatus, Boolean tokenValid, long elapsedMs, String message) {
    }

    /**
     * 연결 확인 — {@code GET /health}. 이 경로는 토큰을 검사한다(없으면 · 틀리면 401, 2026-11-06 실측)라
     * 연결과 토큰을 한 번에 본다. 호출 모드와 상관없이 실제로 부른다(그게 이 버튼의 뜻이다).
     *
     * <p>진행 중인 호출이 있으면 건너뛴다 — 동시 1건 한도를 확인 요청이 잡아먹으면 진짜 호출이 '동시 한도' 를 맞는다.</p>
     */
    public Probe probe() {
        if (!permits.tryAcquire()) {
            return new Probe(null, null, null, 0, "진행 중인 호출이 있어 건너뜀 — 동시 1건 한도");
        }
        long t0 = System.nanoTime();
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + HEALTH))
                    .timeout(Duration.ofSeconds(Math.max(5, props.connectTimeoutSec())))
                    .GET();
            if (hasKey()) {
                rb.header("Authorization", "Bearer " + props.apiKey().trim());
            }
            HttpResponse<byte[]> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            int s = res.statusCode();
            JsonNode body = parse(res.body());
            long ms = ms(t0);
            if (s == 200 && isEnvelopeError(body)) {
                return new Probe(true, s, true, ms, "연결됨 · 토큰 유효 · 제논 사용 중(" + errorCodeOf(body) + ")");
            }
            if (s == 200) {
                return new Probe(true, s, hasKey() ? Boolean.TRUE : null, ms,
                        hasKey() ? "연결 정상 · 토큰 유효" : "연결 정상 · 키 없음(토큰 미확인)");
            }
            if (s == 401 || s == 403) {
                String code = errorCodeOf(body);
                return new Probe(true, s, false, ms, "연결됨 · 토큰 거절(" + (code == null ? "HTTP " + s : code) + ")");
            }
            return new Probe(true, s, null, ms, "연결됨 · HTTP " + s);
        } catch (IOException e) {
            return new Probe(false, null, null, ms(t0), "연결 실패 — " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + cap(e.getMessage(), 160)));
        } catch (IllegalArgumentException e) {
            return new Probe(false, null, null, ms(t0), "주소 설정 오류 — genon.stt.base-url 확인");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Probe(false, null, null, ms(t0), "확인 중 중단됨");
        } finally {
            permits.release();
        }
    }

    // ---------------------------------------------------------------- 보내기

    private AudioInspector.Check validate(Path file, String fileName, String declaredMime) {
        AudioInspector.Check check = inspect(file, fileName, declaredMime);
        if (!check.ok()) {
            throw rejected(check);
        }
        return check;
    }

    private static AudioValidationException rejected(AudioInspector.Check check) {
        log.warn("[STT:GENON] 사전 검증에서 막음(보내지 않음) — {} [{}]", check.violationText(), check.summary());
        return new AudioValidationException(check);
    }

    /** 가이드 필드 셋 — 값은 multipart 헤더에 들어가므로 줄바꿈 · 따옴표가 섞이지 않게 모양을 먼저 본다. */
    static Map<String, String> contactFields(String contactId, String contactType, Instant occurredAt) {
        String id = contactId == null ? "" : contactId.trim();
        if (!CONTACT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("contact_id 는 영문 · 숫자 · 한글 · _ . : - 로 1~64자: " + contactId);
        }
        String type = contactType == null ? "" : contactType.trim();
        if (!CONTACT_TYPES.contains(type)) {
            throw new IllegalArgumentException("contact_type 은 접견 · 전화 중 하나: " + contactType);
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurred_at(발생 시각)이 비었다");
        }
        Map<String, String> f = new LinkedHashMap<>();
        f.put("contact_id", id);
        f.put("contact_type", type);
        f.put("occurred_at", OCCURRED_AT.format(occurredAt.truncatedTo(ChronoUnit.SECONDS).atZone(KST)));
        return f;
    }

    /** 차례를 얻고(동시 1건) 모드대로 보낸다. 성공 · 실패 모두 마지막 호출 요약을 남긴다. */
    GenonCall send(String path, Path file, AudioInspector.Check check, Map<String, String> fields) {
        GenonSttProperties.Mode mode = callMode;
        if (mode == GenonSttProperties.Mode.REST && !hasKey()) {
            throw remember(path, new GenonSttException(GenonSttException.Kind.CONFIG,
                    "제논 STT 설정 오류 — GENON_STT_API_KEY 가 비어 있다(보내지 않음)"));
        }
        if (mode == GenonSttProperties.Mode.REST && !(baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))) {
            throw remember(path, new GenonSttException(GenonSttException.Kind.CONFIG,
                    "제논 STT 설정 오류 — genon.stt.base-url 이 http(s) 주소가 아니다(보내지 않음)"));
        }
        long q0 = System.nanoTime();
        boolean got;
        try {
            got = permits.tryAcquire(Math.max(1, props.queueTimeoutSec()), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw remember(path, new GenonSttException(GenonSttException.Kind.QUEUE_TIMEOUT,
                    "제논 STT 차례 대기 중 중단됨"));
        }
        if (!got) {
            throw remember(path, new GenonSttException(GenonSttException.Kind.QUEUE_TIMEOUT,
                    "제논 STT 차례 대기 타임아웃 — %d초(동시 %d건 한도 · 앞 건 처리 중)"
                            .formatted(props.queueTimeoutSec(), Math.max(1, props.maxConcurrency()))));
        }
        long queuedMs = ms(q0);
        try {
            GenonCall call = mode == GenonSttProperties.Mode.MOCK
                    ? mock(path, check, fields, queuedMs)
                    : rest(path, file, check, fields, queuedMs);
            log.info("[STT:GENON] {} {} — HTTP {} · {}ms · 시도 {} · 대기 {}ms · {}", call.mode(), path,
                    call.status(), call.elapsedMs(), call.attempts(), queuedMs, check.summary());
            lastCall = summary(call.mode(), path, call.status(), null, call.elapsedMs(), call.attempts());
            return call;
        } catch (GenonSttException e) {
            log.warn("[STT:GENON] {} {} 실패 — {} · {}", mode, path, e.kind(), e.getMessage());
            throw remember(path, e);
        } finally {
            permits.release();
        }
    }

    private GenonCall rest(String path, Path file, AudioInspector.Check check, Map<String, String> fields,
                           long queuedMs) {
        String boundary = "----GenonStt" + UUID.randomUUID().toString().replace("-", "");
        long t0 = System.nanoTime();
        int attempts = 0;
        while (true) {
            attempts++;
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(Math.max(1, props.timeoutSec())))
                    .header("Authorization", "Bearer " + props.apiKey().trim())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Accept", "application/json")
                    .POST(multipart(boundary, fields, file, check))
                    .build();
            HttpResponse<byte[]> res;
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            } catch (HttpConnectTimeoutException e) {
                throw new GenonSttException(GenonSttException.Kind.CONNECTION,
                        "제논 STT 연결 타임아웃 — %d초 안에 붙지 못함".formatted(props.connectTimeoutSec()),
                        0, null, null, ms(t0), attempts, e);
            } catch (HttpTimeoutException e) {
                throw new GenonSttException(GenonSttException.Kind.TIMEOUT,
                        "제논 STT 응답 타임아웃 — %d초".formatted(props.timeoutSec()),
                        0, null, null, ms(t0), attempts, e);
            } catch (IOException e) {
                throw new GenonSttException(GenonSttException.Kind.CONNECTION,
                        "제논 STT 연결 실패 — " + e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : ": " + cap(e.getMessage(), 160)),
                        0, null, null, ms(t0), attempts, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GenonSttException(GenonSttException.Kind.CONNECTION, "제논 STT 호출 중 중단됨",
                        0, null, null, ms(t0), attempts, e);
            }
            int status = res.statusCode();
            JsonNode body = parse(res.body());
            String code = errorCodeOf(body);
            boolean envelope = isEnvelopeError(body);

            // 동시 한도 — 제논은 200 본문에 싣는다. 429 · retryable 표시도 같은 뜻으로 받는다
            if (status == 429 || BUSY_CODE.equals(code)
                    || (envelope && body.path("msg_params").path("retryable").asBoolean(false))) {
                String label = code == null ? "HTTP " + status : code;
                if (attempts <= props.busyRetries()) {
                    long wait = Math.min(Math.max(body.path("msg_params").path("retry_after_ms").asLong(1000L), 200L),
                            Math.max(200L, props.busyRetryMaxWaitMs()));
                    log.warn("[STT:GENON] 동시 한도({}) — {}ms 뒤 다시 보냄 {}/{}", label, wait, attempts, props.busyRetries());
                    sleep(wait, t0, attempts);
                    continue;
                }
                throw new GenonSttException(GenonSttException.Kind.BUSY,
                        "제논 STT 동시 한도 — %s, %d번 보냈으나 계속 사용 중".formatted(label, attempts),
                        status, code, cap(body.toString(), BODY_CAP), ms(t0), attempts, null);
            }
            if (status == 200 && envelope) {
                String hint = GenonSttException.HINTS.get(code);
                throw new GenonSttException(GenonSttException.Kind.UPSTREAM,
                        "제논 STT 오류 응답 — " + code + (hint == null ? "" : "(" + hint + ")"),
                        status, code, cap(body.toString(), BODY_CAP), ms(t0), attempts, null);
            }
            if (status == 200) {
                if (!body.isObject()) {
                    throw new GenonSttException(GenonSttException.Kind.UPSTREAM,
                            "제논 STT 응답이 JSON 객체가 아님 — HTTP 200", status, null,
                            cap(body.asText(""), BODY_CAP), ms(t0), attempts, null);
                }
                return new GenonCall("REST", path, status, ms(t0), queuedMs, attempts, body, check);
            }
            throw httpError(status, body, ms(t0), attempts);
        }
    }

    private GenonSttException httpError(int status, JsonNode body, long elapsedMs, int attempts) {
        String code = errorCodeOf(body);
        String hint = code == null ? null : GenonSttException.HINTS.get(code);
        String tail = (code == null ? "" : " " + code) + (hint == null ? "" : "(" + hint + ")");
        String upstream = cap(body.isTextual() ? body.asText() : body.toString(), BODY_CAP);
        if (status == 401 || status == 403) {
            return new GenonSttException(GenonSttException.Kind.AUTH, "제논 STT 인증 실패 — HTTP " + status + tail,
                    status, code, upstream, elapsedMs, attempts, null);
        }
        if (status >= 400 && status < 500 && status != 404) {
            return new GenonSttException(GenonSttException.Kind.REJECTED, "제논 STT 거절 — HTTP " + status + tail,
                    status, code, upstream, elapsedMs, attempts, null);
        }
        return new GenonSttException(GenonSttException.Kind.UPSTREAM, "제논 STT 서버 오류 — HTTP " + status + tail,
                status, code, upstream, elapsedMs, attempts, null);
    }

    /** 텍스트 필드 → 오디오 파일 순서. 텍스트 값은 UTF-8 그대로(가이드 예시 '전화' 가 그렇게 받아졌다). */
    private static HttpRequest.BodyPublisher multipart(String boundary, Map<String, String> fields, Path file,
                                                       AudioInspector.Check check) {
        StringBuilder head = new StringBuilder();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            head.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(f.getKey()).append("\"\r\n\r\n")
                    .append(f.getValue()).append("\r\n");
        }
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"audio\"; filename=\"").append(check.uploadName())
                .append("\"\r\n")
                .append("Content-Type: ").append(check.contentType()).append("\r\n\r\n");
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        try {
            return HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofByteArray(head.toString().getBytes(StandardCharsets.UTF_8)),
                    HttpRequest.BodyPublishers.ofFile(file),
                    HttpRequest.BodyPublishers.ofByteArray(tail));
        } catch (FileNotFoundException e) {
            throw new GenonSttException(GenonSttException.Kind.CONFIG,
                    "제논 STT 보낼 파일이 없다 — " + file.getFileName(), 0, null, null, 0, 0, e);
        }
    }

    /**
     * MOCK — 네트워크 없이 제논과 같은 모양을 돌려준다. 대본을 재생 시간에 고르게 나눠 구간을 만든다.
     *
     * <p>전처리({@code /audio/process})의 {@code speech_results[].segments} 는 실측 때 사인파라 비어 있었다 —
     * 음성이면 전사와 같은 모양({@code start_ms · end_ms · text})일 것으로 보고 채운다.</p>
     */
    private GenonCall mock(String path, AudioInspector.Check check, Map<String, String> fields, long queuedMs) {
        long t0 = System.nanoTime();
        Double measured = check.info().durationSec();
        double sec = measured != null && measured > 0 ? measured : MOCK_LINES.size() * 4.5;
        ArrayNode segs = om.createArrayNode();
        double step = sec / MOCK_LINES.size();
        for (int i = 0; i < MOCK_LINES.size(); i++) {
            ObjectNode s = segs.addObject();
            s.put("start_ms", round1(i * step * 1000d));
            s.put("end_ms", round1((i + 1) * step * 1000d));
            s.put("text", MOCK_LINES.get(i));
        }
        ObjectNode body = om.createObjectNode();
        if (PROCESS.equals(path)) {
            body.put("function", "contact_risk_analysis");
            ObjectNode input = body.putObject("input");
            ObjectNode rec = input.putArray("contact_records").addObject();
            fields.forEach(rec::put);
            ObjectNode sr = input.putArray("speech_results").addObject();
            sr.put("contact_id", fields.get("contact_id"));
            sr.set("segments", segs);
            sr.putArray("prosody_events");
            sr.put("prosody_status", "not_run");
            sr.putArray("audio_events");
            sr.put("audio_status", "analyzed");
            sr.put("duration_ms", round1(sec * 1000d));
        } else {
            body.put("text", String.join(" ", MOCK_LINES));
            body.set("segments", segs);
        }
        return new GenonCall("MOCK", path, 200, ms(t0), queuedMs, 1, body, check);
    }

    // ---------------------------------------------------------------- 공통

    /** 제논 공통 오류 봉투 — {@code {"code":1,"error_code":"13040001","errMsg":…,"msg_params":{…}}}. 성공 본문에는 없다. */
    static boolean isEnvelopeError(JsonNode body) {
        return body != null && body.isObject() && (body.hasNonNull("error_code")
                || (body.path("code").isNumber() && body.path("code").asInt() != 0 && body.has("errMsg")));
    }

    /** 오류 코드 — 봉투 error_code · 400 계열 error · 그 밖의 detail · 텍스트 본문(401 'Invalid token') 순. */
    static String errorCodeOf(JsonNode body) {
        if (body == null || body.isMissingNode() || body.isNull()) {
            return null;
        }
        if (body.isTextual()) {
            String t = body.asText().strip();
            return t.isEmpty() ? null : cap(t, 80);
        }
        for (String k : List.of("error_code", "error", "detail", "message")) {
            JsonNode v = body.get(k);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) {
                return cap(v.asText().strip(), 80);
            }
        }
        return null;
    }

    /** 본문을 JSON 으로 읽는다. JSON 이 아니면(401 은 텍스트) 텍스트 노드로. */
    private JsonNode parse(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return TextNode.valueOf("");
        }
        try {
            return om.readTree(raw);
        } catch (IOException e) {
            return TextNode.valueOf(cap(new String(raw, StandardCharsets.UTF_8), BODY_CAP));
        }
    }

    private void sleep(long ms, long t0, int attempts) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GenonSttException(GenonSttException.Kind.BUSY, "제논 STT 동시 한도 대기 중 중단됨",
                    200, BUSY_CODE, null, ms(t0), attempts, e);
        }
    }

    private GenonSttException remember(String path, GenonSttException e) {
        lastCall = summary(callMode.name(), path, e.httpStatus(), e.kind().name(), e.elapsedMs(), e.attempts());
        return e;
    }

    private static Map<String, Object> summary(String mode, String path, int status, String failure,
                                               long elapsedMs, int attempts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", java.time.ZonedDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime().toString());
        m.put("mode", mode);
        m.put("path", path);
        m.put("httpStatus", status);
        m.put("ok", failure == null);
        if (failure != null) {
            m.put("failure", failure);
        }
        m.put("elapsedMs", elapsedMs);
        m.put("attempts", attempts);
        return m;
    }

    private static long ms(long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000L;
    }

    private static String cap(String s, int n) {
        return s == null ? null : (s.length() <= n ? s : s.substring(0, n) + "…");
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }

    private static double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
