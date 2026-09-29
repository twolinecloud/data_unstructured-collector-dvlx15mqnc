package egovframework.unstructured.collector.image.model;

/**
 * 수용자 이미지 파이프라인의 단계 — 리포트의 단계별 시간과 실패 지점이 이 순서다.
 *
 * <p>{@code QUERY}·{@code FILEKEY} 는 배치 앞에서 한 번(페이지 단위)에 돌고, 나머지는 건마다 돈다.</p>
 */
public enum ImageStage {
    QUERY("최신 이미지 조회"),
    FILEKEY("FILEKEY 추출"),
    ACQUIRE("파일 수신(브로커)"),
    VIRTUAL("가상 처리 지연"),
    DECRYPT("복호화"),
    SAVE("저장"),
    MAP("DB 매핑");

    private final String label;

    ImageStage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
