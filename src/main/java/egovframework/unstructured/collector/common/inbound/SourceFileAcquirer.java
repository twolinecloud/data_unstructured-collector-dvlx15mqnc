package egovframework.unstructured.collector.common.inbound;

import egovframework.unstructured.collector.common.model.VoiceFile;
import egovframework.unstructured.collector.common.model.VoiceTarget;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * <b>원천 파일 확보</b> — 접견 녹음 · 수용자 사진 한 건을 우리 스토리지에서 읽을 수 있게 만든다. 그 뒤(복호화 · STT · 저장 ·
 * 매핑)는 이 인터페이스를 모른다.
 *
 * <p><b>왜 자리를 따로 만들었나</b>(2026-11-28 · XVARM 제거 구현 계획서 1단계): 지금은 건마다 XVARM 브로커에 추출을 요청하고
 * 수신 폴더를 기다린다({@code BrokerAcquirer} · {@code voice.acquire.mode=BROKER}). 2026-11-26 회의(D2 · D3)로 보라미 배치가
 * 전용 폴더에 미리 넣어 주는 방식(SYNCED)으로 바뀐다. 두 방식이 같은 자리에 끼워지도록 접견 {@code VoiceCollectService.acquire}
 * 와 사진 {@code ImageCollectService.processOne} 의 '브로커 수신' 블록이 이 인터페이스만 부르게 했다.</p>
 *
 * <p>실패 · 중단은 {@link IllegalStateException} 으로 알린다 — 사유 문구는 호출자가 그대로 T4 에 남긴다(경로 · 업무 값은 넣지 않는다).
 * 전화는 이 인터페이스를 쓰지 않는다(XVARM 과 무관한 ESB 전화 연계 · 범위 밖).</p>
 */
public interface SourceFileAcquirer {

    /** 확보 방식 — 기동 로그 · 배치 로그에 보인다. 예: {@code BROKER(MOCK)} */
    String mode();

    /**
     * 사진 건에 FILEKEY 가 있어야 받을 수 있나 — 브로커는 FILEKEY(XVARM 저장 위치)로 원본을 찾는다.
     * 동기화 방식은 DOC_ID 로 찾으므로 필요 없다.
     */
    boolean requiresFileKey();

    /**
     * 접견 녹음 한 건 — 수신 폴더에 도착해 쓰기가 끝난(크기 안정) 파일.
     *
     * @param execId   이번 배치 실행 ID — 브로커 요청 키에 넣는다(재처리 배치가 새로 추출받게)
     * @param canceled 중단 여부 — 기다리는 동안에도 보고, 참이면 곧바로 멈춘다
     */
    VoiceFile acquireMeet(VoiceTarget target, String execId, BooleanSupplier canceled);

    /**
     * 수용자 사진 한 건 — 받은 파일의 경로. 받은 파일을 언제 지울지는 호출자가 정한다.
     *
     * @param trace 단계별 실행 기록(6번 탭 표본 한 건)에 남길 곳. {@code null} 이면 남기지 않는다
     */
    Path acquirePhoto(PhotoRequest req, BooleanSupplier canceled, TraceSink trace);

    /**
     * 사진 매핑 표 {@code filekey} 자리(NOT NULL)에 남길 값 — 그 사진을 어디서 받았는지 되짚는 키.
     * 브로커 방식은 XVARM FILEKEY 그대로다(지금). 동기화 방식(2단계)은 받은 파일의 상대 경로를 넣는다
     * (계획서 2.3.3 1안 · DDL 없이 — XVARM 조인을 빼면 FILEKEY 가 없다).
     *
     * @param received {@link #acquirePhoto} 가 돌려준 파일
     */
    String photoRecordKey(PhotoRequest req, Path received);

    /** 사진 실행 결과의 '받은 곳' 표시 — SIM 검증({@code sim})이면 수집기 안의 흉내로 받는다. */
    String photoSourceLabel(boolean sim);

    /**
     * 사진 한 건 요청.
     *
     * @param docId       공통파일 DOC_ID
     * @param fileKey     XVARM FILEKEY — 브로커 방식만 쓴다(조인을 빼면 비어 있다)
     * @param requestId   멱등 키 — 같은 값이면 브로커가 다시 추출하지 않는다(배치마다 다르게 만든다)
     * @param receiveName 받을 이름 — 접견 음성과 섞이지 않게 접두를 둔다
     * @param sim         SIM 검증(6번 탭) 실행 — 원본이 수집기 저장소에만 있어 수집기 안의 흉내로 받는다
     */
    record PhotoRequest(String docId, String fileKey, String requestId, String receiveName, boolean sim) {}

    /** 실행 기록 한 줄 — 제목 · 종류(HTTP · SHELL · CODE · SQL) · 명령 · 결과. */
    @FunctionalInterface
    interface TraceSink {
        void add(String title, String kind, String command, String result);
    }
}
