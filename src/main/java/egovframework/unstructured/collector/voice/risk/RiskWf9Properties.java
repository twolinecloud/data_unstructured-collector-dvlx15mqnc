package egovframework.unstructured.collector.voice.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 접견·전화 위험 신호(제논 WF9) — 1116 통합설계 과제 3.
 *
 * <p>배치는 복호화 직후 음성 사본을 스풀에 넣기만 하고 다음 단계로 간다. 작업자 하나가 음성 배치가 돌지 않을 때
 * 스풀을 비운다: 제논 {@code audio/process} → 커넥터 {@code POST /api/v1/deid/speech-results}(비식별 · 잔존 검사)
 * → 제논 WF9(Code Serving 직접 호출 · SSE) → {@code {findings, reviews}} → 로그 컬렉터
 * {@code PUT /api/v1/logs/contact-risk-signals/{recFileId}}. T1~T5 는 건드리지 않는다(D7) — 단 하나, 제논 실호출(REST)이 정상 응답하면
 * 그 배치 T1 의 폐기 상태({@code PURGE_STS_CD} · {@code PURGE_DTM})만 폐기 완료로 적는다. 제논 엔드포인트가 비저장이라 폐기 신호가
 * 오지 않기 때문이다(PL 합의 2026-11-20 · 11-23 제거 뒤 11-26 복원).</p>
 *
 * <p><b>2026-11-25 호출 방식 전환</b>(대시보드 AI API 연동 가이드 v4 · 제논 메일 11-25) — genportal 로그인({@code /auth/login})
 * → {@code /chat} 을 버리고 서빙별 AuthKey 로 {@code POST {servingBaseUrl}/{servingId}/{servingRev}/run} 을 바로 부른다.
 * 요청 본문({@code {endpoint, message, file_ids: []}})과 SSE 응답 처리는 그대로다.</p>
 *
 * <p><b>AuthKey 는 레포에 적지 않는다</b> — 배포 Secret → 환경 변수 {@code GENON_WF9_AUTH_KEY} 로만 받는다.</p>
 *
 * <p><b>2026-11-27 텍스트 전용</b> — 제논 음성 전처리({@code audio/process})와 커넥터 {@code speech-results} 토큰화를 쓰지 않는다.
 * 본 배치가 비식별(마스킹)한 전사로 WF9 를 바로 부른다({@link Wf9TextDocument}). 그래서 {@code deidPath} · {@code deidTimeoutSec} 는
 * 더 이상 쓰지 않는다(배포 값 · 환경 변수가 남아 있어도 기동이 깨지지 않게 자리만 둔다).</p>
 *
 * @param enabled             켜기(기본 false — 개발계 외부망 · Secret 결정 전까지 끈 채로 배포)
 * @param spoolDir            스풀 위치. 비우면 복호화 작업 폴더 옆 {@code {ROOT}/xvram/wf9-spool}(같은 PVC · 권한)
 * @param skipTestRuns        시험 실행(TST · 시뮬레이터) 건은 넣지 않는다 — 가상 건이 대시보드 표에 섞이지 않게
 * @param maxPending          스풀에 쌓아 둘 작업 수 상한 — 넘으면 새 건을 넣지 않는다(경고)
 * @param maxSpoolMb          스풀 디스크 상한(MB) — 배치와 같은 PVC 라 가득 채우면 배치가 멈춘다
 * @param analyzeMaxSec       앞에서부터 분석할 길이(초 · D9 · 기본 1800 = 운영 상한 30분). 이 시각 뒤에 시작하는 전사 구간은 문서에 싣지 않는다.
 *                            0 이하면 다 싣는다(2026-11-27 — 음성을 자르던 것을 구간 기준으로)
 * @param maxRetries          다시 해 볼 횟수(첫 시도 제외) — 다 쓰면 포기하고 사본을 지운다
 * @param retryBaseSec        다시 하기 전 기다림의 첫 간격(초) — 실패할 때마다 두 배
 * @param maxAgeHours         스풀에 넣은 뒤 이 시간이 지나면 포기하고 사본을 지운다
 * @param dailyLimit          하루(KST) 처리 상한 — 0 이면 없음
 * @param pollSec             작업자가 스풀을 살피는 간격(초) — 작업자 전용 스레드(공용 스케줄러를 붙잡지 않는다)
 * @param initialDelaySec     기동 뒤 첫 순회까지(초)
 * @param deidPath            (2026-11-27 부터 쓰지 않음) 예전 커넥터 speech-results 비식별 경로
 * @param deidTimeoutSec      (2026-11-27 부터 쓰지 않음) 예전 커넥터 speech-results 응답 상한
 * @param portalMode          MOCK(네트워크 없음 · 가이드 예시 빈 결과) · REST(Code Serving 실호출)
 * @param servingBaseUrl      Code Serving 게이트웨이 주소 — 뒤에 {@code /{servingId}/{servingRev}/run} 이 붙는다
 * @param servingId           접견·전화 분석 서빙 ID(가이드 v4 1쪽 표 · 숫자만)
 * @param servingRev          그 서빙의 리비전(숫자만) — 제논이 리비전을 올리면 이 값만 바꾼다
 * @param authKey             서빙별 AuthKey — 환경 변수로만({@code Authorization: Bearer …})
 * @param authKeyExpiresOn    AuthKey 만료일(yyyy-MM-dd · KST · 가이드 v4 1쪽 "2026-12-11 23:59:59 KST 까지 유효") — 기동 로그에 남기고
 *                            14일 안이거나 지났으면 경고한다(값이 아니므로 비밀 아님). 비우면 보지 않는다
 * @param portalEndpoint      요청 본문의 {@code endpoint}(WF9)
 * @param portalConnectTimeoutSec 연결 상한
 * @param portalTimeoutSec    WF9 응답(SSE end 까지 전체 · 감시 스레드) 상한 300초 — 실측 3건 검출 10.8초 · 20분 전처리 결과 2.5초
 * @param contentFields       항목에서 화면 '내용'(SIGNAL_CN)으로 쓸 필드 — 앞에 둔 것부터. <b>첫 이름이 항목 규격</b>({@code content} —
 *                            2026-11-20 실응답과 대시보드 API 필드 이름이 같다)이고, 나머지는 응답이 바뀔 때 받아 주는 후보다.
 *                            첫 이름이 없는 항목은 '규격 밖'으로 경고한다(필드 이름만)
 * @param riskClassFields     항목에서 위험 분류(RISK_CLASS_CD)로 쓸 필드 — 앞에 둔 것부터. 첫 이름 {@code risk_type} 이 2026-11-20 실응답
 *                            (한글 이름 · 예 "자살" · "부정물품 수수"). 값은 그대로 넘기고 코드표(대시보드 위험 유형 9종 + SUICIDE_SELF_HARM + ETC)로는
 *                            로그 컬렉터가 맞춘다. 실응답의 {@code type} 은 접촉 구분("접견" · "전화")이라 후보에 넣지 않는다
 */
@ConfigurationProperties(prefix = "voice.risk-wf9")
public record RiskWf9Properties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String spoolDir,
        @DefaultValue("true") boolean skipTestRuns,
        @DefaultValue("300") int maxPending,
        @DefaultValue("4096") long maxSpoolMb,
        @DefaultValue("1800") int analyzeMaxSec,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("300") int retryBaseSec,
        @DefaultValue("48") int maxAgeHours,
        @DefaultValue("0") int dailyLimit,
        @DefaultValue("30") int pollSec,
        @DefaultValue("60") int initialDelaySec,
        @DefaultValue("/api/v1/deid/speech-results") String deidPath,
        @DefaultValue("660") int deidTimeoutSec,
        @DefaultValue("MOCK") PortalMode portalMode,
        @DefaultValue("https://genos.genon.ai/api/gateway/code_serving") String servingBaseUrl,
        @DefaultValue("709") String servingId,
        @DefaultValue("10721") String servingRev,
        @DefaultValue("") String authKey,
        @DefaultValue("2026-12-11") String authKeyExpiresOn,
        @DefaultValue("pt6i_10rw_jsd9") String portalEndpoint,
        @DefaultValue("10") int portalConnectTimeoutSec,
        @DefaultValue("300") int portalTimeoutSec,
        // 목록 기본값은 배열로 쓴다 — 쉼표 문자열 하나("a,b")로 쓰면 쪼개지지 않고 원소 하나짜리 목록이 된다(2026-11-20 바로잡음 ·
        //   운영은 application.yml 이 같은 값을 문자열로 넘겨 쪼개졌기 때문에 드러나지 않았다)
        @DefaultValue({"content", "주요사항", "내용", "description", "summary", "text", "detail", "reason", "evidence", "finding",
                "review", "message"})
        List<String> contentFields,
        @DefaultValue({"risk_type", "riskClassCode", "risk_class_code", "riskClassCd", "risk_class_cd", "위험분류", "위험유형", "riskType",
                "riskClass", "risk_class", "category", "code"})
        List<String> riskClassFields
) {

    /** MOCK — 네트워크 없이 가이드의 최종 응답 예시({@code {"findings":[],"reviews":[]}})를 돌려준다 · REST — 실제로 부른다. */
    public enum PortalMode { MOCK, REST }

    /** 서빙 ID · 리비전 — 숫자만(경로에 그대로 붙으므로 {@code /} · {@code ..} 따위가 끼지 않게). */
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,12}");

    public RiskWf9Properties {
        spoolDir = spoolDir == null ? "" : spoolDir.strip();
        portalMode = portalMode == null ? PortalMode.MOCK : portalMode;
        servingBaseUrl = servingBaseUrl == null ? "" : servingBaseUrl.strip().replaceAll("/+$", "");
        servingId = servingId == null ? "" : servingId.strip();
        servingRev = servingRev == null ? "" : servingRev.strip();
        authKey = authKey == null ? "" : authKey.strip();
        authKeyExpiresOn = authKeyExpiresOn == null ? "" : authKeyExpiresOn.strip();
        portalEndpoint = portalEndpoint == null ? "" : portalEndpoint.strip();
        contentFields = clean(contentFields);
        riskClassFields = clean(riskClassFields);
    }

    /** AuthKey 가 채워졌는가 — 값은 돌려주지 않는다. */
    public boolean hasAuthKey() {
        return !authKey.isEmpty();
    }

    /**
     * AuthKey 만료까지 남은 날(만료일 당일 0 · 지났으면 음수). 만료일이 비었거나 날짜가 아니면 null.
     *
     * @param today KST 오늘
     */
    public Long authKeyDaysLeft(java.time.LocalDate today) {
        if (authKeyExpiresOn.isEmpty()) {
            return null;
        }
        try {
            return java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.parse(authKeyExpiresOn));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** 서빙 ID · 리비전이 숫자인가. */
    public boolean servingPathValid() {
        return DIGITS.matcher(servingId).matches() && DIGITS.matcher(servingRev).matches();
    }

    /** 부를 주소 — {@code {servingBaseUrl}/{servingId}/{servingRev}/run}. 키가 들어가지 않으므로 로그에 남겨도 된다. */
    public String servingRunUrl() {
        return servingBaseUrl + "/" + servingId + "/" + servingRev + "/run";
    }

    /** AuthKey 를 싣지 않는다(설정을 통째로 찍는 로그에 새지 않게). */
    @Override
    public String toString() {
        return "RiskWf9Properties[enabled=" + enabled + ", spoolDir=" + spoolDir + ", portalMode=" + portalMode
                + ", serving=" + servingRunUrl() + ", portalEndpoint=" + portalEndpoint
                + ", authKey=" + (hasAuthKey() ? "있음" : "없음") + "]";
    }

    private static List<String> clean(List<String> in) {
        return in == null ? List.of() : in.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList();
    }
}
