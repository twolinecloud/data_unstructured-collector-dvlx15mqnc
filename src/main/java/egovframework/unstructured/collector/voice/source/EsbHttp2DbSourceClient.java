package egovframework.unstructured.collector.voice.source;

import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ESB HTTP2DB(P40) 경유 조회 — <b>운영 경로</b>.
 *
 * <p>표준 A 3.11 에 따르면 요청은 {@code https://{연계서버ip}:{포트}/{인터페이스ID}} 로 가고,
 * 본문은 {@code DATA}(조회조건) / {@code RECORD}(입력) 로, 응답은
 * {@code RESULT}(RLT_CODE·RLT_MSG·RLT_RCNT) + {@code RECORD} 로 돌아온다.</p>
 *
 * <p><b>아직 구현하지 않았다.</b> 인터페이스ID(계획서 Q2)와 I/F 테이블 구성(Q15)이
 * 확정되지 않아 요청 본문을 만들 수 없다. 값이 정해지면 이 클래스만 채우면 되고,
 * 배치·하류 코드는 손대지 않는다 — 그러려고 인터페이스로 끊어 뒀다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class EsbHttp2DbSourceClient implements BoramiSourceClient {

    private final VoiceProperties props;

    @Override
    public List<VoiceTarget> findMeetTargets(BatchWindow window, List<String> speclCodes, int limit) {
        throw notReady();
    }

    @Override
    public List<VoiceTarget> findPhoneTargets(BatchWindow window, List<String> speclCodes, int limit) {
        throw notReady();
    }

    @Override
    public String mode() {
        return "ESB_HTTP2DB";
    }

    /**
     * 조용히 빈 목록을 돌려주지 않는다 — "수집 대상 0건"과 "연계가 아직 없음"은 전혀 다른 사실이고,
     * 전자로 위장하면 배치가 성공한 것처럼 보인다.
     */
    private IllegalStateException notReady() {
        return new IllegalStateException(
                "ESB HTTP2DB 연계가 아직 구현되지 않았다 — 인터페이스ID(Q2)와 I/F 테이블 구성(Q15) 확정 필요. "
                        + "현재 설정: esbBaseUrl=" + props.source().esbBaseUrl()
                        + ", interfaceId=" + props.source().interfaceId());
    }
}
