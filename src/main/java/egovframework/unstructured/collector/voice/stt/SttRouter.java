package egovframework.unstructured.collector.voice.stt;

import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceFile;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** STT 라우터 — 재시작 없이 MOCK ↔ NPU 를 바꾼다. */
@Log4j2
@Primary
@Component
public class SttRouter implements SttClient {

    private final VoiceModeState state;
    private final Map<String, SttClient> byMode = new HashMap<>();

    public SttRouter(VoiceModeState state, List<SttClient> impls) {
        this.state = state;
        for (SttClient impl : impls) {
            if (impl instanceof SttRouter) {
                continue;
            }
            byMode.put(impl.mode(), impl);
        }
        log.info("[STT] 사용 가능한 구현 — {}", byMode.keySet());
    }

    @Override
    public SttResult transcribe(VoiceFile file) {
        String mode = state.stt().name();
        SttClient impl = byMode.get(mode);
        if (impl == null) {
            throw new IllegalStateException("STT 구현이 없다: " + mode + " (등록: " + byMode.keySet() + ")");
        }
        return impl.transcribe(file);
    }

    @Override
    public String mode() {
        return state.stt().name();
    }
}
