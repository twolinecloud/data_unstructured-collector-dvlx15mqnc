package egovframework.unstructured.collector.voice.stt.genon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 제논 STT — 사전 검증(20MB · 600초 · 포맷)과 호출(MOCK · REST)을 고정한다.
 *
 * <p>REST 는 진짜 제논 대신 <b>이 JVM 안의 HTTP 서버</b>를 부른다 — 소켓 · multipart · 헤더가 실제로 오간다.
 * 가짜 서버의 응답 모양은 2026-11-06 실측(성공 · 400 · 401 · HTTP 200 본문의 '동시 진행 한도')을 그대로 옮겼다.
 * 진짜 제논은 {@code GENON_STT_LIVE=true} 와 {@code GENON_STT_API_KEY} 가 있을 때만 부른다(기본 건너뜀).</p>
 */
class SttValidationAndCallTest {

    private static final long MAX = 20L * 1024 * 1024;

    private static final String OK_BODY = """
            {"text":"안녕하세요 면회 갈게요","segments":[
              {"start_ms":100.0,"end_ms":1500.0,"text":"안녕하세요"},
              {"start_ms":1500.0,"end_ms":3000.0,"text":"면회 갈게요"}]}""";

    /** 2026-11-06 실측 — 같은 키로 겹친 두 번째 호출이 <b>HTTP 200</b> 으로 받은 본문(대기만 50ms 로 줄임). */
    private static final String BUSY_BODY = """
            {"code":1,"error_code":"13040001",
             "errMsg":"[13040001] 동시 진행 대화 수가 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.",
             "msg_params":{"scope":"k:2921","metric":"cc","limit":1,"current":1,"retryable":true,"retry_after_ms":50}}""";

    /** 2026-11-06 실측 — /audio/process 성공(사인파 입력이라 segments 가 비었다). */
    private static final String PROCESS_BODY = """
            {"function":"contact_risk_analysis","input":{
              "contact_records":[{"contact_id":"CONTACT-SIM-001","contact_type":"접견","occurred_at":"2026-11-06T10:00:00+09:00"}],
              "speech_results":[{"contact_id":"CONTACT-SIM-001","segments":[],"prosody_events":[],"prosody_status":"not_run",
                                 "audio_events":[],"audio_status":"analyzed","duration_ms":3000.0}]}}""";

    @TempDir
    Path dir;

    private final ObjectMapper om = new ObjectMapper();
    private HttpServer server;
    private ExecutorService serverPool;
    private final List<Captured> received = new CopyOnWriteArrayList<>();
    private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
    private volatile long handlerDelayMs;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    /** 가짜 제논이 받은 요청. */
    record Captured(String method, String path, String auth, String contentType, byte[] body) {
    }

    /** 가짜 제논이 줄 응답 — 줄을 세워 두면 차례로 쓰고, 비면 성공 본문. */
    record Reply(int status, String contentType, String body) {
        Reply(int status, String body) {
            this(status, "application/json", body);
        }
    }

    @BeforeEach
    void startFakeGenon() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newCachedThreadPool();
        server.setExecutor(serverPool);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopFakeGenon() {
        server.stop(0);
        serverPool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            received.add(new Captured(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestHeaders().getFirst("Authorization"),
                    ex.getRequestHeaders().getFirst("Content-Type"), body));
            if (handlerDelayMs > 0) {
                try {
                    Thread.sleep(handlerDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            Reply r = replies.isEmpty() ? new Reply(200, OK_BODY) : replies.poll();
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", r.contentType());
            ex.sendResponseHeaders(r.status(), out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        } finally {
            inFlight.decrementAndGet();
            ex.close();
        }
    }

    // ------------------------------------------------------------------ 사전 검증

    @Test
    @DisplayName("20MB 초과 — 보내지 않고 막는다(딱 20MiB 는 통과 · 길이 한도와 섞지 않는다)")
    void blocksOverSize() throws Exception {
        Path over = sparseWav("over.wav", 48_000, 2, MAX + 4 - 44, MAX + 4);
        Path exact = sparseWav("exact.wav", 48_000, 2, MAX - 44, MAX);

        AudioInspector.Check o = check(over, "over.wav", "audio/wav");
        assertThat(codes(o.violations())).containsExactly("SIZE_LIMIT_EXCEEDED");
        assertThat(o.info().durationSec()).as("약 109초 — 크기만 넘는다").isLessThan(600d);
        assertThat(o.violations().get(0).message()).contains("20,971,524 B").contains("20,971,520 B");
        assertThat(check(exact, "exact.wav", "audio/wav").ok()).as("상한과 같으면 통과").isTrue();

        GenonSttClient rest = client(GenonSttProperties.Mode.REST, "test-key", 10);
        AudioValidationException e = assertThrows(AudioValidationException.class, () -> rest.transcribe(over.toFile()));
        assertThat(e.check().violates("SIZE_LIMIT_EXCEEDED")).isTrue();
        assertThat(received).as("제논으로 아무것도 보내지 않았다").isEmpty();
    }

    @Test
    @DisplayName("600초 초과 — WAV · MP3 · M4A 모두 헤더로 재서 막는다(딱 600초 통과 · 잘린 WAV 는 실제 길이)")
    void blocksOverDuration() throws Exception {
        Path wav610 = sparseWav("610.wav", 16_000, 1, 16_000L * 2 * 610, 44 + 16_000L * 2 * 610);
        Path wav600 = sparseWav("600.wav", 16_000, 1, 16_000L * 2 * 600, 44 + 16_000L * 2 * 600);
        Path cut = sparseWav("cut.wav", 16_000, 1, 16_000L * 2 * 610, 44 + 32_000);
        Path mp3 = write("610.mp3", AudioSampleGenerator.silentMp3(610));
        Path m4a = write("610.m4a", AudioSampleGenerator.silentM4a(610));

        assertBlockedByDuration(wav610, "wav:data");
        assertBlockedByDuration(mp3, "mp3:cbr");
        assertBlockedByDuration(m4a, "mp4:mvhd");

        AudioInspector.Check at600 = check(wav600, "600.wav", null);
        assertThat(at600.ok()).as("상한과 같으면 통과").isTrue();
        assertThat(at600.info().durationSec()).isEqualTo(600.0);

        // 헤더만 610초 · 실제 데이터 1초 — 제논도 실제 길이로 보고 받았다(실측 200). 막지 않고 경고만 남긴다
        AudioInspector.Check c = check(cut, "cut.wav", null);
        assertThat(c.ok()).isTrue();
        assertThat(c.info().durationSec()).isEqualTo(1.0);
        assertThat(codes(c.warnings())).contains("HEADER_TRUNCATED");

        // 배치 경로(이름 · MIME 없음) — 실패 사유가 DATA 로 분류되는 문장이어야 한다
        GenonSttClient rest = client(GenonSttProperties.Mode.REST, "test-key", 10);
        AudioValidationException e = assertThrows(AudioValidationException.class,
                () -> rest.transcribe(new VoiceFile(null, wav610, Files.size(wav610), "wav", true)));
        assertThat(e.getMessage()).contains("재생 시간 610.0초");
        assertThat(errStack(e)).startsWith("[DATA]");
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("미지원 포맷 — 확장자 · MIME · 내용 중 하나라도 WAV · MP3 · M4A 가 아니면 막는다")
    void blocksUnsupportedFormat() throws Exception {
        Path ogg = write("a.ogg", AudioSampleGenerator.fakeOgg());
        Path txt = write("a.txt", "오디오 아님".getBytes(StandardCharsets.UTF_8));
        Path wav = write("a.wav", AudioSampleGenerator.sineWav(1, 16_000, 440d));
        Path mp3InWav = write("b.wav", AudioSampleGenerator.silentMp3(1));
        Path noExt = write("recording", AudioSampleGenerator.sineWav(1, 16_000, 440d));
        Path empty = write("empty.wav", new byte[0]);

        assertThat(codes(check(ogg, "a.ogg", "audio/ogg").violations())).containsOnly("UNSUPPORTED_FORMAT");
        assertThat(codes(check(txt, "a.txt", "text/plain").violations())).containsOnly("UNSUPPORTED_FORMAT");
        assertThat(codes(check(wav, "a.txt", null).violations())).as("확장자만 틀림").containsExactly("UNSUPPORTED_FORMAT");
        assertThat(codes(check(wav, "a.wav", "text/plain").violations())).as("MIME 만 틀림").containsExactly("UNSUPPORTED_FORMAT");
        assertThat(codes(check(noExt, "recording", null).violations())).as("확장자 없음").containsExactly("UNSUPPORTED_FORMAT");
        assertThat(codes(check(empty, "empty.wav", "audio/wav").violations())).containsExactly("EMPTY_FILE");
        assertThat(codes(check(ogg, null, null).violations())).as("이름 없이(배치)도 내용으로 막는다")
                .containsExactly("UNSUPPORTED_FORMAT");

        // 받는 쪽 — octet-stream 은 '모름'(경고), 확장자와 내용이 달라도 내용대로 보낸다(경고)
        AudioInspector.Check octet = check(wav, "a.wav", "application/octet-stream");
        assertThat(octet.ok()).isTrue();
        assertThat(codes(octet.warnings())).contains("MIME_UNKNOWN");
        AudioInspector.Check mismatch = check(mp3InWav, "b.wav", "audio/wav");
        assertThat(mismatch.ok()).isTrue();
        assertThat(codes(mismatch.warnings())).contains("FORMAT_MISMATCH");
        assertThat(mismatch.uploadName()).isEqualTo("audio.mp3");
        assertThat(mismatch.contentType()).isEqualTo("audio/mpeg");

        GenonSttClient rest = client(GenonSttProperties.Mode.REST, "test-key", 10);
        assertThrows(AudioValidationException.class, () -> rest.transcribe(txt.toFile()));
        assertThrows(AudioValidationException.class, () -> rest.transcribe(ogg.toFile()));
        assertThat(received).isEmpty();
    }

    // ------------------------------------------------------------------ 호출

    @Test
    @DisplayName("MOCK — 외부망 없이(키 없이도) 제논과 같은 모양을 돌려준다(전사 · 전처리 · 배치)")
    void mockReturnsGenonShape() throws Exception {
        Path wav = write("a.wav", AudioSampleGenerator.sineWav(3, 16_000, 440d));
        GenonSttClient mock = client(GenonSttProperties.Mode.MOCK, "", 10);

        GenonCall t = mock.transcribe(wav.toFile());
        assertThat(t.mode()).isEqualTo("MOCK");
        assertThat(t.status()).isEqualTo(200);
        assertThat(t.body().path("text").asText()).isNotBlank();
        JsonNode seg = t.body().path("segments").path(0);
        assertThat(seg.has("start_ms") && seg.has("end_ms") && seg.has("text")).isTrue();
        assertThat(t.body().path("segments").path(3).path("end_ms").asDouble()).as("재생 시간에 맞춰 나눈다")
                .isEqualTo(3000.0);

        GenonCall p = mock.processContactAudio(wav.toFile(), "CONTACT-SIM-001", "접견",
                Instant.parse("2026-11-06T01:00:00Z"));
        assertThat(p.body().path("function").asText()).isEqualTo("contact_risk_analysis");
        JsonNode rec = p.body().path("input").path("contact_records").path(0);
        assertThat(rec.path("contact_type").asText()).isEqualTo("접견");
        assertThat(rec.path("occurred_at").asText()).isEqualTo("2026-11-06T10:00:00+09:00");
        assertThat(p.body().path("input").path("speech_results").path(0).path("duration_ms").asDouble())
                .isEqualTo(3000.0);

        SttResult r = mock.transcribe(new VoiceFile(null, wav, Files.size(wav), "wav", true));
        assertThat(mock.mode()).isEqualTo("GENON");
        assertThat(r.engine()).isEqualTo("GENON(MOCK)");
        assertThat(r.isEmpty()).isFalse();
        assertThat(r.segments()).hasSize(4);
        assertThat(r.segments().get(1).start()).as("start_ms ÷ 1000").isEqualTo(0.75);
        assertThat(r.duration()).isEqualTo(3.0);
        assertThat(received).as("MOCK 은 소켓을 열지 않는다").isEmpty();
    }

    @Test
    @DisplayName("REST — 토큰 · multipart 를 가이드대로 보낸다(원래 파일 이름은 안 보냄 · contact_type 은 UTF-8)")
    void restSendsGuideShape() throws Exception {
        Path wav = write("홍길동_접견녹음.wav", AudioSampleGenerator.sineWav(3, 16_000, 440d));
        GenonSttClient rest = client(GenonSttProperties.Mode.REST, "test-key", 10);

        GenonCall t = rest.transcribe(wav.toFile());
        Captured c = received.get(0);
        assertThat(c.method()).isEqualTo("POST");
        assertThat(c.path()).isEqualTo("/audio/transcribe");
        assertThat(c.auth()).isEqualTo("Bearer test-key");
        assertThat(c.contentType()).startsWith("multipart/form-data; boundary=");
        String body = new String(c.body(), StandardCharsets.UTF_8);
        assertThat(body).contains("Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"")
                .contains("Content-Type: audio/wav")
                .doesNotContain("홍길동");
        assertThat(indexOf(c.body(), Files.readAllBytes(wav))).as("파일 바이트가 그대로 실렸다").isPositive();
        assertThat(t.mode()).isEqualTo("REST");
        assertThat(t.attempts()).isEqualTo(1);

        SttResult r = GenonSttClient.toSttResult(t);
        assertThat(r.engine()).isEqualTo("GENON");
        assertThat(r.text()).isEqualTo("안녕하세요 면회 갈게요");
        assertThat(r.segments()).containsExactly(
                new SttResult.Segment(0, 0.1, 1.5, "안녕하세요"),
                new SttResult.Segment(1, 1.5, 3.0, "면회 갈게요"));

        replies.add(new Reply(200, PROCESS_BODY));
        GenonCall p = rest.processContactAudio(wav.toFile(), "CONTACT-SIM-001", "접견",
                Instant.parse("2026-11-06T01:00:00Z"));
        Captured pc = received.get(1);
        assertThat(pc.path()).isEqualTo("/audio/process");
        assertThat(new String(pc.body(), StandardCharsets.UTF_8))
                .contains("name=\"contact_id\"\r\n\r\nCONTACT-SIM-001\r\n")
                .contains("name=\"contact_type\"\r\n\r\n접견\r\n")
                .contains("name=\"occurred_at\"\r\n\r\n2026-11-06T10:00:00+09:00\r\n");
        assertThat(p.body().path("function").asText()).isEqualTo("contact_risk_analysis");

        assertThrows(IllegalArgumentException.class, () -> rest.processContactAudio(wav.toFile(),
                "CONTACT-SIM-001", "면회", Instant.now()), "contact_type 은 접견 · 전화만");
    }

    @Test
    @DisplayName("동시 한도 — 제논이 200 본문으로 '사용 중' 을 주면 기다렸다 다시 보내고, 계속이면 BUSY")
    void retriesOnBusyEnvelope() throws Exception {
        Path wav = write("a.wav", AudioSampleGenerator.sineWav(1, 16_000, 440d));
        GenonSttClient rest = client(GenonSttProperties.Mode.REST, "test-key", 10);

        replies.add(new Reply(200, BUSY_BODY));
        GenonCall ok = rest.transcribe(wav.toFile());
        assertThat(ok.attempts()).isEqualTo(2);
        assertThat(received).hasSize(2);
        assertThat(ok.body().path("text").asText()).isEqualTo("안녕하세요 면회 갈게요");

        received.clear();
        for (int i = 0; i < 4; i++) {
            replies.add(new Reply(200, BUSY_BODY));
        }
        GenonSttException e = assertThrows(GenonSttException.class, () -> rest.transcribe(wav.toFile()));
        assertThat(e.kind()).isEqualTo(GenonSttException.Kind.BUSY);
        assertThat(e.attempts()).as("처음 1 + 다시 3").isEqualTo(4);
        assertThat(e.errorCode()).isEqualTo("13040001");
        assertThat(received).hasSize(4);
    }

    @Test
    @DisplayName("동시 1건 — 여러 스레드가 함께 불러도 제논에는 한 번에 하나만 간다")
    void serializesCalls() throws Exception {
        Path wav = write("a.wav", AudioSampleGenerator.sineWav(1, 16_000, 440d));
        GenonSttClient rest = client(GenonSttProperties.Mode.REST, "test-key", 10);
        handlerDelayMs = 150;

        ExecutorService callers = Executors.newFixedThreadPool(4);
        try {
            List<Future<GenonCall>> fs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                fs.add(callers.submit(() -> rest.transcribe(wav.toFile())));
            }
            long maxQueued = 0;
            for (Future<GenonCall> f : fs) {
                maxQueued = Math.max(maxQueued, f.get(30, TimeUnit.SECONDS).queuedMs());
            }
            assertThat(maxInFlight.get()).as("제논에 동시에 걸린 요청 수").isEqualTo(1);
            assertThat(received).hasSize(4);
            assertThat(maxQueued).as("뒤 건은 차례를 기다렸다").isGreaterThanOrEqualTo(100);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    @DisplayName("실패 분류 — 400 거절(코드 · 풀이) · 401 인증 · 502 서버 · 응답 지연 타임아웃 · 연결 거부 · 키 없음")
    void classifiesFailures() throws Exception {
        Path wav = write("a.wav", AudioSampleGenerator.sineWav(1, 16_000, 440d));
        GenonSttClient rest = client(GenonSttProperties.Mode.REST, "test-key", 1);

        replies.add(new Reply(400, "{\"error\":\"audio_duration_limit_exceeded\"}"));
        GenonSttException rejected = assertThrows(GenonSttException.class, () -> rest.transcribe(wav.toFile()));
        assertThat(rejected.kind()).isEqualTo(GenonSttException.Kind.REJECTED);
        assertThat(rejected.httpStatus()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("audio_duration_limit_exceeded");
        assertThat(rejected.hint()).isEqualTo("재생 시간 600초 초과");
        assertThat(errStack(rejected)).startsWith("[DATA]");

        replies.add(new Reply(401, "text/plain", "Invalid token"));
        GenonSttException auth = assertThrows(GenonSttException.class, () -> rest.transcribe(wav.toFile()));
        assertThat(auth.kind()).isEqualTo(GenonSttException.Kind.AUTH);
        assertThat(auth.errorCode()).isEqualTo("Invalid token");

        replies.add(new Reply(502, "{\"error\":\"signal_analysis_incomplete\"}"));
        GenonSttException upstream = assertThrows(GenonSttException.class, () -> rest.transcribe(wav.toFile()));
        assertThat(upstream.kind()).isEqualTo(GenonSttException.Kind.UPSTREAM);
        assertThat(upstream.httpStatus()).isEqualTo(502);

        // 응답 지연 — 타임아웃(1초). 배치 실패 분류는 TIMEOUT
        handlerDelayMs = 2_500;
        GenonSttException slow = assertThrows(GenonSttException.class, () -> rest.transcribe(wav.toFile()));
        assertThat(slow.kind()).isEqualTo(GenonSttException.Kind.TIMEOUT);
        assertThat(errStack(slow)).startsWith("[TIMEOUT]");
        handlerDelayMs = 0;

        // 연결 거부 — 배치 실패 분류는 CONNECTION
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        GenonSttClient refused = new GenonSttClient(
                props(GenonSttProperties.Mode.REST, "test-key", 5, "http://127.0.0.1:" + closedPort), om);
        GenonSttException conn = assertThrows(GenonSttException.class, () -> refused.transcribe(wav.toFile()));
        assertThat(conn.kind()).isEqualTo(GenonSttException.Kind.CONNECTION);
        assertThat(errStack(conn)).startsWith("[CONNECTION]");

        // 키 없음 — 보내지 않는다. REST 로 바꾸는 것도 거절한다
        int before = received.size();
        GenonSttException cfg = assertThrows(GenonSttException.class,
                () -> client(GenonSttProperties.Mode.REST, "", 5).transcribe(wav.toFile()));
        assertThat(cfg.kind()).isEqualTo(GenonSttException.Kind.CONFIG);
        assertThat(received).hasSize(before);
        GenonSttClient noKey = client(GenonSttProperties.Mode.MOCK, "", 5);
        assertThrows(IllegalStateException.class, () -> noKey.setCallMode("REST"));
        assertThat(noKey.maskedKey()).isEmpty();
        assertThat(rest.maskedKey()).as("짧은 키는 전부 가린다").isEqualTo("********");
    }

    // ------------------------------------------------------------------ 샘플 생성기

    @Test
    @DisplayName("샘플 생성기 — 샘플마다 의도한 검사 하나에만 걸린다(경계 샘플은 한 가지만 넘게)")
    void samplesHitIntendedChecks() throws Exception {
        Path out = dir.resolve("samples");
        AudioSampleGenerator.writeAll(out);

        for (AudioSampleGenerator.Kind k : AudioSampleGenerator.Kind.values()) {
            AudioInspector.Check c = check(out.resolve(k.fileName()), k.fileName(), null);
            assertThat(c.ok()).as(k + " " + c.violations()).isEqualTo(k.expectPass());
        }
        assertThat(codes(check(out.resolve("over_size_20_5MiB.wav"), "over_size_20_5MiB.wav", null).violations()))
                .containsExactly("SIZE_LIMIT_EXCEEDED");
        assertThat(Files.size(out.resolve("over_size_20_5MiB.wav"))).isEqualTo(AudioSampleGenerator.OVER_SIZE_BYTES);
        assertThat(codes(check(out.resolve("over_duration_610s.wav"), "over_duration_610s.wav", null).violations()))
                .containsExactly("DURATION_LIMIT_EXCEEDED");
        assertThat(Files.size(out.resolve("over_duration_610s.wav"))).isLessThan(MAX);
        assertThat(codes(check(out.resolve("unsupported.ogg"), "unsupported.ogg", null).violations()))
                .containsOnly("UNSUPPORTED_FORMAT");
        assertThat(codes(check(out.resolve("unsupported.txt"), "unsupported.txt", null).violations()))
                .containsOnly("UNSUPPORTED_FORMAT");

        AudioInspector.Check wav = check(out.resolve("normal_3s_16k_mono.wav"), "normal_3s_16k_mono.wav", null);
        assertThat(wav.info().durationSec()).isEqualTo(3.0);
        assertThat(wav.info().codec()).isEqualTo("pcm_s16le");
        AudioInspector.Check mp3 = check(out.resolve("normal_3s_silent.mp3"), "normal_3s_silent.mp3", null);
        assertThat(mp3.info().format()).isEqualTo("mp3");
        assertThat(mp3.info().durationSec()).isEqualTo(3.0);
        AudioInspector.Check m4a = check(out.resolve("normal_3s_silent.m4a"), "normal_3s_silent.m4a", null);
        assertThat(m4a.info().format()).as("ftyp 브랜드 M4A").isEqualTo("m4a");
        assertThat(m4a.info().durationSec()).isCloseTo(3.0, within(0.05));
        assertThat(m4a.warnings()).isEmpty();
        AudioInspector.Check speech = check(out.resolve("speech_ko_22s.wav"), "speech_ko_22s.wav", null);
        assertThat(speech.info().durationSec()).isBetween(20.0, 25.0);

        try (var files = Files.list(out)) {
            assertThat(files.map(f -> f.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    // ------------------------------------------------------------------ 실제 제논(선택)

    @Test
    @EnabledIfEnvironmentVariable(named = "GENON_STT_LIVE", matches = "true")
    @DisplayName("실제 제논(선택) — GENON_STT_LIVE=true · GENON_STT_API_KEY 가 있을 때만. 합성 낭독 22초를 전사한다")
    void liveGenon() throws Exception {
        GenonSttClient live = new GenonSttClient(props(GenonSttProperties.Mode.REST,
                System.getenv("GENON_STT_API_KEY"), 60,
                "https://genos.genon.ai/api/gateway/code_serving/1007/8690"), om);
        assertThat(live.probe().tokenValid()).as("GET /health 로 토큰 확인").isTrue();

        Path speech = AudioSampleGenerator.write(AudioSampleGenerator.Kind.SPEECH_22S, dir);
        GenonCall c = live.transcribe(speech.toFile());
        assertThat(c.status()).isEqualTo(200);
        assertThat(GenonSttClient.toSttResult(c).text()).contains("면회");
    }

    // ------------------------------------------------------------------ 도우미

    private GenonSttClient client(GenonSttProperties.Mode mode, String key, int timeoutSec) {
        return new GenonSttClient(props(mode, key, timeoutSec,
                "http://127.0.0.1:" + server.getAddress().getPort()), om);
    }

    private static GenonSttProperties props(GenonSttProperties.Mode mode, String key, int timeoutSec, String baseUrl) {
        return new GenonSttProperties(mode, baseUrl, key, "2026-12-11", timeoutSec, 5, MAX, 600, 1, 30, 3, 300, 600, 15);
    }

    private static AudioInspector.Check check(Path p, String name, String mime) {
        return AudioInspector.check(p, name, mime, MAX, 600);
    }

    private void assertBlockedByDuration(Path p, String from) {
        AudioInspector.Check c = check(p, p.getFileName().toString(), null);
        assertThat(codes(c.violations())).as(p.getFileName().toString()).containsExactly("DURATION_LIMIT_EXCEEDED");
        assertThat(c.info().durationFrom()).isEqualTo(from);
        assertThat(c.info().durationSec()).isCloseTo(610.0, within(0.1));
    }

    private static List<String> codes(List<AudioInspector.Issue> issues) {
        return issues.stream().map(AudioInspector.Issue::code).toList();
    }

    /** 배치가 남기는 실패 사유 모양({@code 단순이름: 메시지})으로 분류해 본다. */
    private static String errStack(Exception e) {
        return LogCollectorClient.FileProcReq.errStackOf(e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private Path write(String name, byte[] bytes) throws IOException {
        return Files.write(dir.resolve(name), bytes);
    }

    /** WAV 머리 44바이트만 쓰고 길이를 늘린다 — 검사기는 머리와 크기만 읽으므로 20MB 를 실제로 채우지 않는다. */
    private Path sparseWav(String name, int rate, int channels, long dataLen, long fileLen) throws IOException {
        Path p = dir.resolve(name);
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (36 + dataLen))
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) channels).putInt(rate).putInt(rate * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) dataLen);
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.write(h.array());
            f.setLength(fileLen);
        }
        return p;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
