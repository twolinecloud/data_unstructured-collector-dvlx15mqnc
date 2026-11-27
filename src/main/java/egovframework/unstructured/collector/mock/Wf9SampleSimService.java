package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.util.InmatePidGenerator;
import egovframework.unstructured.collector.voice.risk.RiskWf9Properties;
import egovframework.unstructured.collector.voice.risk.Wf9SendGate;
import egovframework.unstructured.collector.voice.risk.Wf9Spool;
import egovframework.unstructured.collector.voice.transfer.VoiceDeidentClient;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * WF9 위험 대화 샘플(dev · local) — 이미 있는 배치(T1)에 <b>가상 위험 대화 한 건</b>을 얹어 실제 WF9 경로로 보낸다(2026-11-27).
 *
 * <p>MOCK STT 대본에는 위험 발언이 없어 WF9 가 FINDING 을 돌려주지 않는다. 화면('AI 접견 및 전화통화 중 발견된 주요 사항(최근 30일)')
 * 확인에는 특정 수용자(교정번호)의 FINDING 이 필요하므로, 지어낸 위험 대화를 본 배치와 <b>같은 길</b>로 태운다:
 * 커넥터 음성 비식별(실호출 · 그 배치 T5 한 행) → 가린 전사로 스풀 → 작업자 전송 확인 문(그 배치 T2 SEND SUCCESS) → 제논 WF9
 * → 로그 컬렉터 {@code tb_contact_risk_signal} 적재 → T1 폐기 완료. 수동 전송 배치면 [클라우드 전송] 전까지 WF9 를 부르지 않는다.</p>
 *
 * <p>녹음 ID 는 {@code SIMWF9-…} 이라 실제 녹음과 겹치지 않는다. 지우기 = {@link #remove} (그 녹음의 신호 행을 0건으로 통째 교체).
 * 대화 내용은 지어낸 것이며 실제 인물 · 번호가 없다.</p>
 */
@Log4j2
@Service
@Profile({"dev", "local"})
public class Wf9SampleSimService {

    /** 지어낸 위험 대화(자해 암시 · 보복 위협 · 부정물품 반입 요청) — 실제 인물 · 번호 없음. */
    static final List<String> DEFAULT_LINES = List.of(
            "요즘 밤마다 잠을 못 자요. 그냥 다 끝내 버리고 싶다는 생각만 들어요.",
            "여기서 나가면 그 사람한테 꼭 갚아 줄 거예요. 절대 가만두지 않을 겁니다.",
            "다음 면회 때 약 좀 몰래 넣어 줄 수 있어요? 책 사이에 숨기면 검사에 안 걸려요.",
            "이 얘기는 여기서 길게 하면 안 되니까 나머지는 편지로 할게요.",
            "(테스트 데이터 · WF9 위험 대화 샘플)");
    static final String KEY_PREFIX = "SIMWF9-";
    private static final Pattern KEY = Pattern.compile("SIMWF9-[0-9]{14}");
    private static final double SEG_SEC = 4.5;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * @param execId     얹을 배치(T1) — 있어야 한다. 그 배치의 전송(T2 SEND)이 끝나야 WF9 를 부른다
     * @param corrNo     교정번호(예: 화면에서 볼 수용자)
     * @param kind       MEET(기본) · PHONE
     * @param contactDtm 접견 · 통화 일시(KST · 비우면 지금보다 10분 전) — 화면은 최근 30일만 보인다
     * @param lines      대화 줄(비우면 {@link #DEFAULT_LINES})
     */
    public record Request(String execId, String corrNo, String kind, LocalDateTime contactDtm, List<String> lines) {}

    private final LogCollectorClient logCollector;
    private final VoiceDeidentClient voiceDeident;
    private final Wf9Spool spool;
    private final Wf9SendGate sendGate;
    private final InmatePidGenerator pid;
    private final RiskWf9Properties props;

    public Wf9SampleSimService(LogCollectorClient logCollector, VoiceDeidentClient voiceDeident, Wf9Spool spool,
                               Wf9SendGate sendGate, InmatePidGenerator pid, RiskWf9Properties props) {
        this.logCollector = logCollector;
        this.voiceDeident = voiceDeident;
        this.spool = spool;
        this.sendGate = sendGate;
        this.pid = pid;
        this.props = props;
    }

    public Map<String, Object> run(Request req) {
        if (req == null || isBlank(req.execId()) || isBlank(req.corrNo())) {
            throw new IllegalArgumentException("execId 와 corrNo 는 꼭 넣어야 합니다");
        }
        if (!props.enabled()) {
            throw new IllegalStateException("WF9 가 꺼져 있습니다(VOICE_RISK_WF9_ENABLED=false)");
        }
        if (!voiceDeident.enabled()) {
            throw new IllegalStateException("비식별 단계가 꺼져 있습니다 — 가린 전사만 WF9 로 보낸다");
        }
        String execId = req.execId().strip();
        JsonNode batch = logCollector.findBatchDetail(execId);
        if (batch == null) {
            throw new IllegalArgumentException("배치가 없습니다: " + execId);
        }
        VoiceKind kind = "PHONE".equalsIgnoreCase(req.kind()) ? VoiceKind.PHONE : VoiceKind.MEET;
        LocalDateTime at = (req.contactDtm() == null ? LocalDateTime.now().minusMinutes(10) : req.contactDtm()).withNano(0);
        String key = KEY_PREFIX + LocalDateTime.now().format(TS);
        VoiceTarget target = new VoiceTarget(kind, req.corrNo().strip(), key, null, null, false, null, null,
                key + ".wav", null, at);

        List<String> lines = req.lines() == null || req.lines().isEmpty() ? DEFAULT_LINES
                : req.lines().stream().filter(l -> l != null && !l.isBlank()).map(String::strip).toList();
        List<SttResult.Segment> segs = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            segs.add(new SttResult.Segment(i, round1(i * SEG_SEC), round1((i + 1) * SEG_SEC), lines.get(i)));
        }
        SttResult stt = new SttResult(String.join("\n", lines), "SIM", round1(lines.size() * SEG_SEC), false, "ko", segs);

        VoiceDeidentClient.Result d = voiceDeident.deident(execId, target, pid.of(target.corrNo()), stt);
        boolean spooled = spool.offer(execId, target, stt.withDeidentified(d.text(), d.segments()), d.mode(), false);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", execId);
        out.put("recFileId", key);
        out.put("kind", kind.name());
        out.put("contactDtm", at.toString());
        out.put("deidentMode", d.mode());
        out.put("t5SendId", d.sendId());
        out.put("spooled", spooled);
        String send = sendGate.sendStatus(execId);
        out.put("batchSend", send);
        out.put("next", props.afterSend() && !Wf9SendGate.SUCCESS.equals(send)
                ? "배치 " + execId + " 전송(T2 SEND)이 " + (send == null ? "확인 안 됨" : send)
                + " — [클라우드 전송]으로 보내면 다음 순회(" + props.pollSec() + "초)에 WF9 를 부른다"
                : "다음 순회(" + props.pollSec() + "초)에 WF9 를 부른다");
        out.put("cleanup", "DELETE /api/v1/voice/simulator/wf9-sample/" + key + "?corrNo=…&kind=" + kind.name());
        log.info("[WF9:샘플] {} · 배치 {} · 비식별 {} · 스풀 {} · 전송 {}", kind.name().toLowerCase() + "-" + key, execId,
                d.mode(), spooled, send);
        return out;
    }

    /** 샘플 녹음의 신호 행을 지운다(0건으로 통째 교체) — 샘플 키({@code SIMWF9-…})만. */
    public Map<String, Object> remove(String recFileId, String corrNo, String kind) {
        if (recFileId == null || !KEY.matcher(recFileId).matches()) {
            throw new IllegalArgumentException("샘플 녹음 ID(SIMWF9-yyyyMMddHHmmss)만 지울 수 있습니다");
        }
        if (isBlank(corrNo)) {
            throw new IllegalArgumentException("corrNo 를 넣어야 합니다");
        }
        String se = "PHONE".equalsIgnoreCase(kind) ? "CALL" : "VISIT";
        int n = logCollector.replaceContactRiskSignals(recFileId, new LogCollectorClient.ContactRiskReplaceReq(
                corrNo.strip(), se, LocalDateTime.now().withNano(0), null, null, List.of()));
        log.info("[WF9:샘플] 신호 지움 — {} · 남은 {}행", recFileId, n);
        return Map.of("recFileId", recFileId, "remaining", n);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
