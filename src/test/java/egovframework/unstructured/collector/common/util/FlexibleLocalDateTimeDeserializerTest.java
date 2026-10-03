package egovframework.unstructured.collector.common.util;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** admin-api 가 보내는 시각 — 배열과 문자열을 다 받는다(바로 실행 targetToDtm). */
class FlexibleLocalDateTimeDeserializerTest {

    record Body(@JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class) LocalDateTime targetToDtm) {}

    private final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());

    private LocalDateTime read(String json) throws Exception {
        return om.readValue("{\"targetToDtm\":" + json + "}", Body.class).targetToDtm();
    }

    @Test
    @DisplayName("배열 — [y,M,d,H,m] (admin-api RestClient 직렬화) · 초 · 나노 · 날짜만")
    void arrays() throws Exception {
        assertThat(read("[2026,10,2,10,0]")).isEqualTo(LocalDateTime.of(2026, 10, 2, 10, 0));
        assertThat(read("[2026,10,2,10,0,30]")).isEqualTo(LocalDateTime.of(2026, 10, 2, 10, 0, 30));
        assertThat(read("[2026,10,2,10,0,30,500000000]")).isEqualTo(LocalDateTime.of(2026, 10, 2, 10, 0, 30, 500_000_000));
        assertThat(read("[2026,10,2]")).isEqualTo(LocalDateTime.of(2026, 10, 2, 0, 0));
    }

    @Test
    @DisplayName("문자열 — ISO · 분까지 · 공백 구분 · 소수 초 · Z/오프셋(서울로) · 날짜만 · 빈 값과 null 은 null")
    void strings() throws Exception {
        LocalDateTime ten = LocalDateTime.of(2026, 10, 2, 10, 0);
        assertThat(read("\"2026-10-02T10:00:00\"")).isEqualTo(ten);
        assertThat(read("\"2026-10-02T10:00\"")).isEqualTo(ten);
        assertThat(read("\"2026-10-02 10:00:00\"")).isEqualTo(ten);
        assertThat(read("\" 2026-10-02 10:00 \"")).isEqualTo(ten);
        assertThat(read("\"2026-10-02T10:00:00.123\"")).isEqualTo(ten.withNano(123_000_000));
        assertThat(read("\"2026-10-02T01:00:00Z\"")).isEqualTo(ten);
        assertThat(read("\"2026-10-02T10:00:00+09:00\"")).isEqualTo(ten);
        assertThat(read("\"2026-10-02\"")).isEqualTo(LocalDateTime.of(2026, 10, 2, 0, 0));
        assertThat(read("\"\"")).isNull();
        assertThat(read("null")).isNull();
        assertThat(om.readValue("{}", Body.class).targetToDtm()).isNull();
    }

    @Test
    @DisplayName("거절 — 잘못된 날짜 · 배열 길이 · 문자 섞인 배열 · 객체 · 이상한 문자열(필드명 · 받는 형식을 알려 준다)")
    void rejects() {
        for (String bad : new String[] {"[2026,13,2,10,0]", "[2026,10]", "[2026,10,2,10,0,0,0,0]", "[2026,\"10\",2]",
                "{\"y\":2026}", "\"10/02 10:00\"", "12345"}) {
            assertThatThrownBy(() -> read(bad)).as(bad).isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining("targetToDtm 형식 오류");
        }
    }
}
