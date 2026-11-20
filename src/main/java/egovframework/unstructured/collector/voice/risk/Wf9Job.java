package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 스풀 작업 하나 — {@code {spool}/{jobId}/job.json}. 같은 폴더의 {@code audio.dat} 가 음성 사본이다.
 *
 * <p>교정번호(CORR_NO)는 적재 키라 담지만 로그에는 쓰지 않는다({@link #shortId()} 만). 사본과 같은 PVC · 권한이다.</p>
 *
 * @param jobId          폴더 이름 — 같은 녹음은 같은 이름(대기 중 중복 방지)
 * @param recFileId      녹음 파일 ID(T4 · T5 REC_FILE_ID) — 접견 TARE_FILE_NO · 전화 VRFC_ESTL_ID
 * @param corrNo         교정번호 — 로그 컬렉터 적재 키
 * @param kind           MEET · PHONE
 * @param occurredAt     접견일자 / 통화 시작(KST · offset 없는 ISO)
 * @param execId         스풀에 넣은 배치(T1) — 추적용 · 제논 실호출 정상 응답 뒤 이 배치의 폐기 상태를 폐기 완료로 적는다
 * @param contactId      제논에 보내는 무작위 대체 ID(실제 키를 내보내지 않는다)
 * @param audioFile      사본 파일 이름
 * @param cutSec         앞부분만 담았으면 그 길이(초 · D9). 통째면 null
 * @param createdAtMs    스풀에 넣은 시각(epoch ms)
 * @param attempts       실패한 횟수
 * @param nextAttemptAtMs 다음 시도 가능 시각(epoch ms) — 0 이면 바로
 * @param lastError      마지막 실패 요약(단계 · 코드만)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Wf9Job(String jobId, String recFileId, String corrNo, String kind, String occurredAt, String execId,
                     String contactId, String audioFile, Double cutSec, long createdAtMs, int attempts,
                     long nextAttemptAtMs, String lastError) {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 실패 한 번을 적은 사본. */
    public Wf9Job failed(long nextAttemptAtMs, String lastError) {
        return new Wf9Job(jobId, recFileId, corrNo, kind, occurredAt, execId, contactId, audioFile, cutSec, createdAtMs,
                attempts + 1, nextAttemptAtMs, lastError);
    }

    /** 제논 {@code contact_type} — 접견 · 전화. */
    @JsonIgnore
    public String contactType() {
        return "PHONE".equals(kind) ? "전화" : "접견";
    }

    /** 로그 컬렉터 {@code contactSeCd} — VISIT 접견 · CALL 전화. */
    @JsonIgnore
    public String contactSeCd() {
        return "PHONE".equals(kind) ? "CALL" : "VISIT";
    }

    @JsonIgnore
    public LocalDateTime occurredLocal() {
        return LocalDateTime.parse(occurredAt);
    }

    /** 로그용 — 배치 로그와 같은 모양({@code meet-<recFileId>}). 교정번호는 넣지 않는다. */
    @JsonIgnore
    public String shortId() {
        return (kind == null ? "?" : kind.toLowerCase()) + "-" + recFileId;
    }
}
