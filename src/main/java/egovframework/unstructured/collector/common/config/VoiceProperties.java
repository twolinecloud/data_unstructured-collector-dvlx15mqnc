package egovframework.unstructured.collector.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 음성 수집 서비스 설정 — 외부 의존 4종을 <b>각각 독립 스위치</b>로 둔다.
 *
 * <p>이 과제는 보라미 조회·XVARM 브로커·복호화·STT 네 가지가 서로 다른 시점에 준비된다.
 * 하나라도 막히면 전부 멈추는 구조를 피하려고, 준비된 것부터 REAL 로 돌리고 나머지는 MOCK 으로
 * 둘 수 있게 했다.
 * <b>REAL 이면 실서버만 호출하고 실패 시 Mock 으로 빠지지 않는다</b>(Fail-fast).
 * Mock 결과가 실제인 양 섞이면 시연·검증이 전부 무의미해지기 때문이다.</p>
 */
@ConfigurationProperties(prefix = "voice")
public record VoiceProperties(
        @DefaultValue Source source,
        @DefaultValue Broker broker,
        @DefaultValue Phone phone,
        @DefaultValue Sync sync,
        @DefaultValue Dirs dirs,
        @DefaultValue Decrypt decrypt,
        @DefaultValue Stt stt,
        @DefaultValue Batch batch,
        @DefaultValue Sim sim
) {

    /** 보라미 대상 조회 경로. */
    public record Source(
            @DefaultValue("MOCK") SourceMode mode,
            /** ESB_HTTP2DB 모드에서 쓸 연계 서버 주소. 표준 A 규약상 {@code https://{ip}:{port}/{인터페이스ID}} */
            @DefaultValue("") String esbBaseUrl,
            /** 인터페이스ID — 표준 A 명명규칙 {@code IF_송신(3)_수신(3)_일련(3)}. 미확정이라 설정으로 뺀다. */
            @DefaultValue("") String interfaceId,
            @DefaultValue Schema schema,
            @DefaultValue Flag flag,
            /**
             * XVARM 연동 — DIRECT_JDBC(개발계 DB)에서 접견 4단 조인의 뒤 두 테이블을 어디서 볼지.
             * <ul>
             *   <li>{@code MOCK_DEV}(기본): 개발계 DB 에 누락된 공통파일기본·XVARM 테이블을 우리가 만들고
             *       시딩한 {@link XvarmMock} 스키마의 테이블을 조인한다</li>
             *   <li>{@code REAL}: {@link Schema#smsm()}·{@link Schema#xvarm()} 에 설정된 실제 테이블을 조인한다</li>
             * </ul>
             */
            @DefaultValue("MOCK_DEV") XvarmMode xvarmMode,
            @DefaultValue XvarmMock xvarmMock,
            /** {@code MOCK (로컬 H2)} 가 보는 인메모리 DB. 기동 시 Mock 스키마·필터 검증 행을 깔고 시뮬레이션 데이터를 시딩한다. */
            @DefaultValue LocalH2 localH2,
            /** {@code 개발계 DB}(DIRECT_JDBC) 가 보는 실제 DB — 개발계 borami-db(PostgreSQL) 또는 운영 보라미(Oracle). */
            @DefaultValue DirectDb directDb
    ) {}

    /**
     * 로컬 H2 Mock 보라미. 이름이 같은 인메모리 DB 는 JVM 안에서 공유된다({@code DB_CLOSE_DELAY=-1}).
     * 스키마 적재는 Spring 의 sql.init 이 아니라 {@code BoramiDataSourceConfig} 가 <b>이 DataSource 에만</b> 한다 —
     * 라우팅 DataSource 에 sql.init 을 걸면 DROP TABLE 이 실DB 로 갈 수 있다.
     */
    public record LocalH2(
            @DefaultValue("jdbc:h2:mem:borami;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE") String url,
            @DefaultValue("sa") String username,
            @DefaultValue("") String password,
            /** Hikari 최대 연결 수 — 개발계 DB 와 같게 둔다. {@link DirectDb#maxPoolSize()} 참고. */
            @DefaultValue("40") int maxPoolSize,
            /** 연결 누수 감지(ms) — {@link DirectDb#leakDetectionThresholdMs()} 참고. 0 이면 끈다. */
            @DefaultValue("30000") long leakDetectionThresholdMs
    ) {}

    /**
     * DIRECT_JDBC(개발계 DB) 대상. 드롭다운을 {@code 개발계 DB} 로 바꾸는 순간 조회·시딩이 여기로 간다.
     *
     * <p>기동 시 붙어 있지 않아도 뜬다({@code initialization-fail-timeout=-1}) — 로컬은 포트포워딩이 없을 때가 많다.
     * 붙지 못하면 {@code connect-timeout-ms} 뒤에 사유와 함께 실패하고 시뮬레이터 [DB 연결 확인] 이 그것을 보여준다.</p>
     */
    public record DirectDb(
            @DefaultValue("jdbc:postgresql://localhost:15433/borami") String url,
            @DefaultValue("") String driverClassName,
            @DefaultValue("borami") String username,
            @DefaultValue("") String password,
            @DefaultValue("3000") long connectTimeoutMs,
            /**
             * Hikari 최대 연결 수 — 고동시성(STT 워커 30개 이상) 여유분. 최소 유휴는 0 이라 쓰지 않으면 열지 않는다.
             *
             * <p>배치 워커(확보·STT)는 원천 DB 를 쓰지 않는다 — 대상 조회가 루프 전에 끝나고, 로그 적재는 로그 컬렉터
             * API 다. 그래서 정상이면 최고 연결 수는 1~2 다. 이 값은 조회·시딩·진단이 겹칠 때의 여유다.</p>
             */
            @DefaultValue("40") int maxPoolSize,
            /**
             * 연결 누수 감지(ms) — 빌려 간 연결이 이 시간 넘게 돌아오지 않으면 Hikari 가 빌린 자리의 스택과 함께 WARN 을
             * 남긴다("Connection leak detection triggered"). 가장 긴 정상 사용(시딩 트랜잭션 수 초)보다 넉넉히 둔다.
             * 0 이면 끈다. Hikari 규칙상 2,000 미만은 쓸 수 없다.
             */
            @DefaultValue("30000") long leakDetectionThresholdMs,
            /** 유휴 연결을 닫기까지(ms) — 시험이 끝나 최고치까지 늘었던 연결이 이만큼 쉬면 닫혀 0 으로 돌아간다. */
            @DefaultValue("60000") long idleTimeoutMs
    ) {}

    /**
     * XVARM DB MOCK(개발계) — 개발계 borami-db(PostgreSQL)에 우리가 만드는 두 테이블이 놓일 스키마.
     * 2026-09-12 확인 기준 borami-db 에는 공통파일기본(sm)과 XVARM 테이블이 없다.
     * H2(로컬)는 스키마 없이 평평하므로 이 값을 쓰지 않는다.
     */
    public record XvarmMock(
            /** 공통파일기본 {@code tb_smsm_cmfi_bs} 를 만들 스키마 */
            @DefaultValue("sm") String schemaSmsm,
            /** XVARM {@code asyscontentelement} 를 만들 스키마 */
            @DefaultValue("xvarm") String schemaXvarm
    ) {}

    /**
     * 보라미 테이블이 놓인 스키마.
     *
     * <p><b>실제 보라미는 테이블명 접두어가 곧 스키마다</b> — 2026-09-12 실DB 확인 결과
     * {@code im.tb_imsc_ptpr_dt}, {@code re.tb_rerd_tfin_ds}, {@code im.tb_imph_ucdr_ds} 였다.
     * 회의 축어록 {@code [00:17:26]} 의 "TVG의 RE면 RE점, IM이면 IM점" 이 이것이다.</p>
     *
     * <p>반면 개발용 H2 Mock 은 스키마 없이 평평하다. 두 쪽에서 같은 SQL 이 돌게 하려고
     * <b>비우면 수식 없이</b>, 채우면 {@code 스키마.테이블} 로 조립한다.</p>
     */
    public record Schema(
            /** 특이수용자상세·사용자통화내역 — 실제 {@code im} */
            @DefaultValue("") String imsc,
            /** 녹취파일내역 — 실제 {@code re} */
            @DefaultValue("") String rerd,
            /** 공통파일기본 — 실제 {@code sm} (2026-09-12 기준 borami-db 에 테이블 자체가 없음) */
            @DefaultValue("") String smsm,
            /** XVARM 콘텐츠 메타 — 실제 스키마 미확인 (borami-db 에 없음) */
            @DefaultValue("") String xvarm
    ) {}

    /**
     * 플래그 컬럼의 <b>값 도메인</b>.
     *
     * <p><b>하드코딩하면 안 되는 이유</b>: 스펙 문서에는 {@code CHAR NOT NULL} 이라고만 적혀 있고
     * 어떤 값이 들어가는지 없다. 실DB 를 열어 보니 {@code TELP_PTCR_PRSR_YN} 은 {@code 'Y'} 가 아니라
     * <b>{@code '0'}</b> 이었다. {@code 'Y'} 로 비교하던 지름길 필터는 영원히 0건을 돌려준다 —
     * 에러가 아니라 "대상 없음" 으로 보여서 알아채기도 어렵다.</p>
     */
    public record Flag(
            /** {@code TELP_PCALL_RECRD_YN} 의 '녹음됨' 값 */
            @DefaultValue("Y") String recordedYes,
            /** {@code TELP_PTCR_PRSR_YN} 의 '특이수용자' 값 — 실DB 관측값은 '0' 계열이었다 */
            @DefaultValue("Y") String ptcrYes,
            /** {@code DEL_YN}·{@code RECRD_FILE_DEL_YN} 의 '삭제 안 됨' 값 */
            @DefaultValue("N") String notDeleted,
            /** {@code CMMN_FILE_ENC_YN} 의 '암호화됨' 값 */
            @DefaultValue("Y") String encrypted
    ) {}

    /** 보라미 WAS 안에 배포될 XVARM 브로커 호출 설정. */
    public record Broker(
            @DefaultValue("MOCK") BrokerMode mode,
            /**
             * 브로커 주소. 기동 초기값이며, 시뮬레이터에서 런타임으로 바꿀 수 있다
             * ({@code VoiceModeState.brokerBaseUrl}). 바꾼 값은 이 프로세스에만 남는다.
             */
            @DefaultValue("") String baseUrl,
            /**
             * 시뮬레이터의 원클릭 주소 프리셋. 환경마다 브로커가 사는 곳이 달라
             * (개발계는 K8s 서비스명, 로컬은 localhost) 매번 손으로 치게 하지 않으려는 것이다.
             * 설정으로 빼 둔 이유는 주소가 바뀌었을 때 화면 코드를 고치지 않게 하려는 것.
             */
            List<Preset> presets,
            /** 추출 완료를 기다리는 폴링 간격. 브로커는 202 로 받고 상태를 따로 알려준다. */
            @DefaultValue("2000") long pollIntervalMs,
            @DefaultValue("120") int pollTimeoutSec
    ) {
        public Broker {
            presets = (presets == null) ? List.of() : List.copyOf(presets);
        }
    }

    /** 시뮬레이터에서 한 번에 고를 수 있는 주소 후보. */
    public record Preset(String label, String url) {}

    /**
     * 전화 파일 연계 경로. <b>브로커(XVARM)와 무관한 별도 트랙이다.</b>
     *
     * <p>접견은 우리가 XVARM 브로커에 추출을 지시하지만, 전화는 보라미가 아닌
     * <b>별도 서버</b>에 파일이 있고 ESB 가 전화 전용 연계 프로바이더를 구성해 준다
     * (2026-09-11 회의 {@code [00:11:39]~[00:12:07]}). 우리는 요청만 하고 떨어지기를 기다린다.</p>
     *
     * <p>예전에는 이 모드를 브로커 스위치에 얹어 두었는데, 그러면 "XVARM 브로커" 를 REST 로
     * 올리는 순간 XVARM 을 타지도 않는 전화 경로까지 같이 바뀌어 버린다. 두 시나리오는
     * 연동 주체가 다르므로 스위치도 따로 둔다.</p>
     */
    public record Phone(
            @DefaultValue("MOCK") PhoneMode mode
    ) {}

    /** ESB 가 떨궈 준 파일을 집어오는 구간 — <b>대기 정책</b>만 둔다. 경로는 {@link Dirs} 에 있다. */
    public record Sync(
            /**
             * 파일 크기가 이 시간만큼 그대로여야 "쓰기가 끝났다"고 본다.
             * ESB Agent 가 쓰는 중인 파일을 읽으면 깨진 오디오를 STT 에 태우게 된다.
             */
            @DefaultValue("1500") long stableCheckMs,
            @DefaultValue("300") int waitTimeoutSec,
            /**
             * 수신 파일명 규칙. 표준 A 에 두 규칙이 병기되어 있어 확정 전까지 설정으로 둔다(Q7).
             * ORIGINAL = 업무 파일명 그대로 / ESB_DAT = {@code R_...DAT} 규약
             */
            @DefaultValue("ORIGINAL") egovframework.unstructured.collector.common.sync.EsbFileNamingPolicy.Policy namingPolicy
    ) {}

    /**
     * 디렉터리 6종 — ROOT_DIR 아래 <b>표준 배치</b>. 비우면 ROOT_DIR 로 파생되고, 채우면 그 값이 이긴다.
     *
     * <pre>
     *   ROOT_DIR (base-dir)   비우면 OS 로 결정 — Windows C:/k8s/voice_collector · Linux/K8s /k8s/voice_collector
     *   xvarmOriginal          {ROOT}/xvram/original_voice_files   XVARM 접견 원본(시뮬레이션 더미 파일도 여기)
     *   receiveMeet            {ROOT}/esb/meet                     ESB 원본 수신 (접견) — 브로커·ESB 가 떨구는 곳
     *   receivePhone           {ROOT}/esb/phone                    ESB 원본 수신 (전화)
     *   work                   {ROOT}/xvram/decoding               XVARM 접견 복호화 산출물 · 멱등 표식
     *   outputMeet             {ROOT}/xenon/meet                   최종 변환/저장 (접견) — 실제 파일은 {outputMeet}/{execId}/
     *   outputPhone            {ROOT}/xenon/phone                  최종 변환/저장 (전화) — 실제 파일은 {outputPhone}/{execId}/
     * </pre>
     *
     * <p>기동 초기값은 여기서 오고 시뮬레이터에서 런타임으로 바꿀 수 있다({@code VoiceDirState}). 앱 기동과
     * 시뮬레이션 데이터 생성/초기화 때 없는 폴더는 만든다(CREATE_IF_NOT_EXISTS).</p>
     */
    public record Dirs(
            /** ROOT_DIR. 비우면 OS 로 정한다({@code DeployEnvPreset}). */
            @DefaultValue("") String baseDir,
            @DefaultValue("") String receiveMeet,
            @DefaultValue("") String receivePhone,
            @DefaultValue("") String work,
            @DefaultValue("") String outputMeet,
            @DefaultValue("") String outputPhone,
            /** XVARM 접견 원본 음성 스토리지 — 시뮬레이션 데이터 생성이 DB 파일명과 1:1 인 경량 더미 파일을 여기에 쓴다. */
            @DefaultValue("") String xvarmOriginal
    ) {}

    /** 시뮬레이션 데이터(개발계 DB 메타 + 물리 더미 파일) 생성 정책. */
    public record Sim(
            /** 기동 시 자동 생성(Clean &amp; Seed). 로컬(H2)은 켜고, 개발계는 버튼으로만 한다. */
            @DefaultValue("false") boolean seedOnStartup
    ) {}

    /** 복호화. 전화 건의 주체가 아직 확정되지 않아 기본은 SKIP 이다(계획서 11장 Q13). */
    public record Decrypt(
            @DefaultValue("SKIP") DecryptMode mode,
            /** 접견 파일 복호화 키 파일({@code rvs_key.txt}) 경로. 아직 미수령(Q8). */
            @DefaultValue("") String rvsKeyPath
    ) {}

    /** STT(NPU) 연동. */
    public record Stt(
            @DefaultValue("MOCK") SttMode mode,
            @DefaultValue("") String baseUrl,
            @DefaultValue("300") int timeoutSec
    ) {}

    /** 배치 스케줄·대상 조건. */
    public record Batch(
            /** 일배치(정기배치) — 전날 00시부터 오늘 00시까지. 기본 새벽 2시. */
            @DefaultValue("0 0 2 * * *") String dailyCron,
            /** 주기배치 — 10분 단위. */
            @DefaultValue("0 */10 * * * *") String periodicCron,
            /** 주기배치가 훑는 지연 폭(분). 회의 합의는 "20분 전 것까지". */
            @DefaultValue("20") int periodicLagMin,
            /** 스케줄 자동 기동 여부. 개발·시연 중에는 수동 호출만 쓰도록 끌 수 있다. */
            @DefaultValue("false") boolean scheduleEnabled,
            /** 특별관리구분코드 — 조직(0)·마약(1)·관심(2)·엄격(3)·일일중점(5) */
            @DefaultValue({"0", "1", "2", "3", "5"}) List<String> speclMngSeCd,
            /** 한 번에 처리할 파일 상한(안전장치). */
            @DefaultValue("500") int maxFilesPerRun,
            /** 로그 컬렉터에 배치를 열 때 쓸 JOB_ID. 작업코드 VOC 로 채번되도록 협의 필요(Q4). */
            /**
             * 로그 컬렉터에 보낼 JOB_ID. 컬렉터가 이 값으로 EXEC_ID 접두 3자를 정한다
             * ({@code JobId} enum). {@code VOICE_ANALYSIS} → {@code VOC} — 계획서가 음성에 예약한 코드다.
             *
             * <p>⚠ 예전 값 {@code VOICE_BATCH} 는 컬렉터 enum 에 없어서 폴백 규칙("영문만 남겨 앞 3자")이
             * 돌았고, EXEC_ID 가 {@code ...VOI...} 로 채번됐다. 눈에 잘 안 띄는데, 작업코드로 배치를
             * 구분하는 모든 조회·정합성 대사가 어긋난다.</p>
             */
            @DefaultValue("VOICE_ANALYSIS") String jobId,
            /**
             * 시뮬레이터에서 돌리는 배치의 JOB_ID. {@code TEST_BATCH} → EXEC_ID 접두 {@code TST}.
             *
             * <p>컬렉터의 테스트 데이터 삭제({@code DELETE /api/v1/logs/test-data})가 이 JOB_ID 를
             * 기준으로 T1~T8 을 연쇄 삭제한다. 운영 배치(STR/VOC/EXT)는 그 조건에 걸리지 않으므로
             * 시연·시험 기록만 안전하게 지울 수 있다.</p>
             */
            @DefaultValue("TEST_BATCH") String testJobId,
            /**
             * 로그 컬렉터 T1 의 {@code DATA_TYPE_CD}(공통코드 C01, 4종 고정) — 음성은 <b>{@code UNSTRUCTURED}(비정형)</b> 다.
             *
             * <p>⚠ 예전 값 {@code VOICE} 는 C01 에 없는 코드였다. 컬렉터가 거절하지는 않았지만
             * 대시보드 4종 필터에 잡히지 않았고, T2 순번을 정하는 유형별 체인
             * (비정형: COLLECT → ANALYZE → DEIDENT → SEND)도 타지 못해 단계가 체인 밖 순번(11~)으로 밀렸다.</p>
             */
            @DefaultValue("UNSTRUCTURED") String dataTypeCd,
            /** STT 후 복호화 원본을 남길지. 기본은 삭제 — PII 보유를 최소화한다. */
            @DefaultValue("false") boolean retainSourceFile
    ) {}

    public enum SourceMode { MOCK, DIRECT_JDBC, ESB_HTTP2DB }

    /** XVARM 연동 — MOCK_DEV(개발계 DB 에 우리가 만든 테이블) / REAL(실 XVARM DB·공통파일 테이블). */
    public enum XvarmMode { MOCK_DEV, REAL }

    public enum BrokerMode { MOCK, REST }

    /** 전화 파일 연계 — MOCK(내부 더미 생성) / ESB(연계 프로바이더가 떨궈 주기를 대기). */
    public enum PhoneMode { MOCK, ESB }

    public enum DecryptMode { SKIP, REAL }

    public enum SttMode { MOCK, NPU }
}
