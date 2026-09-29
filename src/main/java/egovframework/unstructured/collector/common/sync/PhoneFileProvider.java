package egovframework.unstructured.collector.common.sync;

import egovframework.unstructured.collector.common.model.VoiceTarget;

/**
 * 전화 녹음 파일을 우리 수신 디렉터리까지 오게 만든다.
 *
 * <p><b>접견과 경로가 다르다.</b> 접견은 우리가 XVARM 브로커를 호출해 추출을 <b>지시</b>하지만,
 * 전화는 별도 서버·별도 ESB 프로바이더가 처리한다(2026-09-11 회의 {@code [00:12:07]}).
 * 즉 운영에서 우리가 할 일은 <b>기다리는 것뿐</b>이다.</p>
 *
 * <p>그런데 Mock 환경에는 파일을 떨궈 줄 ESB 가 없다. 브로커 Mock 은 접견만 만들기 때문에
 * 전화 건은 영원히 도착하지 않는다. 그 구멍을 메우려고 이 인터페이스를 둔다 —
 * 운영 구현은 아무것도 하지 않고, Mock 구현만 더미 파일을 만든다.</p>
 */
public interface PhoneFileProvider {

    /** 파일이 오도록 조치한다. 운영에서는 no-op. */
    void request(VoiceTarget target);

    String mode();
}
