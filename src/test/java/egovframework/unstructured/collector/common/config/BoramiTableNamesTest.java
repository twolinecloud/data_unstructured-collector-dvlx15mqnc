package egovframework.unstructured.collector.common.config;

import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.sync.EsbFileNamingPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 테이블명 조립 — <b>같은 SQL 이 Mock(H2)과 실제 보라미 양쪽에서 돌게 하는 장치</b>.
 *
 * <p>실제 보라미는 테이블이 스키마로 나뉘어 있고({@code im.} / {@code re.} / {@code sm.}),
 * H2 Mock 은 평평하다. 이 차이를 SQL 두 벌이 아니라 설정으로 흡수한다.</p>
 *
 * <p>조립 결과는 MyBatis {@code ${}} 로 <b>치환</b>되므로(바인딩이 아니다) 형식 검증이 필수다.</p>
 */
class BoramiTableNamesTest {

    private BoramiTableNames tables(String imsc, String rerd, String smsm, String xvarm) {
        // 실 스키마 검증이 목적이라 XVARM 모드를 REAL 로, DB 를 PostgreSQL 로 고정한다
        return tables(imsc, rerd, smsm, xvarm, VoiceProperties.XvarmMode.REAL, DbKindDetector.DbKind.POSTGRESQL);
    }

    private BoramiTableNames tables(String imsc, String rerd, String smsm, String xvarm,
                                    VoiceProperties.XvarmMode xvarmMode, DbKindDetector.DbKind dbKind) {
        VoiceProperties p = new VoiceProperties(
                new VoiceProperties.Source(VoiceProperties.SourceMode.DIRECT_JDBC, "", "",
                        new VoiceProperties.Schema(imsc, rerd, smsm, xvarm, ""),
                        new VoiceProperties.Flag("Y", "Y", "N", "Y"),
                        xvarmMode, new VoiceProperties.XvarmMock("sm", "xvarm"),
                        new VoiceProperties.LocalH2("jdbc:h2:mem:t", "sa", "", 10, 0),
                        new VoiceProperties.DirectDb("jdbc:postgresql://localhost:1/x", "", "u", "", 1000, 1, 0, 60000)),
                new VoiceProperties.Broker(VoiceProperties.BrokerMode.MOCK, "", java.util.List.of(), 100, 10),
                new VoiceProperties.Phone(VoiceProperties.PhoneMode.MOCK),
                new VoiceProperties.Sync(10, 5, EsbFileNamingPolicy.Policy.ORIGINAL),
                new VoiceProperties.Dirs("b", "m", "p", "w", ""),
                new VoiceProperties.Decrypt(VoiceProperties.DecryptMode.SKIP, ""),
                new VoiceProperties.Stt(VoiceProperties.SttMode.MOCK, "", 30),
                new VoiceProperties.Batch("0 0 2 * * *", "0 */10 * * * *", 20, false,
                        List.of("0", "1"), 500, "VOICE_ANALYSIS", "TEST_BATCH", "UNSTRUCTURED", false),
                new VoiceProperties.Sim(false));
        egovframework.unstructured.collector.common.config.VoiceModeState mode = new egovframework.unstructured.collector.common.config.VoiceModeState(p, new egovframework.unstructured.collector.common.config.DeployEnvPreset(new org.springframework.mock.env.MockEnvironment(), p, ""));
        mode.resetToConfigured();
        return new BoramiTableNames(p, mode, DbKindDetector.fixed(dbKind));
    }

    @Test
    @DisplayName("XVARM MOCK_DEV — 공통파일·XVARM 은 설정 스키마 대신 우리가 만든 sm / xvarm 을 본다")
    void mockDevUsesOwnSchemas() {
        BoramiTableNames t = tables("im", "re", "", "", VoiceProperties.XvarmMode.MOCK_DEV, DbKindDetector.DbKind.POSTGRESQL);

        assertThat(t.smsmCmfiBs()).isEqualTo("sm.TB_SMSM_CMFI_BS");
        assertThat(t.asysContentElement()).isEqualTo("xvarm.ASYSCONTENTELEMENT");
        assertThat(t.imscPtprDt()).isEqualTo("im.TB_IMSC_PTPR_DT");
        assertThat(t.isXvarmMock()).isTrue();
    }

    @Test
    @DisplayName("H2 는 XVARM 모드·설정 스키마와 무관하게 전부 평평하다 — 스키마(im/re/sm)는 개발계 DB 에만 붙는다")
    void h2IsAlwaysFlat() {
        BoramiTableNames t = tables("im", "re", "sm", "xvarm", VoiceProperties.XvarmMode.MOCK_DEV, DbKindDetector.DbKind.H2);

        assertThat(t.smsmCmfiBs()).isEqualTo("TB_SMSM_CMFI_BS");
        assertThat(t.asysContentElement()).isEqualTo("ASYSCONTENTELEMENT");
        assertThat(t.imscPtprDt()).isEqualTo("TB_IMSC_PTPR_DT");
        assertThat(t.rerdTfinDs()).isEqualTo("TB_RERD_TFIN_DS");
        assertThat(t.imphUcdrDs()).isEqualTo("TB_IMPH_UCDR_DS");
    }

    @Test
    @DisplayName("스키마가 비면 테이블명만 쓴다 — H2 Mock 은 평평하다")
    void unqualifiedWhenSchemaBlank() {
        BoramiTableNames t = tables("", "", "", "");

        assertThat(t.imscPtprDt()).isEqualTo("TB_IMSC_PTPR_DT");
        assertThat(t.rerdTfinDs()).isEqualTo("TB_RERD_TFIN_DS");
        assertThat(t.smsmCmfiBs()).isEqualTo("TB_SMSM_CMFI_BS");
        assertThat(t.imphUcdrDs()).isEqualTo("TB_IMPH_UCDR_DS");
        assertThat(t.asysContentElement()).isEqualTo("ASYSCONTENTELEMENT");
    }

    @Test
    @DisplayName("실제 보라미 배치 — im / re / sm 으로 수식된다")
    void qualifiedWithRealSchemas() {
        BoramiTableNames t = tables("im", "re", "sm", "xvarm");

        assertThat(t.imscPtprDt()).isEqualTo("im.TB_IMSC_PTPR_DT");
        assertThat(t.rerdTfinDs()).isEqualTo("re.TB_RERD_TFIN_DS");
        assertThat(t.smsmCmfiBs()).isEqualTo("sm.TB_SMSM_CMFI_BS");
        assertThat(t.asysContentElement()).isEqualTo("xvarm.ASYSCONTENTELEMENT");
    }

    @Test
    @DisplayName("통화내역은 특이수용자와 같은 스키마다 — 실DB 에서 둘 다 im 이었다")
    void phoneSharesImscSchema() {
        BoramiTableNames t = tables("im", "re", "sm", "");

        assertThat(t.imphUcdrDs()).isEqualTo("im.TB_IMPH_UCDR_DS");
        assertThat(t.imscPtprDt()).startsWith("im.");
    }

    @Test
    @DisplayName("공백만 있는 스키마는 비운 것으로 본다")
    void blankIsTreatedAsUnqualified() {
        assertThat(tables("   ", "", "", "").imscPtprDt()).isEqualTo("TB_IMSC_PTPR_DT");
    }

    @Test
    @DisplayName("앞뒤 공백은 다듬는다")
    void trimsSchema() {
        assertThat(tables(" im ", "", "", "").imscPtprDt()).isEqualTo("im.TB_IMSC_PTPR_DT");
    }

    @Test
    @DisplayName("식별자 형식이 아닌 스키마는 거부한다 — ${} 치환이라 그대로 SQL 에 박힌다")
    void rejectsNonIdentifier() {
        assertThatThrownBy(() -> tables("im; DROP TABLE x--", "", "", "").imscPtprDt())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SQL 식별자 형식이 아니다");

        assertThatThrownBy(() -> tables("im.re", "", "", "").imscPtprDt())
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> tables("1im", "", "", "").imscPtprDt())
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> tables("im schema", "", "", "").imscPtprDt())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("describe 는 조립된 전체 이름을 보여 준다 — 기동 시 설정 확인용")
    void describeShowsAll() {
        String d = tables("im", "re", "sm", "").describe();

        assertThat(d).contains("im.TB_IMSC_PTPR_DT")
                     .contains("re.TB_RERD_TFIN_DS")
                     .contains("sm.TB_SMSM_CMFI_BS")
                     .contains("im.TB_IMPH_UCDR_DS");
    }
}
