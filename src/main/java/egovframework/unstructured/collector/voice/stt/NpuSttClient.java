package egovframework.unstructured.collector.voice.stt;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import java.util.ArrayList;
import java.util.List;

/**
 * NPU STT 연동 — <b>운영 경로</b>.
 *
 * <p><b>API 사양이 아직 없다</b>(계획서 Q9). 업로드는 흔한 형태(multipart)를 가정했고,
 * 응답은 <b>Whisper 표준 JSON</b>({@code text} · {@code language} · {@code duration} ·
 * {@code segments[]})으로 읽는다 — 대부분의 오픈소스·상용 STT 가 그 스키마를 따르거나 변환을
 * 제공하므로, 실제 사양이 나와도 이 클래스의 {@link #parse} 만 고치면 된다.</p>
 *
 * <p><b>스트리밍으로 보낸다</b> — {@link FileSystemResource} 를 쓰면 RestTemplate 이 파일을
 * 통째로 메모리에 올리지 않는다. 접견 파일이 건당 5MB 수준이고 배치당 1,300건이라
 * 메모리에 올리기 시작하면 파드가 버티지 못한다.</p>
 */

@Component
@RequiredArgsConstructor
@Log4j2
public class NpuSttClient implements SttClient {

    private final VoiceProperties props;
    private final RestTemplate voiceRestTemplate;

    @Override
    public SttResult transcribe(VoiceFile file) {
        String base = props.stt().baseUrl();
        if (!StringUtils.hasText(base)) {
            throw new IllegalStateException("voice.stt.base-url 이 비어 있다 — NPU 모드에서는 필수다");
        }
        String url = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/api/v1/stt";

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new FileSystemResource(file.path()));
        form.add("language", "ko");
        form.add("format", file.format());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<JsonNode> res = voiceRestTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(form, headers), JsonNode.class);

        JsonNode body = res.getBody();
        if (body == null) {
            throw new IllegalStateException("NPU STT 빈 응답 — " + file.path().getFileName());
        }
        return parse(body, file.path().getFileName().toString());
    }

    /**
     * Whisper 표준 JSON 을 읽는다 — {@code text} · {@code language} · {@code duration} · {@code segments[]}.
     *
     * <p><b>없으면 없는 대로 받는다.</b> 구간을 주지 않는 엔진도 있고, 사양이 확정되기 전이라
     * 필드 이름이 다를 수 있다. 하나라도 빠졌다고 건을 실패시키면 전사 자체는 멀쩡한데
     * 배치가 깨진다 — 전문({@code text})만 있으면 처리한다.</p>
     *
     * <p>길이는 {@code duration}(실수, Whisper 표준)을 먼저 보고, 없으면 예전 가정이던
     * {@code durationSec}(정수)로 떨어진다. 어느 쪽이 오든 받기 위한 폴백이다.</p>
     */
    static SttResult parse(JsonNode body, String fileName) {
        String text = body.path("text").asText("");
        String language = body.hasNonNull("language") ? body.path("language").asText(null) : null;
        double duration = body.has("duration")
                ? body.path("duration").asDouble(0d)
                : body.path("durationSec").asDouble(0d);

        List<SttResult.Segment> segments = new ArrayList<>();
        JsonNode arr = body.path("segments");
        if (arr.isArray()) {
            int i = 0;
            for (JsonNode seg : arr) {
                segments.add(new SttResult.Segment(
                        seg.path("id").asInt(i),
                        seg.path("start").asDouble(0d),
                        seg.path("end").asDouble(0d),
                        seg.path("text").asText("")));
                i++;
            }
        }
        // 전문이 비었는데 구간은 있는 응답도 있다 — 구간을 이어 붙여 전문을 만든다.
        if (!StringUtils.hasText(text) && !segments.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (SttResult.Segment seg : segments) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(seg.text().strip());
            }
            text = sb.toString();
        }
        log.info("[STT:NPU] {} — {}자 · {}초 · 구간 {}개{}", fileName, text.length(),
                duration, segments.size(), language == null ? "" : " · " + language);
        return new SttResult(text, "NPU", duration, false, language, segments);
    }

    @Override
    public String mode() {
        return "NPU";
    }
}
