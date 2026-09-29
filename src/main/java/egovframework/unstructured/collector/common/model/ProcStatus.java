package egovframework.unstructured.collector.common.model;

/**
 * 파일 1건의 처리 결과 상태.
 *
 * <p>로그 컬렉터 공통코드 C04({@code WAIT/RUNNING/SUCCESS/FAIL/PARTIAL/CANCELED})와 맞춘다.
 * 수집 단계에서 쓰는 것은 아래 셋뿐이다.</p>
 */
public enum ProcStatus {
    /** 정상적으로 STT 텍스트까지 만들었다. */
    SUCCESS,
    /** 처리 중 실패 — 사유를 T4 에 남긴다. */
    FAIL,
    /** 이미 처리된 건이라 건너뛰었다(멱등). 정합성 집계에서 제외한다. */
    SKIPPED
}
