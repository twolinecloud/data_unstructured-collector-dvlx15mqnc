package egovframework.unstructured.collector.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한글이 든 URL 경로 — 시험 문서 단건 API 와 정적 자원.
 *
 * <p>개발계 파드는 기반 이미지 {@code openjdk:17-alpine}(조기 접근판 17-ea+14) 위에서 돌아, 경로에 {@code %XX} 가
 * 있으면 무조건 500 이었다. 스프링 6.2 의 경로 디코딩({@code StringUtils.uriDecode})이 GA 17 의 <b>정적</b>
 * {@code HexFormat.fromHexDigits(CharSequence,int,int)} 를 부르는데 조기판에서는 인스턴스 메서드라
 * {@code IncompatibleClassChangeError} 가 난다. 첫 실패는 핸들러가 아니라 {@code CorsFilter} 다.
 * 고친 곳은 이미지(Dockerfile FROM → Temurin 17 GA)이고, 이 시험은 그때 500 이던 요청을 그대로 보내
 * 톰캣 → CorsFilter → 핸들러 · 정적 자원까지 끝까지 도는지 지킨다(MockMvc 는 실제 디코딩 경로를 안 탄다).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class KoreanSamplePathTest {

    private static final String DOC_API = "/api/v1/voice/simulator/doc-deid/samples/";

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("voice.source.mode", () -> "MOCK");
        registry.add("voice.broker.mode", () -> "MOCK");
        registry.add("voice.dirs.base-dir", () -> tmp.toString());
        registry.add("voice.dirs.receive-meet", () -> tmp.resolve("raw/meet").toString());
        registry.add("voice.dirs.receive-phone", () -> tmp.resolve("raw/phone").toString());
        registry.add("voice.dirs.work", () -> tmp.resolve("work").toString());
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("log-collector.enabled", () -> "false");
        registry.add("voice.batch.schedule-enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    /** 브라우저 {@code encodeURIComponent} 와 같은 꼴(한글 → UTF-8 %XX · 공백 → %20). */
    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpResponse<byte[]> get(String rawPath) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] classpath(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("전제 — 이 JVM 의 HexFormat.fromHexDigits 는 정적 메서드다(GA 17 · 조기판이면 아래 요청이 모두 500)")
    void runsOnGaHexFormat() throws Exception {
        Method m = HexFormat.class.getMethod("fromHexDigits", CharSequence.class, int.class, int.class);
        assertThat(Modifier.isStatic(m.getModifiers())).as("java.runtime.version=%s", Runtime.version()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"table_단순2열.docx", "table_값분할.hwpx", "pages_1p.docx"})
    @DisplayName("시험 문서 단건 API — 한글 이름도 200 · 원본 바이트 그대로 · filename* 은 UTF-8 인코딩")
    void singleSampleByName(String name) throws Exception {
        HttpResponse<byte[]> r = get(DOC_API + enc(name));

        assertThat(r.statusCode()).as(name).isEqualTo(200);
        assertThat(r.body()).as(name).isEqualTo(classpath("static/samples/문서비식별/" + name));
        assertThat(r.headers().firstValue("Content-Disposition").orElse(""))
                .as(name).contains("filename*=UTF-8''" + enc(name));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({"문서비식별, probe_1p.hwp", "문서비식별, table_셀내줄바꿈.pdf", "음성, tts_ko_dialog_22s.wav"})
    @DisplayName("정적 자원 — 한글 폴더 · 한글 이름도 200 · 원본 바이트 그대로")
    void staticSampleUnderKoreanFolder(String folder, String file) throws Exception {
        HttpResponse<byte[]> r = get("/samples/" + enc(folder) + "/" + enc(file));

        assertThat(r.statusCode()).as(folder + "/" + file).isEqualTo(200);
        assertThat(r.body()).as(folder + "/" + file).isEqualTo(classpath("static/samples/" + folder + "/" + file));
    }
}
