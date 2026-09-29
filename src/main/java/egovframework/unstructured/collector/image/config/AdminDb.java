package egovframework.unstructured.collector.image.config;

import com.zaxxer.hikari.HikariDataSource;
import egovframework.unstructured.collector.common.config.SourcePoolPeak;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * <b>Admin DB</b> 접속 — 수용자 사진 매핑({@code TB_SRC_INMATE_PHOTO})을 적재하는 곳.
 *
 * <p><b>왜 스프링 빈 DataSource 가 아닌가</b>: 이 서비스의 기본 DataSource·JdbcTemplate·트랜잭션 매니저는 보라미 라우터
 * 하나를 전제로 자동 구성된다. 두 번째 DataSource·JdbcTemplate·트랜잭션 매니저를 빈으로 올리면 자동 구성이 물러나
 * 시딩·조회가 엉뚱한 DB 로 갈 수 있다. 그래서 풀·JdbcTemplate·트랜잭션을 이 안에만 둔다.</p>
 *
 * <p><b>트랜잭션은 짧게</b>: {@link #inTx} 로만 연다 — 파일 수신·복호화·저장 같은 I/O 는 절대 안에 넣지 않는다.
 * 매핑 한 건 = UPSERT 한 문장(PostgreSQL {@code ON CONFLICT} · H2 는 행 잠금 + INSERT/UPDATE)뿐이라 수 ms 에
 * 커넥션을 돌려준다. 워커가 풀보다 많으면 잠깐 줄을 설 뿐 고갈되지 않는다 — 리포트의 풀 대기 지표로 본다.</p>
 *
 * <p>풀은 {@link SourcePoolPeak} 에 붙인다 — 성능 리포트의 Hikari 최고 연결 수 · 종료 후 반납 현황에 같이 잡힌다.</p>
 */
@Log4j2
@Component
public class AdminDb implements DisposableBean {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    public static final String PHOTO_TABLE = "tb_src_inmate_photo";
    public static final String INMATE_TABLE = "tb_src_inmate_bs";

    private final HikariDataSource ds;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String schema;
    private final boolean h2;
    private final String url;

    public AdminDb(ImageProperties props, SourcePoolPeak peak) {
        ImageProperties.AdminDb a = props.adminDb();
        this.url = a.url();
        this.h2 = a.url().startsWith("jdbc:h2:");
        String s = a.schema() == null ? "" : a.schema().trim();
        if (!s.isEmpty() && !IDENTIFIER.matcher(s).matches()) {
            throw new IllegalStateException("image.admin-db.schema 가 SQL 식별자 형식이 아니다: " + s);
        }
        this.schema = s;

        ds = peak.newPool();
        ds.setPoolName("admin-db");
        ds.setJdbcUrl(a.url());
        ds.setUsername(a.username());
        ds.setPassword(a.password());
        ds.setMaximumPoolSize(Math.max(1, a.maxPoolSize()));
        ds.setMinimumIdle(0);
        ds.setIdleTimeout(60_000L);
        ds.setConnectionTimeout(Math.max(250, a.connectTimeoutMs()));
        ds.setInitializationFailTimeout(-1);   // 기동 시 붙어 보지 않는다 — Admin DB 없이도 서비스는 뜬다
        long leak = a.leakDetectionThresholdMs();
        ds.setLeakDetectionThreshold(leak <= 0 ? 0L : Math.max(2_000L, leak));
        this.jdbc = new JdbcTemplate(ds);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        if (h2) {
            // 로컬 H2 — 매핑 테이블과 최소한의 수용자 기본 테이블을 만든다(개발계·운영은 DDL 을 관리자가 적용).
            ResourceDatabasePopulator p = new ResourceDatabasePopulator(new ClassPathResource("admin-schema-h2.sql"));
            p.setSqlScriptEncoding("UTF-8");
            p.execute(ds);
        }
        log.info("[AdminDb] 수용자 사진 매핑 대상 — {} · schema={} · 풀 최대 {}{}", a.url(), schema,
                ds.getMaximumPoolSize(), h2 ? " (로컬 H2 — 테이블 자동 생성)" : "");
    }

    /** {@code 스키마.테이블}. */
    public String table(String name) {
        return schema.isEmpty() ? name : schema + "." + name;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /**
     * 짧은 트랜잭션 하나 — {@code work} 가 끝나면 커밋하고, 예외(Error 포함)면 롤백한다. 어느 쪽이든 이 메서드가
     * 돌아오기 전에 커넥션은 풀에 반납된다 — 트랜잭션이 이 메서드 밖으로 새지 않는다.
     */
    public <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    public boolean isH2() {
        return h2;
    }

    /** PostgreSQL 이면 매핑을 {@code INSERT … ON CONFLICT} 한 문장으로 한다. */
    public boolean isPostgres() {
        return url.startsWith("jdbc:postgresql:");
    }

    public String url() {
        return url;
    }

    /**
     * 연결 · 테이블 확인 — 화면과 상태 API 가 쓴다. 실패해도 예외 대신 사유를 싣는다.
     * 매핑 테이블이 없으면 DDL({@code db/admin/V1__tb_src_inmate_photo.sql}) 적용이 필요하다는 뜻이다.
     */
    public Map<String, Object> probe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("schema", schema);
        m.put("h2", h2);
        try (Connection c = ds.getConnection()) {
            m.put("connected", true);
            m.put("product", c.getMetaData().getDatabaseProductName());
            m.put("photoTable", exists(PHOTO_TABLE));
            m.put("inmateTable", exists(INMATE_TABLE));
        } catch (Exception e) {
            m.put("connected", false);
            m.put("error", rootMessage(e));
        }
        return m;
    }

    private boolean exists(String t) {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM " + table(t) + " WHERE 1 = 0", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String s = c.getMessage();
        return c.getClass().getSimpleName() + ": " + (s == null ? "" : s.replaceAll("\\s+", " ").trim());
    }

    @Override
    public void destroy() {
        ds.close();
    }
}
