package egovframework.unstructured.collector.batch;

import java.util.Map;

/**
 * 파드 간 배치 실행 잠금 — 스케줄 · 바로 실행 · 긴급 재처리가 <b>파드가 여럿이어도</b> 한 번에 하나만 돌게 한다.
 *
 * <p>실행기({@link UnstructuredJobRunner})의 잠금은 프로세스 안뿐이라, 롤링 배포(새 파드를 먼저 띄우고 옛 파드를 나중에 내림)
 * 중에는 옛 파드와 새 파드가 같은 원천을 동시에 처리할 수 있다. 이 잠금이 그 사이를 막는다(2026-10-08).</p>
 */
public interface BatchLock {

    /** 잠금을 쓰지 않는다 — 로컬 H2 · 테스트. 늘 잡힌다. */
    BatchLock NONE = new BatchLock() {
        @Override
        public Held tryAcquire() {
            return () -> { };
        }

        @Override
        public Map<String, Object> describe() {
            return Map.of("enabled", false);
        }
    };

    /**
     * 잠금을 잡아 본다 — 기다리지 않는다.
     *
     * @return 잡았으면 풀 때 닫을 손잡이. 다른 파드가 잡고 있으면 null
     * @throws IllegalStateException 잠금을 확인할 수 없다(Admin DB 연결 실패 등) — 돌리지 않는다(정형과 같다)
     */
    Held tryAcquire();

    /** 상태 화면용 — 켜짐 여부 · 키 · 대상. */
    Map<String, Object> describe();

    /** 잡은 잠금 — {@link #close()} 로 푼다. 여러 번 닫아도 된다. */
    @FunctionalInterface
    interface Held extends AutoCloseable {
        @Override
        void close();
    }
}
