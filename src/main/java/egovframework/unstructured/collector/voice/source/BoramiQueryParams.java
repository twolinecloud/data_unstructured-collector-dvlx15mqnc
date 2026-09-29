package egovframework.unstructured.collector.voice.source;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 보라미 조회 SQL 에 넘기는 값 묶음.
 *
 * <p>테이블명({@code t*})은 MyBatis {@code ${}} 로 <b>치환</b>되고, 나머지는 {@code #{}} 로
 * <b>바인딩</b>된다. 테이블명만 치환인 이유는 SQL 문법상 식별자를 파라미터로 바인딩할 수 없어서다
 * — 그래서 {@link BoramiTableNames} 가 형식을 검증한 값만 여기 들어온다.</p>
 *
 * @param tImsc            특이수용자상세 테이블명 (스키마 포함 가능)
 * @param tImph            사용자통화내역 — 특이수용자와 같은 {@code im} 스키마지만 테이블명이 다르다
 * @param tRerd            녹취파일내역
 * @param tSmsm            공통파일기본
 * @param tXvarm           XVARM 콘텐츠 메타
 * @param flagRecordedYes  {@code TELP_PCALL_RECRD_YN} 의 '녹음됨' 값
 * @param flagPtcrYes      {@code TELP_PTCR_PRSR_YN} 의 '특이수용자' 값
 * @param flagNotDeleted   {@code DEL_YN} 계열의 '삭제 안 됨' 값
 * @param flagEncrypted    {@code CMMN_FILE_ENC_YN} 의 '암호화됨' 값
 * @param from             시간창 시작(포함)
 * @param to               시간창 종료(미포함)
 * @param speclCodes       특별관리구분코드 목록
 * @param limit            상한
 */
public record BoramiQueryParams(
        String tImsc,
        String tImph,
        String tRerd,
        String tSmsm,
        String tXvarm,
        String flagRecordedYes,
        String flagPtcrYes,
        String flagNotDeleted,
        String flagEncrypted,
        LocalDateTime from,
        LocalDateTime to,
        List<String> speclCodes,
        int limit,
        int offset
) {
}
