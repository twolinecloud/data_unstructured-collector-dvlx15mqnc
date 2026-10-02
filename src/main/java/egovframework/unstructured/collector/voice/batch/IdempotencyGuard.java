package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 같은 파일을 두 번 처리하지 않게 막는다.
 *
 * <p><b>왜 필요한가</b>: 주기배치가 10분마다 돌면서 "20분 전 것까지" 훑기로 되어 있어
 * (2026-09-11 회의 {@code [00:19:01]}) <b>연속 실행의 시간창이 겹친다</b>.
 * 막지 않으면 같은 음성을 두 번 STT 에 태우고, T4 도 두 줄이 되어 정합성 대사가 깨진다.</p>
 *
 * <p><b>방식</b>: 처리 완료 표식을 작업 디렉터리에 빈 파일로 남긴다. PV 에 쓰이므로
 * 파드가 재시작돼도 살아남는다. DB 를 새로 두지 않는 이유는 R&amp;R 때문이다 —
 * 이 서비스는 자체 상태 테이블을 만들지 않기로 했다.</p>
 *
 * <p><b>한계</b>: 레플리카를 2 이상으로 늘리면 같은 PV 를 공유해야 하고, 동시에 같은 대상을
 * 집는 경쟁이 생긴다. 그래서 초기 구성은 {@code replicas: 1} 이다. 수평 확장을 하려면
 * 컬렉터의 T4 조회로 판정하도록 바꿔야 한다(계획서 Q4 — 조회 필터 지원 여부에 달렸다).</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class IdempotencyGuard {

    private static final String MARKER_DIR = ".processed";

    private final VoiceDirState dirs;

    /** 이미 처리된 대상인가. */
    public boolean isProcessed(VoiceTarget target) {
        return Files.exists(markerOf(target));
    }

    /**
     * 처리 완료 표식 전체를 <b>한 번에</b> 읽어 둔다 — 넓은 창을 훑을 때 쓴다.
     *
     * <p>{@link #isProcessed} 는 건마다 파일을 stat 한다. 수신 폴더가 NFS 인 배포 환경에서 수천 건을
     * 그렇게 물으면 그것만으로 수십 초가 간다. 폴더를 한 번 읽어(READDIR) 이름 집합으로 대조한다.</p>
     */
    public java.util.function.Predicate<VoiceTarget> snapshot() {
        java.util.Set<String> names = new java.util.HashSet<>();
        Path dir = markerDir();
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                s.forEach(p -> names.add(p.getFileName().toString()));
            } catch (IOException e) {
                // 목록을 못 읽으면 건별 판정으로 물러난다 — 느려도 틀리지는 않는다
                log.warn("[Idempotency] 표식 목록 실패 — {} ({}) · 건별 판정으로 대신한다", dir, e.getMessage());
                return this::isProcessed;
            }
        }
        return t -> names.contains(markerOf(t).getFileName().toString());
    }

    /** 처리 완료로 표시한다. */
    public void markProcessed(VoiceTarget target) {
        Path marker = markerOf(target);
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, String.valueOf(System.currentTimeMillis()));
        } catch (IOException e) {
            // 표식을 못 남겨도 이번 처리는 성공한 것이다 — 다음 실행에서 중복될 뿐이라 경고만 남긴다.
            log.warn("[Idempotency] 완료 표식 기록 실패 — {} ({})", marker, e.getMessage());
        }
    }

    /** 표식을 지운다(재처리·시연 초기화용). */
    public boolean clear(VoiceTarget target) {
        try {
            return Files.deleteIfExists(markerOf(target));
        } catch (IOException e) {
            log.warn("[Idempotency] 표식 삭제 실패 — {}", e.getMessage());
            return false;
        }
    }

    /** 표식을 전부 지운다(시연 초기화). 처리한 건수를 돌려준다. */
    public int clearAll() {
        Path dir = markerDir();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            int[] count = {0};
            stream.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                    count[0]++;
                } catch (IOException ignored) {
                    // 지우지 못한 표식은 남겨 둔다 — 중복 처리보다 낫다
                }
            });
            log.info("[Idempotency] 표식 {}건 삭제", count[0]);
            return count[0];
        } catch (IOException e) {
            log.warn("[Idempotency] 표식 목록 조회 실패 — {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 이름이 조건에 맞는 표식만 지운다 — 용도별 초기화(시뮬레이터 / 대시보드 더미)가 서로의 표식을 건드리지 않게.
     *
     * @param name 표식 파일명({@code meet-DMY-MEET-…} 처럼 {@code 종류-키}) 조건
     * @return 지운 건수
     */
    public int clearMatching(java.util.function.Predicate<String> name) {
        Path dir = markerDir();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.filter(f -> name.test(f.getFileName().toString())).toList()) {
                try {
                    if (Files.deleteIfExists(p)) {
                        n++;
                    }
                } catch (IOException ignored) {
                    // 지우지 못한 표식은 남겨 둔다 — 중복 처리보다 낫다
                }
            }
        } catch (IOException e) {
            log.warn("[Idempotency] 표식 목록 조회 실패 — {}", e.getMessage());
        }
        if (n > 0) {
            log.info("[Idempotency] 표식 {}건 삭제(조건부)", n);
        }
        return n;
    }

    private Path markerDir() {
        return Path.of(dirs.work(), MARKER_DIR);
    }

    /** 파일명에 쓸 수 없는 문자를 걸러 낸다 — 키에 경로 구분자가 들어오면 엉뚱한 곳에 쓰게 된다. */
    private Path markerOf(VoiceTarget target) {
        String safe = target.shortId().replaceAll("[^A-Za-z0-9_.-]", "_");
        return markerDir().resolve(safe);
    }
}
