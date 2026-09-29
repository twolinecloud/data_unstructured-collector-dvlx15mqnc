package egovframework.unstructured.collector.common.config;

import lombok.extern.log4j.Log4j2;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 보라미 조회 DataSource 라우터 — <b>드롭다운(조회 모드)이 곧 어느 DB 에 붙는지</b>를 정한다.
 *
 * <pre>
 *   MOCK        (화면: MOCK (로컬 H2))  → LOCAL_H2  : 인메모리 H2 Mock 보라미
 *   DIRECT_JDBC (화면: 개발계 DB)       → DIRECT    : 개발계 borami-db(PostgreSQL) / 운영 보라미(Oracle)
 *   ESB_HTTP2DB (화면: 메타빌드 (ESB))  → (DB 를 쓰지 않는다 — 조회는 ESB 로 간다. 호출되면 H2 로 떨어진다)
 * </pre>
 *
 * <p><b>왜 라우터인가</b>: 예전에는 DataSource 가 하나라 {@code 개발계 DB} 를 골라도 로컬 H2 를 보고 있었다.
 * "생성 완료" 인데 DBeaver 로 개발계를 보면 아무것도 없는 이유가 그것이었다. MyBatis·JdbcTemplate·트랜잭션이
 * 전부 이 라우터를 쓰므로, 조회도 시뮬레이션 데이터 생성도 같은 곳으로 간다.</p>
 *
 * <p><b>키는 커넥션을 얻는 순간 정해진다</b> — 트랜잭션 안에서는 첫 커넥션의 DB 가 끝까지 쓰인다.
 * 기동 시 시딩처럼 모드와 무관하게 특정 DB 를 써야 하면 {@link #withTarget} 으로 잠깐 고정한다.</p>
 */
@Log4j2
public class BoramiDbRouter extends AbstractRoutingDataSource {

    public enum DbTarget { LOCAL_H2, DIRECT }

    private static final ThreadLocal<DbTarget> OVERRIDE = new ThreadLocal<>();

    private final VoiceModeState modeState;

    public BoramiDbRouter(VoiceModeState modeState, DataSource h2, DataSource direct) {
        this.modeState = modeState;
        setTargetDataSources(Map.of(DbTarget.LOCAL_H2, h2, DbTarget.DIRECT, direct));
        setDefaultTargetDataSource(h2);
        // 기동 시 두 DB 에 붙어 보지 않는다 — 개발계 DB 는 포트포워딩이 없으면 닿지 않는다
        setLenientFallback(true);
        afterPropertiesSet();
    }

    /** 지금 조회 모드가 가리키는 DB. */
    public DbTarget currentTarget() {
        DbTarget o = OVERRIDE.get();
        if (o != null) {
            return o;
        }
        return modeState.source() == VoiceProperties.SourceMode.DIRECT_JDBC ? DbTarget.DIRECT : DbTarget.LOCAL_H2;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return currentTarget();
    }

    /** 이 스레드에서 잠깐 특정 DB 로 고정하고 실행한다(기동 시 H2 시딩 등). */
    public <T> T withTarget(DbTarget target, Supplier<T> body) {
        DbTarget before = OVERRIDE.get();
        OVERRIDE.set(target);
        try {
            return body.get();
        } finally {
            if (before == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(before);
            }
        }
    }

    /** 특정 대상의 실제 DataSource(진단·연결 확인용). */
    public DataSource dataSourceOf(DbTarget target) {
        return (DataSource) getResolvedDataSources().get(target);
    }
}
