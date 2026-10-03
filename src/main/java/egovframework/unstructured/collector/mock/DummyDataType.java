package egovframework.unstructured.collector.mock;

/** 더미 데이터 유형 — 전화 · 접견 음성과 수용자 사진. */
public enum DummyDataType {

    PHONE("전화", 'P'),
    MEET("접견", 'M'),
    IMAGE("이미지", 'I');

    private final String label;
    private final char letter;

    DummyDataType(String label, char letter) {
        this.label = label;
        this.letter = letter;
    }

    public String label() {
        return label;
    }

    /** 가상 교정번호에 들어가는 한 글자 — 유형마다 수용자를 따로 두어 순번이 서로 겹치지 않게 한다. */
    public char letter() {
        return letter;
    }
}
