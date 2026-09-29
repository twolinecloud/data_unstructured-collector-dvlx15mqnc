package egovframework.unstructured.collector.common.broker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 브로커 산출 경로 판정 — <b>5분 대기 대신 즉시 실패</b>가 정확히 로컬 오설정에만 걸리는지.
 *
 * <p>가장 중요한 것은 <b>운영에서 오탐하지 않는 것</b>이다. 브로커의 경로가 우리 파일시스템에 없으면
 * (보라미 서버 경로) 어떤 경우에도 판정하지 않고 ESB 동기화를 기다려야 한다.</p>
 */
class BrokerOutputCheckTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("파일이 실제로 있는데 수신 폴더 밖이면 즉시 실패 사유를 돌려준다 — 브로커를 local 없이 띄운 경우")
    void detectsFileOutsideReceiveDir() throws Exception {
        Path brokerOut = Files.createDirectories(tmp.resolve("broker/work/xvarm_out"));
        Path receive = Files.createDirectories(tmp.resolve("collector/work/voice_raw/meet"));
        Path produced = Files.writeString(brokerOut.resolve("mock_meet_002.m4a"), "wav");

        var reason = BrokerOutputCheck.mismatch(produced.toString(), receive.toString());

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("수신 폴더 밖").contains("local 프로파일");
    }

    @Test
    @DisplayName("파일이 수신 폴더 안에 있으면 정상 — 표기가 달라도(상대·..·슬래시) 같은 폴더면 같다")
    void acceptsFileInsideReceiveDir() throws Exception {
        Path receive = Files.createDirectories(tmp.resolve("collector/work/voice_raw/meet"));
        Path produced = Files.writeString(receive.resolve("mock_meet_002.m4a"), "wav");
        // 브로커가 '../data_voice-collector/work/voice_raw/meet' 처럼 돌아가는 표기로 알려준 경우
        String roundabout = tmp.resolve("collector/work/voice_raw/../voice_raw/meet/mock_meet_002.m4a").toString();

        assertThat(BrokerOutputCheck.mismatch(produced.toString(), receive.toString())).isEmpty();
        assertThat(BrokerOutputCheck.mismatch(roundabout, receive.toString())).isEmpty();
    }

    @Test
    @DisplayName("우리 파일시스템에 없는 경로는 판정하지 않는다 — 운영(보라미 서버 경로)에서 오탐 금지")
    void ignoresPathThatDoesNotExistHere() throws Exception {
        Path receive = Files.createDirectories(tmp.resolve("collector/work/voice_raw/meet"));
        String boramiPath = tmp.resolve("data001/doc01/recv/XVARM/meet_001.m4a").toString();   // 존재하지 않음

        assertThat(BrokerOutputCheck.mismatch(boramiPath, receive.toString())).isEmpty();
        assertThat(BrokerOutputCheck.mismatch("/data001/doc01/recv/XVARM/meet_001.m4a", receive.toString())).isEmpty();
    }

    @Test
    @DisplayName("상대경로·빈 값은 판정하지 않는다 — 브로커의 작업 디렉터리 기준이라 우리가 해석하면 안 된다")
    void ignoresRelativeAndBlank() throws Exception {
        Path receive = Files.createDirectories(tmp.resolve("collector/work/voice_raw/meet"));

        assertThat(BrokerOutputCheck.mismatch("./work/xvarm_out/mock_meet_002.m4a", receive.toString())).isEmpty();
        assertThat(BrokerOutputCheck.mismatch("", receive.toString())).isEmpty();
        assertThat(BrokerOutputCheck.mismatch(null, receive.toString())).isEmpty();
    }
}
