package egovframework.unstructured.collector.batch.schedule;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 스케줄 즉시 반영 요청 본문 — admin-api {@code ScheduleRefreshRequest} · data-collector {@code BatchScheduleReq} 와 같은 필드.
 * admin 이 응답 전체를 실어 보내도 받을 수 있게 부가 필드는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchScheduleReq(String dataTypeCd, String activeYn, String execSchedTypeCd, String schedVal) {

    /** 네 값이 다 있어야 그대로 적용한다. 하나라도 비면 본문 없는 요청처럼 admin 을 다시 읽는다. */
    public boolean hasSchedule() {
        return dataTypeCd != null && activeYn != null && execSchedTypeCd != null && schedVal != null;
    }

    public BatchSchedule toSchedule() {
        return new BatchSchedule(dataTypeCd, activeYn, execSchedTypeCd, schedVal);
    }
}
