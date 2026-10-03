package egovframework.unstructured.collector.image.model;

import egovframework.unstructured.collector.common.model.StepType;

/**
 * 수용자 이미지 파이프라인의 단계 — 리포트의 단계별 시간과 실패 지점이 이 순서다.
 *
 * <p>{@code QUERY}·{@code FILEKEY} 는 배치 앞에서 한 번(페이지 단위)에 돌고, 나머지는 건마다 돈다.</p>
 *
 * <p>로그(T2 · T4)에는 비정형 3단계({@link StepType})로 묶어 남긴다 — 수집(조회 · FILEKEY · 브로커 수신) ·
 * 정제/분석(가상 지연 · 복호화 · 이미지 확인) · 적재/전송(저장소 저장 · Admin DB 매핑).</p>
 */
public enum ImageStage {
    QUERY("최신 이미지 조회", StepType.COLLECT),
    FILEKEY("FILEKEY 추출", StepType.COLLECT),
    ACQUIRE("파일 수신(브로커)", StepType.COLLECT),
    VIRTUAL("가상 처리 지연", StepType.ANALYZE),
    DECRYPT("복호화", StepType.ANALYZE),
    SAVE("저장", StepType.SEND),
    MAP("DB 매핑", StepType.SEND);

    private final String label;
    private final StepType stepType;

    ImageStage(String label, StepType stepType) {
        this.label = label;
        this.stepType = stepType;
    }

    public String label() {
        return label;
    }

    /** 이 세부 단계가 속한 비정형 3단계(C05). */
    public StepType stepType() {
        return stepType;
    }
}
