package egovframework.unstructured.collector.batch;

import com.zaxxer.hikari.HikariDataSource;
import egovframework.unstructured.collector.image.config.ImageProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link BatchLock} — Admin DB(PostgreSQL) {@code pg_try_advisory_lock}. data-collector {@code BatchScheduler} 와 같은 방식이고
 * 키만 다르다(정형 {@code 42120001} · 비정형 {@code 42120002} — 같은 Admin DB 라 키가 같으면 서로를 막는다).
 *
 * <p><b>배치가 도는 동안 연결 하나를 쥐고 있다</b> — advisory lock 은 세션에 붙기 때문이다. 그래서 이미지 매핑 풀({@code AdminDb})이
 * 아니라 전용 풀을 쓴다: 매핑 풀은 누수 감지(30초)가 켜져 있어 몇 분짜리 배치마다 경고가 찍힌다. 다 쓴 연결은 풀에 돌려주지 않고
 * <b>버린다</b>(evict) — 세션이 닫혀 잠금이 확실히 풀리고, 풀에 남은 세션이 잠금을 쥐고 있는 일이 없다. 파드가 배치 도중 죽어도
 * 연결이 끊기면 DB 가 잠금을 푼다.</p>
 *
 * <p>Admin DB 가 PostgreSQL 이 아니면(로컬 H2) 잠금을 쓰지 않는다 — H2 에는 advisory lock 이 없다.</p>
 */
@Log4j2
@Component
public class AdminDbBatchLock implements BatchLock, DisposableBean {

    private final boolean enabled;
    private final long key;
    private final String url;
    private final HikariDataSource ds;

    public AdminDbBatchLock(ImageProperties props,
                            @Value("${unstructured.batch.db-lock.enabled:true}") boolean enabledByConfig,
                            @Value("${unstructured.batch.db-lock.key:42120002}") long key) {
        ImageProperties.AdminDb a = props.adminDb();
        this.url = a.url();
        this.key = key;
        this.enabled = enabledByConfig && url.startsWith("jdbc:postgresql:");
        if (!enabled) {
            this.ds = null;
            log.info("[BatchLock] 파드 간 실행 잠금 끔 — {}", enabledByConfig ? "Admin DB 가 PostgreSQL 이 아니다(로컬 H2)" : "설정 off");
            return;
        }
        ds = new HikariDataSource();
        ds.setPoolName("batch-lock");
        ds.setJdbcUrl(url);
        ds.setUsername(a.username());
        ds.setPassword(a.password());
        ds.setMaximumPoolSize(2);
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(Math.max(250, a.connectTimeoutMs()));
        ds.setInitializationFailTimeout(-1);   // 기동 시 붙어 보지 않는다
        log.info("[BatchLock] 파드 간 실행 잠금 — Admin DB advisory lock 키 {}", key);
    }

    @Override
    public Held tryAcquire() {
        if (!enabled) {
            return () -> { };
        }
        Connection c;
        try {
            c = ds.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("실행 잠금 확인 실패(Admin DB 연결) — " + e.getMessage(), e);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getBoolean(1)) {
                    return release(c);
                }
            }
            discard(c);
            return null;
        } catch (SQLException e) {
            discard(c);
            throw new IllegalStateException("실행 잠금 확인 실패 — " + e.getMessage(), e);
        }
    }

    private Held release(Connection c) {
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, key);
                ps.execute();
            } catch (SQLException e) {
                log.warn("[BatchLock] 잠금 풀기 실패 — 연결을 버려 세션째 푼다: {}", e.getMessage());
            } finally {
                discard(c);
            }
        };
    }

    /** 풀에 돌려주지 않고 버린다 — 세션이 닫히며 잠금도 확실히 풀린다. */
    private void discard(Connection c) {
        ds.evictConnection(c);
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("type", "ADMIN_DB_ADVISORY_LOCK");
        m.put("key", key);
        return m;
    }

    @Override
    public void destroy() {
        if (ds != null) {
            ds.close();
        }
    }
}
