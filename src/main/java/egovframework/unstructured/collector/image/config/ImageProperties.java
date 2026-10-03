package egovframework.unstructured.collector.image.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 수용자 이미지 수집 설정 — {@code image.*}.
 *
 * <p>보라미 조회는 음성과 같은 원천 DB 라우터({@code voice.source.*})를 쓰고, 매핑 적재는 <b>Admin DB</b>
 * ({@code image.admin-db.*}) 에 한다.</p>
 */
@ConfigurationProperties(prefix = "image")
public record ImageProperties(
        /** 사진을 가리키는 {@code TB_IRIM_BSIF_DS.IMAGE_SE_CD} 값 */
        @DefaultValue("1") String imageSeCd,
        /** 복호화한 사진을 둘 저장소. 비우면 {@code {ROOT_DIR}/image} */
        @DefaultValue("") String outputDir,
        /** 건을 동시에 처리할 워커 수(브로커 수신 · 복호화 · 저장 · 매핑). 이미지는 작아 확보·STT 처럼 나누지 않는다 */
        @DefaultValue("4") int workers,
        /** 보라미 최신 이미지 조회 페이지 크기 */
        @DefaultValue("500") int pageSize,
        /** 한 번에 처리할 최대 수용자 수 */
        @DefaultValue("2000") int maxPerRun,
        /**
         * 매핑과 함께 {@code TB_SRC_INMATE_BS.PHOTO_REF} 도 갱신할지. 기본 끔 — 정형 수집기가 같은 테이블을 다시 쓰면
         * 순서에 따라 값이 뒤섞인다(그래서 매핑 테이블을 따로 둔다). 필요하면 {@code POST /api/v1/image/photo-ref/sync} 로 일괄 반영한다
         */
        @DefaultValue("false") boolean updatePhotoRef,
        @DefaultValue AdminDb adminDb,
        @DefaultValue("IMAGE_COLLECT") String jobId
) {

    /**
     * Admin DB(교정 AI 플랫폼 DB · 스키마 {@code kcais}).
     *
     * <p>기본은 로컬 H2 — 접속 정보 없이도 파이프라인 전체를 돌려 볼 수 있게. 개발계는
     * {@code jdbc:postgresql://admin-db-fy9tjq4tsk.service-core.svc.cluster.local:5432/correction_ai}.
     * 기동 시 붙어 보지 않는다 — 붙지 못하면 매핑 단계가 사유와 함께 실패한다.</p>
     */
    public record AdminDb(
            @DefaultValue("jdbc:h2:mem:admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
            String url,
            @DefaultValue("sa") String username,
            @DefaultValue("") String password,
            @DefaultValue("kcais") String schema,
            /** Hikari 최대 연결 수 — 매핑은 건마다 짧은 트랜잭션 하나라 워커 수보다 작아도 된다 */
            @DefaultValue("10") int maxPoolSize,
            @DefaultValue("3000") long connectTimeoutMs,
            /** 누수 감지(ms) — 매핑 트랜잭션은 수 ms 라 넉넉히 둔다. 0 이면 끈다 */
            @DefaultValue("30000") long leakDetectionThresholdMs
    ) {}
}
