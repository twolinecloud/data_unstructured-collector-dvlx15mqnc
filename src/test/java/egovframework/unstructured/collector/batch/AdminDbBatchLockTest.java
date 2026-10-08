package egovframework.unstructured.collector.batch;

import egovframework.unstructured.collector.image.config.ImageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 파드 간 실행 잠금 — Admin DB 가 PostgreSQL 이 아니면(로컬 H2) 끄고 늘 잡힌다. PostgreSQL 동작(advisory lock)은 개발계에서 확인한다
 * (H2 에는 {@code pg_try_advisory_lock} 이 없다).
 */
class AdminDbBatchLockTest {

    private static ImageProperties props(String url) {
        ImageProperties p = mock(ImageProperties.class);
        when(p.adminDb()).thenReturn(new ImageProperties.AdminDb(url, "sa", "", "kcais", 10, 500, 30000));
        return p;
    }

    @Test
    @DisplayName("PostgreSQL 에 붙지 못하면 IllegalStateException — 실행기가 LOCK 으로 거절한다(돌리지 않는다)")
    void unreachableThrows() {
        AdminDbBatchLock lock = new AdminDbBatchLock(props("jdbc:postgresql://127.0.0.1:1/none"), true, 42120002L);
        assertThat(lock.describe()).containsEntry("enabled", true);
        assertThatThrownBy(lock::tryAcquire).isInstanceOf(IllegalStateException.class).hasMessageContaining("실행 잠금 확인 실패");
        lock.destroy();
    }

    @Test
    @DisplayName("로컬 H2 — 잠금을 쓰지 않는다(늘 잡힘 · 닫아도 그만)")
    void h2Disabled() {
        AdminDbBatchLock lock = new AdminDbBatchLock(props("jdbc:h2:mem:admin"), true, 42120002L);
        assertThat(lock.describe()).containsEntry("enabled", false).containsEntry("key", 42120002L);
        BatchLock.Held held = lock.tryAcquire();
        assertThat(held).isNotNull();
        held.close();
        held.close();
        lock.destroy();
    }

    @Test
    @DisplayName("설정으로 끄면 PostgreSQL 이어도 쓰지 않는다 — 연결을 열지 않는다")
    void configOff() {
        AdminDbBatchLock lock = new AdminDbBatchLock(props("jdbc:postgresql://127.0.0.1:1/none"), false, 42120002L);
        assertThat(lock.describe()).containsEntry("enabled", false);
        assertThat(lock.tryAcquire()).isNotNull();
        lock.destroy();
    }
}
