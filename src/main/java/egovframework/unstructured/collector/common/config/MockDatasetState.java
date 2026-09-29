package egovframework.unstructured.collector.common.config;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mock 데이터셋 규모 — 시연 기본은 <b>12건</b>, 대용량 부하 시험 때만 올린다.
 *
 * <p><b>시연 기본 배치</b> (시뮬레이션 데이터 {@code SimulationDataService} 와 같은 구성 — 접견 7 · 전화 7 = 14건)</p>
 * <pre>
 *   일배치(DAILY)      — 접견 5 · 전화 5   (어제 09:10~09:50)
 *   주기배치(PERIODIC) — 접견 2 · 전화 2   (지금-6분 / 지금-3분)
 * </pre>
 * <p>MOCK 소스는 이 두 묶음을 만들어 두고 <b>배치 시간창에 걸리는 것만</b> 돌려준다. 예전에는 창과 무관하게
 * 건수만큼 만들어 매 배치가 같은 대상을 다시 훑었고, 규모를 올려 두면 10분 주기 배치까지 수백 건을 돌았다.
 * 전체 배치가 10초 안에 끝나야 시연이 산다.</p>
 *
 * <p><b>대용량(OOM 방어) 시험</b>: {@link #set(int, int)} 로 <b>일배치용</b> 건수를 올린다. 실데이터 규모는
 * 접견 약 1,300건(6.5GB) · 전화 약 1,300건이다(2026-08-07 메타빌드 협의). 200건을 넘으면 대량 모드로 보고
 * Mock WAV 를 짧게 만든다 — 우리가 보려는 것은 건수에 비례해 메모리가 늘지 않는가이지 파일 크기가 아니다.
 * 건당 파일 안정성 검사({@code voice.sync.stable-check-ms}, local 100ms)가 그대로 곱해진다.</p>
 */
@Log4j2
@Component
public class MockDatasetState {

    /** 이 건수를 넘으면 대량 모드로 보고 파일을 짧게 만든다. */
    private static final int BULK_THRESHOLD = 200;

    public static final int DEFAULT_DAILY_MEET = 5;
    public static final int DEFAULT_DAILY_PHONE = 5;
    public static final int DEFAULT_PERIODIC_MEET = 2;
    public static final int DEFAULT_PERIODIC_PHONE = 2;
    private static final int MAX_PER_KIND = 50_000;

    /** 일배치용 — 대용량 시험 때 올리는 값. */
    private volatile int meetCount = DEFAULT_DAILY_MEET;
    private volatile int phoneCount = DEFAULT_DAILY_PHONE;
    /** 주기배치용 — 고정 2·2. 시연에서 "접견만/전화만 실행" 이 이 건을 집는다. */
    private volatile int periodicMeet = DEFAULT_PERIODIC_MEET;
    private volatile int periodicPhone = DEFAULT_PERIODIC_PHONE;

    /** 일배치용 접견 건수. */
    public int meetCount() {
        return meetCount;
    }

    /** 일배치용 전화 건수. */
    public int phoneCount() {
        return phoneCount;
    }

    public int periodicMeet() {
        return periodicMeet;
    }

    public int periodicPhone() {
        return periodicPhone;
    }

    /** 일배치 + 주기배치 전체 건수. */
    public int total() {
        return meetCount + phoneCount + periodicMeet + periodicPhone;
    }

    /** 대량 모드인가 — Mock 파일 길이를 줄일지 판단한다. */
    public boolean isBulk() {
        return total() > BULK_THRESHOLD;
    }

    /** Mock 이 만들 WAV 길이(초). */
    public int wavSeconds() {
        return isBulk() ? 1 : 2;
    }

    /**
     * 일배치용 규모를 바꾼다(대용량 시험). 주기배치용 1·1 은 그대로다.
     *
     * @throws IllegalArgumentException 음수이거나 상한을 넘는 경우
     */
    public void set(int meet, int phone) {
        validate("meet", meet);
        validate("phone", phone);
        this.meetCount = meet;
        this.phoneCount = phone;
        log.info("[MockDataset] 규모 변경 — 일배치 접견 {}건 · 전화 {}건 (+주기 {}·{}) (대량모드={}, wav={}초)",
                meet, phone, periodicMeet, periodicPhone, isBulk(), wavSeconds());
    }

    /** 시연 기본(일배치 5·5 + 주기 2·2 = 14건)으로 되돌린다. */
    public void reset() {
        this.periodicMeet = DEFAULT_PERIODIC_MEET;
        this.periodicPhone = DEFAULT_PERIODIC_PHONE;
        set(DEFAULT_DAILY_MEET, DEFAULT_DAILY_PHONE);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("meetCount", meetCount);
        m.put("phoneCount", phoneCount);
        m.put("periodicMeet", periodicMeet);
        m.put("periodicPhone", periodicPhone);
        m.put("total", total());
        m.put("bulk", isBulk());
        m.put("wavSeconds", wavSeconds());
        return m;
    }

    private void validate(String name, int n) {
        if (n < 0) {
            throw new IllegalArgumentException(name + " 건수는 음수일 수 없다: " + n);
        }
        if (n > MAX_PER_KIND) {
            throw new IllegalArgumentException(
                    "%s 건수가 상한을 넘는다: %d (최대 %d)".formatted(name, n, MAX_PER_KIND));
        }
    }
}
