package egovframework.unstructured.collector.voice.risk;

import egovframework.unstructured.collector.voice.stt.genon.GenonSttProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 제논 음성 API 가이드 11-19판 기본값 — 코드 기본값({@code @DefaultValue})과 {@code application.yml} 기본값(환경변수가 없을 때)이
 * 둘 다 같은 값을 가리키는지 본다. 이 PC · CI 의 {@code GENON_*} · {@code VOICE_RISK_WF9_*} 환경변수는 보지 않는다
 * (YAML 한 파일만 원천으로 두고 자리표시자를 그 안에서만 푼다 — 레포에 적힌 기본값만 본다).
 */
class GenonGuide1119DefaultsTest {

    private static final Path YML = Path.of("src/main/resources/application.yml");

    @Test
    @DisplayName("음성 API — 주소 1007/9956 · audio/process 읽기 시한 300초 이상 · 한도 256MiB/3900초 · 차례 대기 > 시한 · 분할 조각 1800초")
    void sttDefaults() throws IOException {
        for (GenonSttProperties g : List.of(code("genon.stt", GenonSttProperties.class), yml("genon.stt", GenonSttProperties.class))) {
            assertThat(g.baseUrl()).isEqualTo("https://genos.genon.ai/api/gateway/code_serving/1007/9956");
            assertThat(g.timeoutSec()).as("30분 전처리 예상 약 133초(20분 실측 88.8초)").isGreaterThanOrEqualTo(300);
            assertThat(g.maxBytes()).isEqualTo(268_435_456L);
            assertThat(g.maxDurationSec()).isEqualTo(3900);
            assertThat(g.maxConcurrency()).as("키 한도 동시 1건").isEqualTo(1);
            assertThat(g.queueTimeoutSec()).as("앞 건 하나가 시한까지 쥐어도 기다린다").isGreaterThan(g.timeoutSec());
            assertThat(g.chunkMaxSec()).as("조각 하나는 운영 상한 30분 이하").isEqualTo(1800);
            assertThat(g.mode()).as("기본은 외부망을 부르지 않는다").isEqualTo(GenonSttProperties.Mode.MOCK);
            assertThat(g.apiKey()).as("키는 레포에 없다").isEmpty();
        }
        assertThat(yml("genon.stt", GenonSttProperties.class).keyExpiresOn()).isEqualTo("2026-12-11");
    }

    @Test
    @DisplayName("WF9 — /chat 시한 300초 이상 · 분석 길이 1800초(운영 상한 30분) · 위험 분류 첫 후보 risk_type · 접촉 구분 type 은 후보 아님")
    void wf9Defaults() throws IOException {
        for (RiskWf9Properties w : List.of(code("voice.risk-wf9", RiskWf9Properties.class), yml("voice.risk-wf9", RiskWf9Properties.class))) {
            assertThat(w.portalTimeoutSec()).as("SSE end 까지 전체").isGreaterThanOrEqualTo(300);
            assertThat(w.analyzeMaxSec()).isEqualTo(1800);
            assertThat(w.contentFields()).first().isEqualTo("content");
            assertThat(w.riskClassFields()).first().isEqualTo("risk_type");
            assertThat(w.riskClassFields()).as("실응답 type = 접견 · 전화").doesNotContain("type");
            assertThat(w.portalEndpoint()).isEqualTo("pt6i_10rw_jsd9");
            assertThat(w.deidTimeoutSec()).as("커넥터가 AI-R 을 600초까지 기다린다").isGreaterThan(600);
            assertThat(w.enabled()).as("외부망 · Secret 결정 전까지 꺼 둔다").isFalse();
            assertThat(w.portalMode()).isEqualTo(RiskWf9Properties.PortalMode.MOCK);
            assertThat(w.hasPortalAccount()).as("계정은 레포에 없다").isFalse();
        }
        // 목록 기본값 — 코드(@DefaultValue)도 쪼갠 목록이어야 한다(예전엔 쉼표 문자열 하나 → 원소 하나짜리 목록이었다)
        RiskWf9Properties c = code("voice.risk-wf9", RiskWf9Properties.class);
        RiskWf9Properties y = yml("voice.risk-wf9", RiskWf9Properties.class);
        assertThat(c.contentFields()).hasSize(12).isEqualTo(y.contentFields());
        assertThat(c.riskClassFields()).hasSize(12).isEqualTo(y.riskClassFields());
    }

    private static <T> T code(String prefix, Class<T> type) {
        return new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate(prefix, type);
    }

    private static <T> T yml(String prefix, Class<T> type) throws IOException {
        assertThat(Files.isRegularFile(YML)).as("작업 폴더가 레포 뿌리여야 한다 — " + YML.toAbsolutePath()).isTrue();
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application.yml", new FileSystemResource(YML)).forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bindOrCreate(prefix, type);
    }
}
