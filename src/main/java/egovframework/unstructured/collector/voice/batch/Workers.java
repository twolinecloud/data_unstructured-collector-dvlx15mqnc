package egovframework.unstructured.collector.voice.batch;

/**
 * 배치 워커 구성 — <b>XVARM 확보 워커</b>와 <b>STT 처리 워커</b>를 따로 센다.
 *
 * <p>두 일의 성격이 다르다. 확보(브로커 추출 지시 → 수신 폴더 도착)는 원천 시스템을 기다리는 I/O 이고,
 * STT 는 NPU 연산이다. 한 워커가 둘을 다 하면 워커를 늘릴 때 확보 요청도 같이 늘어 브로커(추출 스레드 4개)가
 * 밀리고 확보 대기가 폭증한다. 그래서 확보는 적은 워커(기본 1)로 차례로 받아 두고, 받아 둔 파일을 많은
 * STT 워커가 곧바로 집어 가게 한다(생산자-소비자).</p>
 *
 * <p>둘 다 1 이면 종전과 같은 순차 처리다 — 운영 기본값.</p>
 *
 * @param acquire XVARM 확보 워커 수 — 접견은 브로커 추출, 전화는 파일 연계 수신까지
 * @param stt     STT 처리 워커 수 — 복호화 · STT · 비식별 · 최종 저장
 */
public record Workers(int acquire, int stt) {

    public Workers {
        acquire = Math.max(1, acquire);
        stt = Math.max(1, stt);
    }

    /** 순차 — 확보 1 · STT 1. */
    public static Workers sequential() {
        return new Workers(1, 1);
    }

    /** 생산자-소비자로 돌리는가 — 둘 중 하나라도 1 보다 크면. */
    public boolean pipelined() {
        return acquire > 1 || stt > 1;
    }

    /** 로그·화면용 한 줄 — "확보 1 · STT 31". */
    public String describe() {
        return "확보 " + acquire + " · STT " + stt;
    }
}
