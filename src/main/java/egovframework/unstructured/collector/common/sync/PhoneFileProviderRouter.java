package egovframework.unstructured.collector.common.sync;

import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 전화 파일 공급 라우터 — <b>전화 전용 스위치</b>({@code voice.phone.mode})를 따른다.
 *
 * <p><b>브로커 스위치를 따라가지 않는다.</b> 예전에는 "전 구간 Mock 을 스위치 하나로 움직이게"
 * 하려고 브로커 모드에 얹어 두었는데, 그 전제가 틀렸다. 전화는 XVARM 을 타지 않는다 —
 * 보라미가 아닌 별도 서버에 파일이 있고 ESB 가 전화 전용 연계 프로바이더를 구성해 준다
 * (2026-09-11 회의 {@code [00:11:39]~[00:12:07]}).</p>
 *
 * <p>그래서 "XVARM 브로커 = REST" 로 올리는 순간 전화 경로까지 ESB 대기 모드로 끌려가
 * 로컬에는 ESB 가 없으니 전화 건이 조용히 타임아웃으로 실패했다. 두 트랙의 연동 주체가
 * 다르므로 스위치도 따로 둔다.</p>
 */
@Log4j2
@Primary
@Component
public class PhoneFileProviderRouter implements PhoneFileProvider {

    private final VoiceModeState state;
    private final Map<String, PhoneFileProvider> byMode = new HashMap<>();

    public PhoneFileProviderRouter(VoiceModeState state, List<PhoneFileProvider> impls) {
        this.state = state;
        for (PhoneFileProvider impl : impls) {
            if (impl instanceof PhoneFileProviderRouter) {
                continue;   // 자기 자신은 제외 — 무한 위임을 막는다
            }
            byMode.put(impl.mode(), impl);
        }
        log.info("[PhoneFile] 사용 가능한 구현 — {}", byMode.keySet());
    }

    @Override
    public void request(VoiceTarget target) {
        PhoneFileProvider impl = byMode.get(mode());
        if (impl == null) {
            throw new IllegalStateException(
                    "전화 파일 공급 구현이 없다: " + mode() + " (등록: " + byMode.keySet() + ")");
        }
        impl.request(target);
    }

    @Override
    public String mode() {
        return state.phone().name();
    }
}
