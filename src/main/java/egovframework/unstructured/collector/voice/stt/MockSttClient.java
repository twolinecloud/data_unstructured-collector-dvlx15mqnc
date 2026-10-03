package egovframework.unstructured.collector.voice.stt;

import egovframework.unstructured.collector.common.config.FaultInjector;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceFile;
import egovframework.unstructured.collector.common.model.VoiceKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * STT Mock — NPU 없이 고정 텍스트를 돌려준다.
 *
 * <p><b>텍스트에 일부러 개인정보를 넣는다.</b> 실제 통화·접견 음성에는 성명·주민등록번호·
 * 전화번호·주소가 섞여 나온다. Mock 도 같은 모양이어야 STT 텍스트가 로그·응답에 그대로 새지
 * 않는지(글자 수만 남기는지)를 실제와 같은 조건으로 확인할 수 있다.</p>
 *
 * <p>아래 값은 전부 <b>가공된 테스트 데이터</b>다. 실제 수용자·수신자 정보가 아니다.</p>
 *
 * <p><b>Whisper 표준 JSON 의 모양으로 돌려준다</b> — 대본을 줄 단위로 끊어 {@code segments} 를
 * 만들고 {@code start}/{@code end} 를 채운다. 실물 엔진이 구간을 줄 때와 같은 모양이라야,
 * 구간을 쓰는 쪽(산출물 {@code transcript})을 Mock 으로도 검증할 수 있다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class MockSttClient implements SttClient {

    private final FaultInjector faultInjector;
    private final MockSttLatency latency;
    /** 중단 요청을 본다 — 가상 지연은 수 분이라, 다 기다리면 [중지]가 먹지 않는 것처럼 보인다. */
    private final egovframework.unstructured.collector.voice.batch.BatchProgress progress;
    /** 고속 모드에서 기다리지 않은 처리 시간을 적어 두는 곳 — 성능 시험 리포트가 합산한다. */
    private final egovframework.unstructured.collector.voice.perf.PerfStageMeter meter;
    /** 더미 키 표식(AF) 장애 — 이 건의 STT 를 한 번만 실패시킨다. 운영 프로필에서는 아무 일도 하지 않는다. */
    private final egovframework.unstructured.collector.mock.ScenarioFaults scenarioFaults;

    /** 가상 지연을 이 간격으로 잘라 자면서 중단 요청을 본다. */
    private static final long PAUSE_SLICE_MS = 200L;

    private static final String PHONE_SCRIPT = """
            여보세요. 저 김수용인데요. 어머니 바꿔주세요.
            네 어머니 저예요. 다음 주 화요일에 면회 오실 수 있으세요?
            아버지한테도 말씀 좀 드려주세요. 연락처는 010-1234-5678 로 하시면 되고요.
            영치금은 우체국 계좌로 보내주시면 됩니다. 제 주민번호는 900101-1234567 이에요.
            주소는 서울특별시 강남구 테헤란로 123번지로 보내주시면 되고요.
            박담당 교도관님이 도와주신다고 하셨어요. 그럼 다음 주에 뵐게요.
            """;

    private static final String MEET_SCRIPT = """
            안녕하세요. 접견 시작하겠습니다.
            형님 오랜만입니다. 밖에 사정은 좀 어떻습니까.
            이정호 변호사님이 이번 주에 오신다고 했는데 아직 연락이 없네요.
            사무실 번호가 02-555-1234 인데 한 번 연락해 보시겠어요.
            집사람한테는 걱정하지 말라고 전해주세요. 아이 학교 문제도 잘 처리되고 있다고요.
            면회 시간 다 됐습니다. 마무리해 주세요.
            """;

    /** 한 줄(=한 구간)이 차지하는 시간. 실제 발화 속도와 무관한 고정값이다 — 모양만 같으면 된다. */
    private static final double SEG_SEC = 4.5;

    @Override
    public SttResult transcribe(VoiceFile file) {
        // 장애 시뮬레이션이 켜져 있으면 여기서 지연·예외가 난다.
        // 한 건이 터져도 배치가 끝까지 도는지(processOne 의 건별 격리) 확인하기 위한 지점이다.
        faultInjector.maybeInject(FaultInjector.Stage.STT);
        // 더미 시나리오 ANALYZE_FAIL — 손상 파일로는 재현되지 않는다(Mock 은 내용을 보지 않고, 복호화 실패는 COLLECT 로 집계된다).
        //   표식 건만 한 번 실패시킨다 — 복호화 오디오가 보존되어 FROM_ANALYZE 재처리가 STT 부터 잇는다.
        if (scenarioFaults.failOnce(egovframework.unstructured.collector.mock.FailureScenario.ANALYZE_FAIL,
                file.target().idempotencyKey())) {
            throw new IllegalStateException("STT 호출 실패 — HTTP 500 (MOCK) [더미 시나리오 ANALYZE_FAIL · 1회]");
        }
        // NPU 응답 시간 흉내 — 기본 0ms. 성능 테스트가 도는 동안만 지연·타임아웃이 걸린다.
        simulateLatency(file);

        String script = (file.target().kind() == VoiceKind.MEET) ? MEET_SCRIPT : PHONE_SCRIPT;
        // 대본을 줄 단위로 끊어 구간을 만든다 — 실물 엔진이 segments 를 줄 때와 같은 모양이라야
        // 구간을 쓰는 쪽(산출물 transcript)을 Mock 으로도 검증할 수 있다.
        List<String> lines = new ArrayList<>(script.strip().lines()
                .map(String::strip).filter(l -> !l.isEmpty()).toList());
        // 건마다 구분되도록 식별자를 덧붙인다 — 로그에서 어느 건의 텍스트인지 추적할 수 있다.
        lines.add("(테스트 데이터 / " + file.target().shortId() + ")");

        List<SttResult.Segment> segments = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            double start = round1(i * SEG_SEC);
            segments.add(new SttResult.Segment(i, start, round1(start + SEG_SEC), lines.get(i)));
        }
        String body = String.join("\n", lines);
        double duration = round1(lines.size() * SEG_SEC);

        log.info("[STT:MOCK] {} — {}자 · {}초 · 구간 {}개",
                file.path().getFileName(), body.length(), duration, segments.size());
        return new SttResult(body, "MOCK", duration, false, "ko", segments);
    }

    /**
     * 가상 지연만큼 기다린다. 지연이 타임아웃을 넘으면 <b>타임아웃만큼만</b> 기다린 뒤 던진다 —
     * 실제 호출도 타임아웃에서 끊기지, 응답이 올 때까지 기다리지 않는다.
     */
    private void simulateLatency(VoiceFile file) {
        long delay = latency.draw(file.target().kind());
        long timeout = latency.timeoutMs();
        if (latency.fastForward()) {
            // 고속 모드 — 기다리지 않는다. 기다렸을 시간(타임아웃이면 타임아웃까지)만 적는다
            boolean timedOut = timeout > 0 && delay > timeout;
            meter.addVirtual(timedOut ? timeout : delay);
            if (timedOut) {
                throw new SttTimeoutException("STT 응답 없음 — %dms 초과 (건당 처리 시간 %dms · 고속 모드: 기다리지 않음 · %s)"
                        .formatted(timeout, delay, file.target().shortId()));
            }
            return;
        }
        if (timeout > 0 && delay > timeout) {
            pause(timeout);
            throw new SttTimeoutException("STT 응답 없음 — %dms 초과 (가상 지연 %dms · %s)"
                    .formatted(timeout, delay, file.target().shortId()));
        }
        pause(delay);
    }

    /**
     * 가상 지연만큼 잔다 — <b>중단 요청이 오면 바로 깬다.</b>
     *
     * <p>실제 처리 중인 건은 끝까지 가는 것이 원칙이지만, 이것은 가짜 대기다. 접견 180초를 다 기다리면
     * [중지]가 3분 동안 먹지 않는 것처럼 보인다. 깬 건은 ANALYZE 실패로 남고 복호화 오디오는 보존되어
     * 재처리가 STT 부터 이어 간다.</p>
     */
    private void pause(long ms) {
        long until = System.currentTimeMillis() + ms;
        try {
            for (long left = ms; left > 0; left = until - System.currentTimeMillis()) {
                if (progress.isCancelRequested()) {
                    throw new IllegalStateException("중단됨 — STT 가상 지연 대기 중 멈췄습니다");
                }
                Thread.sleep(Math.min(left, PAUSE_SLICE_MS));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("STT 가상 지연 중 중단됨", e);
        }
    }

    /** 소수 첫째 자리 — Whisper 도 그 정도로 준다. 자릿수가 길면 산출물만 지저분해진다. */
    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }

    @Override
    public String mode() {
        return "MOCK";
    }
}
