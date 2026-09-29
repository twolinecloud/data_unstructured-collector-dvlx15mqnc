package egovframework.unstructured.collector.common.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * 보라미 DataSource 두 개 + 라우터.
 *
 * <ul>
 *   <li>{@code h2DataSource} — 로컬 H2 Mock 보라미. <b>여기에만</b> Mock 스키마·필터 검증 행을 깐다.
 *       Spring 의 {@code spring.sql.init} 은 쓰지 않는다 — 그것은 기본(라우터) DataSource 에 걸려
 *       모드가 {@code 개발계 DB} 면 DROP TABLE 이 실DB 로 갈 수 있다.</li>
 *   <li>{@code directDataSource} — 개발계 borami-db(PostgreSQL) / 운영 보라미(Oracle).
 *       기동 시 붙어 보지 않고({@code initializationFailTimeout=-1}) 첫 사용 때 붙는다.</li>
 *   <li>{@code dataSource}(@Primary) — {@link BoramiDbRouter}. MyBatis·JdbcTemplate·트랜잭션이 이것을 쓴다.</li>
 * </ul>
 */
@Log4j2
@Configuration
public class BoramiDataSourceConfig {

    @Bean(name = "h2DataSource")
    public DataSource h2DataSource(VoiceProperties props, SourcePoolPeak peak) {
        VoiceProperties.LocalH2 h2 = props.source().localH2();
        // 성능 테스트의 'Hikari 최고 연결 수' — 빌릴 때마다 사용 중 수를 한 번 읽는다. 풀 동작은 그대로다.
        HikariDataSource ds = new SourcePoolPeak.Metered(peak);
        ds.setPoolName("borami-h2");
        ds.setJdbcUrl(h2.url());
        ds.setUsername(h2.username());
        ds.setPassword(h2.password());
        ds.setMaximumPoolSize(Math.max(1, h2.maxPoolSize()));
        ds.setMinimumIdle(1);
        applyLeakDetection(ds, h2.leakDetectionThresholdMs());
        // Mock 스키마(DROP/CREATE) + 필터 검증용 행. 유효 대상 10건은 SimulationSeedRunner 가 넣는다.
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("schema-borami-mock.sql"), new ClassPathResource("data-borami-mock.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(ds);
        log.info("[DB] H2 Mock 보라미 준비 — {}", h2.url());
        return ds;
    }

    @Bean(name = "directDataSource")
    public DataSource directDataSource(VoiceProperties props, SourcePoolPeak peak) {
        VoiceProperties.DirectDb d = props.source().directDb();
        HikariDataSource ds = new SourcePoolPeak.Metered(peak);
        ds.setPoolName("borami-direct");
        ds.setJdbcUrl(d.url());
        if (d.driverClassName() != null && !d.driverClassName().isBlank()) {
            ds.setDriverClassName(d.driverClassName());
        }
        ds.setUsername(d.username());
        ds.setPassword(d.password());
        ds.setMaximumPoolSize(Math.max(1, d.maxPoolSize()));
        ds.setMinimumIdle(0);
        ds.setIdleTimeout(Math.max(10_000L, d.idleTimeoutMs()));   // Hikari 최소 10초
        ds.setConnectionTimeout(Math.max(250, d.connectTimeoutMs()));
        applyLeakDetection(ds, d.leakDetectionThresholdMs());
        ds.setInitializationFailTimeout(-1);   // 기동 시 붙어 보지 않는다 — 포트포워딩 없는 로컬에서도 떠야 한다
        log.info("[DB] 개발계 DB(DIRECT_JDBC) 대상 — {} (user={}) · 풀 최대 {} · 누수 감지 {}", d.url(), d.username(),
                ds.getMaximumPoolSize(), ds.getLeakDetectionThreshold() == 0 ? "끔" : ds.getLeakDetectionThreshold() + "ms");
        return ds;
    }

    /** 연결 누수 감지 — 0 이면 끈다. Hikari 는 2초 미만을 받지 않으므로(경고 후 무시) 2초로 올린다. */
    private static void applyLeakDetection(HikariDataSource ds, long ms) {
        ds.setLeakDetectionThreshold(ms <= 0 ? 0L : Math.max(2_000L, ms));
    }

    /**
     * 선언 타입을 {@link BoramiDbRouter} 로 둔다 — {@code DataSource} 로 두면 이 빈이 만들어지기 전에 라우터를 타입으로
     * 찾는 빈(DbKindDetector 등)이 '없음' 으로 실패한다. 생성 순서에 기대지 않게 한다(이관 때 패키지 순서가 바뀌며 드러났다).
     */
    @Bean
    @Primary
    public BoramiDbRouter dataSource(VoiceModeState modeState,
                                 @org.springframework.beans.factory.annotation.Qualifier("h2DataSource") DataSource h2,
                                 @org.springframework.beans.factory.annotation.Qualifier("directDataSource") DataSource direct) {
        return new BoramiDbRouter(modeState, h2, direct);
    }
}
