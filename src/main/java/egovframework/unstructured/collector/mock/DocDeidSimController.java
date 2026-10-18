package egovframework.unstructured.collector.mock;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 문서 비식별 시뮬레이션(9번 탭) 뒷단. dev · local 전용.
 *
 * <p>하는 일은 셋이다.</p>
 * <ol>
 *   <li><b>시험 문서 제공</b> — 레포에 담긴 고정 자산 목록·단건·zip. hwp · hwpx 는 한글 COM 이
 *       필요해 클러스터에서 만들 수 없어 <b>미리 만들어 담아 둔다</b>.</li>
 *   <li><b>커넥터 중계</b> — AI-R 자격증명·프로파일은 커넥터만 가진다. 화면이 AI-R 을 직접
 *       부르면 401 이다. 그래서 수집기가 얇게 중계한다(화면이 수집기에서 열리든 admin-fe
 *       사본에서 열리든 같게 동작해야 한다).</li>
 *   <li><b>경로 A · B 선택</b> — A=AI-R 파일 API, B=커넥터가 추출 후 텍스트 API.
 *       pdf · hwp 는 A 가 HTTP 500 이라 B 만 된다.</li>
 * </ol>
 *
 * <p>중계는 <b>커넥터 응답을 그대로</b> 내려준다. 커넥터가 이미 AI-R 실패를 200 으로 감싸
 * {@code airStatus}·{@code elapsedMs} 를 담아 주므로, 여기서 다시 가공하면 측정값이 흐려진다.</p>
 */
@Log4j2
@Tag(name = "9-3. 문서 비식별 시뮬레이션 (dev·local)",
        description = "시험 문서 제공(단건·zip) · 커넥터 문서 검출 중계(경로 A/B)")
@RestController
@RequestMapping("/api/v1/voice/simulator/doc-deid")
@Profile({"dev", "local"})
public class DocDeidSimController {

    /** 고정 자산 위치 — static 아래라 화면에서 직접 받을 수도 있지만, zip 묶음과 목록은 여기서 낸다. */
    private static final String BASE = "static/samples/문서비식별/";

    /** 자산 이름은 매니페스트에 있는 것만 허용한다 — 경로 조작으로 다른 파일을 꺼내 가면 안 된다. */
    private static final java.util.regex.Pattern SAFE =
            java.util.regex.Pattern.compile("[A-Za-z0-9가-힣_.\\-]{1,80}");

    private final RestTemplate rest;
    private final String connectorBase;

    public DocDeidSimController(
            @Value("${agent-connector.base-url:}") String configuredBase,
            @Value("${doc-deid.connector-base-url:}") String override) {
        String b = !override.isBlank() ? override
                : (!configuredBase.isBlank() ? configuredBase
                        : "http://agent-connector-dp8qbi7xqh:8080");
        this.connectorBase = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;

        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        // 100장이 38.5초다(4단계 실측). 중계가 먼저 끊으면 '커넥터가 느리다' 로 잘못 읽는다.
        f.setReadTimeout(600_000);
        this.rest = new RestTemplate(f);
        log.info("[문서비식별] 커넥터 중계 대상 = {}", this.connectorBase);
    }

    // ---------------------------------------------------------------- 시험 문서

    @Operation(summary = "시험 문서 목록",
            description = "레포에 담긴 고정 자산의 매니페스트를 그대로 돌려준다(이름·묶음·설명·크기·정답표).")
    @GetMapping(value = "/samples", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> samples() throws IOException {
        return ResponseEntity.ok(text(BASE + "samples.json"));
    }

    @Operation(summary = "시험 문서 한 건 내려받기")
    @GetMapping("/samples/{name}")
    public ResponseEntity<byte[]> sample(@org.springframework.web.bind.annotation.PathVariable String name)
            throws IOException {
        if (!SAFE.matcher(name).matches()) {
            return ResponseEntity.badRequest().build();
        }
        byte[] b = bytes(BASE + name);
        if (b == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(name))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(b);
    }

    @Operation(summary = "시험 문서 여러 건 zip 으로",
            description = """
                    `names` 에 쉼표로 이름을 넘긴다. 비우면 전부.
                    **정답표를 늘 같이 넣는다** — 채팅 실화면에서 같은 파일로 시험하고 같은 정답으로
                    채점할 수 있어야 하기 때문이다. 정답표 없이 문서만 내려가면 숫자를 못 맞춘다.""")
    @GetMapping("/samples.zip")
    public ResponseEntity<byte[]> zip(@RequestParam(required = false) String names) throws IOException {
        List<String> want = new ArrayList<>();
        if (names == null || names.isBlank()) {
            for (String n : manifestNames()) {
                want.add(n);
            }
        } else {
            for (String n : names.split(",")) {
                String t = n.trim();
                if (!t.isEmpty() && SAFE.matcher(t).matches()) {
                    want.add(t);
                }
            }
        }
        // 정답표와 매니페스트는 늘 넣는다
        for (String must : List.of("samples.json", "probe_answers.json", "table_cases.json")) {
            if (!want.contains(must)) {
                want.add(must);
            }
        }

        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(buf, StandardCharsets.UTF_8)) {
            for (String n : want) {
                byte[] b = bytes(BASE + n);
                if (b == null) {
                    continue;
                }
                z.putNextEntry(new ZipEntry(n));
                z.write(b);
                z.closeEntry();
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment("문서비식별_시험문서.zip"))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(buf.toByteArray());
    }

    // ---------------------------------------------------------------- 커넥터 중계

    @Operation(summary = "문서 검출 — 커넥터로 중계(경로 A/B)",
            description = """
                    `path=A` → 커넥터 `/api/v1/deid/air/detect-file`(AI-R 파일 API)
                    `path=B` → 커넥터 `/api/v1/deid/doc/detect`(커넥터가 추출 후 텍스트 API)

                    pdf · hwp 는 A 가 HTTP 500 이다(AI-R 패키지 결함 · 2026-10-18 재확인) — B 만 된다.
                    커넥터 응답을 그대로 내려준다. 커넥터가 이미 실패를 200 으로 감싸
                    airStatus · elapsedMs 를 담으므로 여기서 다시 가공하면 측정값이 흐려진다.""")
    @PostMapping(value = "/detect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> detect(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "path", defaultValue = "B") String path,
            @RequestParam(name = "server", defaultValue = "dev") String server,
            @RequestParam(name = "masking", defaultValue = "true") boolean masking) throws IOException {

        String url = "A".equalsIgnoreCase(path)
                ? connectorBase + "/api/v1/deid/air/detect-file?server=" + server + "&masking=" + masking
                : connectorBase + "/api/v1/deid/doc/detect?server=" + server + "&masking=" + masking;

        String filename = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(file.getBytes()) {
            @Override public String getFilename() { return filename; }
        });
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);

        try {
            ResponseEntity<String> up = rest.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(parts, h), String.class);
            return ResponseEntity.status(up.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(up.getBody());
        } catch (HttpStatusCodeException e) {
            // 커넥터가 4xx·5xx 를 주면 상태·본문을 그대로 넘긴다. 503 으로 뭉개면
            //   '동시 한계에 걸렸다' 와 '커넥터가 죽었다' 를 화면에서 구분할 수 없다.
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            log.warn("[문서비식별] 중계 실패 {} — {}", filename, e.toString());
            return ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":true,\"message\":\"커넥터 중계 실패\",\"detail\":"
                            + jsonString(e.toString()) + "}");
        }
    }

    // ---------------------------------------------------------------- 공통

    private List<String> manifestNames() throws IOException {
        List<String> out = new ArrayList<>();
        String json = text(BASE + "samples.json");
        // 매니페스트에서 "name": "..." 만 뽑는다. 작은 파일이라 파서를 끌어오지 않는다.
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String text(String path) throws IOException {
        byte[] b = bytes(path);
        return b == null ? "{}" : new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String path) throws IOException {
        ClassPathResource r = new ClassPathResource(path);
        if (!r.exists()) {
            return null;
        }
        try (InputStream in = r.getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** 한글 파일명이 깨지지 않게 RFC 5987 로 싣는다. */
    private static String attachment(String name) {
        String enc = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + name.replaceAll("[^A-Za-z0-9._\\-]", "_")
                + "\"; filename*=UTF-8''" + enc;
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
