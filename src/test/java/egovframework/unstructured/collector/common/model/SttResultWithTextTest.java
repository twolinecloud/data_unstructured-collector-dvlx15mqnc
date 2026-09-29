package egovframework.unstructured.collector.common.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 비식별된 텍스트로 바꿀 때 구간 텍스트도 같이 바꾼다 — 전문만 바꾸면 구간에 원문이 남는다. */
class SttResultWithTextTest {

    private static SttResult raw() {
        return new SttResult("저 김수용인데요\n연락처 010-1234-5678", "MOCK", 9.0, false, "ko", List.of(
                new SttResult.Segment(0, 0, 4.5, "저 김수용인데요"),
                new SttResult.Segment(1, 4.5, 9.0, "연락처 010-1234-5678")));
    }

    @Test
    @DisplayName("줄 수가 구간 수와 같으면 구간마다 바꾼 줄을 넣는다 · 시각은 그대로")
    void alignsSegments() {
        SttResult r = raw().withText("저 ***인데요\n연락처 010-****-5678");
        assertThat(r.segments()).extracting(SttResult.Segment::text).containsExactly("저 ***인데요", "연락처 010-****-5678");
        assertThat(r.segments().get(1).start()).isEqualTo(4.5);
    }

    @Test
    @DisplayName("줄 수가 다르면 구간 텍스트를 비운다 — 원문을 남기지 않는다")
    void clearsWhenMisaligned() {
        SttResult r = raw().withText("한 줄로 합쳐진 *** 텍스트");
        assertThat(r.segments()).extracting(SttResult.Segment::text).containsOnly("");
    }

    @Test
    @DisplayName("같은 텍스트(단순 전달)면 그대로")
    void sameTextIsIdentity() {
        SttResult r = raw();
        assertThat(r.withText(r.text())).isSameAs(r);
    }
}
