package egovframework.unstructured.collector.voice.perf;

/**
 * 성능 테스트가 시간을 재는 건별 단계 — 결과 화면의 막대 그래프 순서다.
 *
 * <p>T2 의 단계(COLLECT·ANALYZE·SEND)보다 잘게 나눈다. T2 는 "어느 단계에서 실패했나" 를 가르는 단위고,
 * 여기는 "어디서 시간이 드나" 를 보는 단위라 확보와 복호화를 따로 봐야 한다.</p>
 */
public enum PerfStage {

    /** 원본 확보 — 브로커 추출 요청(접견)·전화 연계 요청 + 수신 폴더 도착 대기. */
    ACQUIRE("확보"),
    /** 복호화 — K8s Secret 키 AES(접견) / 모의 AES(전화). */
    DECRYPT("복호화"),
    /** 포맷 — 복호화된 머리 바이트로 m4a/wav 재판별. 변환은 하지 않는다. */
    FORMAT("포맷"),
    /** STT — MOCK 은 가상 지연, NPU 는 실제 호출. */
    STT("STT"),
    /** 중간 산출물 — {@code stt_temp/{execId}/*.json}. */
    TEMP("중간"),
    /** 적재/전송 — 제논(Zenon) 수신 API 호출(개발계 MOCK). 결과를 PV 에 남기지 않는다. */
    SEND("제논 전송");

    private final String label;

    PerfStage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
