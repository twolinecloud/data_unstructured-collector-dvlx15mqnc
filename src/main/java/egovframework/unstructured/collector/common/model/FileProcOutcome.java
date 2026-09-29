package egovframework.unstructured.collector.common.model;

/**
 * 파일 1건의 처리 결과 — <b>T4({@code TB_FILE_PROC_LOG}) 1행</b>에 대응한다.
 *
 * <p>정합성 규칙이 {@code TB_BATCH_EXEC_LOG.SUCCESS_CNT == Σ(T3·T4·T5)} 라서,
 * <b>파일 1건 = T4 1행</b>을 어기면 배치 전체의 대사가 깨진다.</p>
 *
 * @param target     대상
 * @param status     결과
 * @param errMsg     실패 사유(성공 시 null). PII 가 섞이지 않도록 원문을 넣지 않는다.
 * @param failedStep 실패한 단계(C05) — {@code COLLECT}(파일 확보·복호화) / {@code ANALYZE}(STT·출력 저장). 성공·건너뜀이면 null.
 *                   T2 단계별 건수(in/out/err)를 나누는 근거다.
 * @param fileSize   처리한 파일 크기(byte)
 * @param sttChars   STT 결과 글자 수
 * @param sttPath    STT 텍스트가 저장된 경로({@code {output}/{execId}/…txt}). 성공 건만 값이 있다.
 * @param elapsedMs  소요 시간
 */
public record FileProcOutcome(
        VoiceTarget target,
        ProcStatus status,
        String errMsg,
        String failedStep,
        long fileSize,
        int sttChars,
        String sttPath,
        long elapsedMs
) {

    public static final String STEP_COLLECT = "COLLECT";
    public static final String STEP_ANALYZE = "ANALYZE";
    /**
     * 출력 저장 — STT 결과(.txt · .json)를 배치 폴더 {@code {output}/{execId}/} 에 내보낸다.
     *
     * <p>비정형 T2 체인(COLLECT 1 · ANALYZE 2 · DEIDENT 3 · SEND 4)의 마지막 칸이다.
     * <b>이 서비스는 STT 텍스트를 외부 서비스로 전송하지 않는다</b> — 다음 단계(제논)가 이 폴더에서
     * 집어 가는 것이 인계 방식이므로, 폴더에 내보내는 이 구간을 SEND 로 남긴다. 예전에는 ANALYZE 에
     * 묶여 있어 "STT 는 됐는데 저장에서 깨진" 건과 "STT 자체가 깨진" 건을 T2 에서 구분할 수 없었다.</p>
     */
    public static final String STEP_SEND = "SEND";
    /**
     * 비식별 — STT 와 최종 저장 사이. 비식별 커넥터가 {@code deidentEnabled} 에 따라 비식별을 수행하거나
     * 단순 전달(SEND)한다. 어느 쪽이든 이 단계를 거친다 — T2 에 DEIDENT 행이 늘 남고 T5 가 파일마다 남는다.
     */
    public static final String STEP_DEIDENT = "DEIDENT";

    public static FileProcOutcome success(VoiceTarget t, long size, int chars, String sttPath, long ms) {
        return new FileProcOutcome(t, ProcStatus.SUCCESS, null, null, size, chars, sttPath, ms);
    }

    /** 실패 — 어느 단계에서 났는지 함께 남긴다. */
    public static FileProcOutcome fail(VoiceTarget t, String step, String msg, long ms) {
        return new FileProcOutcome(t, ProcStatus.FAIL, msg, step, 0L, 0, null, ms);
    }

    public static FileProcOutcome skipped(VoiceTarget t, String reason) {
        return new FileProcOutcome(t, ProcStatus.SKIPPED, reason, null, 0L, 0, null, 0L);
    }

    public boolean isSuccess() {
        return status == ProcStatus.SUCCESS;
    }

    public boolean isFail() {
        return status == ProcStatus.FAIL;
    }

    public boolean failedAt(String step) {
        return isFail() && step.equals(failedStep);
    }
}
