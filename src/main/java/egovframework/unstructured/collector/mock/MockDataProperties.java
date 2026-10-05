package egovframework.unstructured.collector.mock;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 더미 데이터 설정 — {@code unstructured.mock.*}.
 *
 * <pre>
 * unstructured:
 *   mock:
 *     dashboard:
 *       enabled: true          # 대시보드 더미(DMY) 생성 허용 — dev/local 기본 true, 운영 프로필은 강제 false(빈도 없다)
 *     daily:
 *       enabled: false         # 매일 어제 날짜로 대시보드 더미를 추가(append) — 기본 끔. 처음 값일 뿐 — 시뮬레이터에서 실행 중에 켜고 끈다
 *       cron: "0 30 1 * * *"   # 일배치(관리 화면 02:00) 전
 *       types: [PHONE, MEET, IMAGE]   # 운영 전환 전까지 이미지 더미도 늘 같이 만든다
 *       count: 5               # 유형별 건수
 *       collect-fail: 0
 *       analyze-fail: 0
 *       send-fail: 0
 *       retain-days: 7         # 이보다 오래된 DMY 행 · 파일을 지운다(catch-up 없음)
 * </pre>
 *
 * <p>생성 API · 스위치 · 스케줄러는 dev/local 프로필에서만 빈이 생긴다 — 운영은 이 설정이 있어도 아무것도 만들지 않는다.</p>
 */
@ConfigurationProperties(prefix = "unstructured.mock")
public record MockDataProperties(
        @DefaultValue Dashboard dashboard,
        @DefaultValue Daily daily) {

    /** @param enabled 대시보드 더미(DMY) 생성 허용. 끄면 생성 API 가 DASHBOARD 를 거절한다(초기화는 된다) */
    public record Dashboard(@DefaultValue("false") boolean enabled) {}

    /**
     * 일 단위 자동 생성.
     *
     * @param enabled     켜면 cron 마다 어제 날짜로 대시보드 더미를 추가한다 — 기동 시 처음 값({@link MockDataScheduler#setEnabled} 로 바뀐다)
     * @param cron        스프링 cron(초 분 시 일 월 요일) — 한국 시각
     * @param types       유형
     * @param count       유형별 건수
     * @param collectFail 유형별 COLLECT_FAIL 건수
     * @param analyzeFail 유형별 ANALYZE_FAIL 건수
     * @param sendFail    유형별 SEND_FAIL 건수
     * @param retainDays  보존 일수 — 대상일이 이보다 오래된 DMY 행 · 파일을 지운다. 0 이하면 지우지 않는다
     */
    public record Daily(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("0 30 1 * * *") String cron,
            @DefaultValue({"PHONE", "MEET", "IMAGE"}) List<DummyDataType> types,
            @DefaultValue("5") int count,
            @DefaultValue("0") int collectFail,
            @DefaultValue("0") int analyzeFail,
            @DefaultValue("0") int sendFail,
            @DefaultValue("7") int retainDays) {}
}
