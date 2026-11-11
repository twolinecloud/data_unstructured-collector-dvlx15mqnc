package egovframework.unstructured.collector.mock;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 음성 STT · 비식별 검증 파이프라인 설정 — {@code unstructured.mock.stt-deid.*}(시뮬레이터 11번 탭 · 일 단위 자동 생성).
 *
 * <pre>
 * unstructured:
 *   mock:
 *     stt-deid:
 *       job-id: VOICE_STT_DEID             # T1 작업 ID — 실제 음성 배치(VOICE_ANALYSIS)와 갈라 워터마크 · 대시보드 증분에 섞이지 않게
 *       job-nm: 음성 STT·비식별 검증
 *       deid-path: /api/v1/deid/run        # 에이전트 커넥터(agent-connector.base-url) 아래 경로
 *       deid-read-timeout-sec: 660         # 커넥터 응답 상한 — 커넥터의 AI-R 대기 한도(10분)보다 길게
 *       air-mode:                          # 비우면 커넥터 기본(REAL). MOCK 은 연동 점검용
 *       seed-pii: true                     # 가상 개인정보 문장을 전사 끝에 덧붙인다(합성 낭독에는 개인정보가 없다)
 *       keep-runs: 20                      # 화면에 남길 최근 실행 · 적재 결과 폴더 수
 *       daily:
 *         enabled: true                    # 일 단위 자동 생성(01:30 · 즉시 실행)에 음성 STT·비식별 배치를 붙인다
 *         min-items: 3
 *         max-items: 5
 *         min-sec: 20                      # 20~30분 긴 음원은 넣지 않는다 — 11번 탭에서 따로 돌린다
 *         max-sec: 300
 * </pre>
 *
 * @param jobId              T1 작업 ID(컬렉터 JobId 열거형 밖 — 이름을 함께 보낸다)
 * @param jobNm              T1 작업명
 * @param deidPath           커넥터 비식별 경로
 * @param deidReadTimeoutSec 커넥터 응답 상한(초)
 * @param airMode            커넥터 {@code airMode}(REAL · MOCK). 비우면 보내지 않는다
 * @param seedPii            가상 개인정보 문장을 덧붙일지 — 화면은 실행마다 고른다(일 단위는 이 값)
 * @param keepRuns           최근 실행 보관 수(메모리 · 적재 폴더)
 * @param daily              일 단위 자동 생성에 붙이는 음성 건
 */
@ConfigurationProperties(prefix = "unstructured.mock.stt-deid")
public record SttDeidProperties(
        @DefaultValue("VOICE_STT_DEID") String jobId,
        @DefaultValue("음성 STT·비식별 검증") String jobNm,
        @DefaultValue("/api/v1/deid/run") String deidPath,
        @DefaultValue("660") int deidReadTimeoutSec,
        @DefaultValue("") String airMode,
        @DefaultValue("true") boolean seedPii,
        @DefaultValue("20") int keepRuns,
        @DefaultValue Daily daily) {

    /**
     * 일 단위 음성 건 — 건수 · 길이를 이 범위에서 무작위로 고른다.
     *
     * @param enabled  일 단위 자동 생성(스케줄 · 즉시 실행)에 붙일지
     * @param minItems 최소 건수
     * @param maxItems 최대 건수
     * @param minSec   최소 길이(초)
     * @param maxSec   최대 길이(초) — 제논 한 번 호출 한도(600초) 안
     */
    public record Daily(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("3") int minItems,
            @DefaultValue("5") int maxItems,
            @DefaultValue("20") int minSec,
            @DefaultValue("300") int maxSec) {}
}
