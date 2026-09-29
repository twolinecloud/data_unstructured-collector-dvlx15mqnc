package egovframework.unstructured.collector.common.broker;

import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** XVARM 브로커 라우터 — 재시작 없이 MOCK ↔ REST 를 바꾼다. */
@Log4j2
@Primary
@Component
public class XvarmBrokerRouter implements XvarmBrokerClient {

    private final VoiceModeState state;
    private final Map<String, XvarmBrokerClient> byMode = new HashMap<>();

    public XvarmBrokerRouter(VoiceModeState state, List<XvarmBrokerClient> impls) {
        this.state = state;
        for (XvarmBrokerClient impl : impls) {
            if (impl instanceof XvarmBrokerRouter) {
                continue;
            }
            byMode.put(impl.mode(), impl);
        }
        log.info("[Broker] 사용 가능한 구현 — {}", byMode.keySet());
    }

    @Override
    public ExtractResult extract(VoiceTarget target) {
        return extract(target, null);
    }

    @Override
    public ExtractResult extract(VoiceTarget target, String execId) {
        String mode = state.broker().name();
        XvarmBrokerClient impl = byMode.get(mode);
        if (impl == null) {
            throw new IllegalStateException("브로커 구현이 없다: " + mode + " (등록: " + byMode.keySet() + ")");
        }
        return impl.extract(target, execId);
    }

    @Override
    public String mode() {
        return state.broker().name();
    }
}
