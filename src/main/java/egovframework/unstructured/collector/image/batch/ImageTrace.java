package egovframework.unstructured.collector.image.batch;

import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 수용자 이미지 수집의 <b>단계별 실행 기록</b> — 백엔드가 실제로 수행한 SQL · HTTP(cURL) · 파일 명령과 그 결과.
 *
 * <p>시뮬레이터 6번 탭이 실행 중에 폴링해 실행 로그에 코드 블록으로 붙이고, 끝나면 리포트의 단계(①~⑤) 레이아웃에
 * 그린다(2번 탭 "실행 결과 상세 검증" 과 같은 모양). 손으로 같은 명령을 돌려 대조할 수 있게 SQL 은 값을 채운 형태로,
 * 브로커 호출은 cURL 로 적는다.</p>
 *
 * <p>건마다 다 적으면 수천 줄이 되므로 <b>조회는 첫 페이지 · 건별 단계는 첫 건(표본)</b>만 적고, 나머지는 건수로 요약한다.
 * 마지막 실행 한 번만 들고 있다(다음 실행이 비운다).</p>
 */
@Component
public class ImageTrace {

    /** 단계 — 화면의 ①~⑤ 와 사후 검증. */
    public enum Step {
        QUERY("① 최신 이미지 조회"), DOC_ID("② DOC_ID"), FILEKEY("③ FILEKEY"),
        RECEIVE("④ 브로커 수신 · 복호화"), MAP("⑤ 저장 · DB 매핑 UPSERT"), VERIFY("검증");

        private final String label;

        Step(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 기록 한 줄.
     *
     * @param kind    SQL · HTTP · SHELL · CODE(백엔드 내부 처리)
     * @param command 실행한 명령(값을 채운 SQL · cURL · 셸)
     * @param output  결과(행 · 응답 · 표준 출력)
     */
    public record Entry(int seq, String step, String stepLabel, String title, String kind, String command,
                        String output, String at) {}

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    /** 한 실행에 남길 최대 줄 수 — 표본만 적으므로 넉넉하다. */
    private static final int MAX = 60;

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private volatile String execId;

    /** 새 실행 — 지난 기록을 비운다. */
    public void begin(String execId) {
        this.execId = execId;
        entries.clear();
        seq.set(0);
    }

    public String execId() {
        return execId;
    }

    public void add(Step step, String title, String kind, String command, String output) {
        if (entries.size() >= MAX) {
            return;
        }
        entries.add(new Entry(seq.incrementAndGet(), step.name(), step.label(), title, kind, command,
                output == null ? "" : output, LocalTime.now().format(AT)));
    }

    /** 전부(최근 실행). */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** {@code afterSeq} 뒤에 붙은 것만 — 화면이 폴링하며 새 줄만 받는다. */
    public List<Entry> since(int afterSeq) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.seq() > afterSeq) {
                out.add(e);
            }
        }
        return out;
    }

    /** 표 모양 출력 — 열 이름 한 줄 + 행들(최대 {@code limit}) + 나머지 건수. */
    public static String table(List<String> cols, List<Map<String, Object>> rows, int total, int limit) {
        StringBuilder sb = new StringBuilder(String.join(" | ", cols)).append('\n');
        int n = 0;
        for (Map<String, Object> r : rows) {
            if (n++ >= limit) {
                break;
            }
            List<String> vals = new ArrayList<>();
            for (String c : cols) {
                Object v = r.get(c);
                vals.add(v == null ? "NULL" : String.valueOf(v));
            }
            sb.append(String.join(" | ", vals)).append('\n');
        }
        sb.append('(').append(total).append("행").append(total > limit ? " — 처음 " + limit + "행만" : "").append(')');
        return sb.toString();
    }

    /** SQL 문자열 값 — 작은따옴표를 겹친다. */
    public static String lit(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Number) {
            return v.toString();
        }
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }
}
