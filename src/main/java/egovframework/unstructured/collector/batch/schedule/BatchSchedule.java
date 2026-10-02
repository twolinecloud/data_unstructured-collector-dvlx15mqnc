package egovframework.unstructured.collector.batch.schedule;

/**
 * 배치 스케줄 설정(admin-api 소유) 1건 — data-collector {@code BatchSchedule} 과 같은 필드.
 *
 * @param dataTypeCd      데이터 구분(C01) — 비정형은 {@code UNSTRUCTURED}
 * @param activeYn        사용 여부(Y/N)
 * @param execSchedTypeCd 스케줄 유형(C13) — {@code FIXED_TIME}(정기) / {@code INTERVAL_BASED}(주기)
 * @param schedVal        시각(FIXED_TIME) / 주기(INTERVAL_BASED). admin 응답은 {@code HH:mm}, DB 는 {@code HH:mm:ss} — 둘 다 받는다
 */
public record BatchSchedule(String dataTypeCd, String activeYn, String execSchedTypeCd, String schedVal) {

    public static final String FIXED_TIME = "FIXED_TIME";
    public static final String INTERVAL_BASED = "INTERVAL_BASED";

    public boolean isActive() {
        return "Y".equalsIgnoreCase(activeYn);
    }

    /** 변경 감지 키 — 셋 중 하나라도 바뀌면 트리거를 다시 건다. */
    public String spec() {
        return activeYn + "|" + execSchedTypeCd + "|" + schedVal;
    }
}
