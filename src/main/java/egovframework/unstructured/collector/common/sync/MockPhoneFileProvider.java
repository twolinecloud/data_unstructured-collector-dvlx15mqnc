package egovframework.unstructured.collector.common.sync;

import egovframework.unstructured.collector.common.config.MockDatasetState;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.util.SilentWav;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 전화 파일 Mock — ESB 대신 더미 WAV 를 수신 디렉터리에 떨군다.
 *
 * <p>브로커 모드가 MOCK 이면 함께 켜진다. "전 구간 Mock" 이 하나의 스위치로 움직이게 하려는 것이다
 * — 전화만 따로 스위치를 두면 조합이 늘어나기만 하고 쓰이지는 않는다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class MockPhoneFileProvider implements PhoneFileProvider {

    private final VoiceProperties props;
    private final VoiceDirState dirs;
    private final EsbFileNamingPolicy namingPolicy;
    private final MockDatasetState dataset;
    private final egovframework.unstructured.collector.common.config.VoiceModeState modeState;
    /** 더미 키 표식(CF) 장애 — 이 건의 파일 연계를 한 번만 거부한다. 운영 프로필에서는 아무 일도 하지 않는다. */
    private final egovframework.unstructured.collector.mock.ScenarioFaults scenarioFaults;

    @Override
    public void request(VoiceTarget target) {
        if (scenarioFaults.failOnce(egovframework.unstructured.collector.mock.FailureScenario.COLLECT_FAIL,
                target.idempotencyKey())) {
            // 파일을 안 만들어 두면 수신 대기 제한(개발계 300초)까지 멈춰 있게 된다 — 제공자가 거부한 것으로 곧바로 끊는다
            throw new IllegalStateException("전화 파일 연계 실패 — 제공자(MOCK)가 이 건을 거부했습니다 [더미 시나리오 COLLECT_FAIL · 1회]");
        }
        Path dir = dirs.receiveDir(target.kind());
        Path file = dir.resolve(namingPolicy.expectedFileName(target, props.sync().namingPolicy()));
        try {
            Files.createDirectories(dir);
            byte[] wav = SilentWav.of(dataset.wavSeconds());
            byte[] body = maybeEncrypt(wav, target);
            Files.write(file, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("[PhoneFile:MOCK] 더미 파일 생성 — {} ({} bytes{})", file.getFileName(), body.length,
                    body == wav ? "" : ", 암호화");
        } catch (IOException e) {
            throw new UncheckedIOException("Mock 전화 파일 생성 실패: " + file, e);
        }
    }

    /**
     * 복호화가 REAL 이고 이 건이 암호화 대상이면 실제로 암호화해 준다 — <b>접견 Mock 브로커와 같은 규약</b>.
     *
     * <p><b>왜 필요한가</b>: 메타는 {@code CMMN_FILE_ENC_YN='Y'} 라고 말하는데 파일은 평문이면
     * 앞뒤가 맞지 않는다. 복호화를 REAL 로 올리는 순간 복호화기가 평문을 암호문으로 알고 달려들어
     * 전화 건이 통째로 실패했다 — 로컬에서 REAL 전 구간을 돌려 볼 수 없었던 이유다.</p>
     *
     * <p>키를 못 읽으면 <b>평문 그대로 둔다</b>. 여기서 예외를 던지면 복호화 문제가 수신 실패로
     * 둔갑해 원인을 엉뚱한 곳에서 찾게 된다.</p>
     */
    private byte[] maybeEncrypt(byte[] plain, VoiceTarget target) {
        if (modeState.decrypt() != VoiceProperties.DecryptMode.REAL || !target.encrypted()) {
            return plain;
        }
        String keyPath = props.decrypt().rvsKeyPath();
        if (!org.springframework.util.StringUtils.hasText(keyPath)) {
            log.warn("[PhoneFile:MOCK] 복호화 REAL 인데 키 경로가 없어 평문으로 둡니다 — voice.decrypt.rvs-key-path");
            return plain;
        }
        try {
            return egovframework.unstructured.collector.common.decrypt.MediaDecryptor
                    .fromKeyFile(Path.of(keyPath.trim())).encrypt(plain);
        } catch (Exception e) {
            log.warn("[PhoneFile:MOCK] 더미 암호화 실패 — 평문으로 둡니다 ({})", e.getMessage());
            return plain;
        }
    }

    @Override
    public String mode() {
        return "MOCK";
    }
}
