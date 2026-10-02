package egovframework.unstructured.collector.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 비정형(UNSTRUCTURED) 단일 진입점 설정 — admin 연동(스케줄 · 바로 실행 · 긴급 재처리)과 오케스트레이션.
 *
 * <p>음성(접견·전화)·이미지 각각의 설정({@code voice.*} · {@code image.*})과 따로 둔다. 관리자 화면에서
 * 비정형은 한 행(UNSTRUCTURED)이고, 그 한 행이 음성과 이미지를 함께 움직이기 때문이다.</p>
 *
 * <pre>
 * unstructured:
 *   admin:
 *     base-url: ${ADMIN_API_BASE_URL:}        # 비우면 admin 연동 끔(설정 조회 안 함 — refresh 본문 적용만)
 *     connect-timeout: 2s
 *     read-timeout: 3s
 *   schedule:
 *     refresh-ms: 300000                      # admin 재조회 주기(5분) — data-collector collector.schedule.refresh-ms 와 같다
 *     zone: Asia/Seoul
 *   batch:
 *     include-image: false                    # true 면 음성 → 이미지 순차
 *     exec-id-wait-ms: 1500                   # 바로 실행·재처리 접수 응답에 실제 execId 를 싣기 위해 기다리는 시간
 * </pre>
 */
@ConfigurationProperties(prefix = "unstructured")
public record UnstructuredBatchProperties(
        @DefaultValue Admin admin,
        @DefaultValue Schedule schedule,
        @DefaultValue Batch batch) {

    /**
     * admin-api 연결.
     *
     * @param baseUrl        예: {@code http://admin-api-yo38xf9iff.service-core.svc.cluster.local:8080}. 비면 연동 끔
     * @param connectTimeout 연결 타임아웃
     * @param readTimeout    응답 타임아웃
     */
    public record Admin(
            @DefaultValue("") String baseUrl,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("3s") Duration readTimeout) {

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    /**
     * 스케줄.
     *
     * @param refreshMs admin 재조회 주기(ms). 관리 화면의 시각이 10분 단위라 그 절반(5분)
     * @param zone      트리거 시간대 — 파드 TZ 와 무관하게 한국 시각으로 돈다
     */
    public record Schedule(
            @DefaultValue("300000") long refreshMs,
            @DefaultValue("Asia/Seoul") String zone) {}

    /**
     * 오케스트레이션.
     *
     * @param includeImage  음성 뒤에 수용자 이미지 수집도 돌릴지. 기본 끔 — 정형 수집기의 {@code PHOTO_REF}
     *                      반영 순서가 아직 정해지지 않았다(회의록 5-4). 켜면 <b>순차(음성 → 이미지)</b> — 병렬은
     *                      보라미 부하·브로커 동시성을 검증하지 않았다
     * @param execIdWaitMs  바로 실행·재처리 접수 뒤 실제 execId(로그 컬렉터 채번)를 기다리는 상한. admin-api 의
     *                      호출 타임아웃(기본 3초)보다 충분히 짧아야 한다
     */
    public record Batch(
            @DefaultValue("false") boolean includeImage,
            @DefaultValue("1500") long execIdWaitMs) {}
}
