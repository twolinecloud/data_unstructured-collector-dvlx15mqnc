package egovframework.unstructured.collector.voice.source;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.voice.mapper.BoramiVoiceMapper;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DB 직결 조회 — 개발계 Mock 보라미(H2) 또는 포트포워딩한 실제 {@code borami-db} 를 본다.
 *
 * <p><b>운영에서는 쓰지 않는다.</b> 2026-08-07 협의로 원장 DB 직접 접근은 금지되어 있고
 * 인터페이스 테이블/뷰 경유가 확정 사항이다. 이 구현은 ESB 연계가 열리기 전까지
 * 4단 조인 SQL 자체를 검증하기 위한 것이다.</p>
 *
 * <p><b>테이블명과 플래그 값을 설정에서 받는다</b> — 실제 보라미는 스키마가 나뉘어 있고
 * ({@code im.} / {@code re.} / {@code sm.}) 플래그 값 도메인도 문서와 달랐다(2026-09-12 확인).
 * 그 차이를 SQL 이 아니라 설정으로 흡수한다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class JdbcBoramiSourceClient implements BoramiSourceClient {

    private final BoramiVoiceMapper mapper;
    private final BoramiTableNames tables;
    private final VoiceProperties props;

    @Override
    public List<VoiceTarget> findMeetTargets(BatchWindow window, List<String> speclCodes, int limit) {
        return findMeetTargets(window, speclCodes, limit, 0);
    }

    @Override
    public List<VoiceTarget> findMeetTargets(BatchWindow window, List<String> speclCodes, int limit, int offset) {
        try {
            List<VoiceTarget> rows = mapper.selectMeetTargets(params(window, speclCodes, limit, offset));
            log.info("[Source:JDBC] 접견 대상 {}건 (window={}{})", rows.size(), window,
                    offset > 0 ? ", offset=" + offset : "");
            return rows;
        } catch (Exception e) {
            throw explain("접견", e);
        }
    }

    @Override
    public List<VoiceTarget> findPhoneTargets(BatchWindow window, List<String> speclCodes, int limit) {
        return findPhoneTargets(window, speclCodes, limit, 0);
    }

    @Override
    public List<VoiceTarget> findPhoneTargets(BatchWindow window, List<String> speclCodes, int limit, int offset) {
        try {
            List<VoiceTarget> rows = mapper.selectPhoneTargets(params(window, speclCodes, limit, offset));
            log.info("[Source:JDBC] 전화 대상 {}건 (window={}{})", rows.size(), window,
                    offset > 0 ? ", offset=" + offset : "");
            return rows;
        } catch (Exception e) {
            throw explain("전화", e);
        }
    }

    /**
     * SQL 오류에 <b>지금 어떤 테이블을 보고 있는지</b>를 붙여 다시 던진다.
     *
     * <p>원본 예외만 올라가면 화면에 "Internal Server Error" 만 남는다. 이 구간에서 실패하는
     * 이유는 거의 항상 <b>스키마·테이블 설정이 실제와 다른 것</b>이라, 조립된 테이블명을 함께
     * 보여 주면 바로 고칠 수 있다. 실제로 2026-09-12 borami-db 에는 공통파일기본과 XVARM 이
     * 없어 접견 조회가 실패했고, 그때 원인을 찾는 데 로그를 뒤져야 했다.</p>
     */
    private IllegalStateException explain(String what, Exception e) {
        String msg = """
                %s 조회 실패 — 스키마·테이블 설정이 실제 DB 와 맞는지 확인할 것.
                  현재 대상: %s
                  설정 키  : voice.source.schema.{imsc,rerd,smsm,xvarm}
                  참고     : 2026-09-12 기준 borami-db 에는 공통파일기본(TB_SMSM_CMFI_BS)과
                             XVARM(ASYSCONTENTELEMENT)이 없어 접견은 실DB 로 조회할 수 없다.
                             접견은 voice.source.mode=MOCK 으로 두고 전화만 DIRECT_JDBC 로 본다.
                  원인     : %s"""
                .formatted(what, tables.describe(), rootMessage(e));
        log.error("[Source:JDBC] {}", msg);
        return new IllegalStateException(msg, e);
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m.replaceAll("\\s+", " ").trim();
    }

    /**
     * 지름길 방식({@code TELP_PTCR_PRSR_YN})의 건수.
     *
     * <p>정공법과 결과가 같아야 이 플래그를 믿을 수 있다. 2026-09-12 실DB 에서는 이 컬럼이
     * 전부 {@code '0'} 이라 {@code voice.source.flag.ptcr-yes} 를 맞춰 주지 않으면 늘 0건이다.</p>
     */
    public int countByFlagShortcut(BatchWindow window, int limit) {
        return mapper.selectPhoneTargetsByFlag(
                params(window, props.batch().speclMngSeCd(), limit, 0)).size();
    }

    /**
     * 특이수용자 테이블에 실제로 접근되는지 확인한다(진단).
     * 스키마 설정이 틀리면 조회 전에 여기서 먼저 드러난다.
     */
    public int probe() {
        BatchWindow now = BatchWindow.manual(
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        Integer n = mapper.probeImsc(params(now, props.batch().speclMngSeCd(), 1, 0));
        return n == null ? -1 : n;
    }

    /** 현재 조립된 테이블명(진단 표기용). */
    public String tableNames() {
        return tables.describe();
    }

    private BoramiQueryParams params(BatchWindow window, List<String> speclCodes, int limit, int offset) {
        VoiceProperties.Flag f = props.source().flag();
        return new BoramiQueryParams(
                tables.imscPtprDt(),
                tables.imphUcdrDs(),
                tables.rerdTfinDs(),
                tables.smsmCmfiBs(),
                tables.asysContentElement(),
                f.recordedYes(),
                f.ptcrYes(),
                f.notDeleted(),
                f.encrypted(),
                window.from(),
                window.to(),
                speclCodes,
                limit,
                Math.max(0, offset));
    }

    @Override
    public String mode() {
        return "DIRECT_JDBC";
    }
}
