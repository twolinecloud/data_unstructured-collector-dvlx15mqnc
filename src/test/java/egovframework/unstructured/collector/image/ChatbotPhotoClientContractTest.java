package egovframework.unstructured.collector.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.util.SampleImage;
import egovframework.unstructured.collector.image.store.ImageFileStore;
import egovframework.unstructured.collector.image.store.InmatePhotoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 챗봇 백엔드 연동 계약 — 사진 파일 API 를 <b>실제 서버</b>(무작위 포트 · 내장 톰캣)로 띄우고, 챗봇 백엔드가 쓸 법한
 * HTTP 클라이언트(RestTemplate · RestClient · JDK HttpClient)로 부른다. 이어서 챗봇 백엔드의 프록시
 * (브라우저 → 챗봇 백엔드 → 수집기)를 흉내 낸 컨트롤러로 스트리밍 중계 · 304 중계 · 대체 이미지까지 본다.
 *
 * <ul>
 *   <li>200 — 바이트 · Content-Type · ETag · Cache-Control 을 받고, 256KB 넘는 사진도 스트림으로 소비한다</li>
 *   <li>304 — 받은 ETag 를 If-None-Match 로 넘기면 본문 없이 304 — 챗봇 백엔드가 그대로 중계할 수 있다</li>
 *   <li>404 · 500(저장소 · 형식) · 503(Admin DB) — {@code application/json} {@code {message}} 를 UTF-8 로 읽어 분기한다</li>
 *   <li>컨테이너(톰캣)가 먼저 거절한 400 은 JSON 이 아니다 — 본문을 JSON 이라 가정하면 안 된다</li>
 * </ul>
 *
 * <p>WebClient 는 이 레포에 spring-webflux 가 없어 돌리지 않는다 — HTTP 수준 응답은 같고, 차이(메모리 버퍼 상한 256KB)는
 * 연동 가이드에 적는다. 저장소 장애(500)와 DB 장애(503)는 실제 빈을 감싼 스파이로 만든다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatbotPhotoClientContractTest {

    private static final String JPG = "CBPX0001";
    private static final String PNG = "CBPX0002";
    private static final String BIG = "CBPX0003";
    private static final String NON_IMAGE = "CBPX0500";
    private static final String NONE = "CBPX0404";
    private static final String DB_DOWN = "CBPX0503";

    private static final String MSG_NOT_FOUND = "해당 수용자의 사진이 없습니다.";
    private static final String MSG_STORAGE = "사진 저장소를 읽지 못했습니다. 잠시 후 다시 시도해 주세요.";
    private static final String MSG_UNSUPPORTED = "사진 파일 형식을 확인할 수 없습니다.";
    private static final String MSG_DB = "사진 정보를 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    @TempDir
    static Path tmp;

    private static byte[] jpeg;
    private static byte[] png;
    private static byte[] big;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("voice.source.mode", () -> "MOCK");
        registry.add("voice.broker.mode", () -> "MOCK");
        registry.add("voice.decrypt.mode", () -> "SKIP");
        registry.add("log-collector.enabled", () -> "false");
        registry.add("agent-connector.enabled", () -> "false");
        registry.add("unstructured.admin.base-url", () -> "");
        registry.add("voice.batch.schedule-enabled", () -> "false");
        registry.add("voice.sim.seed-on-startup", () -> "false");
        registry.add("voice.dirs.base-dir", () -> tmp.toString().replace('\\', '/'));
        // Admin DB 는 H2 로 못 박는다 — Jenkins 는 dev 프로파일로 테스트한다(ImagePipelineTest 참고)
        registry.add("image.admin-db.url", () -> "jdbc:h2:mem:admin-chatbot;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("image.admin-db.username", () -> "sa");
        registry.add("image.admin-db.password", () -> "");
    }

    @LocalServerPort
    int port;
    @MockitoSpyBean
    InmatePhotoRepository photos;
    @MockitoSpyBean
    ImageFileStore store;
    @Autowired
    ObjectMapper om;

    @BeforeAll
    static void images() throws IOException {
        jpeg = SampleImage.jpeg("chatbot");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 80, BufferedImage.TYPE_INT_RGB), "png", out);
        png = out.toByteArray();
        // 256KB 를 넘는 사진 — 잡음 이미지라 JPEG 로도 크다(WebClient 기본 버퍼 상한을 넘는 크기)
        BufferedImage noise = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(28);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, r.nextInt(0xFFFFFF));
            }
        }
        out = new ByteArrayOutputStream();
        ImageIO.write(noise, "jpg", out);
        big = out.toByteArray();
    }

    @BeforeEach
    void seed() throws IOException {
        put(JPG, 2, "jpg", jpeg);
        put(PNG, 1, "png", png);
        put(BIG, 1, "jpg", big);
        put(NON_IMAGE, 1, "html", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        doThrow(new DataAccessResourceFailureException("Connection refused: admin-db:5432")).when(photos).findMapped(DB_DOWN);
    }

    @AfterEach
    void clean() {
        photos.deleteByPrefix("CBPX");
    }

    /**
     * 수집과 같은 자리에 파일을 두고 매핑한다. 같은 내용이면 다시 쓰지 않는다 — 윈도에선 방금 쓴 파일을 다른 프로세스
     * (백신 검사 등)가 매핑해 두고 있어 덮어쓰기가 거부될 수 있다("사용자가 매핑한 구역이 열려 있는 상태").
     */
    private void put(String corrNo, int sn, String ext, byte[] bytes) throws IOException {
        Path dir = Files.createDirectories(store.outputRoot().resolve(corrNo));
        Path p = dir.resolve(corrNo + "_" + sn + "." + ext);
        if (!Files.isRegularFile(p) || !Arrays.equals(Files.readAllBytes(p), bytes)) {
            Files.write(p, bytes);
        }
        photos.upsert(new InmatePhotoRepository.PhotoRow(corrNo, sn, "F" + corrNo, "D" + corrNo, "key-" + corrNo,
                p.toString().replace('\\', '/'), bytes.length, ext, "TEST-CHATBOT"));
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private String url(String corrNo) {
        return base() + "/api/v1/image/photos/" + corrNo + "/file";
    }

    /** 오류 경우 — 교정번호 · 기대 상태 · 기대 안내. STORAGE 는 저장소 루트 정규화가 실패하게(Stale file handle) 만든다. */
    private record ErrorCase(String corrNo, int status, String message) {}

    private ErrorCase errorCase(String kind) {
        return switch (kind) {
            case "NOT_FOUND" -> new ErrorCase(NONE, 404, MSG_NOT_FOUND);
            case "STORAGE" -> {
                Path broken = mock(Path.class);
                when(broken.toFile()).thenReturn(new File("pv-gone") {
                    @Override
                    public File getCanonicalFile() throws IOException {
                        throw new IOException("Stale file handle");
                    }
                });
                doReturn(broken).when(store).outputRoot();
                yield new ErrorCase(JPG, 500, MSG_STORAGE);
            }
            case "UNSUPPORTED" -> new ErrorCase(NON_IMAGE, 500, MSG_UNSUPPORTED);
            case "DB_DOWN" -> new ErrorCase(DB_DOWN, 503, MSG_DB);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** 스트림을 끝까지 읽으며 크기 · SHA-256 — 전체를 한 배열로 모으지 않는다. */
    private static String[] consume(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            long n = 0;
            for (int r; (r = in.read(buf)) != -1; n += r) {
                md.update(buf, 0, r);
            }
            return new String[]{String.valueOf(n), HexFormat.of().formatHex(md.digest())};
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            in.close();
        }
    }

    // ── RestTemplate ──

    @Test
    @DisplayName("RestTemplate — 200 바이트 · image/jpeg · ETag · Cache-Control 수신, 받은 ETag 를 If-None-Match 로 넘기면 304(본문 없음)")
    void restTemplateOkAndNotModified() {
        RestTemplate rt = new RestTemplate();
        ResponseEntity<byte[]> r = rt.getForEntity(url(JPG), byte[].class);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(r.getBody()).isEqualTo(jpeg);
        assertThat(r.getHeaders().getCacheControl()).contains("private").contains("max-age=3600");
        String etag = r.getHeaders().getETag();
        assertThat(etag).startsWith("\"2-");

        HttpHeaders h = new HttpHeaders();
        h.setIfNoneMatch(etag);
        ResponseEntity<byte[]> again = rt.exchange(url(JPG), HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        assertThat(again.getStatusCode().value()).isEqualTo(304);
        assertThat(again.getBody()).isNullOrEmpty();
        assertThat(again.getHeaders().getETag()).isEqualTo(etag);
    }

    @Test
    @DisplayName("RestTemplate — PNG 는 image/png")
    void restTemplatePng() {
        ResponseEntity<byte[]> r = new RestTemplate().getForEntity(url(PNG), byte[].class);
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(r.getBody()).isEqualTo(png);
    }

    @Test
    @DisplayName("RestTemplate — 256KB 넘는 사진을 execute 로 스트림 소비 — 크기 · SHA-256 이 저장 파일과 같다")
    void restTemplateStreamsBigPhoto() throws Exception {
        assertThat(big.length).as("시험 사진은 256KB 보다 커야 한다").isGreaterThan(256 * 1024);
        String[] got = new RestTemplate().execute(url(BIG), HttpMethod.GET, null, res -> consume(res.getBody()));

        assertThat(got).containsExactly(String.valueOf(big.length), sha256(big));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"NOT_FOUND", "STORAGE", "UNSUPPORTED", "DB_DOWN"})
    @DisplayName("RestTemplate — 404 · 500 · 503 은 HttpStatusCodeException — 본문 application/json {message} 를 UTF-8 로 읽는다")
    void restTemplateErrorsAreJson(String kind) throws Exception {
        ErrorCase c = errorCase(kind);
        HttpStatusCodeException e = catchThrowableOfType(
                () -> new RestTemplate().getForEntity(url(c.corrNo()), byte[].class), HttpStatusCodeException.class);

        assertThat(e).as("상태 %d 는 예외로 와야 한다", c.status()).isNotNull();
        assertThat(e.getStatusCode().value()).isEqualTo(c.status());
        assertThat(e.getResponseHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(om.readTree(e.getResponseBodyAsString(StandardCharsets.UTF_8)).path("message").asText()).isEqualTo(c.message());
    }

    // ── RestClient ──

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"OK", "NOT_MODIFIED", "NOT_FOUND", "STORAGE", "DB_DOWN"})
    @DisplayName("RestClient — exchange 로 상태별 분기(200 스트림 · 304 · 404/500/503 JSON) — 예외 없이")
    void restClientExchange(String kind) {
        RestClient rc = RestClient.create();
        String etag = rc.get().uri(url(JPG)).retrieve().toBodilessEntity().getHeaders().getETag();
        ErrorCase c = switch (kind) {
            case "OK", "NOT_MODIFIED" -> null;
            default -> errorCase(kind);
        };
        String corrNo = c == null ? JPG : c.corrNo();

        Map<String, Object> seen = rc.get().uri(url(corrNo))
                .headers(h -> {
                    if ("NOT_MODIFIED".equals(kind)) {
                        h.setIfNoneMatch(etag);
                    }
                })
                .exchange((req, res) -> {
                    int s = res.getStatusCode().value();
                    if (s == 200) {
                        return Map.of("status", s, "bytes", res.getBody().readAllBytes(), "type", res.getHeaders().getContentType());
                    }
                    if (s == 304) {
                        return Map.of("status", s, "bytes", res.getBody().readAllBytes(), "etag", res.getHeaders().getETag());
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = res.bodyTo(Map.class);
                    return Map.of("status", s, "message", body == null ? "" : body.get("message"));
                });

        switch (kind) {
            case "OK" -> {
                assertThat(seen.get("status")).isEqualTo(200);
                assertThat((byte[]) seen.get("bytes")).isEqualTo(jpeg);
                assertThat(seen.get("type")).isEqualTo(MediaType.IMAGE_JPEG);
            }
            case "NOT_MODIFIED" -> {
                assertThat(seen.get("status")).isEqualTo(304);
                assertThat((byte[]) seen.get("bytes")).isEmpty();
                assertThat(seen.get("etag")).isEqualTo(etag);
            }
            default -> {
                assertThat(seen.get("status")).isEqualTo(c.status());
                assertThat(seen.get("message")).isEqualTo(c.message());
            }
        }
    }

    // ── JDK HttpClient ──

    @Test
    @DisplayName("JDK HttpClient(기본 HTTP/2 시도 → HTTP/1.1) — ofInputStream 스트리밍 · 304 · 503 JSON")
    void jdkHttpClient() throws Exception {
        HttpClient hc = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

        HttpResponse<InputStream> r = hc.send(HttpRequest.newBuilder(URI.create(url(BIG))).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(r.headers().firstValueAsLong("Content-Length")).hasValue(big.length);
        assertThat(consume(r.body())).containsExactly(String.valueOf(big.length), sha256(big));

        String etag = r.headers().firstValue("ETag").orElseThrow();
        HttpResponse<byte[]> nm = hc.send(HttpRequest.newBuilder(URI.create(url(BIG))).header("If-None-Match", etag).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(nm.statusCode()).isEqualTo(304);
        assertThat(nm.body()).isEmpty();

        ErrorCase c = errorCase("DB_DOWN");
        HttpResponse<byte[]> err = hc.send(HttpRequest.newBuilder(URI.create(url(c.corrNo()))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(err.statusCode()).isEqualTo(503);
        assertThat(err.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(om.readTree(new String(err.body(), StandardCharsets.UTF_8)).path("message").asText()).isEqualTo(MSG_DB);
    }

    @Test
    @DisplayName("톰캣이 먼저 거절한 400(인코딩한 슬래시 %2F)은 JSON 이 아니라 HTML — 본문을 JSON 이라 가정하면 안 된다")
    void containerRejectionIsNotJson() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + "/api/v1/image/photos/..%2F..%2Fetc%2Fpasswd/file")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
    }

    // ── 챗봇 백엔드 프록시(브라우저 → 챗봇 백엔드 → 수집기) ──

    /**
     * 챗봇 백엔드의 사진 프록시 예 — 연동 가이드의 권장안 그대로.
     * <ul>
     *   <li>브라우저의 조건부 헤더(If-None-Match · If-Modified-Since)만 넘기고, 받은 200 · 304 의 헤더를 그대로 중계한다
     *       (바이트를 바꾸지 않으니 ETag 도 그대로)</li>
     *   <li>본문은 스트림으로 흘려보낸다 — 사진 전체를 메모리에 모으지 않는다</li>
     *   <li>404 는 대체 이미지(5분 캐시), 5xx · 연결 실패는 대체 이미지(no-store — 복구되면 바로 진짜 사진)</li>
     *   <li>(실서비스) 수집기를 부르기 전에 로그인 사용자가 이 수용자를 볼 권한이 있는지 먼저 본다 — 여기선 생략</li>
     * </ul>
     */
    @RestController
    static class ChatbotPhotoProxy {

        static final byte[] AVATAR = ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"160\" viewBox=\"0 0 120 160\">"
                + "<rect width=\"120\" height=\"160\" fill=\"#E6E8EC\"/><circle cx=\"60\" cy=\"58\" r=\"26\" fill=\"#B5BAC4\"/>"
                + "<rect x=\"22\" y=\"98\" width=\"76\" height=\"62\" rx=\"30\" fill=\"#B5BAC4\"/></svg>").getBytes(StandardCharsets.UTF_8);
        static final List<String> RELAY = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_LENGTH, HttpHeaders.ETAG,
                HttpHeaders.LAST_MODIFIED, HttpHeaders.CACHE_CONTROL, "X-Content-Type-Options");

        private final String upstream;
        private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
        private final ObjectMapper om = new ObjectMapper();
        final AtomicReference<String> lastUpstreamMessage = new AtomicReference<>();

        ChatbotPhotoProxy(String upstream) {
            this.upstream = upstream;
        }

        @GetMapping("/chat/inmates/{corrNo}/photo")
        public ResponseEntity<Resource> photo(@PathVariable String corrNo,
                                              @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                              @RequestHeader(value = HttpHeaders.IF_MODIFIED_SINCE, required = false) String ifModifiedSince) {
            if (!corrNo.matches("[A-Za-z0-9]{1,20}")) {
                return fallback("BAD_REQUEST", CacheControl.noStore());
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(upstream + "/api/v1/image/photos/" + corrNo + "/file"))
                    .timeout(Duration.ofSeconds(5));
            if (ifNoneMatch != null) {
                b.header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
            }
            if (ifModifiedSince != null) {
                b.header(HttpHeaders.IF_MODIFIED_SINCE, ifModifiedSince);
            }
            HttpResponse<InputStream> r;
            try {
                r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                return fallback("UNAVAILABLE", CacheControl.noStore());   // 연결 실패 — 롤링 배포 · 파드 재시작
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fallback("UNAVAILABLE", CacheControl.noStore());
            }
            int s = r.statusCode();
            if (s == 200 || s == 304) {
                HttpHeaders h = new HttpHeaders();
                RELAY.forEach(name -> r.headers().firstValue(name).ifPresent(v -> h.set(name, v)));
                if (s == 304) {
                    closeQuietly(r.body());
                    return ResponseEntity.status(304).headers(h).build();
                }
                return ResponseEntity.ok().headers(h).body(new InputStreamResource(r.body()));
            }
            lastUpstreamMessage.set(messageOf(r));
            return switch (s) {
                case 404 -> fallback("NOT_FOUND", CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate());
                case 400 -> fallback("BAD_REQUEST", CacheControl.noStore());
                case 403 -> fallback("FORBIDDEN", CacheControl.noStore());   // 보안 이벤트 — 실서비스는 경보
                default -> fallback("UNAVAILABLE", CacheControl.noStore());  // 500 · 503
            };
        }

        /** 오류 본문 {message} — JSON 이 아니면(컨테이너가 거절한 400 의 HTML 등) null. 오류 본문은 짧다. */
        private String messageOf(HttpResponse<InputStream> r) {
            try (InputStream in = r.body()) {
                byte[] b = in.readAllBytes();
                if (!r.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("").startsWith(MediaType.APPLICATION_JSON_VALUE)) {
                    return null;
                }
                return om.readTree(new String(b, StandardCharsets.UTF_8)).path("message").asText(null);
            } catch (IOException e) {
                return null;
            }
        }

        private static ResponseEntity<Resource> fallback(String reason, CacheControl cache) {
            return ResponseEntity.ok().contentType(MediaType.valueOf("image/svg+xml")).cacheControl(cache)
                    .header("X-Photo-Fallback", reason).header("X-Content-Type-Options", "nosniff")
                    .body(new ByteArrayResource(AVATAR));
        }

        private static void closeQuietly(InputStream in) {
            try {
                in.close();
            } catch (IOException ignored) {
                // 304 는 본문이 없다
            }
        }
    }

    private MockMvc browserToProxy(ChatbotPhotoProxy proxy) {
        return MockMvcBuilders.standaloneSetup(proxy).build();
    }

    @Test
    @DisplayName("프록시 200 — 브라우저가 받은 바이트 = 저장 파일, Content-Type · Content-Length · ETag · Cache-Control · nosniff 를 중계")
    void proxyRelaysPhoto() throws Exception {
        MvcResult r = browserToProxy(new ChatbotPhotoProxy(base())).perform(get("/chat/inmates/{c}/photo", JPG))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(jpeg))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, jpeg.length))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        assertThat(r.getResponse().getHeader(HttpHeaders.ETAG)).startsWith("\"2-");
        assertThat(r.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("private").contains("max-age=3600");
        assertThat(r.getResponse().getHeader(HttpHeaders.LAST_MODIFIED)).isNotBlank();
    }

    @Test
    @DisplayName("프록시 304 — 브라우저의 If-None-Match 를 넘기면 수집기 304 를 본문 없이 그대로 중계")
    void proxyRelaysNotModified() throws Exception {
        MockMvc mvc = browserToProxy(new ChatbotPhotoProxy(base()));
        String etag = mvc.perform(get("/chat/inmates/{c}/photo", JPG)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        MvcResult r = mvc.perform(get("/chat/inmates/{c}/photo", JPG).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andReturn();
        assertThat(r.getResponse().getContentAsByteArray()).isEmpty();
    }

    @Test
    @DisplayName("프록시 — 256KB 넘는 사진을 스트림으로 중계해도 바이트 일치")
    void proxyStreamsBigPhoto() throws Exception {
        browserToProxy(new ChatbotPhotoProxy(base())).perform(get("/chat/inmates/{c}/photo", BIG))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, big.length))
                .andExpect(content().bytes(big));
    }

    @Test
    @DisplayName("프록시 404 — 수집기 JSON {message} 를 읽고 대체 이미지 · X-Photo-Fallback: NOT_FOUND · 5분 캐시")
    void proxyFallsBackOnNotFound() throws Exception {
        ChatbotPhotoProxy proxy = new ChatbotPhotoProxy(base());
        browserToProxy(proxy).perform(get("/chat/inmates/{c}/photo", NONE))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/svg+xml"))
                .andExpect(header().string("X-Photo-Fallback", "NOT_FOUND"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, private"))
                .andExpect(content().bytes(ChatbotPhotoProxy.AVATAR));
        assertThat(proxy.lastUpstreamMessage.get()).isEqualTo(MSG_NOT_FOUND);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"STORAGE", "UNSUPPORTED", "DB_DOWN"})
    @DisplayName("프록시 500 · 503 — 대체 이미지 · X-Photo-Fallback: UNAVAILABLE · no-store(복구되면 바로 진짜 사진)")
    void proxyFallsBackOnServerError(String kind) throws Exception {
        ErrorCase c = errorCase(kind);
        ChatbotPhotoProxy proxy = new ChatbotPhotoProxy(base());
        browserToProxy(proxy).perform(get("/chat/inmates/{c}/photo", c.corrNo()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Photo-Fallback", "UNAVAILABLE"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        assertThat(proxy.lastUpstreamMessage.get()).isEqualTo(c.message());
    }

    @Test
    @DisplayName("프록시 — 수집기에 연결 못 함(롤링 배포 · 파드 재시작) → 대체 이미지 · UNAVAILABLE (브라우저엔 500 을 내지 않는다)")
    void proxyFallsBackWhenCollectorDown() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        browserToProxy(new ChatbotPhotoProxy("http://localhost:" + closed)).perform(get("/chat/inmates/{c}/photo", JPG))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Photo-Fallback", "UNAVAILABLE"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }
}
