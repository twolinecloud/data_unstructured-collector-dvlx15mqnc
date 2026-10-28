package egovframework.unstructured.collector.image;

import egovframework.unstructured.collector.common.util.SampleImage;
import egovframework.unstructured.collector.image.controller.ImagePhotoFileController;
import egovframework.unstructured.collector.image.store.ImageFileStore;
import egovframework.unstructured.collector.image.store.InmatePhotoRepository;
import egovframework.unstructured.collector.image.store.PhotoFileLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 챗봇 연동 사진 파일 API — {@code GET /api/v1/image/photos/{corrNo}/file}.
 *
 * <p>매핑(Admin DB)은 목으로, 저장소는 임시 폴더의 실제 파일로 둔다 — 정규화 · 경로 이탈 검사가 진짜 파일 시스템에서 돈다.
 * 저장 규칙은 수집과 같다: {@code {루트}/{교정번호}/{교정번호}_{순번}.{확장자}}.</p>
 */
class ImagePhotoFileControllerTest {

    private static final String URL = "/api/v1/image/photos/{corrNo}/file";

    @TempDir
    Path tmp;

    private Path base;
    private InmatePhotoRepository photos;
    private ImageFileStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(tmp.resolve("image"));
        photos = mock(InmatePhotoRepository.class);
        store = mock(ImageFileStore.class);
        when(store.outputRoot()).thenReturn(base);
        mvc = MockMvcBuilders.standaloneSetup(new ImagePhotoFileController(new PhotoFileLocator(photos, store))).build();
    }

    /** 수집과 같은 자리에 사진을 둔다. */
    private Path storePhoto(String corrNo, int sn, String ext, byte[] bytes) throws IOException {
        Path dir = Files.createDirectories(base.resolve(corrNo));
        return Files.write(dir.resolve(corrNo + "_" + sn + "." + ext), bytes);
    }

    /** 매핑 한 행 — 수집기처럼 슬래시 경로로 적는다. */
    private void map(String corrNo, int sn, String photoPath) {
        when(photos.findMapped(corrNo)).thenReturn(Optional.of(
                new InmatePhotoRepository.Mapped(corrNo, sn, "key-" + corrNo, photoPath.replace('\\', '/'))));
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    // ── 200 · 304 ──

    @Test
    @DisplayName("200 — 저장된 JPEG 를 그대로 · image/jpeg · Cache-Control private, max-age=3600 · ETag · nosniff")
    void servesJpeg() throws Exception {
        byte[] jpeg = SampleImage.jpeg("A");
        Path p = storePhoto("127040220200103500", 3, "jpg", jpeg);
        map("127040220200103500", 3, p.toString());

        mvc.perform(get(URL, "127040220200103500"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(jpeg))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, jpeg.length))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, allOf(containsString("private"), containsString("max-age=3600"))))
                .andExpect(header().string(HttpHeaders.ETAG, matchesPattern("\"3-[0-9a-f]+-[0-9a-f]+\"")))
                .andExpect(header().exists(HttpHeaders.LAST_MODIFIED))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("200 — PNG 는 image/png (확장자로 판별)")
    void servesPng() throws Exception {
        byte[] png = png();
        Path p = storePhoto("SIMIMG00001", 1, "png", png);
        map("SIMIMG00001", 1, p.toString());

        mvc.perform(get(URL, "SIMIMG00001"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png));
    }

    @Test
    @DisplayName("304 — If-None-Match 가 지금 ETag 와 같으면 본문 없이 304 (파일을 다시 보내지 않는다)")
    void notModifiedForSameEtag() throws Exception {
        Path p = storePhoto("DMYIMG2610260001", 2, "jpg", SampleImage.jpeg("B"));
        map("DMYIMG2610260001", 2, p.toString());
        MvcResult first = mvc.perform(get(URL, "DMYIMG2610260001")).andExpect(status().isOk()).andReturn();
        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotBlank();

        MvcResult again = mvc.perform(get(URL, "DMYIMG2610260001").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andReturn();
        assertThat(again.getResponse().getContentAsByteArray()).isEmpty();
    }

    @Test
    @DisplayName("200 — 사진이 바뀌면(새 순번 · 크기) ETag 도 바뀌어 옛 ETag 로는 304 가 아니다")
    void etagChangesWhenPhotoChanges() throws Exception {
        Path p1 = storePhoto("C1", 1, "jpg", SampleImage.jpeg("old"));
        map("C1", 1, p1.toString());
        String oldTag = mvc.perform(get(URL, "C1")).andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        Files.delete(p1);
        Path p2 = storePhoto("C1", 2, "jpg", SampleImage.jpeg("new photo"));
        map("C1", 2, p2.toString());

        mvc.perform(get(URL, "C1").header(HttpHeaders.IF_NONE_MATCH, oldTag))
                .andExpect(status().isOk())
                .andExpect(content().bytes(Files.readAllBytes(p2)));
    }

    // ── 404 ──

    @Test
    @DisplayName("404 — 매핑이 없다")
    void notFoundWithoutMapping() throws Exception {
        mvc.perform(get(URL, "DMYIMG2610260009"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("해당 수용자의 사진이 없습니다."));
    }

    @Test
    @DisplayName("404 — 매핑은 있는데 디스크에 파일이 없다")
    void notFoundWhenFileMissing() throws Exception {
        Files.createDirectories(base.resolve("C1"));
        map("C1", 2, base.resolve("C1").resolve("C1_2.jpg").toString());

        mvc.perform(get(URL, "C1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("해당 수용자의 사진이 없습니다."));
    }

    @Test
    @DisplayName("404 — 매핑 경로가 파일이 아니라 폴더다")
    void notFoundWhenPathIsDirectory() throws Exception {
        Path dir = Files.createDirectories(base.resolve("C1").resolve("C1_2.jpg"));
        map("C1", 2, dir.toString());

        mvc.perform(get(URL, "C1")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("오류도 JSON — Accept: image/jpeg 로 불러도 406 이 아니라 404 · {message}")
    void errorIsJsonEvenWhenClientAcceptsOnlyImages() throws Exception {
        mvc.perform(get(URL, "C404").accept(MediaType.IMAGE_JPEG))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("해당 수용자의 사진이 없습니다."));
    }

    // ── 400 · 403 — 경로 조작 ──

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"..", "...", "a.b", "C1.jpg", "..\\..\\windows\\win.ini", "a b", "a-b", "a_b", "한글",
            "C1%00", "123456789012345678901"})
    @DisplayName("400 — 교정번호에 특수문자 · 상위 폴더 탐색 · 20자 초과 → DB 를 부르기 전에 거절")
    void badRequestForInvalidCorrNo(String corrNo) throws Exception {
        mvc.perform(get(URL, corrNo))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("교정번호 형식을 확인해 주세요."));
        verifyNoInteractions(photos, store);
    }

    /** 슬래시 · NUL 은 경로 변수 템플릿이 그대로 두므로(경로가 바뀐다) 인코딩한 URI 를 직접 보낸다. */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"%2e%2e", "..%2F..%2Fetc%2Fpasswd", "C1%2F..%2FC2", "..%5C..%5Cwin.ini", "C1%00", "%2E%2E%2F"})
    @DisplayName("400 — 인코딩한 상위 폴더(%2e%2e · %2F · %5C) · NUL(%00)도 디코딩 뒤 형식 검사에 걸린다")
    void badRequestForEncodedTraversal(String rawSegment) throws Exception {
        mvc.perform(get(URI.create("/api/v1/image/photos/" + rawSegment + "/file")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("교정번호 형식을 확인해 주세요."));
        verifyNoInteractions(photos, store);
    }

    @Test
    @DisplayName("403 — 매핑 경로가 저장소 밖을 가리킨다 (본문에 경로를 싣지 않는다)")
    void forbiddenOutsideStore() throws Exception {
        Path secret = Files.write(Files.createDirectories(tmp.resolve("outside")).resolve("secret.jpg"), SampleImage.jpeg("S"));
        map("C1", 2, secret.toString());

        MvcResult r = mvc.perform(get(URL, "C1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("허용되지 않은 사진 경로입니다."))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("outside", "secret", tmp.getFileName().toString());
    }

    @Test
    @DisplayName("403 — 매핑 경로의 .. 로 저장소를 빠져나간다 (정규화 뒤 검사)")
    void forbiddenDotDotInMappedPath() throws Exception {
        Files.write(Files.createDirectories(tmp.resolve("outside")).resolve("secret.jpg"), SampleImage.jpeg("S"));
        Files.createDirectories(base.resolve("C1"));
        map("C1", 2, base + "/C1/../../outside/secret.jpg");

        mvc.perform(get(URL, "C1")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("403 — 저장소 안이라도 다른 수용자 폴더 · 하위 폴더를 가리키면 내주지 않는다")
    void forbiddenOtherInmateFolder() throws Exception {
        Path other = storePhoto("C2", 1, "jpg", SampleImage.jpeg("C2"));
        map("C1", 1, other.toString());
        mvc.perform(get(URL, "C1")).andExpect(status().isForbidden());

        Path nested = Files.write(Files.createDirectories(base.resolve("C3").resolve("sub")).resolve("C3_1.jpg"),
                SampleImage.jpeg("C3"));
        map("C3", 1, nested.toString());
        mvc.perform(get(URL, "C3")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("403 — 수용자 폴더의 심볼릭 링크가 저장소 밖 파일을 가리킨다")
    void forbiddenSymlinkEscape() throws Exception {
        Path secret = Files.write(Files.createDirectories(tmp.resolve("outside")).resolve("secret.jpg"), SampleImage.jpeg("S"));
        Path link = Files.createDirectories(base.resolve("C1")).resolve("C1_2.jpg");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "이 환경에서는 심볼릭 링크를 만들 수 없다(윈도 권한) — " + e.getMessage());
        }
        map("C1", 2, link.toString());

        mvc.perform(get(URL, "C1")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("403 — 수용자 폴더 자체가 저장소 밖으로 이어진 링크 · 정션이다 (윈도 getCanonicalPath 가 못 푸는 경우 — 실제 경로로 막는다)")
    void forbiddenLinkedInmateFolder() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.write(outside.resolve("C1_2.jpg"), SampleImage.jpeg("S"));
        Path link = base.resolve("C1");
        linkDirectory(link, outside);
        try {
            map("C1", 2, link.resolve("C1_2.jpg").toString());

            mvc.perform(get(URL, "C1"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("허용되지 않은 사진 경로입니다."));
        } finally {
            unlinkDirectory(link);
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** 폴더 링크 — 심볼릭 링크를 먼저, 안 되면(윈도 권한) 권한이 필요 없는 정션(mklink /J). 둘 다 안 되면 건너뛴다. */
    private static void linkDirectory(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(windows(), "폴더 링크를 만들 수 없다 — " + e.getMessage());
        }
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue(p.waitFor() == 0 && Files.isDirectory(link), "정션을 만들 수 없다");
    }

    /** 링크만 지운다(대상은 그대로) — 임시 폴더 정리가 링크를 따라 들어가지 않게. */
    private static void unlinkDirectory(Path link) throws Exception {
        if (windows()) {
            new ProcessBuilder("cmd", "/c", "rmdir", link.toString()).redirectErrorStream(true).start().waitFor();
        } else {
            Files.deleteIfExists(link);
        }
    }

    // ── 500 · 503 ──

    @Test
    @DisplayName("500 — PV 디스크 I/O 오류(정규화 중 Stale file handle) · 본문에 내부 사유를 싣지 않는다")
    void storageErrorIs500() throws Exception {
        map("C1", 2, base.resolve("C1").resolve("C1_2.jpg").toString());
        Path broken = mock(Path.class);
        when(broken.toFile()).thenReturn(new File("broken-pv") {
            @Override
            public File getCanonicalFile() throws IOException {
                throw new IOException("Stale file handle");
            }
        });
        when(store.outputRoot()).thenReturn(broken);

        MvcResult r = mvc.perform(get(URL, "C1"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("사진 저장소를 읽지 못했습니다. 잠시 후 다시 시도해 주세요."))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("Stale", "broken-pv");
    }

    @Test
    @DisplayName("500 — 저장소 안에 놓인 이미지 아닌 파일(.html)은 형식을 정해 내주지 않는다")
    void nonImageFileIsNotServed() throws Exception {
        Path html = Files.writeString(Files.createDirectories(base.resolve("C1")).resolve("C1_2.html"), "<script>alert(1)</script>");
        map("C1", 2, html.toString());

        mvc.perform(get(URL, "C1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("사진 파일 형식을 확인할 수 없습니다."));
    }

    @Test
    @DisplayName("503 — 매핑 조회(Admin DB) 실패 · 본문에 DB 사유를 싣지 않는다")
    void dbErrorIs503() throws Exception {
        when(photos.findMapped("C1")).thenThrow(new DataAccessResourceFailureException("Connection refused: admin-db:5432"));

        MvcResult r = mvc.perform(get(URL, "C1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("사진 정보를 조회하지 못했습니다. 잠시 후 다시 시도해 주세요."))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("refused", "5432");
    }
}
