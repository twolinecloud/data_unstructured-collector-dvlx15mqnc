package egovframework.unstructured.collector.voice.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 접견·전화 위험 신호(제논 WF9) — 1116 통합설계 과제 3.
 *
 * <p>배치는 복호화 직후 음성 사본을 스풀에 넣기만 하고 다음 단계로 간다. 작업자 하나가 음성 배치가 돌지 않을 때
 * 스풀을 비운다: 제논 {@code audio/process} → 커넥터 {@code POST /api/v1/deid/speech-results}(비식별 · 잔존 검사)
 * → 제논 채팅 WF9(SSE) → {@code {findings, reviews}} → 로그 컬렉터 {@code PUT /api/v1/logs/contact-risk-signals/{recFileId}}.
 * T1~T5 는 건드리지 않는다(D7) — 단 하나, 제논 실호출(REST)이 정상 응답(200 OK)하면 그 배치 T1 의 폐기 상태
 * ({@code PURGE_STS_CD} · {@code PURGE_DTM})만 폐기 완료로 적는다. 제논 엔드포인트가 비저장이라 폐기 신호가 오지 않기 때문이다(PL 합의 2026-11-20).</p>
 *
 * <p><b>계정은 레포에 적지 않는다</b> — {@code GENON_PORTAL_USER_ID} · {@code GENON_PORTAL_PASSWORD}(Secret)로만 받는다.
 * 음성 키는 기존 {@code GENON_STT_API_KEY} 를 그대로 쓴다(STT 와 같은 '동시 1건' 차례).</p>
 *
 * @param enabled             켜기(기본 false — 개발계 외부망 · Secret 결정 전까지 끈 채로 배포)
 * @param spoolDir            스풀 위치. 비우면 복호화 작업 폴더 옆 {@code {ROOT}/xvram/wf9-spool}(같은 PVC · 권한)
 * @param skipTestRuns        시험 실행(TST · 시뮬레이터) 건은 넣지 않는다 — 가상 건이 대시보드 표에 섞이지 않게
 * @param maxPending          스풀에 쌓아 둘 작업 수 상한 — 넘으면 새 건을 넣지 않는다(경고)
 * @param maxSpoolMb          스풀 디스크 상한(MB) — 배치와 같은 PVC 라 가득 채우면 배치가 멈춘다
 * @param analyzeMaxSec       앞에서부터 분석할 길이(초 · D9 · 기본 1800 = 운영 상한 30분). 더 긴 PCM WAV 는 앞부분만 스풀에 담는다.
 *                            2026-11-20 600 → 1800 — 제논 가이드 11-19판 한도(3900초) 안이고, 30분 전처리 예상 약 133초가 시한 300초 안에 든다
 * @param maxRetries          다시 해 볼 횟수(첫 시도 제외) — 다 쓰면 포기하고 사본을 지운다
 * @param retryBaseSec        다시 하기 전 기다림의 첫 간격(초) — 실패할 때마다 두 배
 * @param maxAgeHours         스풀에 넣은 뒤 이 시간이 지나면 포기하고 사본을 지운다
 * @param dailyLimit          하루(KST) 처리 상한 — 0 이면 없음
 * @param pollSec             작업자가 스풀을 살피는 간격(초) — 작업자 전용 스레드(공용 스케줄러를 붙잡지 않는다)
 * @param initialDelaySec     기동 뒤 첫 순회까지(초)
 * @param deidPath            커넥터 비식별 경로({@code agent-connector.base-url} 뒤)
 * @param deidTimeoutSec      커넥터 응답 상한 — 커넥터가 AI-R 을 최대 600초 기다리므로 그보다 길게
 * @param portalMode          MOCK(네트워크 없음 · 가이드 예시 빈 결과) · REST(실호출)
 * @param portalBaseUrl       genportal 주소 — {@code /auth/login} · {@code /chat} 이 붙는다
 * @param portalUserId        제논 공통 계정 ID — 환경변수로만
 * @param portalPassword      제논 공통 계정 비밀번호 — 환경변수로만
 * @param portalEndpoint      WF9 endpoint
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
        @DefaultValue("https://genos.genon.ai/api/genportal") String portalBaseUrl,
        @DefaultValue("") String portalUserId,
        @DefaultValue("") String portalPassword,
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

    public RiskWf9Properties {
        spoolDir = spoolDir == null ? "" : spoolDir.strip();
        portalUserId = portalUserId == null ? "" : portalUserId.strip();
        portalPassword = portalPassword == null ? "" : portalPassword;
        portalMode = portalMode == null ? PortalMode.MOCK : portalMode;
        contentFields = clean(contentFields);
        riskClassFields = clean(riskClassFields);
    }

    /** 포털 계정이 채워졌는가 — 값은 돌려주지 않는다. */
    public boolean hasPortalAccount() {
        return !portalUserId.isEmpty() && !portalPassword.isEmpty();
    }

    /** 계정 · 비밀번호를 싣지 않는다(설정을 통째로 찍는 로그에 새지 않게). */
    @Override
    public String toString() {
        return "RiskWf9Properties[enabled=" + enabled + ", spoolDir=" + spoolDir + ", portalMode=" + portalMode
                + ", portalBaseUrl=" + portalBaseUrl + ", portalEndpoint=" + portalEndpoint
                + ", portalAccount=" + (hasPortalAccount() ? "있음" : "없음") + "]";
    }

    private static List<String> clean(List<String> in) {
        return in == null ? List.of() : in.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList();
    }
}
