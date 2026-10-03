package egovframework.unstructured.collector.common.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 시각을 너그럽게 읽는다 — admin-api 가 보내는 두 모양을 다 받는다.
 *
 * <ul>
 *   <li><b>배열</b> {@code [yyyy, MM, dd, HH, mm(, ss(, nanos))]} — admin-api 가 {@code RestClient.builder()} 를 직접 써서
 *       Jackson 의 날짜 문자열 설정 없이 직렬화하면 이렇게 나간다(초가 0 이면 5개 — 예 {@code [2026,10,2,10,0]}).
 *       3개({@code [yyyy, MM, dd]})는 그날 0시.</li>
 *   <li><b>문자열</b> — ISO {@code 2026-10-02T10:00:00} · {@code 2026-10-02T10:00} · 공백 구분 {@code 2026-10-02 10:00:00} ·
 *       소수 초 · 오프셋/Z 가 붙은 ISO(서울 시각으로 바꾼다) · 날짜만 {@code 2026-10-02}(0시).</li>
 * </ul>
 *
 * <p>null · 빈 문자열은 null. 그 밖은 {@link JsonMappingException} — 컨트롤러가 400 으로 돌려준다(메시지에 필드명과 받는 형식).</p>
 */
public class FlexibleLocalDateTimeDeserializer extends StdDeserializer<LocalDateTime> {

    /** 오프셋이 붙은 시각을 바꿀 기준 — 수집 구간 · 보라미 CRT_DT 는 서울 벽시계다. */
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final String FORMS = "yyyy-MM-dd'T'HH:mm[:ss] · 공백 구분 · 배열 [yyyy, MM, dd, HH, mm(, ss)]";

    public FlexibleLocalDateTimeDeserializer() {
        super(LocalDateTime.class);
    }

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        String field = p.currentName() == null ? "시각" : p.currentName();
        JsonToken t = p.currentToken();
        if (t == JsonToken.VALUE_NULL) {
            return null;
        }
        if (t == JsonToken.VALUE_STRING) {
            try {
                return parse(p.getText());
            } catch (IllegalArgumentException e) {
                throw JsonMappingException.from(p, field + " " + e.getMessage());
            }
        }
        if (t == JsonToken.START_ARRAY) {
            List<Integer> v = new ArrayList<>();
            for (JsonToken x = p.nextToken(); x != JsonToken.END_ARRAY; x = p.nextToken()) {
                if (x != JsonToken.VALUE_NUMBER_INT) {
                    throw JsonMappingException.from(p, field + " 형식 오류 — 배열에는 정수만(" + FORMS + "): " + x);
                }
                v.add(p.getIntValue());
            }
            try {
                return fromArray(v);
            } catch (IllegalArgumentException | DateTimeException e) {
                throw JsonMappingException.from(p, field + " 형식 오류 — " + e.getMessage() + " (" + FORMS + "): " + v);
            }
        }
        throw JsonMappingException.from(p, field + " 형식 오류 — 문자열 또는 배열만 받는다(" + FORMS + "): " + t);
    }

    @Override
    public LocalDateTime getNullValue(DeserializationContext ctx) {
        return null;
    }

    /** {@code [yyyy, MM, dd(, HH, mm(, ss(, nanos)))]} — 3~7개. */
    public static LocalDateTime fromArray(List<Integer> v) {
        if (v.size() < 3 || v.size() > 7) {
            throw new IllegalArgumentException("배열 길이 " + v.size() + " — 3~7개여야 한다");
        }
        int[] a = new int[7];
        for (int i = 0; i < v.size(); i++) {
            a[i] = v.get(i);
        }
        return LocalDateTime.of(a[0], a[1], a[2], a[3], a[4], a[5], a[6]);
    }

    /** 문자열 — 비면 null. 읽을 수 없으면 {@link IllegalArgumentException}("형식 오류 …"). */
    public static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim();
        String iso = v.replace(' ', 'T');
        try {
            return LocalDateTime.parse(iso);
        } catch (DateTimeParseException ignored) {
            // 다음 모양
        }
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(SEOUL).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 다음 모양
        }
        try {
            return LocalDate.parse(v).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // 아래에서 거절
        }
        throw new IllegalArgumentException("형식 오류(" + FORMS + "): " + s);
    }
}
