package egovframework.unstructured.collector.batch.schedule;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 스케줄 즉시 반영 요청 본문 — admin-api {@code ScheduleRefreshRequest} · data-collector {@code BatchScheduleReq} 와 같은 필드.
 * admin 이 응답 전체를 실어 보내도 받을 수 있게 부가 필드는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchScheduleReq(String dataTypeCd, String activeYn, String execSchedTypeCd, String schedVal) {

    /**
     * 그대로 적용할 수 있는 본문인가. 아니면 본문 없는 요청처럼 admin 을 다시 읽는다.
     *
     * <p>{@code schedVal} 은 <b>사용(Y)일 때만</b> 요구한다(2026-10-12 회의 결정: scheduledValue 필수값 해제).
     * 사용 N 이면 트리거를 걸지 않으므로 시각 · 주기가 필요 없다 — 예전에는 그 자리를 admin 이 임의 기본값으로
     * 채워 보내야 했고, 비워 보내면 본문을 버리고 재조회했다.</p>
     */
    public boolean hasSchedule() {
        if (dataTypeCd == null || activeYn == null || execSchedTypeCd == null) {
            return false;
        }
        return schedVal != null || !"Y".equalsIgnoreCase(activeYn);
    }

    public BatchSchedule toSchedule() {
        return new BatchSchedule(dataTypeCd, activeYn, execSchedTypeCd, schedVal);
    }
}
