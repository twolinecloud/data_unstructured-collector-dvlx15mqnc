package egovframework.unstructured.collector.mock;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 더미 데이터의 키 · 식별자 규칙 — <b>용도 접두 + 날짜 + 순번</b>(+ 장애 표식).
 *
 * <p>날짜(대상일)와 순번으로 유일하다. 같은 날짜로 여러 번 추가(append)해도 순번을 이어 받으므로 키가 겹치지 않는다.
 * 컬럼 길이는 보라미 테이블 정의서(Mock 스키마와 같음)에 맞췄다:
 * 교정번호 18 · 녹취파일번호 26 · 공통파일ID/문서ID/ELEMENTID 20 · 검증고유ID 50.</p>
 *
 * <table>
 *   <tr><th>항목</th><th>형식</th><th>예(DASHBOARD · 2026-10-01 · 3번)</th></tr>
 *   <tr><td>접견 키 TARE_FILE_NO</td><td>{P}-MEET-yyyyMMdd[-표식]-nnnn</td><td>DMY-MEET-20261001-SF-0003</td></tr>
 *   <tr><td>전화 키 VRFC_ESTL_ID</td><td>{P}-PHONE-yyyyMMdd[-표식]-nnnn</td><td>DMY-PHONE-20261001-0003</td></tr>
 *   <tr><td>음성 교정번호</td><td>{P}{M|P}yyyyMMddnnnn (16)</td><td>DMYM202610010003</td></tr>
 *   <tr><td>공통파일ID · 문서ID</td><td>{P}CMFIyyMMddMnnnn · {P}DOCyyMMddMnnnn</td><td>DMYCMFI261001M0003 · DMYDOC261001M0003</td></tr>
 *   <tr><td>이미지 교정번호</td><td>{P}IMGyyMMddnnnn[표식] (16/18)</td><td>DMYIMG2610010003SF</td></tr>
 *   <tr><td>이미지 공통파일ID · 문서ID</td><td>{P}IMGFyyMMddnnnn순번 · {P}IMGDyyMMddnnnn순번</td><td>DMYIMGF26100100032</td></tr>
 * </table>
 *
 * <p>SIMULATOR 의 접두({@code SIM-MEET-} · {@code SIMCMFI} · {@code SIMDOC} · {@code SIMIMG} · {@code mock_meet_})는 기존 시뮬레이션
 * 정리 조건과 같다 — 기존 [시뮬레이션 데이터 초기화] 가 그대로 지운다. 기존 시연 키({@code SIM-MEET-001})와는 형식이 달라 겹치지 않는다.</p>
 */
public final class DummyKeys {

    private DummyKeys() {
    }

    private static final DateTimeFormatter YMD8 = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter YMD6 = DateTimeFormatter.ofPattern("yyMMdd");
    /** 순번 상한 — 네 자리. 하루 · 용도 · 유형마다 9999건. */
    public static final int MAX_SEQ = 9999;

    private static final Pattern VOICE_KEY = Pattern.compile("^(SIM|DMY)-(MEET|PHONE)-(\\d{8})(?:-(?:CF|AF|SF))?-(\\d{4})$");
    private static final Pattern IMAGE_CORR = Pattern.compile("^(SIM|DMY)IMG(\\d{6})(\\d{4})(?:CF|AF|SF)?$");
    /** 로컬 산출물 이름에서 대시보드 더미의 날짜를 읽는다 — 보존 기간 정리용. */
    private static final Pattern DMY_VOICE_NAME = Pattern.compile("(?i)dmy[-_](?:meet|phone)[-_](\\d{8})");
    private static final Pattern DMY_IMAGE_NAME = Pattern.compile("DMYIMG(\\d{6})\\d{4}");

    public static String ymd8(LocalDate d) {
        return YMD8.format(d);
    }

    public static String ymd6(LocalDate d) {
        return YMD6.format(d);
    }

    private static String seq4(int seq) {
        if (seq < 1 || seq > MAX_SEQ) {
            throw new IllegalArgumentException("순번은 1~" + MAX_SEQ + " 이어야 한다: " + seq);
        }
        return "%04d".formatted(seq);
    }

    private static String mark(FailureScenario s, String sep) {
        return s == null ? "" : sep + s.marker();
    }

    // ── 음성 ──────────────────────────────────────────────────────────────

    /** 접견 키 앞부분(날짜까지) — 순번을 이어 받을 때 {@code LIKE '…%'} 로 쓴다. */
    public static String meetKeyHead(DummyTarget t, LocalDate d) {
        return t.prefix() + "-MEET-" + ymd8(d) + "-";
    }

    public static String phoneKeyHead(DummyTarget t, LocalDate d) {
        return t.prefix() + "-PHONE-" + ymd8(d) + "-";
    }

    /** 접견 녹취파일번호(TARE_FILE_NO). */
    public static String meetKey(DummyTarget t, LocalDate d, int seq, FailureScenario s) {
        return meetKeyHead(t, d) + (s == null ? "" : s.marker() + "-") + seq4(seq);
    }

    /** 전화 검증고유ID(VRFC_ESTL_ID). */
    public static String phoneKey(DummyTarget t, LocalDate d, int seq, FailureScenario s) {
        return phoneKeyHead(t, d) + (s == null ? "" : s.marker() + "-") + seq4(seq);
    }

    /** 음성 가상 교정번호 — 유형마다 따로(접견 M · 전화 P) 둔다. 실제 교정번호와 겹치지 않는 접두다. */
    public static String voiceCorrNo(DummyTarget t, DummyDataType type, LocalDate d, int seq) {
        return t.prefix() + type.letter() + ymd8(d) + seq4(seq);
    }

    public static String cmfiId(DummyTarget t, LocalDate d, int seq) {
        return t.prefix() + "CMFI" + ymd6(d) + "M" + seq4(seq);
    }

    public static String docId(DummyTarget t, LocalDate d, int seq) {
        return t.prefix() + "DOC" + ymd6(d) + "M" + seq4(seq);
    }

    public static String recordFileId(DummyTarget t, LocalDate d, int seq) {
        return t.prefix() + "TRCD" + ymd6(d) + seq4(seq);
    }

    public static String phoneFileKey(DummyTarget t, LocalDate d, int seq) {
        return t.prefix() + "PHONEKEY" + ymd6(d) + seq4(seq);
    }

    /** 접견 더미 원본 파일명 — SIMULATOR 는 {@code mock_meet_…}(기존 정리 대상), DASHBOARD 는 {@code dmy_meet_…}. */
    public static String meetFileName(DummyTarget t, LocalDate d, int seq, FailureScenario s) {
        return t.filePrefix() + "meet_" + ymd8(d) + (s == null ? "" : "_" + s.marker().toLowerCase()) + "_" + seq4(seq) + ".m4a";
    }

    public static String phoneFileName(DummyTarget t, LocalDate d, int seq, FailureScenario s) {
        return t.filePrefix() + "phone_" + ymd8(d) + (s == null ? "" : "_" + s.marker().toLowerCase()) + "_" + seq4(seq) + ".wav";
    }

    // ── 이미지 ────────────────────────────────────────────────────────────

    /** 이미지 교정번호 앞부분(날짜까지). */
    public static String imageCorrHead(DummyTarget t, LocalDate d) {
        return t.imagePrefix() + ymd6(d);
    }

    /** 이미지 가상 교정번호 — 이미지 파이프라인은 교정번호가 곧 건 키라 표식을 여기에 박는다. */
    public static String imageCorrNo(DummyTarget t, LocalDate d, int seq, FailureScenario s) {
        return imageCorrHead(t, d) + seq4(seq) + mark(s, "");
    }

    public static String imageFileId(DummyTarget t, LocalDate d, int seq, int sn) {
        return t.imagePrefix() + "F" + ymd6(d) + seq4(seq) + sn;
    }

    public static String imageDocId(DummyTarget t, LocalDate d, int seq, int sn) {
        return t.imagePrefix() + "D" + ymd6(d) + seq4(seq) + sn;
    }

    // ── 해석 ──────────────────────────────────────────────────────────────

    /** 키 · 교정번호의 순번 — 형식이 맞지 않으면 0. 순번을 이어 받을 때 쓴다. */
    public static int seqOf(String key) {
        if (key == null) {
            return 0;
        }
        Matcher m = VOICE_KEY.matcher(key.trim());
        if (m.matches()) {
            return Integer.parseInt(m.group(4));
        }
        m = IMAGE_CORR.matcher(key.trim());
        if (m.matches()) {
            return Integer.parseInt(m.group(3));
        }
        return 0;
    }

    /** 대시보드 더미 산출물 이름의 대상일 — 보존 기간 정리용. 읽히지 않으면 빈 값. */
    public static Optional<LocalDate> dashboardDateOf(String name) {
        if (name == null) {
            return Optional.empty();
        }
        try {
            Matcher m = DMY_VOICE_NAME.matcher(name);
            if (m.find()) {
                return Optional.of(LocalDate.parse(m.group(1), YMD8));
            }
            m = DMY_IMAGE_NAME.matcher(name);
            if (m.find()) {
                return Optional.of(LocalDate.parse(m.group(1), YMD6));
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
