package egovframework.unstructured.collector.voice.batch;

import com.fasterxml.jackson.annotation.JsonProperty;
import egovframework.unstructured.collector.common.model.FileProcOutcome;

import java.util.List;
import java.util.Map;

/**
 * 배치 1회 실행 결과.
 *
 * @param execId              로그 컬렉터가 채번한 EXEC_ID. 미연동이면 로컬 임시 ID
 * @param execIdFromCollector EXEC_ID 를 컬렉터가 채번했는가(T1 이 실제로 열렸는가). false 면 이력이 남지 않은 것이다
 * @param window              처리한 시간창
 * @param targetCnt           조회된 대상 수
 * @param successCnt          STT·제논 전송까지 성공한 수
 * @param failCnt             실패 수
 * @param skippedCnt          이미 처리되어 건너뛴 수(멱등)
 * @param elapsedMs           소요 시간
 * @param zenon               STT 결과를 보낸 곳 — {@code mode}(MOCK·REST) · {@code endpoint} · {@code sent}(보낸 건수).
 *                            결과는 PV 에 남기지 않는다(2026-10-01, 예전 {@code outputDirs} 자리)
 * @param steps               로그 컬렉터에 남긴 T2 단계 기록(COLLECT · ANALYZE · SEND)
 * @param outcomes            파일별 결과(= T4 행)
 * @param canceled            사용자가 도중에 멈췄는가 — 남은 건은 {@code 건너뜀(중단됨)} 으로 들어 있다
 */
public record VoiceBatchResult(
        String execId,
        boolean execIdFromCollector,
        String window,
        int targetCnt,
        int successCnt,
        int failCnt,
        int skippedCnt,
        long elapsedMs,
        Map<String, String> zenon,
        List<StepLog> steps,
        List<FileProcOutcome> outcomes,
        boolean canceled
) {

    /** 중단이 아닌 보통의 배치 — 기존 호출부를 그대로 둔다. */
    public VoiceBatchResult(String execId, boolean execIdFromCollector, String window,
                            int targetCnt, int successCnt, int failCnt, int skippedCnt,
                            long elapsedMs, Map<String, String> zenon,
                            List<StepLog> steps, List<FileProcOutcome> outcomes) {
        this(execId, execIdFromCollector, window, targetCnt, successCnt, failCnt, skippedCnt,
                elapsedMs, zenon, steps, outcomes, false);
    }

    /**
     * T2 단계 1행의 요약 — 컬렉터에 보낸 값 그대로다.
     *
     * @param stepTypeCd C05 — COLLECT / ANALYZE / SEND
     * @param stepLogId  컬렉터가 채번한 STEP_LOG_ID. 미연동·실패면 null
     * @param stepStsCd  C04 — SUCCESS / PARTIAL / FAIL
     * @param inCnt      들어온 건수
     * @param outCnt     다음 단계로 넘긴 건수
     * @param errCnt     이 단계에서 실패한 건수
     * @param elapsedSec 소요(초)
     * @param logged     컬렉터에 실제로 적재됐는가
     */
    public record StepLog(String stepTypeCd, String stepLogId, String stepStsCd,
                          long inCnt, long outCnt, long errCnt, int elapsedSec, boolean logged) {}

    /**
     * 배치 상태 코드(C04) — 하나도 실패하지 않았으면 SUCCESS, 일부만 성공이면 PARTIAL.
     *
     * <p><b>중단은 성공이 아니다.</b> 사용자가 멈춘 배치는 남은 건이 실패가 아니라 건너뜀이라
     * {@code failCnt==0} 이 되어 SUCCESS 로 떨어졌다 — 160건 중 43건만 처리하고 멈춘 배치가
     * 이력에 초록색 '성공' 으로 남는다는 뜻이다. C04 에 있는 {@code CANCELED} 를 쓴다.</p>
     *
     * <p>{@code @JsonProperty} 가 필요한 이유: record 는 <b>컴포넌트만</b> 자동 직렬화된다.
     * 파생 값을 주는 이런 메서드는 {@code get} 접두어도 없어 Jackson 이 그냥 건너뛴다 —
     * 응답에서 조용히 빠져 화면에 빈칸으로 나온다.</p>
     */
    @JsonProperty("execStsCd")
    public String execStsCd() {
        if (canceled) {
            return "CANCELED";
        }
        if (failCnt == 0) {
            return "SUCCESS";
        }
        return successCnt > 0 ? "PARTIAL" : "FAIL";
    }

    /** 배치 대표 오류 한 줄(T1.ERR_MSG 후보) — 실패 건의 사유 중 가장 많은 것. 실패가 없으면 null. */
    @JsonProperty("errMsg")
    public String errMsg() {
        if (failCnt == 0 || outcomes == null) {
            return null;
        }
        java.util.Map<String, Integer> freq = new java.util.LinkedHashMap<>();
        for (FileProcOutcome o : outcomes) {
            if (o.isFail() && o.errMsg() != null) {
                freq.merge(o.errMsg(), 1, Integer::sum);
            }
        }
        String top = null;
        int max = 0;
        for (var e : freq.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                top = e.getKey();
            }
        }
        if (top == null) {
            return null;
        }
        return freq.size() > 1 ? top + " (외 " + (failCnt - max) + "건 다른 사유)" : top;
    }

    public String summary() {
        return "execId=%s %s 대상%d 성공%d 실패%d 건너뜀%d (%.1f초) · 제논 %s"
                .formatted(execId, window, targetCnt, successCnt, failCnt, skippedCnt,
                        elapsedMs / 1000.0, zenon == null ? "-" : zenon);
    }
}
