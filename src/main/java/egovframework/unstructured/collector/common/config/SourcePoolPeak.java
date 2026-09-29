package egovframework.unstructured.collector.common.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 원천(보라미) DB 풀의 <b>동시 사용 연결 최고치</b> — 폴링 없이 잰다.
 *
 * <p>최고치는 늘 누군가 연결을 빌리는 순간에 생긴다. 그래서 빌릴 때마다 그 순간의 사용 중 연결 수를
 * 한 번 읽어 최대값만 남긴다({@link Metered#getConnection()}). 주기적으로 읽는 방식은 짧은 순간을
 * 놓치고, Hikari 의 메트릭 트래커는 Micrometer 가 이미 쓰고 있어(한 번만 설정 가능) 건드리지 않는다.</p>
 *
 * <p>이 서비스에서 원천 DB 는 배치의 <b>대상 조회</b>에서만 쓴다 — 건 처리(확보·복호화·STT·저장)는 DB 를
 * 쓰지 않는다. 그래서 동시성을 올려도 최고치는 1~2 가 정상이다. 로그 적재는 DB 가 아니라 로그 컬렉터
 * HTTP 호출이다.</p>
 *
 * <p><b>풀별 지표</b>({@link #snapshot()} 의 {@code pools}) — 최고 사용 수 · 빌린 횟수 · 연결을 얻으려고 줄 선 스레드의
 * 최고치 · 연결을 얻기까지 걸린 최대 시간. 수용자 이미지 매핑처럼 워커가 풀보다 많을 때 "줄은 섰지만 고갈(타임아웃)은
 * 없었다"를 이것으로 보인다.</p>
 */
@Component
public class SourcePoolPeak {

    private int peak;
    private String pool;
    /** 풀 이름 → 이번 측정의 풀별 지표. */
    private final Map<String, PoolStat> byPool = new LinkedHashMap<>();

    private static final class PoolStat {
        int peak;
        int waitingPeak;
        long borrows;
        long maxWaitNanos;
        int max;
    }
    /** 이 서비스의 원천 풀들 — 시험이 끝난 뒤 연결이 모두 돌아왔는지 본다. */
    private final List<Metered> pools = new CopyOnWriteArrayList<>();

    /**
     * 이 계측기에 붙은 새 Hikari 풀 — 빌릴 때마다 사용 중 수를 적고, 시험 뒤 반납 현황({@link #state()})에도 잡힌다.
     * Admin DB 풀(수용자 이미지 매핑)이 쓴다.
     */
    public HikariDataSource newPool() {
        return new Metered(this);
    }

    /** 새로 잰다. */
    public synchronized void reset() {
        peak = 0;
        pool = null;
        byPool.clear();
    }

    synchronized void observe(String poolName, int active, int waiting, long waitNanos, int max) {
        if (active > peak) {
            peak = active;
            pool = poolName;
        }
        PoolStat s = byPool.computeIfAbsent(String.valueOf(poolName), k -> new PoolStat());
        s.peak = Math.max(s.peak, active);
        s.waitingPeak = Math.max(s.waitingPeak, waiting);
        s.maxWaitNanos = Math.max(s.maxWaitNanos, waitNanos);
        s.max = max;
        s.borrows++;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("peak", peak);
        m.put("pool", pool);
        List<Map<String, Object>> list = new ArrayList<>();
        byPool.forEach((name, s) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pool", name);
            p.put("peak", s.peak);
            p.put("max", s.max);
            p.put("borrows", s.borrows);
            p.put("waitingPeak", s.waitingPeak);
            p.put("maxWaitMs", Math.round(s.maxWaitNanos / 100_000d) / 10d);
            list.add(p);
        });
        m.put("pools", list);
        return m;
    }

    /**
     * 지금 풀 현황 — 시험이 끝난 직후에 읽어 <b>빌려 간 연결이 모두 돌아왔는지</b> 본다.
     *
     * <p>한 번도 열리지 않은 풀(연결 0)은 뺀다. {@code returned} 는 모든 풀의 사용 중 0 · 대기 0 이다 — 아니면 누수거나
     * 아직 끝나지 않은 사용이다. 유휴({@code idle})는 반납된 연결이 풀에 남아 있는 수로, {@code idle-timeout-ms} 가
     * 지나면 닫혀 0 으로 돌아간다.</p>
     */
    public Map<String, Object> state() {
        List<Map<String, Object>> list = new ArrayList<>();
        boolean returned = true;
        for (Metered p : pools) {
            HikariPoolMXBean mx = p.getHikariPoolMXBean();
            if (mx == null || mx.getTotalConnections() == 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pool", p.getPoolName());
            m.put("active", mx.getActiveConnections());
            m.put("idle", mx.getIdleConnections());
            m.put("total", mx.getTotalConnections());
            m.put("waiting", mx.getThreadsAwaitingConnection());
            m.put("max", p.getMaximumPoolSize());
            m.put("leakDetectionMs", p.getLeakDetectionThreshold());
            returned &= mx.getActiveConnections() == 0 && mx.getThreadsAwaitingConnection() == 0;
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("returned", returned);
        out.put("pools", list);
        return out;
    }

    /** 빌릴 때마다 사용 중 연결 수를 {@link SourcePoolPeak} 에 알리는 Hikari 풀. 나머지 동작은 그대로다. */
    static final class Metered extends HikariDataSource {

        private final SourcePoolPeak peak;

        Metered(SourcePoolPeak peak) {
            this.peak = peak;
            peak.pools.add(this);
        }

        @Override
        public Connection getConnection() throws SQLException {
            HikariPoolMXBean before = getHikariPoolMXBean();
            int waiting = before == null ? 0 : before.getThreadsAwaitingConnection();
            long t0 = System.nanoTime();
            Connection c = super.getConnection();
            long waited = System.nanoTime() - t0;
            HikariPoolMXBean p = getHikariPoolMXBean();
            if (p != null) {
                peak.observe(getPoolName(), p.getActiveConnections(), Math.max(waiting, p.getThreadsAwaitingConnection()),
                        waited, getMaximumPoolSize());
            }
            return c;
        }
    }
}
