package egovframework.unstructured.collector.voice.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.transfer.ZenonClient;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 음성 한 건을 제논(Zenon) 전송 레코드로 만든다 — payload 원소 {@code {managementNo, rawDataset}}.
 *
 * <p>양식은 data-collector 와 같다(2026-10-05): {@code managementNo} 는 예측 전송과 같은 <b>교정번호(CORR_NO)</b>,
 * {@code rawDataset} 은 처리 메타 + 전사 구조(Whisper 표준 모양 — 예전 {@code {건ID}.json} 과 같은 내용).
 * {@code inmatePid} 는 로그 컬렉터 T4 {@code INMATE_PID} 와 같은 가명 ID 라 수신 쪽 레코드와 처리 이력을 맞춰 볼 수 있다.</p>
 */
public final class ZenonVoiceDocument {

    private ZenonVoiceDocument() {
    }

    /** 미리보기 글자 수 — 시뮬레이터 검증 화면용. */
    private static final int PREVIEW = 200;

    /**
     * @param inmatePid T4 와 같은 가명 ID({@code InmatePidGenerator.of(corrNo)})
     */
    public static ZenonClient.Record of(String execId, VoiceTarget target, SttResult stt, long audioBytes,
                                        String inmatePid, ObjectMapper objectMapper) {
        String fileName = safeName(target.shortId()) + ".json";
        String text = stt.text() == null ? "" : stt.text();

        ObjectNode m = objectMapper.createObjectNode();
        m.put("recFileId", target.idempotencyKey());     // T4 REC_FILE_ID 와 같은 값 — 접견 TARE_FILE_NO · 전화 VRFC_ESTL_ID
        m.put("kind", target.kind().name());
        m.put("inmatePid", inmatePid);
        m.put("execId", execId);
        m.put("srcFileName", target.srcFileName());
        m.put("srcFilePath", target.srcFilePath());
        m.put("engine", stt.engine());
        m.put("fromSource", stt.fromSource());
        m.put("durationSec", stt.durationSec());
        m.put("charCount", stt.charCount());
        m.put("audioBytes", audioBytes);
        m.put("processedAt", LocalDateTime.now().withNano(0).toString());

        ObjectNode transcript = m.putObject("transcript");
        transcript.put("text", text);
        transcript.put("language", stt.language());
        transcript.put("duration", stt.duration());
        ArrayNode segs = transcript.putArray("segments");
        for (SttResult.Segment seg : stt.segments()) {
            ObjectNode one = segs.addObject();
            one.put("id", seg.id());
            one.put("start", seg.start());
            one.put("end", seg.end());
            one.put("text", seg.text());
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", target.kind().name());
        meta.put("idempotency_key", target.idempotencyKey());
        meta.put("src_file_name", target.srcFileName());
        meta.put("char_count", stt.charCount());
        meta.put("engine", stt.engine());
        meta.put("inmate_pid", inmatePid);
        return new ZenonClient.Record("VOICE", execId, target.idempotencyKey(), target.corrNo(), fileName, m, meta,
                text.length() > PREVIEW ? text.substring(0, PREVIEW) + "…" : text);
    }

    private static String safeName(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
