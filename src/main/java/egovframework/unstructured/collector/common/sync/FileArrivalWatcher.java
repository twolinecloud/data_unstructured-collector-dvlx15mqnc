package egovframework.unstructured.collector.common.sync;

import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.model.VoiceFile;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.util.AudioFormatDetector;
import egovframework.unstructured.collector.common.util.StaleFiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * ESB 가 동기화해 준 파일이 도착했는지 보고, <b>쓰기가 끝났는지</b>까지 확인한 뒤 넘긴다.
 *
 * <p><b>왜 크기 안정성까지 보는가</b>: ESB Agent 가 파일을 쓰는 도중에 우리가 읽으면 잘린
 * 오디오를 STT 에 태우게 된다. 표준 A 는 {@code rcvtmp}(작업용) → {@code rcv}(완료) 로 옮기는
 * 디렉터리 규약을 두고 있어 원칙적으로는 {@code rcv} 에 있는 것만 보면 되지만, 그 규약이
 * 실제로 적용되는지 확정되지 않았고(Q7·Q14) 로컬 Mock 에는 적용되지 않는다.
 * 그래서 <b>크기가 일정 시간 그대로인지</b>를 한 번 더 본다 — 규약과 무관하게 성립하는 안전장치다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class FileArrivalWatcher {

    private final VoiceProperties props;
    private final VoiceDirState dirs;
    private final EsbFileNamingPolicy namingPolicy;

    /** 파일명을 정책으로 추측해 기다린다. 공급자가 이름을 알려주지 않는 경우(전화)에 쓴다. */
    public VoiceFile await(VoiceTarget target) {
        return await(target, null);
    }

    /**
     * 대상에 해당하는 파일이 수신 디렉터리에 나타날 때까지 기다린다.
     *
     * @param hintFileName 파일을 만든 쪽이 알려준 실제 파일명. 있으면 <b>이것을 우선</b>한다.
     *                     <p>브로커는 자기가 정한 이름으로 파일을 만든다. 우리가 정책으로
     *                     추측한 이름과 다르면 영영 찾지 못하고 타임아웃이 난다 — Mock 브로커는
     *                     같은 정책을 써서 우연히 일치했을 뿐이다. 실제 XVARM 이 원본명을
     *                     어떻게 정하는지 모르므로(계획서 Q3), 알려준 이름이 있으면 그걸 믿는다.</p>
     * @return 도착·안정 확인된 파일
     * @throws IllegalStateException 타임아웃
     */
    public VoiceFile await(VoiceTarget target, String hintFileName) {
        Path dir = dirFor(target.kind());
        String name = StringUtils.hasText(hintFileName)
                ? hintFileName
                : namingPolicy.expectedFileName(target, props.sync().namingPolicy());
        Path file = dir.resolve(name);

        long deadline = System.currentTimeMillis() + props.sync().waitTimeoutSec() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (arrived(dir, name) && isStable(file)) {
                return describe(target, file);
            }
            sleep(Math.max(props.sync().stableCheckMs() / 2, 200));
        }
        // 무엇을 기다렸는지만 적으면 원인을 못 찾는다 — 폴더에 지금 무엇이 있는지까지 같이 남긴다.
        List<String> present = StaleFiles.names(dir);
        throw new IllegalStateException("수신 파일 대기 타임아웃 — %s (%d초, dir=%s) · 폴더 현황: %s"
                .formatted(name, props.sync().waitTimeoutSec(), dir,
                        present.isEmpty() ? "비어 있음(아무도 파일을 만들지 않았다)" : present));
    }

    /**
     * 추출을 <b>지시하기 직전</b>에 호출한다 — 그 이름에 남아 있는 지난 배치의 파일을 치운다.
     *
     * <p><b>왜 대기 중이 아니라 요청 전인가</b>: 수집은 언제나 "요청 → 대기" 순서다. 요청 전에
     * 그 이름이 있으면 그것은 이번 요청의 산출물일 수 없다 — <b>시계를 보지 않고</b> 잔재라고
     * 단정할 수 있는 유일한 시점이다.</p>
     *
     * <p>처음에는 대기 중에 "요청 시각보다 오래된 파일" 을 골라내려 했는데, 벽시계
     * ({@code System.currentTimeMillis()})와 파일시스템 mtime 을 비교하는 방식이었다.
     * mtime 해상도가 거친 파일시스템(컨테이너의 overlayfs 등)에서는 <b>방금 쓴 파일이
     * 요청 시각보다 이전으로 보인다</b>. 그래서 갓 만들어진 파일을 잔재로 알고 지워 버렸고,
     * CI 에서 수집 단계가 통째로 대기 타임아웃으로 죽었다. 시계 비교를 걷어낸 이유다.</p>
     *
     * <p>치우지 않으면 두 가지로 깨진다 — ① 옛 음성을 새 것인 양 STT 에 태우거나,
     * ② 윈도우에서 그 이름이 삭제 대기로 잡혀 브로커·ESB 가 새 파일을 만들지 못한다.</p>
     *
     * @return 실제로 치운 것이 있으면 {@code true}
     */
    public boolean clearStale(VoiceTarget target) {
        Path file = dirFor(target.kind())
                .resolve(namingPolicy.expectedFileName(target, props.sync().namingPolicy()));
        if (!Files.exists(file)) {
            return false;
        }
        log.warn("[Sync] 지난 배치의 잔재를 치운다 — {} (요청 전부터 있던 파일)", file.getFileName());
        StaleFiles.delete(file);
        return true;
    }

    /**
     * 정해진 폴더에 정해진 이름의 파일이 도착해 안정될 때까지 기다린다 — 대상 모델 없이(수용자 이미지).
     *
     * @return 도착한 파일
     * @throws IllegalStateException 타임아웃 — 폴더 현황을 같이 싣는다
     */
    public Path awaitFile(Path dir, String fileName) {
        Path file = dir.resolve(fileName);
        long deadline = System.currentTimeMillis() + props.sync().waitTimeoutSec() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (arrived(dir, fileName) && isStable(file)) {
                return file;
            }
            sleep(Math.max(props.sync().stableCheckMs() / 2, 200));
        }
        List<String> present = StaleFiles.names(dir);
        throw new IllegalStateException("수신 파일 대기 타임아웃 — %s (%d초, dir=%s) · 폴더 현황: %s"
                .formatted(fileName, props.sync().waitTimeoutSec(), dir,
                        present.isEmpty() ? "비어 있음(아무도 파일을 만들지 않았다)" : present));
    }

    /** 요청 직전 — 그 이름에 남아 있는 지난 요청의 파일을 치운다({@link #clearStale(VoiceTarget)} 와 같은 원칙). */
    public boolean clearStale(Path dir, String fileName) {
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) {
            return false;
        }
        log.warn("[Sync] 지난 요청의 잔재를 치운다 — {} (요청 전부터 있던 파일)", file.getFileName());
        StaleFiles.delete(file);
        return true;
    }

    /** 이미 와 있는지만 즉시 확인한다(대기 없음). */
    public boolean isArrived(VoiceTarget target) {
        return arrived(dirFor(target.kind()),
                namingPolicy.expectedFileName(target, props.sync().namingPolicy()));
    }

    private Path dirFor(VoiceKind kind) {
        return dirs.receiveDir(kind);
    }

    /**
     * 파일이 도착했는가 — <b>디렉터리를 읽어서</b> 확인한다. {@code Files.exists} 를 쓰지 않는다.
     *
     * <p><b>왜 굳이 목록을 훑나</b>: 수신 폴더는 NFS 이고, 파일을 만드는 쪽은 <b>다른 파드</b>
     * (브로커·ESB)다. {@code exists()} 로 없는 이름을 한 번 물어보면 그 "없음" 이 커널의
     * negative dentry 에 캐시되고, 이후 호출은 그 캐시로 답한다. 갱신 시점은 부모 디렉터리의
     * 속성 캐시가 만료될 때인데, 이 마운트는 {@code ac*} 옵션이 없어 기본값
     * {@code acdirmin=30초} 가 걸린다 — <b>파일은 이미 디스크에 있는데 30초를 기다렸다.</b></p>
     *
     * <p>개발계 실측: 브로커가 1.5초에 산출을 끝냈는데 수집기는 29초 뒤에야 인지했고,
     * 일배치 10건이 184초 걸렸다. 같은 폴더를 계속 {@code ls} 하는 프로세스를 하나 띄워 두자
     * 12.3초로 떨어졌다 — READDIR 이 그 캐시를 깨기 때문이다. 그 일을 여기서 직접 한다.</p>
     *
     * <p>수신 폴더에는 이번 배치가 기다리는 파일 몇 개뿐이라 목록 비용은 무시할 만하다.
     * 같은 파일시스템을 로컬로 쓰는 환경에서는 {@code exists()} 와 차이가 없다.</p>
     */
    private boolean arrived(Path dir, String name) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(p -> name.equals(p.getFileName().toString()));
        } catch (IOException e) {
            // 폴더가 아직 없을 수 있다 — 실패가 아니라 '아직'이다.
            return false;
        }
    }

    /** 크기가 {@code stableCheckMs} 동안 변하지 않으면 쓰기가 끝난 것으로 본다. */
    private boolean isStable(Path file) {
        try {
            long first = Files.size(file);
            if (first <= 0) {
                return false;
            }
            sleep(props.sync().stableCheckMs());
            return Files.size(file) == first;
        } catch (IOException e) {
            // 아직 쓰는 중이면 예외가 날 수 있다 — 실패가 아니라 '아직'이다.
            return false;
        }
    }

    private VoiceFile describe(VoiceTarget target, Path file) {
        try {
            long size = Files.size(file);
            // 파일명이 .DAT 일 수 있으므로 매직 넘버를 먼저 본다.
            String format = AudioFormatDetector.detect(file, target.srcFileName());
            log.info("[Sync] 파일 확인 — {} ({} bytes, format={})", file.getFileName(), size, format);
            return new VoiceFile(target, file, size, format, false);
        } catch (IOException e) {
            throw new IllegalStateException("수신 파일 확인 실패: " + file, e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("파일 대기 중 인터럽트", e);
        }
    }
}
