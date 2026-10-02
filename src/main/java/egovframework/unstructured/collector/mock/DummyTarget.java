package egovframework.unstructured.collector.mock;

/**
 * 더미 데이터의 <b>용도</b> — 누가 읽어 가는 데이터인가.
 *
 * <ul>
 *   <li>{@link #SIMULATOR} — 시뮬레이터 2·3번 탭 수동 실행({@code test=true}, 시험 워터마크 {@code TEST_BATCH})<b>만</b> 읽는다.
 *       실제 실행(스케줄러 · {@code /internal/batch/run} · {@code test=false})은 집지 않는다(2026-10-02 결정 — 선점 · 충돌 방지).
 *       기존 시뮬레이션 데이터와 같은 {@code SIM} 접두 · 생성자 {@code simadm}. 기존 [시뮬레이션 데이터 초기화]
 *       ({@code SimulationDataService.clean()} · 이미지 {@code ImageSimulationService.clean()})가 그대로 지운다.</li>
 *   <li>{@link #DASHBOARD} — 실제 스케줄러 · {@code /internal/batch/run} · {@code /internal/batch/reprocess} 가 읽어
 *       로그 컬렉터 이력 · 관리 화면 대시보드에 나온다. 실존하지 않는 {@code DMY} 접두의 가상 교정번호만 쓴다 —
 *       실제 수용자에 더미 접견·전화·사진을 붙이면 정형 수집기의 타임라인·요인·피처에 섞인다(작업 지시 1-2).
 *       생성자 {@code dmyadm}. 시뮬레이터 시험 실행({@code test=true})은 이 데이터를 집어 가지 않는다.
 *       개발계는 {@code include-image=true} 라 이미지({@code DMYIMG…})도 실제 배치가 같이 처리한다.</li>
 * </ul>
 *
 * <p>⚠ 어느 용도든 수용자기본({@code TB_IRIM_PRBS_BS}) · 신상({@code TB_IRIM_PEIN_BS})에는 쓰지 않는다 — 그 두 테이블에
 * 행이 생기면 가상 교정번호가 고아가 아니게 되어 정형 JSON · 특이수용자 원장 · 학습셋 · 대시보드에 모두 들어간다.</p>
 */
public enum DummyTarget {

    SIMULATOR("SIM", "simadm", "mock_", "시뮬레이터 — 시험 실행(TEST_BATCH)"),
    DASHBOARD("DMY", "dmyadm", "dmy_", "대시보드 — 실제 배치(VOICE_ANALYSIS)");

    private final String prefix;
    private final String usr;
    private final String filePrefix;
    private final String label;

    DummyTarget(String prefix, String usr, String filePrefix, String label) {
        this.prefix = prefix;
        this.usr = usr;
        this.filePrefix = filePrefix;
        this.label = label;
    }

    /** 키 · 교정번호 접두 — {@code SIM} / {@code DMY}. */
    public String prefix() {
        return prefix;
    }

    /** 생성자 {@code CRT_USR_ID} — 정리할 때 접두와 함께 조건으로 건다. */
    public String usr() {
        return usr;
    }

    /**
     * 더미 원본 파일명 접두. SIMULATOR 는 기존 시뮬레이션과 같은 {@code mock_} 이라 기존 정리가 같이 지운다.
     */
    public String filePrefix() {
        return filePrefix;
    }

    public String label() {
        return label;
    }

    /** 이미지 교정번호 접두 — {@code SIMIMG} / {@code DMYIMG}. SIMIMG 는 실제 이미지 수집이 대상에서 뺀다. */
    public String imagePrefix() {
        return prefix + "IMG";
    }

    /** 음성 대상 키(접견 TARE_FILE_NO · 전화 VRFC_ESTL_ID)가 이 용도의 것인가. */
    public boolean ownsKey(String key) {
        return key != null && (key.startsWith(prefix + "-MEET-") || key.startsWith(prefix + "-PHONE-"));
    }

    /** 키 표식 장애의 키(음성 대상 키 · 이미지 교정번호)가 이 용도의 것인가 — 초기화가 그 용도의 소진 표시만 지운다. */
    public boolean ownsScenarioKey(String key) {
        return ownsKey(key) || (key != null && key.startsWith(imagePrefix()));
    }

    /**
     * 수집기 로컬 산출물의 이름이 <b>대시보드 더미</b>의 것인가 — 멱등 표식({@code meet-DMY-…}) · 전사 보존물
     * ({@code meet-DMY-….json}) · 복호화 보존물({@code decrypted_dmy_…}) · 수신 파일({@code dmy_…} · {@code img_DMYIMG…}) ·
     * 사진 원본/저장 폴더({@code DMYIMG…}).
     *
     * <p>시뮬레이터 초기화는 이 이름들을 <b>남기고</b>, 대시보드 초기화는 이 이름들<b>만</b> 지운다 — 두 초기화가 서로의
     * 데이터를 건드리지 않게 하는 기준이다. SIMULATOR 쪽은 기존 동작(전부 지움)을 그대로 두므로 이 판정을 쓰지 않는다.</p>
     */
    public static boolean isDashboardLocalName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return name.startsWith("meet-DMY-") || name.startsWith("phone-DMY-")
                || lower.startsWith("dmy_") || lower.startsWith("decrypted_dmy_")
                || name.startsWith("DMYIMG") || name.startsWith("img_DMYIMG");
    }
}
