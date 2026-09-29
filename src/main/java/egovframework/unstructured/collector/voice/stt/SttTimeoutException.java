package egovframework.unstructured.collector.voice.stt;

/**
 * STT 가 정해진 시간 안에 답하지 않았다.
 *
 * <p>MOCK 에서 가상 지연이 성능 테스트의 'STT 타임아웃' 을 넘으면 던진다 — NPU 가 읽기 타임아웃을
 * 내는 상황을 흉내 낸다. 배치는 이 건을 실패(ANALYZE)로 적고 다음 건으로 넘어간다. 복호화 오디오는
 * 보존되어 재처리({@code FROM_ANALYZE})가 STT 부터 다시 할 수 있다.</p>
 *
 * <p>T4 사유가 {@code SttTimeoutException: …} 으로 시작하므로 성능 테스트가 타임아웃 건수를 따로 센다.</p>
 */
public class SttTimeoutException extends RuntimeException {

    public SttTimeoutException(String message) {
        super(message);
    }
}
