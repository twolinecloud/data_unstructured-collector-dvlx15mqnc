package egovframework.unstructured.collector.common.broker;

import egovframework.unstructured.collector.common.model.VoiceTarget;

/**
 * 보라미 WAS 안에 배포될 XVARM 브로커를 호출한다.
 *
 * <p><b>왜 브로커가 필요한가</b>: AI 플랫폼에서 보라미 내부의 XVARM 솔루션 API 를 직접 부를 수
 * 없다. 그래서 보라미 서버 안에 "XVARM 을 대신 호출해 주는 최소 REST 서비스"를 하나 띄운다
 * (2026-09-11 회의 `[00:09:38]`, `[00:10:11]`).</p>
 *
 * <p><b>비동기 + 폴링</b>: 추출이 오래 걸릴 수 있어 요청은 {@code 202 Accepted} 로 받고
 * 상태를 따로 묻는다. 동기 대기는 망연계 구간 타임아웃에 걸린다.</p>
 */
public interface XvarmBrokerClient {

    /**
     * 파일 추출을 요청하고 완료될 때까지 기다린다.
     *
     * @param target 접견 대상({@code docId}·{@code fileKey} 필요)
     * @return 보라미 임시 폴더에 생성된 파일의 경로. 실패 시 예외
     */
    ExtractResult extract(VoiceTarget target);

    /**
     * 배치(EXEC_ID) 단위로 추출을 요청한다 — <b>재처리 배치가 새로 추출받게</b> 하기 위해서다.
     *
     * <p>브로커는 {@code requestId} 로 멱등 처리한다(같은 키면 기존 작업 상태를 돌려주고 다시 추출하지
     * 않는다). 키를 대상만으로 만들면, 앞선 배치가 파일을 받아 STT 후 지운 뒤에 재처리 배치가 같은 대상을
     * 요청했을 때 브로커가 "이미 DONE" 을 돌려주고 파일은 없어 수신 대기 타임아웃이 난다(실제로 그랬다).
     * 그래서 키에 EXEC_ID 를 넣어 배치마다 별도 요청이 되게 한다 — 한 배치 안에서는 멱등 표식이
     * 같은 대상을 두 번 요청하지 않게 막으므로 중복 추출은 여전히 없다.</p>
     *
     * @param execId 이번 배치의 EXEC_ID. 비면 대상만으로 키를 만든다(구 동작)
     */
    default ExtractResult extract(VoiceTarget target, String execId) {
        return extract(target);
    }

    String mode();

    /** 브로커 요청 키 — {@code VOC-{execId}-{대상키}}. execId 가 없으면 {@code VOC-{대상키}}. */
    static String requestIdOf(VoiceTarget target, String execId) {
        return (execId == null || execId.isBlank())
                ? "VOC-" + target.idempotencyKey()
                : "VOC-" + execId + "-" + target.idempotencyKey();
    }

    /**
     * @param requestId 멱등 키 — 같은 값으로 재요청하면 중복 추출하지 않는다
     * @param filePath  보라미 임시 폴더 내 생성 경로
     * @param fileSize  크기(byte). 모르면 0
     */
    record ExtractResult(String requestId, String filePath, long fileSize) {}
}
