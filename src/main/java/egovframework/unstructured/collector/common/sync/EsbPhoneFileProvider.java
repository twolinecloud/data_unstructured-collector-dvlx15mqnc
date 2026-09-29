package egovframework.unstructured.collector.common.sync;

import egovframework.unstructured.collector.common.model.VoiceTarget;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 * 전화 파일 — 운영 경로. <b>우리가 할 일이 없다.</b>
 *
 * <p>전화는 별도 서버에 있고, ESB 가 전화 전용 연계 프로바이더를 구성해 우리 스토리지로
 * 떨궈 준다(2026-09-11 회의). 요청·지시할 대상이 우리 쪽에 없으므로 기다리기만 한다.</p>
 *
 * <p><b>빈 구현이지만 클래스를 만드는 이유</b>: 이 자리를 비워 두면 "전화는 어떻게 오는가"라는
 * 질문이 코드 어디에도 남지 않는다. 나중에 ESB 구성이 바뀌어 우리가 뭔가 호출해야 하게 되면
 * 여기가 그 자리다.</p>
 */
@Log4j2
@Component
public class EsbPhoneFileProvider implements PhoneFileProvider {

    @Override
    public void request(VoiceTarget target) {
        // INFO 로 남긴다. 로컬·개발계에는 ESB 가 없어서 이 지점부터 파일이 영영 안 떨어지는데,
        // debug 로 두면 아무 흔적도 없이 수신 대기 타임아웃만 나서 원인을 찾기 어렵다.
        log.info("[PhoneFile:ESB] 파일 요청 — ESB 전화 연계 프로바이더가 수신 디렉터리에 떨궈 주기를 기다린다 ({})",
                target.shortId());
    }

    @Override
    public String mode() {
        return "ESB";
    }
}
