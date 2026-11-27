package egovframework.unstructured.collector.mock;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** WF9 위험 대화 샘플 — 이미 있는 배치에 가상 위험 대화 한 건을 얹어 실제 WF9 경로(비식별 → 전송 확인 → WF9 → 적재 → 폐기)로 보낸다. dev · local 전용. */
@Log4j2
@Tag(name = "9-5. WF9 위험 대화 샘플 (dev·local)", description = "특정 교정번호의 '주요 사항(최근 30일)' 화면 확인용 — 가상 위험 대화 · 실제 비식별 · 실제 WF9")
@RestController
@RequestMapping(value = "/api/v1/voice/simulator", produces = MediaType.APPLICATION_JSON_VALUE)
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class Wf9SampleSimController {

    private final Wf9SampleSimService service;

    @Operation(summary = "WF9 위험 대화 샘플 얹기",
            description = """
                    본문 `{"execId":"20261127UNS001","corrNo":"…","kind":"MEET","contactDtm":"2026-11-27T10:00:00","lines":["…"]}`
                    (kind · contactDtm · lines 는 선택 — 비우면 접견 · 10분 전 · 지어낸 위험 대화 4줄).

                    1. 커넥터 음성 비식별(실호출) — 그 배치에 T5 한 행이 생긴다
                    2. 가린 전사로 WF9 스풀에 넣는다(녹음 ID `SIMWF9-yyyyMMddHHmmss`)
                    3. 작업자가 그 배치의 전송(T2 SEND)이 SUCCESS 인지 본다 — 수동 전송이면 [클라우드 전송] 뒤에야 WF9 를 부른다
                    4. WF9 응답 → `tb_contact_risk_signal` 적재 → 그 배치 T1 폐기 완료

                    400 — execId · corrNo 누락 · 없는 배치 · 409 — WF9 · 비식별 꺼짐
                    """)
    @PostMapping("/wf9-sample")
    public Map<String, Object> run(@RequestBody Wf9SampleSimService.Request req) {
        return service.run(req);
    }

    @Operation(summary = "WF9 위험 대화 샘플 신호 지우기", description = "샘플 녹음(`SIMWF9-…`)의 신호 행을 0건으로 통째 교체한다.")
    @DeleteMapping("/wf9-sample/{recFileId}")
    public Map<String, Object> remove(@PathVariable("recFileId") String recFileId,
                                      @RequestParam("corrNo") String corrNo,
                                      @RequestParam(name = "kind", required = false, defaultValue = "MEET") String kind) {
        return service.remove(recFileId, corrNo, kind);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> state(IllegalStateException e) {
        log.warn("[WF9:샘플] 실패 — {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }
}
