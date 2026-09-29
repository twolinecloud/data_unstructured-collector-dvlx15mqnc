package egovframework.unstructured.collector.voice.stt;

import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceFile;

/**
 * 음성을 텍스트로 바꾼다.
 *
 * <p>엔진은 NPU 서버가 제공한다(2026-09-11 회의 {@code [00:04:24]} "STT로 던지고").
 * 우리는 클라이언트만 만든다 — 엔진 자체는 메타빌드 지원 범위 밖이기도 하다(2026-07-29 메일).</p>
 *
 * <p><b>파일 1건 = 요청 1건</b>으로 보낸다. 오디오는 크기가 커서 벌크로 묶으면
 * 메모리·타임아웃 양쪽에서 문제가 된다.</p>
 */
public interface SttClient {

    SttResult transcribe(VoiceFile file);

    String mode();
}
