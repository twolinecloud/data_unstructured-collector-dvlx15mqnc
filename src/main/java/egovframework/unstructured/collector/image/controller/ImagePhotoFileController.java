package egovframework.unstructured.collector.image.controller;

import egovframework.unstructured.collector.image.store.PhotoFileLocator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * 수용자 사진 파일 제공 — 챗봇 연동.
 *
 * <p>챗봇 백엔드 파드는 이 수집기의 PV 를 마운트하지 못한다. 그래서 이미지 수집이 PV 에 저장하고 Admin DB
 * ({@code TB_SRC_INMATE_PHOTO})에 매핑한 사진을 이 API 가 파일 그대로 내준다. 클러스터 안에서만 부른다
 * (서비스는 ClusterIP · Ingress 없음).</p>
 * <ul>
 *   <li>본문은 {@link FileSystemResource} 로 스트리밍한다 — 사진을 {@code byte[]} 로 힙에 올리지 않는다</li>
 *   <li>{@code Cache-Control: private, max-age=3600} · {@code ETag} · {@code Last-Modified} — 같은 사진을 다시 부르면
 *       클라이언트 캐시가 받고, 캐시가 지난 뒤에도 {@code If-None-Match} 가 맞으면 304(파일을 열지 않는다)</li>
 *   <li>{@code X-Content-Type-Options: nosniff} — 선언한 이미지 형식으로만 해석하게</li>
 *   <li>오류 본문은 {@code {message}} 하나 — 짧은 안내만, 경로 같은 내부 값은 서버 로그에만</li>
 * </ul>
 *
 * <p>JSON 컨트롤러({@link ImageController} — 클래스 단위 {@code produces=application/json})와 나눈 이유:
 * 이 API 는 이미지를 내므로 {@code Accept: image/*} 요청도 받아야 하고, 오류는 그때도 JSON 으로 내야 한다.</p>
 */
@Log4j2
@Tag(name = "2-1. 수용자 사진 파일(챗봇 연동)",
        description = "이미지 수집이 PV 에 저장한 수용자 사진을 파일로 내준다 — 챗봇 백엔드는 PV 를 직접 읽지 못한다")
@RestController
@RequestMapping("/api/v1/image")
@RequiredArgsConstructor
public class ImagePhotoFileController {

    /** 캐시 유지 1시간 — 사진은 하루 한 번(정기 배치) 바뀔 수 있다. 수용자 사진이라 공유 캐시엔 두지 않는다(private). */
    static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePrivate();

    static final String MSG_DB = "사진 정보를 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    private final PhotoFileLocator locator;

    @Operation(summary = "수용자 사진 파일 (챗봇 연동)",
            description = """
                    매핑(`TB_SRC_INMATE_PHOTO`)의 저장 경로에서 사진 파일을 그대로 내준다 — `image/jpeg` · `image/png` 등.

                    - 클러스터 안: `http://unstructured-collector-dvlx15mqnc.data-pipeline.svc.cluster.local:8080/api/v1/image/photos/{corrNo}/file`
                    - `corrNo` — 영숫자 1~20자만(특수문자 · `..` 는 DB 조회 전에 400)
                    - 매핑 경로는 정규화(`getCanonicalPath`)해 저장소 루트 · 그 수용자 폴더 안인지 확인한다 — 벗어나면 403
                    - `Cache-Control: private, max-age=3600` · `ETag` — `If-None-Match` 가 맞으면 304
                    - 오류 본문은 `{"message": "…"}` 하나(내부 경로는 서버 로그에만)
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "사진 파일",
                    content = {@Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary"))}),
            @ApiResponse(responseCode = "304", description = "If-None-Match 가 지금 ETag 와 같다 — 본문 없음", content = @Content),
            @ApiResponse(responseCode = "400", description = "교정번호 형식 오류(특수문자 · 상위 폴더 탐색 · 20자 초과)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
            @ApiResponse(responseCode = "403", description = "매핑 경로가 저장소 · 그 수용자 폴더를 벗어남 · 심볼릭 링크",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
            @ApiResponse(responseCode = "404", description = "매핑이 없거나 디스크에 파일이 없다",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
            @ApiResponse(responseCode = "500", description = "PV 디스크 I/O 오류 등 저장소 오류 · 알 수 없는 파일 형식",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
            @ApiResponse(responseCode = "503", description = "매핑 조회(Admin DB) 실패",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
    })
    @GetMapping("/photos/{corrNo}/file")
    public ResponseEntity<Resource> file(
            @Parameter(description = "교정번호 — 영숫자 1~20자", example = "127040220200103500") @PathVariable String corrNo) {
        PhotoFileLocator.PhotoFile f = locator.locate(corrNo);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CACHE)
                .eTag(f.etag())
                .lastModified(f.lastModified())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(f.path()));
    }

    @ExceptionHandler(PhotoFileLocator.Rejection.class)
    public ResponseEntity<Map<String, Object>> rejected(PhotoFileLocator.Rejection e) {
        HttpStatus status = statusOf(e.reason());
        switch (e.reason()) {
            case NOT_MAPPED -> log.info("[Image] 사진 파일 {} — {}", status.value(), e.detail());
            case STORAGE_ERROR -> log.error("[Image] 사진 파일 {} — {}", status.value(), e.detail(), e.getCause());
            default -> log.warn("[Image] 사진 파일 {} — {}", status.value(), e.detail());
        }
        return json(status, e.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> dataAccess(DataAccessException e) {
        log.warn("[Image] 사진 파일 503 — 매핑 조회 실패 ({})", e.getMostSpecificCause().toString());
        return json(HttpStatus.SERVICE_UNAVAILABLE, MSG_DB);
    }

    static HttpStatus statusOf(PhotoFileLocator.Reason reason) {
        return switch (reason) {
            case INVALID_CORR_NO -> HttpStatus.BAD_REQUEST;
            case NOT_MAPPED, FILE_MISSING -> HttpStatus.NOT_FOUND;
            case PATH_ESCAPE -> HttpStatus.FORBIDDEN;
            case STORAGE_ERROR, UNSUPPORTED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** 오류 본문 — 형식을 JSON 으로 못 박는다. {@code Accept: image/*} 로 부른 클라이언트에게도 406 대신 이 본문이 간다. */
    private static ResponseEntity<Map<String, Object>> json(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("message", message));
    }
}
