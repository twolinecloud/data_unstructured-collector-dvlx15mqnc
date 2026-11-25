package egovframework.unstructured.collector.common.config;

import egovframework.unstructured.collector.common.model.BatchWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 운영 선별 테스트 조건 — 환경 변수 파싱 · 검증 · fail-closed · 기본값 복귀. */
class SourceTestFilterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 10, 0);

    private static SourceTestFilter parse(String... kv) {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin");   // 상관없는 변수는 무시
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return SourceTestFilter.parse(env, NOW);
    }

    private static final BatchWindow DAILY = BatchWindow.daily(NOW);

    @Test
    @DisplayName("변수가 없거나 빈 값이면 OFF — 시간창 · 상한 · 조건 모두 기존 그대로")
    void offWhenAbsentOrBlank() {
        for (SourceTestFilter f : List.of(parse(), parse("SOURCE_TEST_FROM", "", "SOURCE_TEST_INSTT_CD", "   ",
                "SOURCE_TEST_MAX_ROWS", "\"\""))) {
            assertThat(f.active()).isFalse();
            assertThat(f.invalid()).isFalse();
            assertThat(f.apply(DAILY)).isSameAs(DAILY);
            assertThat(f.limitOr(500)).isEqualTo(500);
            assertThat(f.insttCds()).isEmpty();
            assertThat(f.corrNos()).isEmpty();
            f.requireValid();   // 던지지 않는다
            assertThat(f.snapshot()).containsEntry("state", "OFF");
        }
    }

    @Test
    @DisplayName("날짜만 쓴 FROM 하나 = 그날 하루 · 라벨(실행 유형)은 그대로")
    void singleDay() {
        SourceTestFilter f = parse("SOURCE_TEST_FROM", "2026-11-20");
        BatchWindow w = f.apply(DAILY);
        assertThat(w.from()).isEqualTo(LocalDateTime.of(2026, 11, 20, 0, 0));
        assertThat(w.to()).isEqualTo(LocalDateTime.of(2026, 11, 21, 0, 0));
        assertThat(w.label()).isEqualTo("DAILY");
        assertThat(w.execTypeCd()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("날짜만 쓴 TO 는 그날까지 포함 · yyyyMMdd · 시각 형식 · 감싼 따옴표도 받는다")
    void formats() {
        SourceTestFilter f = parse("SOURCE_TEST_FROM", "20261118", "SOURCE_TEST_TO", "'2026-11-20'");
        assertThat(f.from()).isEqualTo(LocalDateTime.of(2026, 11, 18, 0, 0));
        assertThat(f.to()).isEqualTo(LocalDateTime.of(2026, 11, 21, 0, 0));

        SourceTestFilter t = parse("SOURCE_TEST_FROM", "2026-11-20 09:00", "SOURCE_TEST_TO", "2026-11-20T18:30:00");
        assertThat(t.from()).isEqualTo(LocalDateTime.of(2026, 11, 20, 9, 0));
        assertThat(t.to()).isEqualTo(LocalDateTime.of(2026, 11, 20, 18, 30));
    }

    @Test
    @DisplayName("기관 · 교정번호는 쉼표(공백) 구분 · 중복 제거 · 상한은 콤마 숫자 허용")
    void lists() {
        SourceTestFilter f = parse("SOURCE_TEST_INSTT_CD", " CI00001, CI00002 ,CI00001",
                "SOURCE_TEST_CORR_NO", "2026-000123 2026-000124", "SOURCE_TEST_MAX_ROWS", "1,000");
        assertThat(f.active()).isTrue();
        assertThat(f.insttCds()).containsExactly("CI00001", "CI00002");
        assertThat(f.corrNos()).containsExactly("2026-000123", "2026-000124");
        assertThat(f.limitOr(500)).isEqualTo(1000);
        assertThat(f.hasPeriod()).isFalse();
        assertThat(f.apply(DAILY)).isSameAs(DAILY);   // 기간이 없으면 창은 기존 그대로
        assertThat(f.narrowsWithoutPeriod()).isTrue();
    }

    @Test
    @DisplayName("값이 틀리면 INVALID — 조건을 하나도 내주지 않고(fail-closed) requireValid 가 사유와 함께 멈춘다")
    void invalidIsFailClosed() {
        List<SourceTestFilter> bad = List.of(
                parse("SOURCE_TEST_FROM", "2026-02-30"),                         // 없는 날짜
                parse("SOURCE_TEST_FROM", "2026/11/20"),                         // 형식
                parse("SOURCE_TEST_TO", "2026-11-20"),                           // TO 만
                parse("SOURCE_TEST_FROM", "2026-11-20 09:00"),                   // 시각인데 TO 없음
                parse("SOURCE_TEST_FROM", "2026-11-21", "SOURCE_TEST_TO", "2026-11-19"),  // 거꾸로
                parse("SOURCE_TEST_FROM", "2026-09-01", "SOURCE_TEST_TO", "2026-11-20"),  // 31일 초과
                parse("SOURCE_TEST_FROM", "2027-11-20"),                         // 미래(연도 오타)
                parse("SOURCE_TEST_INSTT_CD", "CI00001' OR '1'='1"),             // 주입 시도
                parse("SOURCE_TEST_INSTT_CD", "CORR_INSTT_CD"),                  // 컬럼명(8자) — 형식 밖
                parse("SOURCE_TEST_CORR_NO", "A;DROP"),                          // ; 는 구분자 → DROP 은 형식 통과지만 A 도 통과 — 아래 별도
                parse("SOURCE_TEST_MAX_ROWS", "0"),
                parse("SOURCE_TEST_MAX_ROWS", "abc"),
                parse("SOURCE_TEST_MAX_ROWS", "10001"),
                parse("SOURCE_TEST_INST_CD", "CI00001"));                        // 키 오타
        for (SourceTestFilter f : bad.subList(0, 9)) {
            assertInvalid(f);
        }
        assertInvalid(bad.get(10));
        assertInvalid(bad.get(11));
        assertInvalid(bad.get(12));
        assertInvalid(bad.get(13));
        // 구분자(;)로 나뉜 값은 각각 형식 검사를 받고, 바인딩으로만 들어가므로 SQL 이 되지 않는다
        assertThat(bad.get(9).corrNos()).containsExactly("A", "DROP");
    }

    private static void assertInvalid(SourceTestFilter f) {
        assertThat(f.invalid()).as(f.describe()).isTrue();
        assertThat(f.active()).isFalse();
        assertThat(f.insttCds()).isEmpty();
        assertThat(f.corrNos()).isEmpty();
        assertThat(f.maxRows()).isNull();
        assertThat(f.snapshot()).containsEntry("state", "INVALID");
        assertThatThrownBy(f::requireValid).isInstanceOf(IllegalStateException.class).hasMessageContaining("SOURCE_TEST_");
    }

    @Test
    @DisplayName("교정번호 501개 · 기관 51개는 거부(Oracle IN 1,000 제한 · 넓게 잡는 실수)")
    void tooMany() {
        StringBuilder corr = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            corr.append("C").append(i).append(',');
        }
        assertThat(parse("SOURCE_TEST_CORR_NO", corr.toString()).invalid()).isTrue();
        StringBuilder instt = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            instt.append("I").append(i).append(',');
        }
        assertThat(parse("SOURCE_TEST_INSTT_CD", instt.toString()).invalid()).isTrue();
    }

    @Test
    @DisplayName("기간만 있으면 사진 범위를 줄이지 못한다(narrowsWithoutPeriod=false)")
    void periodOnlyDoesNotNarrowImage() {
        assertThat(parse("SOURCE_TEST_FROM", "2026-11-20").narrowsWithoutPeriod()).isFalse();
    }
}
