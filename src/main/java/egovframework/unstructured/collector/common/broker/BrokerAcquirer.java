package egovframework.unstructured.collector.common.broker;

import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.inbound.SourceFileAcquirer;
import egovframework.unstructured.collector.common.model.VoiceFile;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.sync.FileArrivalWatcher;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * 파일 확보 — <b>XVARM 브로커 방식</b>({@code voice.acquire.mode=BROKER} · 지금). 건마다 브로커에 추출을 요청하고
 * 수신 폴더(= 브로커 출력 폴더 = 접견 수신 폴더)에 파일이 도착해 쓰기가 끝날 때까지 기다린다.
 *
 * <p>2026-11-28 XVARM 제거 1단계로 접견 {@code VoiceCollectService.acquire} 의 접견 갈래와 사진
 * {@code ImageCollectService.processOne} 의 '브로커 수신' 블록을 <b>그대로</b> 옮겼다(순서 · 로그 · 사유 문구 같음).
 * 보라미 배치가 동기화 폴더로 넣어 주는 방식(SYNCED · 회의 D2)으로 바꾸고 실데이터 시험을 통과하면 지운다(4단계).</p>
 *
 * <p><b>요청 전 잔재 삭제({@link FileArrivalWatcher#clearStale})는 접견 · 사진에서 이 클래스만 부른다.</b> 브로커 방식은
 * '요청 → 대기' 순서라 요청 전에 그 이름에 있는 파일은 이번 요청의 산출물일 수 없다. 동기화 방식에서는 그 파일이 보라미가 넣어 준
 * 원본이라 지우면 다시 받을 길이 없다 — 그래서 그쪽은 이 길을 타지 않고, {@code FileArrivalWatcher} 도 BROKER 가 아니면 지우지 않는다.</p>
 *
 * <p>빈은 {@code InboundConfig} 가 만든다(확보 방식을 고르는 곳이 한 군데여야 한다).</p>
 */
@Log4j2
public class BrokerAcquirer implements SourceFileAcquirer {

    /** 설정 브로커 — 라우터(@Primary · 런타임 MOCK ↔ REST). */
    private final XvarmBrokerClient broker;
    /**
     * 수집기 내장 Mock 브로커 — 사진 SIM 검증(6번 탭)이 쓴다. SIM 원본은 수집기 저장소에만 있어 실제 브로커가 읽을 수 없다
     * (개발계 브로커는 별도 파드 · DUMMY 어댑터라 FILEKEY 와 무관한 가짜 음성을 자기 파드에 만든다).
     */
    private final MockXvarmBrokerClient mockBroker;
    private final FileArrivalWatcher watcher;
    private final VoiceDirState dirs;
    /** 브로커 주소 — 사진 실행 기록의 cURL 에 쓴다. */
    private final VoiceModeState modeState;

    public BrokerAcquirer(XvarmBrokerClient broker, MockXvarmBrokerClient mockBroker, FileArrivalWatcher watcher,
                          VoiceDirState dirs, VoiceModeState modeState) {
        this.broker = broker;
        this.mockBroker = mockBroker;
        this.watcher = watcher;
        this.dirs = dirs;
        this.modeState = modeState;
    }

    @Override
    public String mode() {
        return "BROKER(" + broker.mode() + ")";
    }

    @Override
    public boolean requiresFileKey() {
        return true;
    }

    @Override
    public VoiceFile acquireMeet(VoiceTarget target, String execId, BooleanSupplier canceled) {
        // 요청 전에 그 이름에 남아 있는 것은 이번 요청의 산출물일 수 없다 — 먼저 치운다.
        watcher.clearStale(target);
        log.info("[Track:MEET] ② XVARM 추출 요청 — {} via 브로커 {} (execId={})", target.shortId(), broker.mode(), execId);
        XvarmBrokerClient.ExtractResult extracted = broker.extract(target, execId);
        log.info("[Track:MEET] ③ ESB 수신 대기 — {} (브로커 산출 {})",
                target.shortId(), extracted.filePath());
        // 브로커가 만든 파일이 우리 쪽에 보이는데 수신 폴더 밖이면 기다려 봐야 타임아웃이다.
        //   브로커를 local 프로파일 없이 띄웠을 때 300초를 날리던 자리 — 지금 끊는다.
        //   운영은 보라미 서버 경로라 우리 쪽에 없어 이 판정에 걸리지 않는다(BrokerOutputCheck).
        BrokerOutputCheck.mismatch(extracted.filePath(), dirs.receiveMeet())
                .ifPresent(reason -> { throw new IllegalStateException(reason); });
        // 브로커가 알려준 실제 파일명을 그대로 쓴다.
        //   추측한 이름으로 찾으면 브로커가 다른 이름으로 만들었을 때 영영 못 찾고 타임아웃이 난다.
        //   Mock 브로커는 우리와 같은 명명 정책을 써서 우연히 일치했을 뿐이고,
        //   실제 XVARM 이 파일명을 어떻게 정하는지는 아직 모른다(계획서 Q3).
        // 중단 요청은 대기 중에도 곧바로 먹는다 — 파일이 끝내 오지 않는 건에서 대기 제한(300초)까지 멈춰 있지 않게
        return watcher.await(target, fileNameOf(extracted.filePath()), canceled);
    }

    @Override
    public String photoSourceLabel(boolean sim) {
        // SIM 검증이면 수집기 내장 Mock 브로커로 받는다 — 설정 브로커(개발계 REST·DUMMY)는 SIM 원본을 읽을 수 없다
        if (sim && !"MOCK".equals(broker.mode())) {
            return "MOCK(SIM 검증 — 수집기 내장 · 설정 " + broker.mode() + " 는 쓰지 않음)";
        }
        return (sim ? mockBroker : broker).mode();
    }

    @Override
    public Path acquirePhoto(PhotoRequest req, BooleanSupplier canceled, TraceSink trace) {
        XvarmBrokerClient via = req.sim() ? mockBroker : broker;
        Path dir = dirs.receiveDir(VoiceKind.MEET);   // 브로커 출력 폴더 = 접견 수신 폴더
        watcher.clearStale(dir, req.receiveName());
        XvarmBrokerClient.FileRequest fr = new XvarmBrokerClient.FileRequest(
                req.docId(), req.fileKey(), req.requestId(), req.receiveName());
        XvarmBrokerClient.ExtractResult r = via.extractFile(fr);
        if (trace != null) {
            traceExtract(trace, via, fr, r);
        }
        BrokerOutputCheck.mismatch(r.filePath(), dirs.receiveMeet()).ifPresent(reason -> {
            throw new IllegalStateException(reason);
        });
        return watcher.awaitFile(dir, photoFileNameOf(r.filePath(), req.receiveName()), canceled);
    }

    /** 지금과 같다 — 매핑 {@code filekey} 에 XVARM FILEKEY 를 남긴다(이 방식은 FILEKEY 없는 건을 그 전에 실패시킨다). */
    @Override
    public String photoRecordKey(PhotoRequest req, Path received) {
        return req.fileKey();
    }

    /** ④ 브로커 추출 요청을 cURL 로 적는다 — 내장 Mock 이면 같은 요청을 수집기 안에서 처리한 것이다. */
    private void traceExtract(TraceSink trace, XvarmBrokerClient via, XvarmBrokerClient.FileRequest fr,
                              XvarmBrokerClient.ExtractResult r) {
        String body = "{\"docId\":\"" + fr.docId() + "\",\"fileKey\":\"" + fr.fileKey() + "\",\"requestId\":\""
                + fr.requestId() + "\",\"fileName\":\"" + fr.fileName() + "\"}";
        boolean internal = via == mockBroker;
        String base = internal ? "http://(수집기 내장 Mock)" : String.valueOf(modeState.brokerBaseUrl()).replaceAll("/+$", "");
        String cmd = (internal ? "# 수집기 내장 Mock 브로커 — 아래 요청을 수집기 안에서 처리한다(SIM 원본은 수집기 저장소에만 있다)\n" : "")
                + "curl -s -X POST '" + base + "/api/v1/xvarm/extract' \\\n  -H 'Content-Type: application/json' \\\n  -d '"
                + body + "'";
        trace.add("브로커 추출 요청 — FILEKEY 의 원본을 수신 폴더로(" + via.mode() + ")", "HTTP", cmd,
                "{\"requestId\":\"" + r.requestId() + "\",\"status\":\"DONE\",\"filePath\":\""
                        + String.valueOf(r.filePath()).replace('\\', '/') + "\",\"fileSize\":" + r.fileSize() + "}");
    }

    /** 경로에서 파일명만 뽑는다(접견). 경로가 비었으면 null — watcher 가 정책으로 되돌아간다. */
    static String fileNameOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path name = Path.of(path).getFileName();
            return name == null ? null : name.toString();
        } catch (Exception e) {
            // 브로커가 우리 OS 와 다른 경로 표기를 줄 수 있다(보라미는 리눅스).
            // 파싱이 안 되면 구분자로 직접 자른다.
            int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String tail = (i >= 0) ? path.substring(i + 1) : path;
            return tail.isBlank() ? null : tail;
        }
    }

    /** 경로에서 파일명만 뽑는다(사진). 비었으면 요청한 이름. */
    static String photoFileNameOf(String path, String fallback) {
        if (path == null || path.isBlank()) {
            return fallback;
        }
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String tail = i >= 0 ? path.substring(i + 1) : path;
        return tail.isBlank() ? fallback : tail;
    }
}
