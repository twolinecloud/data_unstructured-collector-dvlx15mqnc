package egovframework.unstructured.collector.common.transfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 제논(Zenon) 전송 설정 — 파이프라인 마지막 단계 SEND 가 STT 결과를 넘기는 곳.
 *
 * <pre>
 * zenon:
 *   mode: MOCK                         # MOCK — 수집기 내장 수신기로(네트워크 없음) · REST — 제논 수신 API 로
 *   base-url: http://zenon:8080
 *   transfer-path: /api/v1/learn/transfer
 *   gzip: true
 *   chunk-records: 50
 * </pre>
 *
 * <p><b>전송 양식은 data-collector 와 같다</b>(2026-10-05 — 에이전트 커넥터 없이 제논으로 직접). 배치 1회 = 전송 런 1개
 * ({@code X-Run-Id} = 실행 ID), 레코드를 {@code chunk-records} 건씩 묶어 순번({@code X-Seq} 1부터) 순서대로 보내고,
 * 마지막 청크에 {@code X-Is-Last: true}. 본문 {@code {header:{runId,dataTypeCd,collectDtm,setTypeCd}, payload:[{managementNo,rawDataset}]}}
 * 을 gzip + chunked 로 흘린다. 성공 = HTTP 2xx. 자세한 규칙은 {@link ZenonClient}.</p>
 *
 * @param mode                      MOCK · REST
 * @param baseUrl                   REST 일 때 수신 서버 주소(경로 접두가 있으면 포함)
 * @param transferPath              수신 경로 — data-collector {@code collector.admin-integration.transfer-path} 와 같은 기본값
 * @param gzip                      본문 gzip(Content-Encoding) — data-collector 와 같은 토글
 * @param dataTypeCd                {@code X-Data-Type} · {@code header.dataTypeCd} — 비정형
 * @param setTypeCd                 {@code header.setTypeCd} — 용도 구분(정형은 PREDICTION/LEARNING). 음성 전사는 {@code VOICE}
 * @param chunkRecords              청크 하나에 담을 레코드 수
 * @param maxPayloadBytes           청크 하나의 직렬화 상한 — 넘으면 레코드 수를 줄여 더 잘게 나눈다(data-collector max-payload)
 * @param connectTimeoutMs          연결 제한
 * @param readTimeoutMs             응답 제한(소켓)
 * @param keepReceipts              화면 검증용으로 메모리에 남길 최근 수신증 수
 * @param mockMaxDecompressedBytes  내장 수신기(MOCK) 해제 상한 — 넘으면 413(압축 폭탄 방어). 에이전트 커넥터와 같은 512MB
 * @param mockGapTimeoutSec         내장 수신기 유실 판정 — 마지막 청크를 받고도 빈 순번이 이만큼 안 채워지면 INGEST-GAP
 */
@ConfigurationProperties(prefix = "zenon")
public record ZenonProperties(
        @DefaultValue("MOCK") Mode mode,
        @DefaultValue("") String baseUrl,
        @DefaultValue("/api/v1/learn/transfer") String transferPath,
        @DefaultValue("true") boolean gzip,
        @DefaultValue("UNSTRUCTURED") String dataTypeCd,
        @DefaultValue("VOICE") String setTypeCd,
        @DefaultValue("50") int chunkRecords,
        @DefaultValue("52428800") long maxPayloadBytes,
        @DefaultValue("3000") int connectTimeoutMs,
        @DefaultValue("60000") int readTimeoutMs,
        @DefaultValue("500") int keepReceipts,
        @DefaultValue("536870912") long mockMaxDecompressedBytes,
        @DefaultValue("60") int mockGapTimeoutSec
) {

    public enum Mode { MOCK, REST }
}
