package egovframework.unstructured.collector.voice.stt.genon;

/**
 * 사전 검증에서 막혔다 — <b>제논으로 보내지 않았다.</b> 시뮬레이터는 400 으로 낸다.
 *
 * <p>배치에서는 그 건의 ANALYZE 실패 사유가 된다({@code AudioValidationException: 제논 STT 사전 검증 실패 — …}).
 * 메시지에 '연결' · '타임아웃' 을 쓰지 않는다 — 실패 분류({@code errStackOf})가 DATA 로 잡아야 맞다.</p>
 */
public class AudioValidationException extends RuntimeException {

    private final transient AudioInspector.Check check;

    public AudioValidationException(AudioInspector.Check check) {
        super("제논 STT 사전 검증 실패 — " + check.violationText() + " [" + check.summary() + "]");
        this.check = check;
    }

    public AudioInspector.Check check() {
        return check;
    }
}
