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
        /**
         * 청크 크기 — 보라미 최신 사진을 이 수만큼씩 {@code CORR_NO} 커서로 끝까지(0건이 나올 때까지) 조회·처리한다.
         * 한 번의 실행이 처리하는 총수의 상한이 아니다(예전 {@code max-per-run} 은 이 값에 첫 2,000명만 보고 끝났다)
         */
        @DefaultValue("2000") int chunkSize,
        /**
         * 수집 대상 수용상태 — 수용자기본 {@code TB_IRIM_PRBS_BS.ACMD_STTUS_CD} 가 이 값인 수용자만 사진을 받는다.
         * Y 수용 · I 이송 · C 정문출소. 비우면 상태로 거르지 않는다(출소자 포함 전체)
         */
        @DefaultValue({"Y", "I", "C"}) java.util.List<String> activeStatusCodes,
        /**
         * 매핑과 함께 {@code TB_SRC_INMATE_BS.PHOTO_REF} 도 갱신할지. 기본 끔 — 정형 수집기가 같은 테이블을 다시 쓰면
         * 순서에 따라 값이 뒤섞인다(그래서 매핑 테이블을 따로 둔다). 필요하면 {@code POST /api/v1/image/photo-ref/sync} 로 일괄 반영한다
         */
        @DefaultValue("false") boolean updatePhotoRef,
        @DefaultValue AdminDb adminDb,
        @DefaultValue("IMAGE_COLLECT") String jobId,
        /**
         * 사진 복호화 — {@code SKIP}(기본 · 2026-11-27 정책: 사진은 평문으로 받는다 · 복호화하지 않고 이미지인지(매직 넘버)만
         * 본다) | {@code ENC_YN}(예전 동작: 복호화 모드 {@code voice.decrypt.mode} 가 REAL 이고 공통파일기본
         * {@code CMMN_FILE_ENC_YN} 이 암호화면 접견과 같은 RVS 키로 푼다). 접견·전화 음성 복호화와는 따로 논다
         */
        @DefaultValue("SKIP") Decrypt decrypt
) {

    /** 사진 복호화 정책 — {@link ImageProperties#decrypt()}. */
    public enum Decrypt {
        /** 복호화하지 않는다(사진 평문 정책) — 받은 바이트를 그대로 저장하고 이미지인지만 확인한다 */
        SKIP,
        /** 예전 동작 — 복호화 모드 REAL 이고 원천이 암호화로 표시된 건만 푼다 */
        ENC_YN
    }

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
