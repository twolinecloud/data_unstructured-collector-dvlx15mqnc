package egovframework.unstructured.collector.mock;

import egovframework.unstructured.collector.voice.stt.genon.AudioValidationException;
import egovframework.unstructured.collector.voice.stt.genon.GenonSttException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 음성 STT 시뮬레이션(11번 탭) 뒷단 — 제논(GenOS) 음성 API 실호출 · 사전 검증 · 경계 샘플. dev · local 전용.
 *
 * <p><b>REST 모드는 외부망(genos.genon.ai)으로 파일을 보낸다.</b> 실제 수용자 음성을 올리지 않는다 — 생성 샘플이나
 * 합성 낭독으로 시험한다. 원래 파일 이름은 제논으로 보내지 않는다({@code audio.wav} 처럼 내용 기준 이름만).</p>
 *
 * <p>상태 코드 — 400 사전 검증 위반(보내지 않음) · 요청 오류 / 404 샘플 없음 / 409 설정(키 없음) / 422 제논이 거절 /
 * 502 제논 인증 · 서버 · 연결 오류 / 503 동시 한도 · 차례 대기 초과 / 504 응답 타임아웃.
 * 413(업로드 한도 25MB 초과)은 핸들러 앞에서 나므로 {@code UploadLimitAdvice} 가 낸다.</p>
 */
@Log4j2
@Tag(name = "9-5. 음성 STT 시뮬레이션 — 제논 (dev·local)",
        description = "제논 음성 API 실호출(MOCK·REST) · 사전 검증(포맷·20MB·600초) · 경계 샘플 생성")
@RestController
@RequestMapping(value = "/api/v1/voice/simulator/stt", produces = MediaType.APPLICATION_JSON_VALUE)
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class SttSimController {

    private final SttSimService service;

    @Operation(summary = "상태 — 호출 모드 · 주소 · 키(가림) · 한도 · 진행 중 건수",
            description = """
                    `probe=true` 면 제논 `GET /health` 를 실제로 부른다 — 이 경로가 토큰을 검사하므로(없음 · 틀림 → 401)
                    연결과 토큰 유효를 한 번에 본다. 다른 호출이 진행 중이면 건너뛴다(동시 1건 한도).
                    키 원문은 내려가지 않는다(앞 4 · 뒤 4 글자만).""")
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(name = "probe", defaultValue = "false") boolean probe) {
        return service.status(probe);
    }

    @Operation(summary = "호출 모드 바꾸기 — MOCK · REST",
            description = """
                    MOCK — 외부망을 부르지 않고 제논 모양 응답을 돌려준다. REST — 실제로 부른다.
                    키(GENON_STT_API_KEY)가 없으면 REST 로 못 바꾼다(409). 재기동하면 설정값(genon.stt.mode)으로 돌아간다.
                    배치가 GENON 을 쓰게 하려면 STT 스위치(`PUT /api/v1/mock/modes/stt?value=GENON`)도 바꿔야 한다.""")
    @PutMapping("/mode")
    public Map<String, Object> mode(@RequestParam String value) {
        return service.setMode(value);
    }

    @Operation(summary = "경계 샘플 만들기",
            description = """
                    `kind` — 비우거나 ALL 이면 전부. `WAV_3S` · `MP3_3S` · `M4A_3S` · `OVER_SIZE`(20.5MiB) ·
                    `OVER_DURATION`(610초) · `UNSUPPORTED`(OGG + 텍스트) · `SPEECH_22S`(합성 낭독).
                    `{ROOT}/mock/stt_samples` 에 만든다(같은 이름은 덮어씀). 경계 샘플은 한 가지만 넘게 만든다 —
                    20MB 초과는 약 112초, 600초 초과는 18.6MiB(크기 통과).""")
    @PostMapping("/generate-samples")
    public Map<String, Object> generate(@RequestParam(name = "kind", required = false) String kind) throws IOException {
        return service.generate(kind);
    }

    @Operation(summary = "샘플 목록 — 만들어진 것은 사전 검증 결과를 붙인다")
    @GetMapping("/samples")
    public List<Map<String, Object>> samples() {
        return service.samples();
    }

    @Operation(summary = "샘플 한 건 내려받기(미리 듣기용)")
    @GetMapping(value = "/samples/{name}", produces = MediaType.ALL_VALUE)
    public ResponseEntity<FileSystemResource> sample(@PathVariable String name) {
        Path p = service.samplePath(name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + p.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(SttSimService.mimeOf(p.getFileName().toString())))
                .body(new FileSystemResource(p));
    }

    @Operation(summary = "샘플 지우기")
    @DeleteMapping("/samples")
    public Map<String, Object> deleteSamples() throws IOException {
        return Map.of("deleted", service.deleteSamples());
    }

    @Operation(summary = "STT 호출 — 업로드 파일 또는 생성 샘플",
            description = """
                    multipart — `file`(업로드) 과 `sample`(생성 샘플 이름) 중 **하나만**.
                    `api` — `transcribe`(기본 · POST /audio/transcribe) · `process`(POST /audio/process).
                    process 는 `contactId`(기본 CONTACT-SIM-001) · `contactType`(접견 · 전화) · `occurredAt`
                    (`2026-11-06T10:00` 은 KST 로 봄 · 비면 지금)을 함께 보낸다.

                    먼저 사전 검증(확장자 · MIME · 내용 / 20MiB / 600초)을 하고, 위반이면 **보내지 않고 400** 이다.
                    `skipValidation=true` 면 검증을 건너뛰고 보낸다 — 제논이 직접 거절하는지(422) 볼 때만.

                    응답 — `httpStatus`(제논) · `elapsedMs` · `queuedMs`(동시 1건 차례 대기) · `attempts`(동시 한도로 다시 보낸
                    횟수 포함) · `audio`(검증 결과) · `transcript`(text · segments) · `response`(제논 본문 그대로).""")
    @PostMapping(value = "/call", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> call(
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "sample", required = false) String sample,
            @RequestParam(name = "api", defaultValue = "transcribe") String api,
            @RequestParam(name = "contactId", defaultValue = "CONTACT-SIM-001") String contactId,
            @RequestParam(name = "contactType", defaultValue = "접견") String contactType,
            @RequestParam(name = "occurredAt", required = false) String occurredAt,
            @RequestParam(name = "skipValidation", defaultValue = "false") boolean skipValidation) throws IOException {
        return service.call(file, sample, api, contactId, contactType, occurredAt, skipValidation);
    }

    // ---------------------------------------------------------------- 실패 모양

    @ExceptionHandler(AudioValidationException.class)
    public ResponseEntity<Map<String, Object>> invalid(AudioValidationException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("stage", "VALIDATION");
        m.put("message", "사전 검증에서 막혔다 — 제논으로 보내지 않았다");
        m.put("detail", e.check().violationText());
        m.put("audio", SttSimService.view(e.check()));
        return ResponseEntity.badRequest().body(m);
    }

    @ExceptionHandler(GenonSttException.class)
    public ResponseEntity<Map<String, Object>> genon(GenonSttException e) {
        HttpStatus status = switch (e.kind()) {
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case BUSY, QUEUE_TIMEOUT -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case CONFIG -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("stage", "GENON");
        m.put("kind", e.kind().name());
        m.put("message", e.getMessage());
        m.put("upstreamStatus", e.httpStatus());
        m.put("errorCode", e.errorCode());
        m.put("hint", e.hint());
        m.put("attempts", e.attempts());
        m.put("elapsedMs", e.elapsedMs());
        m.put("upstreamBody", e.upstreamBody());
        return ResponseEntity.status(status).body(m);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        log.warn("[STT시뮬] 요청 거절(400) — {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("ok", false, "stage", "REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> missing(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("ok", false, "stage", "REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> state(IllegalStateException e) {
        log.warn("[STT시뮬] 거절(409) — {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("ok", false, "stage", "CONFIG", "message", e.getMessage()));
    }
}
