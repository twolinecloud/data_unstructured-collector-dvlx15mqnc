package egovframework.unstructured.collector.common.inbound;

import egovframework.unstructured.collector.common.broker.BrokerAcquirer;
import egovframework.unstructured.collector.common.broker.MockXvarmBrokerClient;
import egovframework.unstructured.collector.common.broker.XvarmBrokerClient;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.config.VoiceProperties.AcquireMode;
import egovframework.unstructured.collector.common.config.VoiceProperties.XvarmJoin;
import egovframework.unstructured.collector.common.sync.FileArrivalWatcher;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 파일 확보 방식({@link SourceFileAcquirer})을 <b>한 군데에서</b> 고른다 — {@code voice.acquire.mode}.
 *
 * <p>1단계(2026-11-28)에는 BROKER 뿐이다. SYNCED 는 동기화 폴더 방식이 들어오는 2단계에서 연결한다 — 그 전에 SYNCED 를 고르면
 * 아무 일도 하지 않는 대신 <b>기동을 막는다</b>. 조용히 BROKER 로 돌면 '바꿨다고 믿는 설정'으로 운영이 돌게 된다.</p>
 *
 * <p>BROKER 에 XVARM 조인을 빼면({@code voice.source.xvarm-join=NONE}) FILEKEY 가 비어 사진은 전건 'FILEKEY 추출' 실패,
 * 접견은 빈 FILEKEY 로 브로커를 부르게 된다. 이 조합도 기동을 막는다.</p>
 */
@Log4j2
@Configuration
public class InboundConfig {

    @Bean
    public SourceFileAcquirer sourceFileAcquirer(VoiceProperties props, VoiceDirState dirs, XvarmBrokerClient broker,
                                                 MockXvarmBrokerClient mockBroker, FileArrivalWatcher watcher,
                                                 VoiceModeState modeState) {
        AcquireMode mode = props.acquire().mode();
        XvarmJoin join = props.source().xvarmJoin();
        check(mode, join);
        SourceFileAcquirer acquirer = new BrokerAcquirer(broker, mockBroker, watcher, dirs, modeState);
        VoiceProperties.Inbound in = props.inbound();
        log.info("[Mode] 파일 확보 — acquire={} · {} · xvarm-join={} · 사진 수신=접견 수신 폴더 공용({}) | "
                        + "동기화 폴더 규칙(SYNCED 에서 씀 · 2단계): 사진 폴더 {} · 이름 {} · 날짜 폴더 {}(최근 {}일) · 대조 {}초 간격 · 예산 {}분 · "
                        + "끝 표식 {} · 지우기 {} · 보관 {}일 · 제외 {}",
                mode, acquirer.mode(), join, dirs.receiveMeet(), dirs.receivePhoto(), in.naming(),
                in.dateSubdir() ? "씀" : "안 씀", in.lookbackDays(), in.reconcile().intervalSec(), in.reconcile().budgetMin(),
                in.reconcile().doneMarker().isBlank() ? "안 봄" : in.reconcile().doneMarker(), in.deleteAfter(),
                in.retentionDays(), in.ignorePatterns());
        return acquirer;
    }

    /**
     * 고를 수 없는 조합이면 사유와 함께 던진다 — 기동이 멈춘다.
     *
     * @throws IllegalStateException SYNCED(아직 구현 전) · BROKER + XVARM 조인 없음
     */
    static void check(AcquireMode mode, XvarmJoin join) {
        if (mode == AcquireMode.SYNCED) {
            throw new IllegalStateException("voice.acquire.mode=SYNCED 는 아직 쓸 수 없다 — 동기화 폴더 방식은 2단계에서 연결한다. "
                    + "지금은 BROKER 로 둔다(VOICE_ACQUIRE_MODE)");
        }
        if (mode == AcquireMode.BROKER && join == XvarmJoin.NONE) {
            throw new IllegalStateException("voice.acquire.mode=BROKER 에는 voice.source.xvarm-join=TRACE 가 필요하다 — "
                    + "브로커가 FILEKEY(XVARM 저장 위치)로 원본을 찾는다(VOICE_XVARM_JOIN)");
        }
    }
}
