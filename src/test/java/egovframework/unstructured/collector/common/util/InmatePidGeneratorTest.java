package egovframework.unstructured.collector.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비식별 수용자 ID — 두 성질을 동시에 만족해야 한다.
 * <b>같은 사람은 늘 같은 값</b>(통계가 성립해야 함) 이면서
 * <b>교정번호가 드러나지 않을 것</b>(로그·페이로드에 실리므로).
 */
class InmatePidGeneratorTest {

    private InmatePidGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new InmatePidGenerator();
        ReflectionTestUtils.setField(generator, "salt", "test-salt");
        ReflectionTestUtils.setField(generator, "prefix", "VP");
    }

    @Test
    @DisplayName("같은 교정번호는 항상 같은 PID 가 된다")
    void deterministic() {
        String a = generator.of("MOCK0000000000001");
        String b = generator.of("MOCK0000000000001");

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("다른 교정번호는 다른 PID 가 된다")
    void distinct() {
        assertThat(generator.of("MOCK0000000000001"))
                .isNotEqualTo(generator.of("MOCK0000000000002"));
    }

    @Test
    @DisplayName("PID 에 교정번호가 그대로 드러나지 않는다")
    void doesNotLeakCorrNo() {
        String corrNo = "MOCK0000000000001";

        String pid = generator.of(corrNo);

        assertThat(pid).doesNotContain(corrNo);
        assertThat(pid).startsWith("VP").hasSize(18);   // prefix 2 + hex 16
    }

    @Test
    @DisplayName("salt 가 다르면 같은 교정번호라도 다른 PID 가 된다")
    void saltChangesEverything() {
        String before = generator.of("MOCK0000000000001");
        ReflectionTestUtils.setField(generator, "salt", "another-salt");

        assertThat(generator.of("MOCK0000000000001")).isNotEqualTo(before);
    }

    @Test
    @DisplayName("교정번호가 없어도 터지지 않는다")
    void handlesNull() {
        assertThat(generator.of(null)).isEqualTo("VPUNKNOWN");
        assertThat(generator.of("  ")).isEqualTo("VPUNKNOWN");
    }
}
