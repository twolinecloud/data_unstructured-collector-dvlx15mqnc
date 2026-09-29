package egovframework.unstructured.collector.voice.source;

import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceTarget;

import java.util.List;

/**
 * 보라미에서 수집 대상을 골라 온다.
 *
 * <p><b>구현이 셋인 이유</b>: 연계 방식이 아직 확정되지 않았다.
 * 2026-08-07 협의로 <b>원장 DB 직접 접근은 금지</b>되고 인터페이스 테이블/뷰 경유가 확정됐지만,
 * 그 I/F 테이블의 실체는 아직 정해지지 않았다(계획서 Q15). 그래서 조회 주체를 인터페이스로
 * 끊어 두고 MOCK → DIRECT_JDBC → ESB_HTTP2DB 순으로 갈아 끼운다.</p>
 */
public interface BoramiSourceClient {

    /**
     * 접견 녹음 대상을 찾는다.
     *
     * <p>4단 조인이다 — 특이수용자({@code TB_IMSC_PTPR_DT}) → 녹취파일내역({@code TB_RERD_TFIN_DS})
     * → 공통파일기본({@code TB_SMSM_CMFI_BS}) → {@code XVARM.ASYSCONTENTELEMENT}.</p>
     *
     * @param window     시간창
     * @param speclCodes 특별관리구분코드 목록 — 조직(0)·마약(1)·관심(2)·엄격(3)·일일중점(5)
     * @param limit      상한
     */
    List<VoiceTarget> findMeetTargets(BatchWindow window, List<String> speclCodes, int limit);

    /**
     * 전화 녹음 대상을 찾는다. 단일 테이블({@code TB_IMPH_UCDR_DS}) 조회다.
     *
     * <p>{@code TELP_PTCR_PRSR_YN}(전화특이수용자여부) 플래그가 이 테이블에 이미 있어,
     * 특이수용자 조인 없이 거를 수 있는 지름길이 존재한다. 두 방식의 결과 건수가 같은지는
     * 실연동 때 대사해야 한다(계획서 4.2).</p>
     */
    List<VoiceTarget> findPhoneTargets(BatchWindow window, List<String> speclCodes, int limit);

    /**
     * 접견 대상 — <b>{@code offset} 행을 건너뛴 다음 페이지</b>. 정렬은 {@code CRT_DT, 키} 오름차순으로 고정이다.
     *
     * <p>창이 넓으면(주기배치 당일 전체 · [바로 실행] 30일) 앞쪽 행이 이미 처리된 건으로 상한을 다 채워,
     * 뒤쪽의 미처리 건이 영영 조회되지 않는다. 호출 측이 페이지를 넘겨 가며 미처리 건을 모은다.</p>
     *
     * <p>페이지를 지원하지 않는 구현은 첫 페이지만 돌려준다 — 종전과 같은 동작이다.</p>
     */
    default List<VoiceTarget> findMeetTargets(BatchWindow window, List<String> speclCodes, int limit, int offset) {
        return offset == 0 ? findMeetTargets(window, speclCodes, limit) : List.of();
    }

    /** 전화 대상 — 다음 페이지. {@link #findMeetTargets(BatchWindow, List, int, int)} 와 같은 규칙. */
    default List<VoiceTarget> findPhoneTargets(BatchWindow window, List<String> speclCodes, int limit, int offset) {
        return offset == 0 ? findPhoneTargets(window, speclCodes, limit) : List.of();
    }

    /** 이 구현이 어떤 모드인지(진단·응답 표기용). */
    String mode();
}
