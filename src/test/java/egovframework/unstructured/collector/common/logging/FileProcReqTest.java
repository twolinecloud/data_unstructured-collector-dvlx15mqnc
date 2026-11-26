package egovframework.unstructured.collector.common.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4 적재 요청이 <b>컬렉터 스펙의 필드명</b>으로 나가는지 못 박는다.
 *
 * <p>2026-09-15 까지 이 record 는 추정 필드명(fileTypeCd·sttCharCnt·errTypeCd)으로 보냈고, 컬렉터는
 * 모르는 필드를 조용히 버린 뒤 필수 {@code recFileId} 가 없어 NOT NULL 위반으로 한 건도 적재하지 못했다.
 * 필드명은 컴파일러가 잡아 주지 않는 계약이라 JSON 으로 직렬화해 확인한다.</p>
 */
class FileProcReqTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("직렬화 필드명이 컬렉터 docs/API_SPEC.md §T4 ⑥ 과 같다 — recFileId 가 빠지면 NOT NULL 위반")
    void serializesWithCollectorFieldNames() throws Exception {
        var req = new LogCollectorClient.FileProcReq("MEET-002-0000000000000000", "/data001/doc01/recv",
                "mock_meet_002.m4a", "VP809c84d5c96dc7fc", 64044L, "SUCCESS", null);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(req));

        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("recFileId", "filePath", "fileNm", "inmatePid",
                        "fileSize", "procStsCd", "errStack", "stepTypeCd");
        assertThat(json.path("recFileId").asText()).isEqualTo("MEET-002-0000000000000000");
        assertThat(json.path("procStsCd").asText()).isEqualTo("SUCCESS");
        assertThat(json.path("errStack").isNull()).as("성공 건은 errStack 이 null").isTrue();
    }

    @Test
    @DisplayName("실패 사유는 컬렉터 표준 [코드] 상세 한 줄 — 코드는 C12 에서 사유에 맞게 고른다")
    void errStackCarriesC12Code() {
        assertThat(LogCollectorClient.FileProcReq.errStackOf(
                "IllegalStateException: 수신 파일 대기 타임아웃 — mock_meet_002.m4a (300초)"))
                .startsWith("[TIMEOUT] ");
        assertThat(LogCollectorClient.FileProcReq.errStackOf(
                "ResourceAccessException: I/O error on POST request for \"http://localhost:8082/...\""))
                .startsWith("[CONNECTION] ");
        assertThat(LogCollectorClient.FileProcReq.errStackOf("STT 결과가 비어 있음"))
                .isEqualTo("[DATA] STT 결과가 비어 있음");
        assertThat(LogCollectorClient.FileProcReq.errStackOf(null)).isNull();
        assertThat(LogCollectorClient.FileProcReq.errStackOf("  ")).isNull();
    }

    @Test
    @DisplayName("사유가 C12 코드를 달고 오면 그 코드를 맨 앞으로 — 음성 격리 [RECONCILE] · 단계 표시 · T1 오류 구분도 RECONCILE")
    void taggedReasonKeepsItsCode() {
        String reason = "DeidentException: [RECONCILE] 비식별 후 잔존 개인정보 1건(PHONE) — 격리 · 전송 제외";

        assertThat(LogCollectorClient.FileProcReq.errStackOf(reason))
                .isEqualTo("[RECONCILE] DeidentException: 비식별 후 잔존 개인정보 1건(PHONE) — 격리 · 전송 제외");
        assertThat(LogCollectorClient.FileProcReq.errStackOf(
                egovframework.unstructured.collector.common.model.StepType.DEIDENT, reason))
                .isEqualTo("[RECONCILE] [DEIDENT] DeidentException: 비식별 후 잔존 개인정보 1건(PHONE) — 격리 · 전송 제외");
        assertThat(LogCollectorClient.errTypeOf(LogCollectorClient.FileProcReq.errStackOf(reason))).isEqualTo("RECONCILE");
        assertThat(LogCollectorClient.FileProcReq.errStackOf("[TIMEOUT] 응답 없음")).isEqualTo("[TIMEOUT] 응답 없음");

        // 꼬리표가 맨 앞(예외 이름 바로 뒤)이 아니거나 C12 가 아니면 종전대로 낱말로 고른다
        assertThat(LogCollectorClient.FileProcReq.errStackOf(
                "IllegalStateException: 에이전트 커넥터 비식별 HTTP 500 (MOCK) [더미 시나리오 DEIDENT_FAIL · 1회]"))
                .startsWith("[DATA] IllegalStateException: ");
        assertThat(LogCollectorClient.FileProcReq.errStackOf("DeidentException: [HTTP-500] 실패"))
                .isEqualTo("[DATA] DeidentException: [HTTP-500] 실패");
        assertThat(LogCollectorClient.FileProcReq.errStackOf("DeidentException: 에이전트 커넥터 비식별 응답이 비어 있음"))
                .isEqualTo("[DATA] DeidentException: 에이전트 커넥터 비식별 응답이 비어 있음");
    }

    @Test
    @DisplayName("컬렉터 응답 ids 는 문자열(FILE_PROC_ID) 이다 — 숫자로 읽으면 0 이 된다")
    void idsAreStrings() throws Exception {
        JsonNode result = mapper.readTree("{\"count\":2,\"ids\":[\"20260915TST004001\",\"20260915TST004002\"]}");

        List<String> ids = new java.util.ArrayList<>();
        result.path("ids").forEach(n -> ids.add(n.asText()));

        assertThat(ids).containsExactly("20260915TST004001", "20260915TST004002");
        assertThat(result.path("ids").get(0).asLong()).as("asLong 이면 정보가 사라진다").isZero();
    }
}
