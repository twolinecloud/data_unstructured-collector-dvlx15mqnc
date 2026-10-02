package egovframework.unstructured.collector.common.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 로그 컬렉터 REST 클라이언트 — 수집 이력을 <b>API 로</b> 적재한다.
 *
 * <p><b>R&amp;R</b>: 로그 테이블(kcais T1~T11)의 단일 writer 는 <b>로그 컬렉터</b>다
 * (확정 2026-08-19). 커넥터도 DB 직접 INSERT 를 걷어내고 이 방식으로 바꿨다.
 * 수집 서비스도 자체 로그 테이블을 만들지 않는다.</p>
 *
 * <p><b>이 서비스가 쓰는 경로</b> (base-url 은 context-path {@code /logc} 까지 포함해 주입)</p>
 * <ul>
 *   <li>T1 생성: {@code POST {base}/api/v1/logs/batches} → <b>EXEC_ID 채번</b></li>
 *   <li>T1 종료: {@code PATCH {base}/api/v1/logs/batches/{execId}}</li>
 *   <li>T2 생성: {@code POST {base}/api/v1/logs/batches/{execId}/steps}</li>
 *   <li>T2 종료: {@code PATCH {base}/api/v1/logs/steps/{stepLogId}}</li>
 *   <li><b>T4 적재</b>: {@code POST {base}/api/v1/logs/batches/{execId}/file-procs}</li>
 * </ul>
 *
 * <p><b>정합성</b>: {@code TB_BATCH_EXEC_LOG.SUCCESS_CNT == Σ(T3·T4·T5)} 규칙이 있어
 * <b>파일 1건 = T4 1행</b>을 어기면 배치 전체의 대사가 깨진다.</p>
 *
 * <p><b>방어적 기본값</b>: {@code enabled=false} 이거나 base-url 이 비면 호출 자체를 하지 않는다.
 * 호출이 실패해도 예외를 밖으로 던지지 않는다 — 이력 적재 실패가 수집을 멈추게 하지 않는다.</p>
 *
 * <p><b>왜 전용 RestTemplate 인가</b>: 종료 갱신이 전부 <b>PATCH</b> 인데 기본
 * {@code SimpleClientHttpRequestFactory}(=HttpURLConnection)는 PATCH 를 지원하지 않는다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class LogCollectorClient {

    private final ObjectMapper objectMapper;
    private final egovframework.unstructured.collector.common.config.DeployEnvPreset env;

    private RestTemplate restTemplate;

    @Value("${log-collector.connect-timeout-sec:5}")
    private int connectTimeoutSec;

    @Value("${log-collector.read-timeout-sec:30}")
    private int readTimeoutSec;

    /** context-path({@code /logc})까지 포함해서 주입한다. 예) {@code http://log-collector-a2z96kgyrm:8080/logc} */
    @Value("${log-collector.base-url:}")
    private String baseUrl;

    @Value("${log-collector.enabled:false}")
    private boolean enabled;

    @Value("${log-collector.auth-token:}")
    private String authToken;

    @PostConstruct
    void init() {
        // 설정(log-collector.base-url / LOG_COLLECTOR_BASE_URL)이 비면 환경 프리셋 — 로컬 8090 · K8s 컬렉터 서비스명
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = env.logCollectorBaseUrl();
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSec)).build());
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSec));
        RestTemplate rt = new RestTemplate(factory);
        rt.getMessageConverters().removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
        rt.getMessageConverters().add(new MappingJackson2HttpMessageConverter(objectMapper));
        this.restTemplate = rt;
        if (isEnabled()) {
            log.info("[LogCollector] 이력 적재 활성 — base={}", baseUrl);
        } else {
            log.info("[LogCollector] 이력 적재 비활성(enabled={}, base-url={}) — 콘솔 이력만 남긴다",
                    enabled, StringUtils.hasText(baseUrl) ? baseUrl : "(미설정)");
        }
    }

    public boolean isEnabled() {
        return enabled && StringUtils.hasText(baseUrl);
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ── T1 배치 ────────────────────────────────────────────────────────────

    /**
     * T1 배치를 생성하고 <b>컬렉터가 채번한 EXEC_ID</b> 를 돌려준다(중앙 채번).
     *
     * <p>채번 규칙은 컬렉터가 소유한다 — {@code 실행일자(8) + 작업코드(3) + 회차(3)}.
     * 음성은 작업코드 {@code VOC} 로 떨어져야 한다(예: {@code 20260915VOC001}).
     * {@code jobId} 가 어떤 값일 때 {@code VOC} 가 되는지는 컬렉터 규칙이라 확인이 필요하다(계획서 Q4).</p>
     *
     * @return 채번된 execId. 미연동·실패 시 null
     */
    public String createBatch(String jobId, String dataTypeCd, String execTypeCd, String triggerBy) {
        return createBatch(jobId, dataTypeCd, execTypeCd, triggerBy, null, null);
    }

    /**
     * T1 을 연다 — <b>훑을 구간</b>({@code target_from_dtm}·{@code target_to_dtm})까지 남긴다.
     *
     * <p>구간의 끝이 [바로 실행] 워터마크의 재료다({@link #watermark}). 예전에는 둘 다 null 로 보내
     * T1 의 구간 칸이 비어 있었고, 그래서 "어디까지 수집했나" 를 파일에 따로 적어야 했다.</p>
     */
    public String createBatch(String jobId, String dataTypeCd, String execTypeCd, String triggerBy,
                              LocalDateTime targetFrom, LocalDateTime targetTo) {
        return createBatch(jobId, null, dataTypeCd, execTypeCd, triggerBy, targetFrom, targetTo);
    }

    /**
     * 위와 같되 작업명({@code JOB_NM})을 준다 — 컬렉터 {@code JobId} 열거형에 없는 작업(예: 수용자 이미지 {@code IMAGE_COLLECT})은
     * 이름을 주지 않으면 작업 ID 문자열이 그대로 이름으로 적힌다. 채번 접두사는 작업이 아니라 데이터 구분(C01)으로 정해진다
     * (UNSTRUCTURED → VOC, 작업이 TEST_BATCH 면 TST).
     */
    public String createBatch(String jobId, String jobNm, String dataTypeCd, String execTypeCd, String triggerBy,
                              LocalDateTime targetFrom, LocalDateTime targetTo) {
        if (!isEnabled()) {
            return null;
        }
        JsonNode result = exchange(HttpMethod.POST, url("/api/v1/logs/batches"),
                new BatchCreateReq(jobId, jobNm, dataTypeCd,
                        targetFrom == null ? null : targetFrom.withNano(0),
                        targetTo == null ? null : targetTo.withNano(0),
                        execTypeCd, LocalDateTime.now().withNano(0), triggerBy));
        return (result == null || result.path("execId").isMissingNode())
                ? null : result.path("execId").asText(null);
    }

    /**
     * T1 배치를 종료 상태로 갱신한다(C04: SUCCESS/FAIL/PARTIAL 등).
     *
     * @param errMsg 대표 오류 한 줄 — 컬렉터 표준 {@code [코드] 상세}({@link FileProcReq#errStackOf}).
     *               앞의 코드(C12: CONNECTION/TIMEOUT/DATA/SYSTEM/RECONCILE)를 {@code ERR_TYPE_CD} 로 쓴다.
     *               성공이면 null
     */
    public void finishBatch(String execId, String execStsCd, Integer elapsedSec,
                            Long targetCnt, Long successCnt, Long failCnt, String errMsg) {
        if (!isEnabled() || !StringUtils.hasText(execId)) {
            return;
        }
        exchange(HttpMethod.PATCH, url("/api/v1/logs/batches/" + execId),
                new BatchFinishReq(execStsCd, LocalDateTime.now().withNano(0), elapsedSec,
                        targetCnt, successCnt, failCnt,
                        errMsg == null ? null : errTypeOf(errMsg), null, errMsg, errMsg));
    }

    /** {@code [CODE] 상세} 의 CODE 가 C12 값이면 그것, 아니면 DATA. */
    static String errTypeOf(String errStack) {
        if (errStack == null) {
            return null;
        }
        String s = errStack.strip();
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end > 1) {
                String code = s.substring(1, end).trim().toUpperCase();
                if (List.of("CONNECTION", "TIMEOUT", "DATA", "SYSTEM", "RECONCILE").contains(code)) {
                    return code;
                }
            }
        }
        return "DATA";
    }

    // ── T2 단계 ────────────────────────────────────────────────────────────

    /**
     * T2 단계를 생성한다(상태 RUNNING) — {@code POST /api/v1/logs/batches/{execId}/steps}.
     *
     * <p>순번({@code stepSeq})은 참고값이다. 컬렉터가 유형별 체인(비정형: COLLECT 1 · ANALYZE 2 · SEND 3 — 2026-10-01 3단계 복원)
     * 위치로 다시 정하고, 같은 단계를 다시 부르면 새 행을 만들지 않고 <b>기존 행의 ID</b> 를 돌려준다.
     * 그래서 이 서비스는 배치당 COLLECT·ANALYZE·SEND 를 각각 한 번씩만 열고 마감한다.</p>
     *
     * @param stepTypeCd 공통코드 C05 — 이 서비스는 COLLECT / ANALYZE / SEND 만 쓴다
     * @return 채번된 STEP_LOG_ID. 미연동·실패 시 null
     */
    public String createStep(String execId, Short stepSeq, String stepTypeCd) {
        if (!isEnabled() || !StringUtils.hasText(execId)) {
            return null;
        }
        JsonNode result = exchange(HttpMethod.POST, url("/api/v1/logs/batches/" + execId + "/steps"),
                new StepCreateReq(stepSeq, stepTypeCd, LocalDateTime.now().withNano(0)));
        return (result == null || result.path("stepLogId").isMissingNode())
                ? null : result.path("stepLogId").asText(null);
    }

    public void finishStep(String stepLogId, String stepStsCd, Integer elapsedSec,
                           Long inCnt, Long outCnt, Long errCnt, String errStack) {
        if (!isEnabled() || !StringUtils.hasText(stepLogId)) {
            return;
        }
        exchange(HttpMethod.PATCH, url("/api/v1/logs/steps/" + stepLogId),
                new StepFinishReq(stepStsCd, LocalDateTime.now().withNano(0), elapsedSec,
                        inCnt, outCnt, errCnt, errStack));
    }

    // ── T4 음성 파일 처리 이력 ──────────────────────────────────────────────

    /**
     * T4({@code TB_FILE_PROC_LOG}) 를 <b>bulk 적재</b>한다 — 파일 1건 = 1행.
     *
     * <p>요청 스키마는 컬렉터 {@code docs/API_SPEC.md} §T4 ⑥ 을 따른다(계획서 Q4 해소, 2026-09-15):
     * {@code REC_FILE_ID · FILE_PATH · FILE_NM · INMATE_PID · FILE_SIZE · PROC_STS_CD(C04) · ERR_STACK?}.
     * 예전에 추정으로 보내던 {@code fileTypeCd}·{@code sttCharCnt}·{@code errTypeCd} 는 컬렉터가
     * 무시했고, 정작 필수인 {@code recFileId} 가 빠져 NOT NULL 위반으로 한 건도 적재되지 않았다.</p>
     *
     * @return 채번된 FILE_PROC_ID 목록({@code yyyyMMddTSTnnnNNN} 꼴 문자열). 미적재·실패 시 빈 목록
     */
    public List<String> createFileProcs(String execId, List<FileProcReq> rows) {
        if (!isEnabled() || rows == null || rows.isEmpty()) {
            return List.of();
        }
        JsonNode result = exchange(HttpMethod.POST,
                url("/api/v1/logs/batches/" + execId + "/file-procs"), rows);
        List<String> ids = new ArrayList<>();
        if (result != null && result.path("ids").isArray()) {
            result.path("ids").forEach(n -> ids.add(n.asText()));
        }
        return ids;
    }

    /** 배치 상세(T1 + 단계 + 하위 집계) 조회 — 멱등 판정·정합성 대사의 근거. */
    public JsonNode getBatchDetail(String execId) {
        if (!isEnabled() || !StringUtils.hasText(execId)) {
            return null;
        }
        return exchange(HttpMethod.GET, url("/api/v1/logs/batches/" + execId), null);
    }

    /**
     * 컬렉터의 <b>테스트 데이터 연쇄 삭제</b>를 호출한다 — {@code DELETE /api/v1/logs/test-data}.
     *
     * <p>컬렉터가 {@code JOB_ID='TEST_BATCH'} 인 배치와 그 하위(T2~T8)를 FK 안전 순서로 지운다.
     * 그 조건에 걸리는 EXEC_ID 는 작업코드 자리가 {@code TST} 인 것뿐이라,
     * 운영 배치({@code STR}/{@code VOC}/{@code EXT})는 어떤 경우에도 지워지지 않는다.</p>
     *
     * @return 테이블별 삭제 건수. 컬렉터 미연동이거나 실패하면 {@code null}
     */
    /**
     * 배치 상세 — T1 + T2 + 하위 집계(T4·T5 건수·상태별). 검증 패널이 쓴다.
     * 컬렉터가 꺼져 있거나 호출이 실패하면 null.
     */
    public JsonNode batchDetail(String execId) {
        if (!isEnabled() || execId == null || execId.isBlank()) {
            return null;
        }
        return exchange(HttpMethod.GET, url("/api/v1/logs/batches/" + execId.trim()), null);
    }

    /**
     * [바로 실행] 워터마크 — 이 작업의 <b>SUCCESS 배치가 훑은 구간의 끝 중 가장 늦은 것</b>.
     *
     * <p>{@code GET /api/v1/logs/batches/watermark}. T1 이 원본이다. 컬렉터가 꺼져 있거나 호출이 실패하거나 아직 성공한
     * 배치가 없으면 null — 호출 측이 기본 창(최근 30일)으로 되짚는다.</p>
     *
     * @param jobId 운영({@code VOICE_ANALYSIS})과 시험({@code TEST_BATCH})을 가른다 — 시뮬레이터가
     *              운영 기준점을 밀면 안 된다
     */
    public Watermark watermark(String dataTypeCd, String jobId) {
        if (!isEnabled()) {
            return null;
        }
        String q = "/api/v1/logs/batches/watermark?dataTypeCd=" + dataTypeCd
                + (jobId == null || jobId.isBlank() ? "" : "&jobId=" + jobId);
        JsonNode r = exchange(HttpMethod.GET, url(q), null);
        if (r == null || r.path("watermark").isNull() || r.path("watermark").isMissingNode()) {
            return null;
        }
        try {
            return new Watermark(parseDtm(r.path("watermark").asText()), r.path("execId").asText(null));
        } catch (RuntimeException e) {
            log.warn("[LogCollector] 워터마크 형식을 읽지 못했다 — {} ({})", r.path("watermark"), e.getMessage());
            return null;
        }
    }

    /**
     * 컬렉터가 돌려주는 시각을 이 서버의 벽시계로 읽는다.
     *
     * <p>컬렉터는 시간대 없는 문자열({@code 2026-09-27T00:00:00})을 준다. 그래도 형식이 바뀔 수 있으니 셋을 다
     * 받는다 — 시간대가 붙었으면({@code +00:00}·{@code Z}) 이 서버 시간대로 옮기고, 에폭 밀리초면 그대로 환산한다.
     * <b>시간대 붙은 값을 앞부분만 잘라 읽으면 9시간이 어긋난다</b> — 기준점이 9시간 늦으면 그 사이 건이 빠진다.</p>
     */
    static LocalDateTime parseDtm(String v) {
        java.time.ZoneId here = java.time.ZoneId.systemDefault();
        if (v.matches("\\d{10,}")) {
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(Long.parseLong(v)), here);
        }
        String s = v.trim().replace(' ', 'T');
        if (s.endsWith("Z") || s.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            return java.time.OffsetDateTime.parse(s.matches(".*[+-]\\d{4}$")
                            ? s.substring(0, s.length() - 2) + ":" + s.substring(s.length() - 2) : s)
                    .atZoneSameInstant(here).toLocalDateTime().withNano(0);
        }
        return LocalDateTime.parse(s).withNano(0);
    }

    /** 워터마크 한 건 — 그 시각과, 그 시각을 남긴 배치. */
    public record Watermark(LocalDateTime at, String execId) {}

    public JsonNode deleteTestData() {
        if (!isEnabled()) {
            return null;
        }
        return exchange(HttpMethod.DELETE, url("/api/v1/logs/test-data"), null);
    }

    // ── 내부 ───────────────────────────────────────────────────────────────

    private String url(String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + path;
    }

    /**
     * 컬렉터 호출 공통부. 응답 봉투 {@code {success, code, result}} 에서 {@code result} 만 반환한다.
     * 실패 시 예외를 던지지 않고 경고 후 null 을 돌려준다 — 이력 적재가 수집을 막지 않는다.
     */
    private JsonNode exchange(HttpMethod method, String url, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            if (StringUtils.hasText(authToken)) {
                headers.setBearerAuth(authToken);
            }
            ResponseEntity<JsonNode> res = restTemplate.exchange(
                    java.net.URI.create(url), method, new HttpEntity<>(body, headers), JsonNode.class);
            JsonNode env = res.getBody();
            if (env == null) {
                log.warn("[LogCollector] 빈 응답: {} {}", method, url);
                return null;
            }
            if (env.hasNonNull("success") && !env.path("success").asBoolean()) {
                log.warn("[LogCollector] 실패 응답: {} {} — {}", method, url,
                        env.path("error_message").asText(""));
                return null;
            }
            return env.path("result");
        } catch (Exception e) {
            log.warn("[LogCollector] 호출 실패(수집은 계속 진행): {} {} — {}", method, url, e.getMessage());
            return null;
        }
    }

    // ── 요청 DTO ───────────────────────────────────────────────────────────

    public record BatchCreateReq(String jobId, String jobNm, String dataTypeCd,
                                 LocalDateTime targetFromDtm, LocalDateTime targetToDtm,
                                 String execTypeCd, LocalDateTime startDtm, String triggerBy) {}

    public record BatchFinishReq(String execStsCd, LocalDateTime endDtm, Integer elapsedSec,
                                 Long targetCnt, Long successCnt, Long failCnt,
                                 String errTypeCd, String errCd, String errMsg, String errStack) {}

    public record StepCreateReq(Short stepSeq, String stepTypeCd, LocalDateTime startDtm) {}

    public record StepFinishReq(String stepStsCd, LocalDateTime endDtm, Integer elapsedSec,
                                Long inCnt, Long outCnt, Long errCnt, String errStack) {}

    /**
     * T4 적재 요청 1건 — 컬렉터 {@code LogDto.FileProcReq} 와 필드명이 같아야 한다.
     *
     * <p>T4 는 <b>처리 상태만</b> 남기는 표다(스펙: "분석 결과는 본 테이블 미저장"). STT 글자 수·트랙
     * 종류 같은 것은 컬럼이 없으므로 보내지 않는다 — 배치 결과({@code FileProcOutcome})와 로그에만 남는다.</p>
     *
     * @param recFileId 보라미 녹취 식별자 — 접견 {@code TARE_FILE_NO}, 전화 {@code VRFC_ESTL_ID}
     *                  (= 멱등 키). <b>NOT NULL</b>. 전화의 {@code TELP_RECRD_FILE_ID} 는 실DB 에서 전부
     *                  NULL 이었으므로(2026-09-12) 그것으로는 이 자리를 채울 수 없다
     * @param filePath  보라미 쪽 원본 경로({@code TARE_FLPTH_NM} / {@code TELP_RECRD_FLPTH_NM}).
     *                  우리 수신·작업 경로는 넣지 않는다 — 처리 직후 지워지는 임시 위치라 추적 가치가 없고
     *                  내부 구조만 드러난다
     * @param fileNm    원본 파일명
     * @param inmatePid 비식별 수용자ID. 교정번호를 그대로 넣지 않는다
     * @param fileSize  byte
     * @param procStsCd C04 — SUCCESS / FAIL
     * @param errStack  실패 사유, 컬렉터 표준 {@code [코드] 상세} 한 줄({@link #errStackOf}). 성공이면 null.
     *                  PII 가 섞이지 않도록 원문을 넣지 않는다. 실패 단계를 {@code [코드] [단계] 상세} 로 함께 적는다
     * @param stepTypeCd 그 파일이 끝난 단계(C05 비정형 3단계) — 실패면 실패한 단계, 성공이면 {@code SEND}.
     *                  <b>2026-10-02 로그 컬렉터 T4({@code tb_file_proc_log})에는 아직 이 컬럼이 없다</b> — 컬렉터는 모르는 필드를
     *                  버리므로 보내도 해가 없고, 컬럼이 생기면 그대로 적재된다(그 전까지 실패 단계는 {@code errStack} 의 {@code [단계]} 로 남는다)
     */
    public record FileProcReq(String recFileId, String filePath, String fileNm, String inmatePid,
                              Long fileSize, String procStsCd, String errStack, String stepTypeCd) {

        /** 단계 없이 — 종전 시그니처. */
        public FileProcReq(String recFileId, String filePath, String fileNm, String inmatePid,
                           Long fileSize, String procStsCd, String errStack) {
            this(recFileId, filePath, fileNm, inmatePid, fileSize, procStsCd, errStack, null);
        }

        /**
         * 실패 사유를 {@code [코드] [단계] 상세} 로 — T4 에 단계 컬럼이 없는 동안 실패 단계를 ERR_STACK 에서 읽을 수 있게.
         * 컬렉터의 정규화는 맨 앞 {@code [코드]} 만 떼어 쓰므로 단계 표시는 상세에 그대로 남는다.
         */
        public static String errStackOf(egovframework.unstructured.collector.common.model.StepType step, String reason) {
            String base = errStackOf(reason);
            if (base == null || step == null) {
                return base;
            }
            int end = base.indexOf("] ");
            return base.substring(0, end + 2) + "[" + step.name() + "] " + base.substring(end + 2);
        }

        /**
         * 실패 사유를 컬렉터 표준 {@code [코드] 상세} 로 만든다. 코드는 C12(ERR_TYPE_CD) 중에서 고른다 —
         * 수신 대기 타임아웃은 {@code TIMEOUT}, 브로커·STT 에 붙지 못한 것은 {@code CONNECTION},
         * 나머지(빈 STT·복호화 실패·경로 불일치 등)는 {@code DATA}. 컬렉터는 앞의 코드를 그대로 쓰고,
         * 없으면 본문에서 추론하다가 {@code [ERR]} 로 떨어뜨리므로 여기서 붙여 보내는 편이 정확하다.
         *
         * @return 사유가 없으면 null — 성공 건에 빈 문자열을 남기지 않는다
         */
        public static String errStackOf(String reason) {
            if (reason == null || reason.isBlank()) {
                return null;
            }
            String r = reason.toLowerCase();
            String code = r.contains("타임아웃") || r.contains("timeout") || r.contains("timed out") ? "TIMEOUT"
                    : r.contains("connect") || r.contains("i/o error") || r.contains("연결") ? "CONNECTION"
                    : "DATA";
            return "[" + code + "] " + reason.strip();
        }
    }
}
