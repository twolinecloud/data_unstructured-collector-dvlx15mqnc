package egovframework.unstructured.collector.voice.stt.genon;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 제논 호출 한 번의 결과(성공만 — 실패는 {@link GenonSttException}).
 *
 * @param mode      MOCK · REST
 * @param path      {@code /audio/transcribe} · {@code /audio/process}
 * @param status    HTTP 상태(MOCK 은 200)
 * @param elapsedMs 차례를 얻은 뒤 응답까지 — '동시 한도' 로 다시 보낸 대기를 포함한다
 * @param queuedMs  이 프로세스 안에서 차례를 기다린 시간(동시 1건)
 * @param attempts  보낸 횟수(다시 보내기 포함)
 * @param body      제논 응답 본문 그대로 — 전사 텍스트가 들어 있다. 로그에 남기지 않는다
 * @param audio     사전 검증 결과(보낸 파일의 포맷 · 크기 · 길이)
 */
public record GenonCall(String mode, String path, int status, long elapsedMs, long queuedMs, int attempts,
                        JsonNode body, AudioInspector.Check audio) {
}
