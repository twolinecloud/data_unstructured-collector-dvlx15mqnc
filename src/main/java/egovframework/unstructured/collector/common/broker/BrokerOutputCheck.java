package egovframework.unstructured.collector.common.broker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 브로커가 만든 파일이 <b>우리 파일시스템에 보이는데 수신 폴더 밖</b>인지 판정한다.
 *
 * <p><b>가장 많이 당한 함정</b>: 브로커를 local 프로파일 없이 띄우면 자기 레포의
 * {@code work/xvarm_out} 에 파일을 만들고 "추출 완료" 를 돌려준다. 수집기는 빈 수신 폴더를 보며
 * {@code wait-timeout-sec}(기본 300초)까지 기다린 뒤에야 타임아웃으로 실패한다 — 그 사이 원인을
 * 알려주는 신호가 없다. 브로커 응답의 {@code filePath} 가 절대경로로 오면, 그 파일이 <b>실제로
 * 있는데</b> 우리 수신 폴더가 아니라는 것을 요청 직후에 알 수 있다.</p>
 *
 * <p><b>운영에서 오탐하지 않는 이유</b>: 브로커는 보라미 서버에 있고 {@code filePath} 는 그쪽
 * 파일시스템 기준이다. 우리 파드에는 그 경로가 없으므로 {@code exists} 가 거짓이라 판정을 건너뛰고,
 * 원래대로 ESB 동기화를 기다린다. 판정에 걸리는 것은 브로커와 같은 파일시스템을 보는 로컬·개발계뿐이고,
 * 그 경우 수신 폴더 밖의 파일은 아무도 옮겨 주지 않으므로 기다려 봐야 타임아웃이다.</p>
 *
 * <p>상대경로는 판정하지 않는다 — 브로커의 작업 디렉터리 기준이지 우리 기준이 아니라서, 우리 쪽에
 * 우연히 같은 상대경로가 있으면 엉뚱한 파일을 보게 된다.</p>
 */
public final class BrokerOutputCheck {

    private BrokerOutputCheck() {}

    /**
     * @param brokerFilePath 브로커가 알려준 산출 경로(절대경로 기대)
     * @param meetDir        우리 접견 수신 디렉터리
     * @return 즉시 실패시켜야 하면 그 사유. 판단할 수 없거나 정상이면 {@code empty}
     */
    public static Optional<String> mismatch(String brokerFilePath, String meetDir) {
        if (brokerFilePath == null || brokerFilePath.isBlank() || meetDir == null || meetDir.isBlank()) {
            return Optional.empty();
        }
        Path produced;
        try {
            produced = Path.of(brokerFilePath);
        } catch (Exception e) {
            return Optional.empty();   // 다른 OS 표기 등 — 판단 불가면 기존대로 기다린다
        }
        if (!produced.isAbsolute() || !Files.isRegularFile(produced)) {
            return Optional.empty();
        }
        Path receive = Path.of(meetDir).toAbsolutePath().normalize();
        Path parent = produced.toAbsolutePath().normalize().getParent();
        if (parent != null && parent.equals(receive)) {
            return Optional.empty();
        }
        return Optional.of("브로커가 파일을 우리 수신 폴더 밖에 만들었다 — 산출 " + produced
                + " / 수신 " + receive + ". 브로커를 local 프로파일로 띄우거나 BROKER_OUTPUT_DIR 을 수신 폴더로 맞출 것"
                + " (시뮬레이터 [브로커 연결 확인] 으로 대조)");
    }
}
