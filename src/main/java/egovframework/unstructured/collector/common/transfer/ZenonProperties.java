package egovframework.unstructured.collector.common.transfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 제논(Zenon) AI 전송 설정 — 파이프라인 마지막 단계 SEND 가 수집·복호화·분석을 마친 결과를 넘기는 곳.
 *
 * <pre>
 * zenon:
 *   mode: MOCK            # MOCK — 수집기 안에서 수신증만 만든다(네트워크 없음) · REST — 제논 수신 API 호출
 *   base-url: http://localhost:8000
 *   receive-path: /api/v1/zenon/receive
 * </pre>
 *
 * <p>클라우드 전송·비식별화가 빠지고(2026-10-01) 온프레미스 제논으로 넘기는 것이 SEND 다. 제논 수신 사양이
 * 확정되기 전까지 개발계는 MOCK 이고, 로컬에서는 {@code tools/zenon-mock}(FastAPI)을 띄워 REST 로 시험한다.</p>
 *
 * @param mode             MOCK · REST
 * @param baseUrl          REST 일 때 제논 주소
 * @param receivePath      수신 API 경로
 * @param connectTimeoutMs 연결 제한
 * @param readTimeoutMs    응답 제한
 * @param keepReceipts     화면 검증용으로 메모리에 남길 최근 수신증 수
 */
@ConfigurationProperties(prefix = "zenon")
public record ZenonProperties(
        @DefaultValue("MOCK") Mode mode,
        @DefaultValue("") String baseUrl,
        @DefaultValue("/api/v1/zenon/receive") String receivePath,
        @DefaultValue("3000") int connectTimeoutMs,
        @DefaultValue("30000") int readTimeoutMs,
        @DefaultValue("500") int keepReceipts
) {

    public enum Mode { MOCK, REST }
}
