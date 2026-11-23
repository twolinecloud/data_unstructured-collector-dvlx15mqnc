package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.voice.stt.genon.AudioValidationException;
import egovframework.unstructured.collector.voice.stt.genon.GenonCall;
import egovframework.unstructured.collector.voice.stt.genon.GenonSttClient;
import egovframework.unstructured.collector.voice.stt.genon.GenonSttException;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * WF9 작업 한 건 — ① 제논 전처리 → ② 커넥터 비식별 → ③ 제논 WF9 → ④ 파싱 · 행 변환 → ⑤ 로그 컬렉터 통째 교체.
 *
 * <p>음성 · 전사 · 가린 문서 · 사전 · WF9 답 · 되돌린 내용은 <b>이 호출의 메모리에만</b> 있다(로그 · 디스크에 남기지 않는다).
 * 다시 하면 ①부터 다시 한다 — 사전을 디스크에 두지 않기 위해서다(D6).</p>
 *
 * <p>T1 ~ T5 는 건드리지 않는다(D7). 예전엔 제논 비저장 엔드포인트가 정상 응답하면 그 배치 T1 폐기 상태를 폐기 완료로 적었으나
 * 화면에서 폐기 표시가 빠져 기록도 뺐다(2026-11-23).</p>
 */
@Log4j2
@Component
public class RiskWf9Pipeline {

    /** 한 건의 결과 — 집계만. */
    public record Outcome(int findings, int reviews, int inserted, int skipped, int tokens, long elapsedMs) {}

    private final GenonSttClient genon;
    private final SpeechResultsDeidClient deid;
    private final GenonPortalClient portal;
    private final LogCollectorClient logCollector;
    private final RiskWf9Properties props;
    private final ObjectMapper om;

    public RiskWf9Pipeline(GenonSttClient genon, SpeechResultsDeidClient deid, GenonPortalClient portal,
                           LogCollectorClient logCollector, RiskWf9Properties props, ObjectMapper objectMapper) {
        this.genon = genon;
        this.deid = deid;
        this.portal = portal;
        this.logCollector = logCollector;
        this.props = props;
        this.om = objectMapper;
    }

    /** 끝까지 갈 수 없는 까닭 — 갈 수 있으면 null. 막혀 있으면 스풀에 넣지 않고 작업자도 돌지 않는다(재시도를 태우지 않게). */
    public String blocker() {
        String why = deid.blocker();
        if (why != null) {
            return why;
        }
        if (!logCollector.isEnabled()) {
            return "로그 컬렉터 미연동(log-collector.enabled · base-url)";
        }
        return portal.blocker();
    }

    /**
     * @throws Wf9Failure 단계 · 다시 할지
     */
    public Outcome run(Wf9Job job, Path audio) {
        long t0 = System.currentTimeMillis();

        // ① 제논 음성 전처리 — STT 와 같은 '동시 1건' 차례를 기다린다(GenonSttClient 의 공정 세마포어)
        GenonCall call = process(job, audio);
        JsonNode processed = call.body();

        // ② 커넥터 비식별 — 잔존이 있으면 422 로 끊긴다(제논에 보내지 않는다)
        SpeechResultsDeidClient.Result masked = deid.deidentify(job.recFileId(), job.execId(), processed);

        // ③ 제논 WF9
        String message;
        try {
            message = om.writeValueAsString(masked.document());
        } catch (Exception e) {
            throw Wf9Failure.permanent(Wf9Failure.Stage.PORTAL, "가린 문서를 글로 바꾸지 못함 — " + e.getClass().getSimpleName());
        }
        String answer = portal.wf9(message);   // HTTP 200 + SSE end 일 때만 돌아온다(그 밖은 Wf9Failure)

        // ④ {findings, reviews} → 행(내용만 되돌린다 · 위험 분류는 받은 값 그대로 — 코드표로는 로그 컬렉터가 맞춘다)
        Wf9Signals.Parsed parsed = Wf9Signals.parse(om, answer);
        Wf9Signals.Rows rows = Wf9Signals.toRows(parsed, masked.mapping(), props.contentFields(), props.riskClassFields());
        if (rows.offSpec() > 0) {
            // 항목 규격(2026-11-20 제논 실응답 기준 — content · risk_type)과 다른 답. 값은 싣지 않고 필드 이름만 — 응답 모양이 바뀌면 설정으로 맞춘다
            log.warn("[WF9] {} — 항목 규격 밖 {}개(약속한 내용 필드 '{}' 없음) · 받은 필드 {} · 맞추려면 voice.risk-wf9.content-fields · risk-class-fields",
                    job.shortId(), rows.offSpec(), props.contentFields().isEmpty() ? "-" : props.contentFields().get(0),
                    rows.offSpecFields());
        }

        // ⑤ 로그 컬렉터 — 녹음 단위 통째 교체(0건이면 지우기만)
        int inserted;
        try {
            inserted = logCollector.replaceContactRiskSignals(job.recFileId(), new LogCollectorClient.ContactRiskReplaceReq(
                    job.corrNo(), job.contactSeCd(), job.occurredLocal(), job.execId(), null, rows.signals()));
        } catch (LogCollectorClient.RejectedException e) {
            throw Wf9Failure.permanent(Wf9Failure.Stage.STORE, e.getMessage());
        } catch (LogCollectorClient.UnavailableException e) {
            throw Wf9Failure.retry(Wf9Failure.Stage.STORE, e.getMessage());
        }
        return new Outcome(parsed.findings().size(), parsed.reviews().size(), inserted, rows.skipped(),
                masked.mapping().size(), System.currentTimeMillis() - t0);
    }

    private GenonCall process(Wf9Job job, Path audio) {
        GenonCall call;
        try {
            call = genon.processContactAudio(audio.toFile(), null, null, job.contactId(), job.contactType(),
                    job.occurredLocal().atZone(Wf9Job.KST).toInstant());
        } catch (AudioValidationException e) {
            throw Wf9Failure.permanent(Wf9Failure.Stage.PROCESS, "제논 사전 검증 위반 — " + e.check().violationText());
        } catch (GenonSttException e) {
            String msg = "제논 전처리 " + e.kind() + (e.httpStatus() > 0 ? " · HTTP " + e.httpStatus() : "")
                    + (e.errorCode() != null && e.errorCode().matches("[A-Za-z0-9_.:-]{1,40}") ? " · " + e.errorCode() : "");
            if (e.kind() == GenonSttException.Kind.REJECTED) {
                throw Wf9Failure.permanent(Wf9Failure.Stage.PROCESS, msg);
            }
            throw Wf9Failure.retry(Wf9Failure.Stage.PROCESS, msg);
        } catch (IllegalArgumentException e) {
            throw Wf9Failure.permanent(Wf9Failure.Stage.PROCESS, "제논 전처리 필드 오류(contact_id · contact_type · occurred_at)");
        }
        JsonNode body = call.body();
        if (body == null || !body.isObject() || !body.path("input").isObject()) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PROCESS, "제논 전처리 응답에 input 이 없다");
        }
        return call;
    }
}
