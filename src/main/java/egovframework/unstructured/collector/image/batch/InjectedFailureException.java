package egovframework.unstructured.collector.image.batch;

import egovframework.unstructured.collector.image.model.ImageStage;

/**
 * 의도적 실패(주입) — 시뮬레이터 6번 탭이 정한 건을 정한 단계에서 실패시킨다(SIM 검증에서만).
 *
 * <p>실제 단계의 일을 <b>한 뒤에</b> 던진다 — 수신은 파일을 받은 뒤, 저장은 파일을 쓴 뒤, DB 매핑은 UPSERT 문을
 * 실행한 뒤 커밋 전에. 그래서 재실행(멱등성) 검증이 "받은 원본 정리 · 남은 저장 파일 덮어쓰기 · 매핑 롤백"을
 * 실제로 거친다.</p>
 */
public class InjectedFailureException extends RuntimeException {

    private final transient ImageStage stage;

    public InjectedFailureException(ImageStage stage) {
        super("의도적 실패(주입) — " + stage.label() + (stage == ImageStage.MAP ? " 트랜잭션 안에서 · 커밋 전 롤백" : " 뒤"));
        this.stage = stage;
    }

    public ImageStage stage() {
        return stage;
    }
}
