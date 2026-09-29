package egovframework.unstructured.collector.voice.mapper;

import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.voice.source.BoramiQueryParams;
import org.apache.ibatis.annotations.Param;
import org.egovframe.rte.psl.dataaccess.mapper.EgovMapper;

import java.util.List;

/**
 * 보라미 음성 대상 조회 매퍼.
 *
 * <p><b>실제 DB 는 Oracle(운영) / PostgreSQL(개발 borami-db), Mock 은 H2</b> 다.
 * SQL 은 세 쪽에서 모두 도는 표준 문법만 쓴다 — {@code FETCH FIRST n ROWS ONLY},
 * 명시적 JOIN, 파라미터 바인딩. Oracle 전용 함수({@code NVL}·{@code ROWNUM}·{@code SYSDATE})는 쓰지 않는다.</p>
 *
 * <p><b>테이블명과 플래그 값을 밖에서 받는다</b>(2026-09-12 실DB 확인 반영).</p>
 * <ul>
 *   <li>실제 보라미는 테이블이 스키마로 나뉘어 있다 — {@code im.}, {@code re.}, {@code sm.}</li>
 *   <li>플래그 값 도메인이 문서와 다르다 — {@code TELP_PTCR_PRSR_YN} 은 {@code 'Y'} 가 아니라 {@code '0'} 이었다</li>
 * </ul>
 * <p>둘 다 하드코딩하면 실연동에서 <b>에러 없이 0건</b>이 나와 알아채기 어렵다.</p>
 */
@EgovMapper
public interface BoramiVoiceMapper {

    /** 접견 녹음 대상 — 특이수용자 → 녹취파일내역 → 공통파일기본 → XVARM 4단 조인. */
    List<VoiceTarget> selectMeetTargets(@Param("p") BoramiQueryParams p);

    /** 전화 녹음 대상 — 사용자통화내역 + 특이수용자 조인(정공법). */
    List<VoiceTarget> selectPhoneTargets(@Param("p") BoramiQueryParams p);

    /**
     * 전화 대상을 {@code TELP_PTCR_PRSR_YN} 플래그만으로 고른다(지름길).
     *
     * <p>특이수용자 테이블과 조인하지 않아 가볍다. 다만 <b>2026-09-12 실DB 에서는 이 컬럼 값이
     * 전부 {@code '0'}</b> 이었다 — 값 도메인이 확정되기 전에는 결과를 믿을 수 없으므로
     * 반드시 {@link #selectPhoneTargets} 와 건수를 대사해야 한다.</p>
     */
    List<VoiceTarget> selectPhoneTargetsByFlag(@Param("p") BoramiQueryParams p);

    /** 조회 전 연결·테이블 접근을 확인한다(진단용). 행이 없어도 예외가 없으면 접근 가능. */
    Integer probeImsc(@Param("p") BoramiQueryParams p);
}
