package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WF9 답 → 주요사항 행 — 울타리 · 앞뒤 글 · 필드 이름 목록 · 내용만 되돌리기(D6). 모든 값은 가상이다. */
class Wf9SignalsTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final List<String> CONTENT = List.of("content", "description", "summary");
    private static final List<String> RISK = List.of("riskClassCode", "category", "type");

    @Test
    @DisplayName("코드 울타리 · 앞뒤 설명 글이 있어도 첫 울타리 안의 첫 '{' ~ 끝 '}' 를 읽는다")
    void parsesFencedAnswer() {
        String answer = "분석 결과입니다.\n```json\n{\"findings\":[{\"content\":\"가\"}],\"reviews\":[]}\n```\n참고: {끝}";

        Wf9Signals.Parsed p = Wf9Signals.parse(OM, answer);

        assertThat(p.findings()).hasSize(1);
        assertThat(p.reviews()).isEmpty();
        assertThat(Wf9Signals.parse(OM, "  {\"reviews\":[\"나\"]} ").reviews()).hasSize(1);
        assertThat(Wf9Signals.parse(OM, "{\"findings\":null,\"reviews\":[]}").findings()).isEmpty();
    }

    @Test
    @DisplayName("JSON 이 없거나 깨졌거나 findings · reviews 가 없으면 PARSE 실패(다시 하기 — 다시 물으면 맞을 수 있다)")
    void parseFailuresAreRetryable() {
        for (String bad : List.of("주요사항이 없습니다.", "{\"findings\":[", "{\"result\":[]}", "{\"findings\":{\"a\":1}}")) {
            assertThatThrownBy(() -> Wf9Signals.parse(OM, bad))
                    .as(bad)
                    .isInstanceOfSatisfying(Wf9Failure.class, f -> {
                        assertThat(f.stage()).isEqualTo(Wf9Failure.Stage.PARSE);
                        assertThat(f.retryable()).isTrue();
                    });
        }
    }

    @Test
    @DisplayName("항목 하나 = 행 하나 — 내용 · 분류는 앞에 둔 필드부터, 내용만 되돌리고 원 항목은 가린 그대로")
    void rowsRestoreContentOnly() throws Exception {
        Wf9Signals.Parsed p = Wf9Signals.parse(OM, """
                {"findings":[{"summary":"요약","content":"[NAME_1]이 [PHONE_1]로 연락하라고 함","category":"외부연락","riskClassCode":"CONTACT"}],
                 "reviews":[{"description":"[NAME_1] 면회 일정 확인","type":"CHECK"},"[NAME_2] 언급 — 확인 필요"]}""");
        Map<String, String> mapping = Map.of("[NAME_1]", "홍길동", "[PHONE_1]", "010-1234-5678");

        Wf9Signals.Rows rows = Wf9Signals.toRows(p, mapping, CONTENT, RISK);

        List<LogCollectorClient.ContactRiskSignalReq> s = rows.signals();
        assertThat(s).hasSize(3);
        assertThat(s.get(0).signalSeCd()).isEqualTo("FINDING");
        assertThat(s.get(0).signalCn()).isEqualTo("홍길동이 010-1234-5678로 연락하라고 함");
        assertThat(s.get(0).riskClassCd()).as("앞에 둔 riskClassCode").isEqualTo("CONTACT");
        assertThat(s.get(0).signalData().path("content").asText()).as("원 항목은 가린 그대로")
                .isEqualTo("[NAME_1]이 [PHONE_1]로 연락하라고 함");
        assertThat(s.get(0).signalSn()).as("순번은 로그 컬렉터가 매긴다").isNull();
        assertThat(s.get(1).signalSeCd()).isEqualTo("REVIEW");
        assertThat(s.get(1).signalCn()).isEqualTo("홍길동 면회 일정 확인");
        assertThat(s.get(1).riskClassCd()).isEqualTo("CHECK");
        assertThat(s.get(2).signalCn()).as("글 항목 · 사전에 없는 토큰은 그대로").isEqualTo("[NAME_2] 언급 — 확인 필요");
        assertThat(s.get(2).riskClassCd()).isNull();
        assertThat(s.get(0).toString()).as("toString 에 내용이 없다").doesNotContain("홍길동");
    }

    @Test
    @DisplayName("내용 필드가 없으면 항목 JSON 을 그대로(되돌려서) · 빈 내용은 뺀다")
    void contentFallbackAndBlank() throws Exception {
        Wf9Signals.Parsed p = Wf9Signals.parse(OM, "{\"findings\":[{\"who\":\"[NAME_1]\",\"score\":3},\"  \",{\"content\":\"\"}]}");

        Wf9Signals.Rows rows = Wf9Signals.toRows(p, Map.of("[NAME_1]", "홍길동"), CONTENT, RISK);

        assertThat(rows.signals()).hasSize(2);
        assertThat(rows.signals().get(0).signalCn()).isEqualTo("{\"who\":\"홍길동\",\"score\":3}");
        assertThat(rows.signals().get(1).signalCn()).as("빈 content 는 건너뛰고 항목 JSON").isEqualTo("{\"content\":\"\"}");
        assertThat(rows.skipped()).as("공백 글 항목").isEqualTo(1);
    }

    @Test
    @DisplayName("항목 규격 — 약속한 '내용' 필드(목록 첫 이름)에 글이 없는 항목은 규격 밖으로 세고, 쓴 필드 이름만 모은다(값은 안 모은다)")
    void offSpecTally() throws Exception {
        Wf9Signals.Parsed p = Wf9Signals.parse(OM, """
                {"findings":[{"riskClassCode":"ESCAPE","content":"[NAME_1] 도주 암시","evidence":"[NAME_1]: 나갈 거야"},
                             {"위험유형":"도주","주요사항":"[NAME_1] 발언","근거":"[NAME_1]: 나갈 거야"}],
                 "reviews":[{"description":"확인 필요","type":"CHECK"},"글 항목",{"content":"  ","note":"x"},{"bad key!":"v"}]}""");
        List<String> content = List.of("content", "주요사항", "description");
        List<String> risk = List.of("riskClassCode", "위험유형", "type");

        Wf9Signals.Rows rows = Wf9Signals.toRows(p, Map.of("[NAME_1]", "홍길동"), content, risk);

        assertThat(rows.offSpec()).as("규격대로인 첫 항목만 빼고 다섯").isEqualTo(5);
        assertThat(rows.offSpecFields()).containsExactly(
                "위험유형", "주요사항", "근거", "description", "type", "(글)", "content", "note", "(이름 형식 밖)");
        assertThat(String.join(",", rows.offSpecFields())).doesNotContain("홍길동").doesNotContain("발언").doesNotContain("CHECK");
        assertThat(rows.signals()).as("규격 밖이어도 후보 이름으로 읽어 넣는다").hasSize(6);
        assertThat(rows.signals().get(1).signalCn()).isEqualTo("홍길동 발언");
        assertThat(rows.signals().get(1).riskClassCd()).as("분류 값은 그대로 — 코드로는 로그 컬렉터가 맞춘다").isEqualTo("도주");

        assertThat(Wf9Signals.inSpec(OM.readTree("{\"content\":\"가\"}"), List.of("content"))).isTrue();
        assertThat(Wf9Signals.inSpec(OM.readTree("{\"content\":1}"), List.of("content"))).as("글만").isFalse();
        assertThat(Wf9Signals.inSpec(OM.readTree("{\"content\":\"가\"}"), List.of())).isFalse();
    }

    @Test
    @DisplayName("규격 밖 필드 이름은 10개까지만 · 규격대로면 0")
    void offSpecFieldsCapped() throws Exception {
        StringBuilder many = new StringBuilder("{\"findings\":[{");
        for (int i = 1; i <= 12; i++) {
            many.append(i > 1 ? "," : "").append("\"f").append(i).append("\":\"v\"");
        }
        Wf9Signals.Rows rows = Wf9Signals.toRows(Wf9Signals.parse(OM, many.append("}]}").toString()), Map.of(), CONTENT, RISK);
        assertThat(rows.offSpec()).isEqualTo(1);
        assertThat(rows.offSpecFields()).hasSize(10).startsWith("f1").endsWith("f10");

        Wf9Signals.Rows ok = Wf9Signals.toRows(Wf9Signals.parse(OM,
                "{\"findings\":[{\"riskClassCode\":\"ESCAPE\",\"content\":\"가\"}],\"reviews\":[{\"content\":\"나\"}]}"), Map.of(), CONTENT, RISK);
        assertThat(ok.offSpec()).isZero();
        assertThat(ok.offSpecFields()).isEmpty();
    }

    @Test
    @DisplayName("되돌리기는 한 번 훑는다 — 되돌린 값 안의 토큰 모양을 다시 바꾸지 않는다")
    void restoreSinglePass() {
        Map<String, String> m = Map.of("[NAME_1]", "[NAME_2]", "[NAME_2]", "홍길동");

        assertThat(Wf9Signals.restore("[NAME_1]와 [NAME_2]", m)).isEqualTo("[NAME_2]와 홍길동");
        assertThat(Wf9Signals.restore("[CASE_NO_1] 사건", Map.of("[CASE_NO_1]", "2026고단123"))).isEqualTo("2026고단123 사건");
        assertThat(Wf9Signals.restore("값 $1 \\ 그대로", Map.of("[X_1]", "a"))).isEqualTo("값 $1 \\ 그대로");
        assertThat(Wf9Signals.restore("[A_1]", Map.of("[A_1]", "$1\\"))).isEqualTo("$1\\");
    }
}
