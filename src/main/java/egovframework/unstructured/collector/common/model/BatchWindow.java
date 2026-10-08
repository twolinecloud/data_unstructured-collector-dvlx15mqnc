package egovframework.unstructured.collector.common.model;

import java.time.LocalDateTime;

/**
 * 배치가 훑을 시간창.
 *
 * <p>주기배치는 10분마다 돌면서 "앞에서 못 돌린 것까지 다 하고 20분 전 것까지" 처리한다
 * (2026-09-11 회의). 그래서 <b>연속한 실행의 시간창이 겹친다</b> — 같은 파일을 두 번 STT 에
 * 태우지 않으려면 멱등 키 기반 제외가 반드시 필요하다.</p>
 *
 * @param from  시작(포함)
 * @param to    종료(미포함)
 * @param label 로그 표기용 — DAILY / PERIODIC / MANUAL
 */
public record BatchWindow(LocalDateTime from, LocalDateTime to, String label) {

    public static BatchWindow daily(LocalDateTime now) {
        LocalDateTime today = now.toLocalDate().atStartOfDay();
        return new BatchWindow(today.minusDays(1), today, "DAILY");
    }

    /**
     * 주기배치 창 — <b>당일 00:00 부터 지금까지</b>.
     *
     * <p><b>왜 최근 20분이 아니라 당일 전체인가</b>: 예전에는 {@code [지금-20분, 지금]} 만 봤다.
     * 스케줄러가 멈춰 있었거나(파드 재기동·장애) 데이터가 그보다 먼저 들어와 있었으면,
     * 그 건들은 다음 창에도 들어오지 않아 <b>영영 수집되지 않는다</b>. 일배치는 어제치만
     * 보므로 오늘 낮에 빠진 건을 주워 줄 사람이 없다.</p>
     *
     * <p>창을 넓혀도 같은 건을 두 번 처리하지는 않는다 — 멱등 표식(T4)이 이미 성공한 건을
     * 건너뛴다. 넓힌 만큼 조회 행수가 늘 뿐이고, 그것은 놓치는 것보다 훨씬 싸다.</p>
     *
     * @param lagMinutes 더 이상 창의 길이를 정하지 않는다. 당일 00:00 이 그보다 늦을 수 없으므로
     *                   {@code 지금-lag} 가 자정보다 이르면(자정 직후) 그쪽까지 넓힌다 —
     *                   00:05 에 도는 배치가 어제 23:55 건을 놓치지 않게. 스케줄은 '주기 + 10분'(최소 20분)을 넘긴다
     *                   ({@code UnstructuredBatchService#planScheduled}) — 주기가 길어도 앞 회차 창과 맞물리게.
     */
    public static BatchWindow periodic(LocalDateTime now, int lagMinutes) {
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime lag = now.minusMinutes(Math.max(0, lagMinutes));
        return new BatchWindow(lag.isBefore(todayStart) ? lag : todayStart, now, "PERIODIC");
    }

    /**
     * [바로 실행] 창 — <b>DB 워터마크 ~ 지금</b>. 워터마크가 없으면 최근 {@code lookbackDays} 일.
     *
     * <p>워터마크는 로그 컬렉터 T1 에서 읽는다: {@code exec_sts_cd='SUCCESS'} 인 배치의
     * {@code MAX(target_to_dtm)}. "이 시각까지는 다 수집했다" 는 뜻이다.
     * 성공한 배치가 아직 없으면(첫 실행·시험 이력 초기화 직후) 최근 {@code lookbackDays} 일을 되짚는다.</p>
     *
     * @param watermark    DB 워터마크. 없으면 null
     * @param lookbackDays 워터마크가 없을 때 되짚을 일수(1 이상)
     */
    public static BatchWindow onDemand(LocalDateTime now, LocalDateTime watermark, int lookbackDays) {
        LocalDateTime from = watermark != null ? watermark : now.minusDays(Math.max(1, lookbackDays));
        return new BatchWindow(from, now, "MANUAL");
    }

    public static BatchWindow manual(LocalDateTime from, LocalDateTime to) {
        return new BatchWindow(from, to, "MANUAL");
    }

    /**
     * T1 의 {@code EXEC_TYPE_CD} — <b>{@code SCHEDULED} / {@code MANUAL} 둘뿐이다.</b>
     *
     * <p>{@code label()}(DAILY·PERIODIC·MANUAL)을 그대로 보내고 있었는데, 로그 테이블
     * 설계서(TC-21223-01)가 허용하는 값은 스케줄·수동 둘뿐이다. 코드 밖의 값을 넣으면
     * 통계·필터가 그 행을 집계에서 놓친다.</p>
     *
     * <p>일배치·주기배치는 <b>스케줄러가 도는 작업</b>이므로 둘 다 {@code SCHEDULED} 다.
     * 시뮬레이터에서 손으로 눌러 돌려도 같은 성격의 작업이라 같은 코드로 남긴다 —
     * 누가 눌렀는지는 {@code TRIGGER_BY} 가 따로 들고 있다.</p>
     */
    public String execTypeCd() {
        return "MANUAL".equals(label) ? "MANUAL" : "SCHEDULED";
    }

    /**
     * T1 에 남길 구간의 끝 — <b>'지금' 을 넘지 않는다.</b>
     *
     * <p>재처리 창은 시계 오차 여유로 끝을 '지금+1분' 으로 잡는다. 그 끝을 그대로 T1 에 남기면 SUCCESS 일 때
     * [바로 실행] 워터마크가 미래가 되어, 그 1분 사이에 들어온 건을 다음 [바로 실행]이 창 밖으로 놓친다.
     * 처리 범위는 그대로 두고, 기록만 '지금' 에서 자른다 — 겹친 만큼은 멱등 표식이 건너뛴다.</p>
     */
    public LocalDateTime recordedEnd(LocalDateTime now) {
        return to.isAfter(now) ? now : to;
    }

    /** 시각이 창 [from, to) 안에 있는가 — 조회 SQL 의 {@code CRT_DT >= from AND CRT_DT < to} 와 같은 판정. */
    public boolean contains(LocalDateTime at) {
        return at != null && !at.isBefore(from) && at.isBefore(to);
    }

    @Override
    public String toString() {
        return "%s[%s ~ %s)".formatted(label, from, to);
    }
}
