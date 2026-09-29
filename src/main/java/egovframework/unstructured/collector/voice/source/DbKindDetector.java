package egovframework.unstructured.collector.voice.source;

import egovframework.unstructured.collector.common.config.BoramiDbRouter;
import egovframework.unstructured.collector.common.config.BoramiDbRouter.DbTarget;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 지금 조회 모드가 가리키는 DB 가 무엇인지 — H2(로컬 Mock 보라미) / PostgreSQL(개발계 borami-db) / Oracle(운영 보라미).
 *
 * <p>같은 코드가 세 DB 를 상대한다. 스키마를 붙일지(H2 는 평평), 누락 테이블을 만들어도 되는지
 * (개발계 PostgreSQL 만), 시딩을 어디에 하는지가 전부 여기에 달려 있어 한곳에서 판정한다.
 * 종류는 <b>커넥션을 열지 않고</b> 라우터의 현재 대상과 설정 URL 로 정한다 — 개발계 DB 가 닿지 않아도
 * 화면은 "지금 개발계 DB 를 보고 있다(연결 안 됨)" 를 보여줘야 한다. 실제 연결 여부는 {@link #probe()} 로 따로 본다.</p>
 */
@Log4j2
@Component
public class DbKindDetector {

    public enum DbKind { H2, POSTGRESQL, ORACLE, UNKNOWN }

    private final BoramiDbRouter router;
    private final VoiceProperties props;
    private final DbKind fixedKind;

    /** 대상별 마지막 연결 확인 결과 — 화면 표시용. */
    private final Map<DbTarget, Map<String, Object>> lastProbe = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public DbKindDetector(BoramiDbRouter router, VoiceProperties props) {
        this.router = router;
        this.props = props;
        this.fixedKind = null;
    }

    private DbKindDetector(DbKind fixed) {
        this.router = null;
        this.props = null;
        this.fixedKind = fixed;
    }

    /** 테스트용 — DB 를 열지 않고 종류를 고정한다. */
    public static DbKindDetector fixed(DbKind kind) {
        return new DbKindDetector(kind);
    }

    /** 지금 조회 모드가 가리키는 대상. */
    public DbTarget target() {
        if (fixedKind != null) {
            return fixedKind == DbKind.H2 ? DbTarget.LOCAL_H2 : DbTarget.DIRECT;
        }
        return router.currentTarget();
    }

    public DbKind kind() {
        if (fixedKind != null) {
            return fixedKind;
        }
        if (router.currentTarget() == DbTarget.LOCAL_H2) {
            return DbKind.H2;
        }
        return kindOf(props.source().directDb().url());
    }

    static DbKind kindOf(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        if (lower.startsWith("jdbc:h2:")) return DbKind.H2;
        if (lower.startsWith("jdbc:postgresql:")) return DbKind.POSTGRESQL;
        if (lower.startsWith("jdbc:oracle:")) return DbKind.ORACLE;
        return DbKind.UNKNOWN;
    }

    /** 현재 대상의 JDBC URL(계정 없음) — 화면 표기용. */
    public String url() {
        if (fixedKind != null) {
            return "";
        }
        String u = router.currentTarget() == DbTarget.LOCAL_H2
                ? props.source().localH2().url() : props.source().directDb().url();
        return u == null ? "" : u.replaceAll("(?i)(password|user)=[^;&]*", "$1=***");
    }

    public boolean isH2() {
        return kind() == DbKind.H2;
    }

    public boolean isPostgres() {
        return kind() == DbKind.POSTGRESQL;
    }

    /** 화면에 보여 줄 한 줄 — "H2 (로컬 Mock 보라미)" / "PostgreSQL (개발계 borami-db)". */
    public String label() {
        return switch (kind()) {
            case H2 -> "H2 (로컬 Mock 보라미)";
            case POSTGRESQL -> "PostgreSQL (개발계 borami-db)";
            case ORACLE -> "Oracle (실 보라미)";
            default -> "알 수 없는 DB";
        };
    }

    /**
     * 현재 대상에 실제로 붙어 본다 — 시뮬레이터 [DB 연결 확인] 과 드롭다운 전환 직후.
     * 붙지 못하면 사유를 싣는다(포트포워딩 없음·계정 오류 등). 결과는 대상별로 기억해 상태 조회에 싣는다.
     */
    public Map<String, Object> probe() {
        Map<String, Object> out = new LinkedHashMap<>();
        DbTarget t = target();
        out.put("target", t.name());
        out.put("kind", kind().name());
        out.put("label", label());
        out.put("url", url());
        if (fixedKind != null) {
            out.put("reachable", true);
            return out;
        }
        long t0 = System.currentTimeMillis();
        DataSource ds = router.dataSourceOf(t);
        try (Connection c = ds.getConnection()) {
            out.put("reachable", c.isValid(2));
            out.put("product", c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion());
        } catch (Exception e) {
            out.put("reachable", false);
            out.put("error", rootMessage(e));
            log.warn("[DB] 연결 확인 실패 — {} ({})", t, rootMessage(e));
        }
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        out.put("checkedAt", java.time.LocalDateTime.now().withNano(0).toString());
        lastProbe.put(t, out);
        return out;
    }

    /** 마지막 연결 확인 결과(없으면 null) — 상태 조회가 커넥션을 열지 않게 하려는 캐시. */
    public Map<String, Object> lastProbe() {
        return fixedKind != null ? null : lastProbe.get(router.currentTarget());
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m.replaceAll("\\s+", " ").trim();
    }
}
