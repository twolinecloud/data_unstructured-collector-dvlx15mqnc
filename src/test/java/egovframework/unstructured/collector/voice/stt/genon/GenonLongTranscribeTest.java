package egovframework.unstructured.collector.voice.stt.genon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.SttResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 긴 음원 전사({@link GenonSttClient#transcribeLong}) — 600초를 넘는 PCM WAV 를 조각내 차례로 보내고 잇는다.
 *
 * <p>가짜 제논은 받은 조각의 재생 시간을 응답 글에 적어 돌려준다 — 순서 · 길이를 응답만 보고 확인할 수 있게.
 * 표본 빈도는 2kHz 로 낮춰 20분도 4.8MB 다(규칙은 빈도와 상관없다).</p>
 */
class GenonLongTranscribeTest {

    private static final long MAX = 20L * 1024 * 1024;
    private static final int RATE = 2_000;

    @TempDir
    Path dir;

    private final ObjectMapper om = new ObjectMapper();
    private HttpServer server;
    private ExecutorService pool;
    /** 받은 조각 — 재생 시간(초) · 업로드 이름. */
    private final List<double[]> received = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    /** 줄 세운 응답(비면 성공) — {상태, 본문}. */
    private final Deque<Object[]> replies = new ConcurrentLinkedDeque<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            String head = new String(body, 0, Math.min(body.length, 600), StandardCharsets.UTF_8);
            bodies.add(head);
            int riff = indexOf(body, "RIFF".getBytes(StandardCharsets.US_ASCII));
            long dataLen = riff < 0 ? 0 : (body[riff + 40] & 0xFFL) | (body[riff + 41] & 0xFFL) << 8
                    | (body[riff + 42] & 0xFFL) << 16 | (body[riff + 43] & 0xFFL) << 24;
            double sec = dataLen / (RATE * 2d);
            received.add(new double[]{sec});
            Object[] r = replies.isEmpty() ? null : replies.poll();
            int status = r == null ? 200 : (int) r[0];
            String out = r != null ? (String) r[1]
                    : "{\"text\":\"조각%d %.1f초\",\"segments\":[{\"start_ms\":1000.0,\"end_ms\":%.1f,\"text\":\"조각%d\"}]}"
                    .formatted(received.size(), sec, sec * 1000 - 1000, received.size());
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } finally {
            ex.close();
        }
    }

    @Test
    @DisplayName("20분 — 600초 × 2 로 차례로 보내고 잇는다(글은 줄바꿈 · 구간 시각은 원래 음원 기준) · 조각 파일은 남지 않는다")
    void splitsAndMerges() throws Exception {
        Path wav = wav("20m.wav", 1200);
        Path work = dir.resolve("work");
        List<String> events = new ArrayList<>();
        GenonSttClient.LongTranscript lt = client(GenonSttProperties.Mode.REST).transcribeLong(wav, work,
                new GenonSttClient.ChunkListener() {
                    @Override
                    public void sending(int no, int total, double startSec, double endSec) {
                        events.add("send " + no + "/" + total + " " + startSec + "~" + endSec);
                    }

                    @Override
                    public void done(GenonSttClient.ChunkCall call, int total) {
                        events.add("done " + call.no() + "/" + total);
                    }
                });

        assertThat(received).hasSize(2);
        assertThat(received.get(0)[0]).isCloseTo(600.0, within(0.01));
        assertThat(received.get(1)[0]).isCloseTo(600.0, within(0.01));
        assertThat(bodies).allSatisfy(b -> assertThat(b).contains("filename=\"audio.wav\""));
        assertThat(events).containsExactly("send 1/2 0.0~600.0", "done 1/2", "send 2/2 600.0~1200.0", "done 2/2");

        SttResult r = lt.result();
        assertThat(lt.split()).isTrue();
        assertThat(r.text()).isEqualTo("조각1 600.0초\n조각2 600.0초");
        assertThat(r.duration()).isEqualTo(1200.0);
        assertThat(r.segments()).extracting(SttResult.Segment::id).containsExactly(0, 1);
        assertThat(r.segments().get(1).start()).as("두 번째 조각 구간은 600초를 더한다").isEqualTo(601.0);
        assertThat(lt.chunks()).extracting(GenonSttClient.ChunkCall::mode).containsOnly("REST");
        assertThat(lt.chunks()).extracting(GenonSttClient.ChunkCall::resplit).containsOnly(false);
        try (var files = Files.list(work)) {
            assertThat(files.toList()).as("조각 파일은 받자마자 지운다").isEmpty();
        }
    }

    @Test
    @DisplayName("한도 안(600초 이하)이면 나누지 않고 한 번 — 결과 모양은 같다")
    void shortGoesOnce() throws Exception {
        Path wav = wav("10m.wav", 600);
        GenonSttClient.LongTranscript lt = client(GenonSttProperties.Mode.REST).transcribeLong(wav, dir.resolve("w"), null);
        assertThat(received).hasSize(1);
        assertThat(lt.split()).isFalse();
        assertThat(lt.chunks()).hasSize(1);
        assertThat(lt.result().text()).isEqualTo("조각1 600.0초");
        assertThat(Files.exists(dir.resolve("w"))).as("나누지 않으면 작업 폴더도 안 만든다").isFalse();
    }

    @Test
    @DisplayName("제논이 600초 조각을 길이 한도로 거절하면 그 조각만 반으로 나눠 다시 보낸다")
    void resplitsOnDurationReject() throws Exception {
        Path wav = wav("20m.wav", 1200);
        replies.add(new Object[]{400, "{\"error\":\"audio_duration_limit_exceeded\"}"});
        GenonSttClient.LongTranscript lt = client(GenonSttProperties.Mode.REST).transcribeLong(wav, dir.resolve("w"), null);

        assertThat(received).as("거절 1 + 반쪽 2 + 두 번째 조각 1").hasSize(4);
        assertThat(received.get(1)[0] + received.get(2)[0]).isCloseTo(600.0, within(0.01));
        assertThat(received.get(1)[0]).isBetween(285.0, 315.0);
        assertThat(lt.chunks()).extracting(GenonSttClient.ChunkCall::resplit).containsExactly(true, true, false);
        assertThat(lt.chunks()).extracting(GenonSttClient.ChunkCall::no).containsExactly(1, 2, 3);
        assertThat(lt.result().text().split("\n")).hasSize(3);
        assertThat(lt.chunks().get(2).startSec()).isEqualTo(600.0);
    }

    @Test
    @DisplayName("조각 실패 — 몇 번째 조각인지 앞에 붙고 종류 · 코드는 그대로(배치 분류 낱말도 남는다)")
    void chunkFailureKeepsKind() throws Exception {
        Path wav = wav("20m.wav", 1200);
        replies.add(new Object[]{200, "{\"text\":\"첫 조각\",\"segments\":[]}"});
        replies.add(new Object[]{502, "{\"error\":\"signal_analysis_incomplete\"}"});
        GenonSttException e = assertThrows(GenonSttException.class,
                () -> client(GenonSttProperties.Mode.REST).transcribeLong(wav, dir.resolve("w"), null));
        assertThat(e.getMessage()).startsWith("조각 2/2 — 제논 STT 서버 오류 — HTTP 502");
        assertThat(e.kind()).isEqualTo(GenonSttException.Kind.UPSTREAM);
        assertThat(e.errorCode()).isEqualTo("signal_analysis_incomplete");
        assertThat(LogCollectorClient.FileProcReq.errStackOf(e.getMessage())).startsWith("[DATA]");
        try (var files = Files.list(dir.resolve("w"))) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    @DisplayName("MOCK — 30분도 600 × 3 으로 나눠 제논 모양 응답을 잇는다(소켓 없음)")
    void mockSplitsWithoutNetwork() throws Exception {
        Path wav = wav("30m.wav", 1800);
        GenonSttClient.LongTranscript lt = client(GenonSttProperties.Mode.MOCK).transcribeLong(wav, dir.resolve("w"), null);
        assertThat(received).isEmpty();
        assertThat(lt.chunks()).hasSize(3).extracting(GenonSttClient.ChunkCall::mode).containsOnly("MOCK");
        assertThat(lt.result().engine()).isEqualTo("GENON(MOCK)");
        assertThat(lt.result().text().split("\n")).hasSize(3);
        assertThat(lt.result().segments()).hasSize(12);
        assertThat(lt.result().segments().get(4).start()).as("두 번째 조각 첫 구간 = 600초").isEqualTo(600.0);
        assertThat(lt.result().segments().get(11).end()).isEqualTo(1800.0);
    }

    @Test
    @DisplayName("나눌 수 없는 위반(MP3 610초 · 형식)은 보내지 않고 사전 검증 실패")
    void nonWavIsNotSplit() throws Exception {
        Path mp3 = Files.write(dir.resolve("610.mp3"), AudioSampleGenerator.silentMp3(610));
        AudioValidationException e = assertThrows(AudioValidationException.class,
                () -> client(GenonSttProperties.Mode.REST).transcribeLong(mp3, dir.resolve("w"), null));
        assertThat(e.check().violates("DURATION_LIMIT_EXCEEDED")).isTrue();
        assertThat(received).isEmpty();
        assertThat(GenonSttClient.splittable(e.check())).isFalse();
    }

    // ------------------------------------------------------------------ 도우미

    private GenonSttClient client(GenonSttProperties.Mode mode) {
        return new GenonSttClient(new GenonSttProperties(mode, "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "2026-12-11", 10, 5, MAX, 600, 1, 30, 3, 300, 600, 15), om);
    }

    /** 2kHz 잡음 WAV — 내용은 상관없고 길이만 본다. */
    private Path wav(String name, int seconds) throws IOException {
        int n = seconds * RATE;
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            short v = (short) ((i * 7919) % 6000 - 3000);
            pcm[2 * i] = (byte) v;
            pcm[2 * i + 1] = (byte) (v >> 8);
        }
        byte[] h = PcmWav.header(RATE, 1, pcm.length);
        byte[] all = new byte[h.length + pcm.length];
        System.arraycopy(h, 0, all, 0, h.length);
        System.arraycopy(pcm, 0, all, h.length, pcm.length);
        return Files.write(dir.resolve(name), all);
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
