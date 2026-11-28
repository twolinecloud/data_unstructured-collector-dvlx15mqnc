package egovframework.unstructured.collector.common.inbound;

import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.config.VoiceProperties.AcquireMode;
import egovframework.unstructured.collector.common.config.VoiceProperties.XvarmJoin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 파일 확보 방식 설정(2026-11-28 XVARM 제거 1단계) — 기본값이 <b>지금 동작 그대로</b>(BROKER · XVARM 조인 TRACE)인지,
 * 고를 수 없는 조합에서 기동이 멈추는지 본다.
 */
class InboundConfigTest {

    @Test
    @DisplayName("BROKER + TRACE(기본) 만 통과 — SYNCED 는 2단계 전이라 막고, BROKER 에 XVARM 조인을 빼면 막는다")
    void onlyBrokerWithTraceStarts() {
        assertThatCode(() -> InboundConfig.check(AcquireMode.BROKER, XvarmJoin.TRACE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InboundConfig.check(AcquireMode.SYNCED, XvarmJoin.TRACE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SYNCED").hasMessageContaining("2단계");
        assertThatThrownBy(() -> InboundConfig.check(AcquireMode.SYNCED, XvarmJoin.NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SYNCED");
        assertThatThrownBy(() -> InboundConfig.check(AcquireMode.BROKER, XvarmJoin.NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("xvarm-join=TRACE");
    }

    @Test
    @DisplayName("설정이 비면 지금 동작 그대로 — BROKER · TRACE · 사진 폴더 비움(ROOT 파생) · 동기화 규칙은 기본값")
    void defaultsKeepCurrentBehavior() {
        VoiceProperties p = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("voice", VoiceProperties.class);

        assertThat(p.acquire().mode()).isEqualTo(AcquireMode.BROKER);
        assertThat(p.source().xvarmJoin()).isEqualTo(XvarmJoin.TRACE);
        assertThat(p.dirs().receivePhoto()).isEmpty();
        assertThat(p.inbound()).isEqualTo(VoiceProperties.Inbound.defaults());
        assertThat(p.inbound().ignorePatterns()).containsExactly("*.tmp", "*.part", ".*");
        assertThat(p.inbound().reconcile().budgetMin()).isEqualTo(30);
        assertThat(p.inbound().deleteAfter()).isEqualTo(VoiceProperties.DeleteAfter.SUCCESS);
    }

    @Test
    @DisplayName("환경 변수 값(쉼표 목록 · 열거형)이 그대로 묶인다 — 제외 목록은 원소 3개")
    void bindsOverrides() {
        VoiceProperties p = new Binder(new MapConfigurationPropertySource(Map.of(
                "voice.acquire.mode", "SYNCED",
                "voice.source.xvarm-join", "NONE",
                "voice.dirs.receive-photo", "/k8s/unstructured_collector/esb/photo",
                "voice.inbound.naming", "ORIGINAL",
                "voice.inbound.ignore-patterns", "*.tmp,*.part,.*",
                "voice.inbound.reconcile.budget-min", "45",
                "voice.inbound.reconcile.done-marker", "_DONE",
                "voice.inbound.delete-after", "NEVER")))
                .bindOrCreate("voice", VoiceProperties.class);

        assertThat(p.acquire().mode()).isEqualTo(AcquireMode.SYNCED);
        assertThat(p.source().xvarmJoin()).isEqualTo(XvarmJoin.NONE);
        assertThat(p.dirs().receivePhoto()).isEqualTo("/k8s/unstructured_collector/esb/photo");
        assertThat(p.inbound().naming()).isEqualTo(VoiceProperties.InboundNaming.ORIGINAL);
        assertThat(p.inbound().ignorePatterns()).isEqualTo(List.of("*.tmp", "*.part", ".*"));
        assertThat(p.inbound().reconcile().budgetMin()).isEqualTo(45);
        assertThat(p.inbound().reconcile().intervalSec()).as("안 준 값은 기본").isEqualTo(300);
        assertThat(p.inbound().reconcile().doneMarker()).isEqualTo("_DONE");
        assertThat(p.inbound().deleteAfter()).isEqualTo(VoiceProperties.DeleteAfter.NEVER);
    }
}
