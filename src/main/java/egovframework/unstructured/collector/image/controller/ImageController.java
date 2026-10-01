package egovframework.unstructured.collector.image.controller;

import egovframework.unstructured.collector.common.broker.XvarmBrokerClient;
import egovframework.unstructured.collector.common.config.DbKindDetector;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.image.batch.ImageTrace;
import egovframework.unstructured.collector.image.config.AdminDb;
import egovframework.unstructured.collector.image.config.ImageProperties;
import egovframework.unstructured.collector.image.sim.ImageSimulationService;
import egovframework.unstructured.collector.image.source.ImageSourceService;
import egovframework.unstructured.collector.image.store.ImageFileStore;
import egovframework.unstructured.collector.image.store.InmatePhotoRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 수용자 이미지 수집 API — 보라미 최신 사진을 받아 복호화·저장하고 Admin DB 에 매핑한다.
 */
@Tag(name = "2. 수용자 이미지 수집",
        description = "보라미 TB_IRIM_BSIF_DS(사진) → TB_SMSM_CMFI_BS(DOC_ID) → ASYSCONTENTELEMENT(FILEKEY) → 브로커 수신 → "
                + "접견과 같은 복호화 → Target 저장 → Admin DB TB_SRC_INMATE_PHOTO 매핑")
@RestController
@RequestMapping(value = "/api/v1/image", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ImageController {

    private final ImageCollectService collect;
    private final InmatePhotoRepository photos;
    private final AdminDb adminDb;
    private final ImageSourceService source;
    private final ImageFileStore store;
    private final ImageProperties props;
    private final VoiceModeState modes;
    private final XvarmBrokerClient broker;
    private final DbKindDetector dbKind;
    private final ImageSimulationService sim;
    private final ImageTrace trace;

    @Operation(summary = "지금 구성 · Admin DB 상태",
            description = "원천(보라미) DB · 조회 테이블 · 브로커·복호화 모드 · 저장소 · Admin DB 연결과 매핑 테이블 존재 여부. "
                    + "`admin.photoTable=false` 면 DDL(`db/admin/V1__tb_src_inmate_photo.sql`)을 먼저 적용해야 한다.")
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", dbKind.label());
        m.put("tables", source.tables());
        m.put("broker", broker.mode());
        m.put("decrypt", modes.decrypt().name());
        m.put("outputRoot", store.outputRoot().toString().replace('\\', '/'));
        m.put("workers", props.workers());
        m.put("updatePhotoRef", props.updatePhotoRef());
        Map<String, Object> admin = adminDb.probe();
        if (Boolean.TRUE.equals(admin.get("photoTable"))) {
            admin.put("mapping", photos.stats("SIMIMG"));
        }
        m.put("admin", admin);
        m.put("mapTable", adminDb.table(AdminDb.PHOTO_TABLE));
        m.put("upsertMode", photos.upsertMode());
        m.put("sim", sim.residual());
        m.put("running", collect.isRunning());
        return m;
    }

    @Operation(summary = "수용자 이미지 수집 실행 (동기)",
            description = """
                    최신 사진을 골라 받아 저장하고 매핑한다. 끝날 때까지 돌아오지 않는다 — 진행은 `GET /progress`.

                    - `corrNos` 를 주면 그 수용자만, 비우면 전체(최대 `image.max-per-run`)
                    - 매핑된 순번·FILEKEY 가 같고 저장 파일이 있으면 **건너뜀**(다시 받지 않음) — `force=true` 면 다시 받는다
                    - 다시 받는 건은 매핑을 UPSERT 한다 — 몇 번 다시 돌려도 키 중복 없이 있으면 갱신 · 없으면 넣기(멱등)
                    - 더 최신 사진이 이미 매핑된 수용자는 덮지 않는다(STALE)
                    - 한 건의 오류는 그 건만 실패로 남기고 다음 건을 계속한다
                    - 6번 탭이 남겨 둔 SIM(`SIMIMG…`) 행은 대상에서 뺀다
                    - 409 — 이미 돌고 있음
                    """)
    @PostMapping("/batches")
    public ImageCollectService.ImageRunResult run(@RequestBody(required = false) ImageCollectService.ImageRunRequest req) {
        ImageCollectService.ImageRunRequest r = req == null ? null : new ImageCollectService.ImageRunRequest(
                req.corrNos(), req.corrNoPrefix(), req.limit(), req.workers(), req.force(), req.virtualLatencyMs(), "API", false, false,
                null);
        return collect.run(r);
    }

    @Operation(summary = "진행 상황")
    @GetMapping("/progress")
    public Map<String, Object> progress() {
        return collect.progress();
    }

    @Operation(summary = "단계별 실행 기록",
            description = "마지막(또는 진행 중) 수집이 실제로 수행한 명령과 결과 — ① 최신 이미지 조회 SQL(첫 페이지) · ②③ DOC_ID/FILEKEY 조인 SQL · "
                    + "④ 브로커 추출 cURL · 수신 파일 · 복호화 · ⑤ 저장 파일 · UPSERT SQL · 매핑 확인(첫 건 표본). "
                    + "SQL 은 값을 채운 형태라 그대로 복사해 DB 에서 돌려 대조할 수 있다. `after` 뒤의 seq 만 돌려준다.")
    @GetMapping("/trace")
    public Map<String, Object> trace(@RequestParam(defaultValue = "0") int after) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("execId", trace.execId());
        m.put("entries", trace.since(Math.max(0, after)));
        return m;
    }

    @Operation(summary = "중단", description = "처리 중인 건은 끝까지, 시작하지 않은 건은 '건너뜀'.")
    @PostMapping("/cancel")
    public Map<String, Object> cancel() {
        return Map.of("accepted", collect.cancel());
    }

    @Operation(summary = "수용자 한 명의 사진 매핑")
    @GetMapping("/photos/{corrNo}")
    public ResponseEntity<Map<String, Object>> photo(@PathVariable String corrNo) {
        Map<String, Object> row = photos.find(corrNo);
        return row.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(row);
    }

    @Operation(summary = "TB_SRC_INMATE_BS.PHOTO_REF 일괄 반영(선택)",
            description = "매핑 테이블의 저장 경로를 수용자 기본의 PHOTO_REF 로 옮긴다 — 값이 다른 행만. "
                    + "정형 수집기가 PHOTO_REF 를 비우며 다시 썼다면 이것을 다시 돌리면 된다.")
    @PostMapping("/photo-ref/sync")
    public Map<String, Object> syncPhotoRef() {
        return Map.of("updated", photos.syncPhotoRef());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, Object>> dataAccess(org.springframework.dao.DataAccessException e) {
        Throwable c = e.getMostSpecificCause();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message",
                "DB 오류 — " + c.getClass().getSimpleName() + ": " + c.getMessage()));
    }
}
