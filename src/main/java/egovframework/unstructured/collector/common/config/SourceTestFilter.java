package egovframework.unstructured.collector.common.config;

import egovframework.unstructured.collector.common.model.BatchWindow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 운영 선별 테스트 조건 — 환경 변수 {@code SOURCE_TEST_*} 로 보라미 조회 조건을 바꾼다.
 *
 * <p><b>왜</b>: 운영계에서 전체(전날 하루분 · 현재 수용자 전원)가 아니라 일부만 골라 돌려 보고 싶다(11-25 요청).
 * 환경 변수를 비우거나 지우고 재기동하면 기존 기준으로 그대로 돌아간다 — 코드·설정 파일은 손대지 않는다.</p>
 *
 * <table>
 *   <tr><th>변수</th><th>뜻</th><th>적용</th></tr>
 *   <tr><td>{@code SOURCE_TEST_FROM}</td><td>조회 시작(포함). 날짜만 쓰면 그날 00:00</td><td>접견·전화 {@code CRT_DT}</td></tr>
 *   <tr><td>{@code SOURCE_TEST_TO}</td><td>조회 끝. 날짜만 쓰면 <b>그날까지 포함</b>(다음 날 00:00 전). 비우면 FROM 하루</td><td>접견·전화</td></tr>
 *   <tr><td>{@code SOURCE_TEST_INSTT_CD}</td><td>교정기관코드(쉼표 구분)</td><td>접견·전화 {@code CORR_INSTT_CD} · 사진은 수용자기본</td></tr>
 *   <tr><td>{@code SOURCE_TEST_CORR_NO}</td><td>교정번호(쉼표 구분)</td><td>접견·전화·사진 {@code CORR_NO}</td></tr>
 *   <tr><td>{@code SOURCE_TEST_MAX_ROWS}</td><td>1회 처리 건수(사진은 수용자 수) 상한</td><td>세 대상 모두</td></tr>
 * </table>
 *
 * <p><b>안전 장치</b></p>
 * <ul>
 *   <li><b>컬럼명·SQL 조각은 받지 않는다</b> — 위 다섯 키만 있고, 값은 전부 바인딩 변수로만 SQL 에 들어간다.
 *       {@code SOURCE_TEST_} 로 시작하는 다른 이름(오타)이 있으면 오류로 본다.</li>
 *   <li><b>값이 틀리면 조회하지 않는다(fail-closed)</b> — 기존 기준으로 슬쩍 돌아가면 '일부만' 돌리려던 운영 테스트가
 *       전날 전체를 처리해 버린다. 대신 배치가 사유와 함께 FAIL 로 닫힌다.</li>
 *   <li>기간은 최대 {@value #MAX_SPAN_DAYS}일 · 기관 {@value #MAX_INSTT}개 · 교정번호 {@value #MAX_CORR}개 ·
 *       상한 {@value #MAX_ROWS_LIMIT}건까지(Oracle IN 1,000개 제한 · 실수로 넓게 잡는 것 방지).</li>
 * </ul>
 *
 * <p>빈 값({@code ""} · 공백)은 '없음' 이다. 환경 변수는 기동할 때 한 번 읽는다.</p>
 */
public final class SourceTestFilter {

    public static final String PREFIX = "SOURCE_TEST_";
    public static final String FROM = PREFIX + "FROM";
    public static final String TO = PREFIX + "TO";
    public static final String INSTT_CD = PREFIX + "INSTT_CD";
    public static final String CORR_NO = PREFIX + "CORR_NO";
    public static final String MAX_ROWS = PREFIX + "MAX_ROWS";
    /** 허용 키 — 이 밖의 {@code SOURCE_TEST_*} 는 오타로 보고 거부한다. */
    public static final List<String> KEYS = List.of(FROM, TO, INSTT_CD, CORR_NO, MAX_ROWS);

    static final int MAX_SPAN_DAYS = 31;
    static final int MAX_INSTT = 50;
    static final int MAX_CORR = 500;
    static final int MAX_ROWS_LIMIT = 10_000;

    /** 교정기관코드 — 정의서 VARCHAR2(7). 영숫자만. */
    private static final Pattern INSTT_RE = Pattern.compile("[0-9A-Za-z]{1,7}");
    /** 교정번호 — 정의서 VARCHAR2(18). 영숫자와 하이픈. */
    private static final Pattern CORR_RE = Pattern.compile("[0-9A-Za-z-]{1,18}");

    private static final DateTimeFormatter[] DATE_ONLY = {
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT),
    };
    private static final DateTimeFormatter[] DATE_TIME = {
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT),
    };

    private final LocalDateTime from;
    private final LocalDateTime to;
    private final List<String> insttCds;
    private final List<String> corrNos;
    private final Integer maxRows;
    private final Map<String, String> given;
    private final List<String> errors;

    private SourceTestFilter(LocalDateTime from, LocalDateTime to, List<String> insttCds, List<String> corrNos,
                             Integer maxRows, Map<String, String> given, List<String> errors) {
        this.from = from;
        this.to = to;
        this.insttCds = List.copyOf(insttCds);
        this.corrNos = List.copyOf(corrNos);
        this.maxRows = maxRows;
        this.given = java.util.Collections.unmodifiableMap(given);
        this.errors = List.copyOf(errors);
    }

    /** 조건 없음 — 기존 기준. */
    public static SourceTestFilter none() {
        return new SourceTestFilter(null, null, List.of(), List.of(), null, new LinkedHashMap<>(), List.of());
    }

    /**
     * 환경 변수(또는 같은 모양의 맵)에서 읽는다. 예외를 던지지 않는다 — 틀린 값은 {@link #errors()} 에 모으고
     * {@link #invalid()} 상태가 된다.
     *
     * @param env 보통 {@code System.getenv()}
     * @param now 미래 날짜 판정 기준
     */
    public static SourceTestFilter parse(Map<String, String> env, LocalDateTime now) {
        Map<String, String> given = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        Set<String> unknown = new TreeSet<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            String k = e.getKey();
            if (k == null || !k.toUpperCase(java.util.Locale.ROOT).startsWith(PREFIX)) {
                continue;
            }
            String v = clean(e.getValue());
            if (!KEYS.contains(k)) {
                if (v != null) {
                    unknown.add(k);
                }
                continue;
            }
            if (v != null) {
                given.put(k, v);
            }
        }
        if (!unknown.isEmpty()) {
            errors.add("모르는 변수 " + unknown + " — 허용: " + KEYS + " (대소문자 그대로)");
        }

        // ── 기간 ──
        LocalDateTime from = null;
        LocalDateTime to = null;
        String fromRaw = given.get(FROM);
        String toRaw = given.get(TO);
        if (fromRaw == null && toRaw != null) {
            errors.add(TO + " 만 있다 — " + FROM + " 도 지정할 것");
        } else if (fromRaw != null) {
            Parsed f = parseTime(fromRaw);
            if (f == null) {
                errors.add(FROM + "='" + fromRaw + "' 날짜 형식 오류 — 예: 2026-11-20 / 20261120 / 2026-11-20 09:00[:00]");
            } else {
                from = f.at();
                if (toRaw == null) {
                    if (f.dateOnly()) {
                        to = from.plusDays(1);   // 날짜만 — 그날 하루
                    } else {
                        errors.add(FROM + " 에 시각까지 썼으면 " + TO + " 도 지정할 것(날짜만 쓰면 그날 하루)");
                    }
                } else {
                    Parsed t = parseTime(toRaw);
                    if (t == null) {
                        errors.add(TO + "='" + toRaw + "' 날짜 형식 오류 — 예: 2026-11-21 / 2026-11-21 18:00");
                    } else {
                        // 날짜만 쓴 끝은 '그날까지 포함' — 사람이 'FROM=11-20 TO=11-20' 이라 쓰면 하루를 뜻한다
                        to = t.dateOnly() ? t.at().plusDays(1) : t.at();
                    }
                }
                if (to != null) {
                    if (!from.isBefore(to)) {
                        errors.add("기간 오류 — 시작 " + from + " 이 끝 " + to + " 보다 앞이어야 한다");
                    } else if (from.plusDays(MAX_SPAN_DAYS).isBefore(to)) {
                        errors.add("기간이 " + MAX_SPAN_DAYS + "일을 넘는다(" + from + " ~ " + to + ") — 선별 테스트는 좁게");
                    }
                }
                if (now != null && !from.isBefore(now)) {
                    errors.add(FROM + "=" + from + " 이 미래다 — 연도 오타가 아닌지 확인");
                }
            }
        }

        List<String> instt = list(given.get(INSTT_CD), INSTT_CD, INSTT_RE, MAX_INSTT, "영숫자 1~7자", errors);
        List<String> corr = list(given.get(CORR_NO), CORR_NO, CORR_RE, MAX_CORR, "영숫자·하이픈 1~18자", errors);

        Integer max = null;
        String maxRaw = given.get(MAX_ROWS);
        if (maxRaw != null) {
            try {
                max = Integer.valueOf(maxRaw.replace(",", "").replace("_", ""));
                if (max < 1 || max > MAX_ROWS_LIMIT) {
                    errors.add(MAX_ROWS + "=" + maxRaw + " 범위 밖 — 1 ~ " + MAX_ROWS_LIMIT);
                    max = null;
                }
            } catch (NumberFormatException e) {
                errors.add(MAX_ROWS + "='" + maxRaw + "' 숫자가 아니다");
            }
        }
        return new SourceTestFilter(from, to, instt, corr, max, given, errors);
    }

    private record Parsed(LocalDateTime at, boolean dateOnly) {}

    private static Parsed parseTime(String raw) {
        for (DateTimeFormatter f : DATE_ONLY) {
            try {
                return new Parsed(LocalDate.parse(raw, f).atStartOfDay(), true);
            } catch (DateTimeParseException ignore) {
                // 다음 형식
            }
        }
        for (DateTimeFormatter f : DATE_TIME) {
            try {
                return new Parsed(LocalDateTime.parse(raw, f), false);
            } catch (DateTimeParseException ignore) {
                // 다음 형식
            }
        }
        return null;
    }

    private static List<String> list(String raw, String key, Pattern re, int maxCount, String rule, List<String> errors) {
        if (raw == null) {
            return List.of();
        }
        List<String> vals = Arrays.stream(raw.split("[,;\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        List<String> bad = vals.stream().filter(v -> !re.matcher(v).matches()).toList();
        if (!bad.isEmpty()) {
            errors.add(key + " 형식 오류 " + bad.stream().limit(5).toList() + " — " + rule);
            return List.of();
        }
        if (vals.size() > maxCount) {
            errors.add(key + " 가 " + vals.size() + "개 — 최대 " + maxCount + "개");
            return List.of();
        }
        return vals;
    }

    /** 앞뒤 공백과 감싼 따옴표를 걷어 낸다. 남는 게 없으면 null(= 지정 안 함). */
    private static String clean(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s.isEmpty() ? null : s;
    }

    // ── 상태 ────────────────────────────────────────────────────────────

    /** 값이 하나라도 틀렸다 — 조회하지 않는다. */
    public boolean invalid() {
        return !errors.isEmpty();
    }

    /** 유효한 테스트 조건이 하나라도 있다. */
    public boolean active() {
        return !invalid() && !given.isEmpty();
    }

    /** 틀린 값이 있으면 조회 전에 멈춘다 — 사유를 그대로 배치 FAIL 사유로 남긴다. */
    public void requireValid() {
        if (invalid()) {
            throw new IllegalStateException("운영 테스트 조건(SOURCE_TEST_*) 값 오류로 조회하지 않음 — "
                    + String.join(" / ", errors) + " · 값을 고치거나 비우고 재기동");
        }
    }

    public List<String> errors() {
        return errors;
    }

    public boolean hasPeriod() {
        return active() && from != null;
    }

    public LocalDateTime from() {
        return from;
    }

    public LocalDateTime to() {
        return to;
    }

    /** 교정기관코드 — 비면 거르지 않는다. */
    public List<String> insttCds() {
        return active() ? insttCds : List.of();
    }

    /** 교정번호 — 비면 거르지 않는다. */
    public List<String> corrNos() {
        return active() ? corrNos : List.of();
    }

    public Integer maxRows() {
        return active() ? maxRows : null;
    }

    /** 사진처럼 기간 조건이 없는 대상에서 범위를 줄이는 조건(기관 · 교정번호 · 상한)이 있는가. */
    public boolean narrowsWithoutPeriod() {
        return active() && (!insttCds.isEmpty() || !corrNos.isEmpty() || maxRows != null);
    }

    /**
     * 기간 조건이 있으면 시간창을 바꾼다 — 라벨(DAILY · PERIODIC · MANUAL)은 그대로(T1 실행 유형이 안 바뀌게).
     * 조건이 없으면 받은 창 그대로.
     */
    public BatchWindow apply(BatchWindow window) {
        if (!hasPeriod() || window == null) {
            return window;
        }
        return new BatchWindow(from, to, window.label());
    }

    /** 1회 상한 — 지정하면 그 값, 아니면 기본값. */
    public int limitOr(int defaultLimit) {
        Integer m = maxRows();
        return m != null ? m : defaultLimit;
    }

    /** 로그 한 줄 — 최종 조건. */
    public String describe() {
        if (invalid()) {
            return "오류 " + errors;
        }
        if (!active()) {
            return "없음(기존 기준)";
        }
        List<String> parts = new ArrayList<>();
        if (from != null) {
            parts.add("기간 [" + from + " ~ " + to + ")");
        }
        if (!insttCds.isEmpty()) {
            parts.add("기관 " + insttCds);
        }
        if (!corrNos.isEmpty()) {
            parts.add("교정번호 " + (corrNos.size() <= 5 ? corrNos : corrNos.subList(0, 5) + " 외 " + (corrNos.size() - 5) + "명"));
        }
        if (maxRows != null) {
            parts.add("상한 " + maxRows + "건");
        }
        return String.join(" · ", parts);
    }

    /** 설정 조회 API 용. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", invalid() ? "INVALID" : active() ? "ACTIVE" : "OFF");
        m.put("given", given);
        m.put("effective", describe());
        if (hasPeriod()) {
            m.put("from", from.toString());
            m.put("toExclusive", to.toString());
        }
        m.put("insttCds", insttCds());
        m.put("corrNoCount", corrNos().size());
        m.put("maxRows", maxRows());
        m.put("errors", errors);
        return m;
    }

    @Override
    public String toString() {
        return describe();
    }
}
