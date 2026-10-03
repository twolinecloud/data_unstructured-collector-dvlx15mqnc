package egovframework.unstructured.collector.mock;

import egovframework.unstructured.collector.image.model.ImageStage;
import egovframework.unstructured.collector.voice.batch.ResumeMode;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 장애 시나리오 — 더미 키에 박는 <b>표식</b>과, 그 건이 실패하는 단계 · 기대 재처리 모드.
 *
 * <table>
 *   <tr><th>시나리오</th><th>표식</th><th>음성 실패 단계(T2/T4)</th><th>기대 재처리</th><th>이미지 실패 단계</th></tr>
 *   <tr><td>COLLECT_FAIL</td><td>{@code CF}</td><td>COLLECT</td><td>FULL</td><td>ACQUIRE(브로커 수신)</td></tr>
 *   <tr><td>ANALYZE_FAIL</td><td>{@code AF}</td><td>ANALYZE(STT)</td><td>FROM_ANALYZE</td><td>DECRYPT(복호화·이미지 확인)</td></tr>
 *   <tr><td>SEND_FAIL</td><td>{@code SF}</td><td>SEND(제논)</td><td>FROM_SEND</td><td>MAP(Admin DB 매핑 — 롤백)</td></tr>
 * </table>
 *
 * <p><b>키 형식</b> — 표식은 날짜와 순번 사이에 둔다(접견 키 컬럼 {@code TARE_FILE_NO} 가 26자라 짧은 두 글자).</p>
 * <ul>
 *   <li>음성: {@code DMY-MEET-20261001-SF-0003} · {@code SIM-PHONE-20261001-CF-0001} (정상 건은 표식 없이 {@code DMY-MEET-20261001-0002})</li>
 *   <li>이미지(교정번호가 곧 키, 18자): {@code DMYIMG2610010003SF} (정상 건은 {@code DMYIMG2610010002})</li>
 * </ul>
 *
 * <p>판정은 <b>이 형식에 정확히 맞는 키만</b> 한다 — 실제 보라미 키나 기존 시뮬레이션 키({@code SIM-MEET-001})는 어떤 경우에도
 * 표식으로 읽히지 않는다.</p>
 */
public enum FailureScenario {

    COLLECT_FAIL("CF", "COLLECT", ResumeMode.FULL, ImageStage.ACQUIRE, "수집"),
    ANALYZE_FAIL("AF", "ANALYZE", ResumeMode.FROM_ANALYZE, ImageStage.DECRYPT, "정제/분석"),
    SEND_FAIL("SF", "SEND", ResumeMode.FROM_SEND, ImageStage.MAP, "적재/전송");

    /** 이미지 재처리 — 이미지에는 단계별 재처리가 없다. 다시 돌리면 실패했던 건만 다시 처리한다(성공 건은 '변경 없음'). */
    public static final String IMAGE_RERUN = "IMAGE_RERUN";

    private static final Pattern VOICE_KEY = Pattern.compile("^(SIM|DMY)-(MEET|PHONE)-\\d{8}-(CF|AF|SF)-\\d{4}$");
    private static final Pattern IMAGE_CORR = Pattern.compile("^(SIM|DMY)IMG\\d{10}(CF|AF|SF)$");

    private final String marker;
    private final String voiceStep;
    private final ResumeMode expectedResume;
    private final ImageStage imageStage;
    private final String label;

    FailureScenario(String marker, String voiceStep, ResumeMode expectedResume, ImageStage imageStage, String label) {
        this.marker = marker;
        this.voiceStep = voiceStep;
        this.expectedResume = expectedResume;
        this.imageStage = imageStage;
        this.label = label;
    }

    /** 키에 박는 두 글자. */
    public String marker() {
        return marker;
    }

    /** 음성 T2/T4 STEP_TYPE_CD — 이 단계에서 실패한다. */
    public String voiceStep() {
        return voiceStep;
    }

    /** 음성 기대 재처리 모드. */
    public ResumeMode expectedResume() {
        return expectedResume;
    }

    /** 이미지가 실패하는 단계. */
    public ImageStage imageStage() {
        return imageStage;
    }

    public String label() {
        return label;
    }

    /** 키(음성 대상 키 · 이미지 교정번호)에 박힌 시나리오. 형식이 맞지 않거나 표식이 없으면 빈 값. */
    public static Optional<FailureScenario> ofKey(String key) {
        if (key == null || key.length() < 16) {
            return Optional.empty();
        }
        Matcher m = VOICE_KEY.matcher(key);
        if (m.matches()) {
            return ofMarker(m.group(3));
        }
        m = IMAGE_CORR.matcher(key);
        if (m.matches()) {
            return ofMarker(m.group(2));
        }
        return Optional.empty();
    }

    private static Optional<FailureScenario> ofMarker(String marker) {
        for (FailureScenario s : values()) {
            if (s.marker.equals(marker)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
