package egovframework.unstructured.collector.batch.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * admin 설정 응답 해석 — scheduledValue 가 필수값에서 빠진 뒤(2026-10-12 회의 결정) 값이 없거나 null 로 올 수 있다.
 */
class AdminBatchScheduleClientTest {

    private final ObjectMapper om = new ObjectMapper();

    private BatchSchedule parse(String json) throws Exception {
        return AdminBatchScheduleClient.toSchedule(om.readTree(json));
    }

    @Test
    @DisplayName("schedVal 이 JSON null 이면 문자열 \"null\" 이 아니라 null")
    void jsonNullSchedVal() throws Exception {
        BatchSchedule s = parse("{\"dataTypeCd\":\"UNSTRUCTURED\",\"activeYn\":\"N\",\"execSchedTypeCd\":\"FIXED_TIME\",\"schedVal\":null}");
        assertThat(s.schedVal()).isNull();
        assertThat(s.isActive()).isFalse();
    }

    @Test
    @DisplayName("schedVal 이 아예 없어도 null")
    void missingSchedVal() throws Exception {
        assertThat(parse("{\"dataTypeCd\":\"UNSTRUCTURED\",\"activeYn\":\"N\",\"execSchedTypeCd\":\"FIXED_TIME\"}").schedVal())
                .isNull();
    }

    @Test
    @DisplayName("값이 있으면 그대로")
    void presentSchedVal() throws Exception {
        assertThat(parse("{\"dataTypeCd\":\"UNSTRUCTURED\",\"activeYn\":\"Y\",\"execSchedTypeCd\":\"FIXED_TIME\",\"schedVal\":\"02:00\"}"))
                .isEqualTo(new BatchSchedule("UNSTRUCTURED", "Y", "FIXED_TIME", "02:00"));
    }
}
