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
 * @param failedStep 실패한 단계(C05) — {@code COLLECT}(파일 확보·복호화) / {@code ANALYZE}(STT) / {@code SEND}(에이전트 커넥터 전송). 성공·건너뜀이면 null.
 *                   T2 단계별 건수(in/out/err)를 나누는 근거다.
 * @param fileSize   처리한 파일 크기(byte)
 * @param sttChars   STT 결과 글자 수
 * @param sttPath    STT 결과를 보낸 곳({@code agent-connector:…/{건ID}.json}). 성공 건만 값이 있다 — PV 에는 남기지 않는다.
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

    /** 수집 — {@link StepType#COLLECT} 의 코드. 문자열 상수로 두는 것은 단계 비교가 문자열(T2 · 화면 · 응답)이라서다. */
    public static final String STEP_COLLECT = "COLLECT";
    /** 정제/분석(STT) — {@link StepType#ANALYZE}. */
    public static final String STEP_ANALYZE = "ANALYZE";
    /**
     * 적재/전송 — STT 결과를 에이전트 커넥터 bypass API 로 보내고 그 건의 임시 파일을 지운다. {@link StepType#SEND}.
     *
     * <p>비정형 T2 체인(COLLECT 1 · ANALYZE 2 · SEND 3)의 마지막 칸이다. PV 에는 결과를 남기지 않는다.</p>
     */
    public static final String STEP_SEND = "SEND";

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

    /**
     * T4 에 남길 단계 — 실패면 실패한 단계, 성공이면 마지막까지 간 단계(SEND), 건너뜀이면 null(T4 에 남기지 않는다).
     */
    public StepType stepTypeForLog() {
        if (isFail()) {
            return StepType.parse(failedStep).orElse(StepType.COLLECT);
        }
        return isSuccess() ? StepType.SEND : null;
    }
}
