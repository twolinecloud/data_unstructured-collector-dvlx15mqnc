package egovframework.unstructured.collector.common.broker;

import egovframework.unstructured.collector.common.config.MockDatasetState;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.sync.EsbFileNamingPolicy;
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
 * XVARM 브로커 Mock — 실제 추출 대신 <b>재생 가능한 더미 WAV 파일</b>을 수신 디렉터리에 만든다.
 *
 * <p>ESB 파일 동기화까지 한꺼번에 흉내 낸다. 브로커가 보라미 임시 폴더에 떨구고 ESB 가 우리
 * 스토리지로 옮겨 주는 두 단계를, Mock 에서는 "수신 디렉터리에 바로 생성"으로 줄인다 —
 * 그래야 {@code FileArrivalWatcher} 가 실제와 같은 방식으로 파일을 집어 갈 수 있다.</p>
 *
 * <p><b>복호화 스위치가 REAL 이면 암호화해서 떨군다.</b> 실제 XVARM 원본은 암호화돼 있는데
 * Mock 이 평문을 주면, 시뮬레이터에서 복호화를 REAL 로 올리는 순간 접견이 <b>전건 실패</b>한다
 * (평문을 복호화하려 드니 당연히 깨진다). 그러면 복호화 스위치를 Mock 환경에서 켜 볼 수가 없다.
 * 대상이 {@code CMMN_FILE_ENC_YN='Y'} 이고 키가 준비돼 있을 때만 암호화한다 — 'N' 인 건은
 * 그대로 둬야 복호화 통과(Noop) 경로도 같이 확인된다.</p>
 *
 * <p><b>파일명을 표준 A 규약대로 만든다.</b> 수신 파일은
 * {@code 'R'_인터페이스ID_송신도메인_수신도메인_송신일시+난수.DAT} 형식이라
 * <b>원본 확장자가 사라진다</b>. 개발 중에 이 함정을 못 보고 지나가지 않도록 Mock 도 {@code .DAT} 로 만든다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class MockXvarmBrokerClient implements XvarmBrokerClient {

    private final VoiceProperties props;
    private final VoiceDirState dirs;
    private final EsbFileNamingPolicy namingPolicy;
    private final MockDatasetState dataset;
    private final egovframework.unstructured.collector.common.config.VoiceModeState modeState;

    @Override
    public ExtractResult extract(VoiceTarget target) {
        return extract(target, null);
    }

    @Override
    public ExtractResult extract(VoiceTarget target, String execId) {
        String requestId = "MOCKREQ-" + (execId == null ? "" : execId + "-") + target.idempotencyKey();
        Path dir = dirs.receiveDir(target.kind());
        Path file = dir.resolve(namingPolicy.expectedFileName(target, props.sync().namingPolicy()));
        try {
            Files.createDirectories(dir);
            byte[] wav = SilentWav.of(dataset.wavSeconds());
            byte[] body = maybeEncrypt(wav, target);
            Files.write(file, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("[Broker:MOCK] 더미 파일 생성 — {} ({} bytes{})", file.getFileName(), body.length,
                    body == wav ? "" : ", 암호화");
            return new ExtractResult(requestId, file.toString(), body.length);
        } catch (IOException e) {
            throw new UncheckedIOException("Mock 브로커 파일 생성 실패: " + file, e);
        }
    }

    /**
     * 파일 단건 — XVARM 원본 스토리지에 {@code fileKey} 경로의 파일이 있으면 그것을 그대로(암호화된 채) 옮긴다.
     * 시뮬레이션 데이터 생성이 그 경로에 암호화된 더미 이미지를 써 둔다. 없으면 더미 이미지를 만들어 떨군다.
     */
    @Override
    public ExtractResult extractFile(FileRequest req) {
        String requestId = "MOCKREQ-" + req.requestId();
        Path dir = dirs.receiveDir(egovframework.unstructured.collector.common.model.VoiceKind.MEET);   // 브로커 출력 = 접견 수신 폴더
        Path file = dir.resolve(req.fileName());
        try {
            Files.createDirectories(dir);
            Path src = sourceOf(req.fileKey());
            byte[] body = src != null ? Files.readAllBytes(src)
                    : maybeEncrypt(egovframework.unstructured.collector.common.util.SampleImage.jpeg(req.requestId()), true);
            Files.write(file, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("[Broker:MOCK] 파일 추출 — {} ({} bytes{})", file.getFileName(), body.length,
                    src != null ? ", XVARM 원본 복사" : ", 더미 이미지");
            return new ExtractResult(requestId, file.toString(), body.length);
        } catch (IOException e) {
            throw new UncheckedIOException("Mock 브로커 파일 생성 실패: " + file, e);
        }
    }

    /** 파일키가 가리키는 원본 — 없거나 경로로 읽히지 않으면 null. */
    private static Path sourceOf(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(fileKey);
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] maybeEncrypt(byte[] plain, boolean encrypted) {
        if (modeState.decrypt() != VoiceProperties.DecryptMode.REAL || !encrypted) {
            return plain;
        }
        String keyPath = props.decrypt().rvsKeyPath();
        if (!org.springframework.util.StringUtils.hasText(keyPath)) {
            return plain;
        }
        try {
            return egovframework.unstructured.collector.common.decrypt.MediaDecryptor
                    .fromKeyFile(Path.of(keyPath.trim())).encrypt(plain);
        } catch (Exception e) {
            log.warn("[Broker:MOCK] 더미 암호화 실패 — 평문으로 둡니다 ({})", e.getMessage());
            return plain;
        }
    }

    /**
     * 복호화가 REAL 이고 이 건이 암호화 대상이면 실제로 암호화해 준다.
     *
     * <p>키를 못 읽으면 <b>평문 그대로 둔다</b> — 여기서 예외를 던지면 복호화 문제가 브로커 실패로
     * 둔갑해 원인을 엉뚱한 곳에서 찾게 된다. 복호화 단계가 "암호문이 아니다" 라고 말하게 두는 편이
     * 훨씬 읽기 쉽다.</p>
     */
    private byte[] maybeEncrypt(byte[] plain, VoiceTarget target) {
        if (modeState.decrypt() != VoiceProperties.DecryptMode.REAL || !target.encrypted()) {
            return plain;
        }
        String keyPath = props.decrypt().rvsKeyPath();
        if (!org.springframework.util.StringUtils.hasText(keyPath)) {
            log.warn("[Broker:MOCK] 복호화 REAL 인데 키 경로가 없어 평문으로 둡니다 — voice.decrypt.rvs-key-path");
            return plain;
        }
        try {
            return egovframework.unstructured.collector.common.decrypt.MediaDecryptor
                    .fromKeyFile(Path.of(keyPath.trim())).encrypt(plain);
        } catch (Exception e) {
            log.warn("[Broker:MOCK] 더미 암호화 실패 — 평문으로 둡니다 ({})", e.getMessage());
            return plain;
        }
    }

    @Override
    public String mode() {
        return "MOCK";
    }
}
