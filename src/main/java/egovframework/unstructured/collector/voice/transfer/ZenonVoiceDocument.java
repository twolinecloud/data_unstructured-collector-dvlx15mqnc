package egovframework.unstructured.collector.voice.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.transfer.ZenonClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 음성 한 건을 제논(Zenon)에 보낼 문서로 만든다 — {@code {건ID}.json}(처리 메타 + 전사 구조).
 *
 * <p>내용은 예전에 PV 의 {@code xenon/{meet|phone}/{execId}/} 에 쓰던 {@code .json} 과 같다(처리 메타는 이름 그대로,
 * 전사 구조는 {@code transcript} 아래 Whisper 표준 모양). 2026-10-01 부터 그 폴더에 남기지 않고 제논 수신 API 로
 * 바로 보낸다 — 디스크를 거치지 않으므로 전송 뒤 정리할 산출물도 없다.</p>
 */
public final class ZenonVoiceDocument {

    private ZenonVoiceDocument() {
    }

    /** 미리보기 글자 수 — 시뮬레이터 검증 화면용. */
    private static final int PREVIEW = 200;

    public static ZenonClient.Document of(String execId, VoiceTarget target, SttResult stt, long audioBytes,
                                          ObjectMapper objectMapper) {
        String fileName = safeName(target.shortId()) + ".json";
        String text = stt.text() == null ? "" : stt.text();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("execId", execId);
        m.put("kind", target.kind().name());
        m.put("key", target.idempotencyKey());
        m.put("srcFileName", target.srcFileName());
        m.put("srcFilePath", target.srcFilePath());
        m.put("engine", stt.engine());
        m.put("fromSource", stt.fromSource());
        m.put("durationSec", stt.durationSec());
        m.put("charCount", stt.charCount());
        m.put("audioBytes", audioBytes);
        m.put("processedAt", LocalDateTime.now().withNano(0).toString());

        Map<String, Object> transcript = new LinkedHashMap<>();
        transcript.put("text", text);
        transcript.put("language", stt.language());
        transcript.put("duration", stt.duration());
        List<Map<String, Object>> segs = new ArrayList<>();
        for (SttResult.Segment seg : stt.segments()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", seg.id());
            one.put("start", seg.start());
            one.put("end", seg.end());
            one.put("text", seg.text());
            segs.add(one);
        }
        transcript.put("segments", segs);
        m.put("transcript", transcript);

        byte[] body;
        try {
            body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(m);
        } catch (JsonProcessingException e) {
            throw new ZenonClient.ZenonSendException("전사 문서 직렬화 실패 — " + e.getMessage(), e);
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("kind", target.kind().name());
        extra.put("idempotency_key", target.idempotencyKey());
        extra.put("src_file_name", target.srcFileName());
        extra.put("char_count", stt.charCount());
        extra.put("engine", stt.engine());
        return new ZenonClient.Document("VOICE", execId, target.corrNo(), fileName, body, "application/json", extra,
                text.length() > PREVIEW ? text.substring(0, PREVIEW) + "…" : text);
    }

    private static String safeName(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
