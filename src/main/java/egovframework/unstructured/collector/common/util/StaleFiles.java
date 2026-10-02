package egovframework.unstructured.collector.common.util;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 남은 파일을 <b>확실히</b> 지운다 — 윈도우의 삭제 지연까지 보고 나서 지웠다고 말한다.
 *
 * <p><b>왜 이게 따로 필요한가</b>: 리눅스에서 {@code unlink} 는 열린 핸들이 있어도 이름을 즉시
 * 비워 준다. 윈도우는 다르다. 자바가 여는 파일에는 {@code FILE_SHARE_DELETE} 가 붙어 있어
 * {@link Files#delete} 자체는 <b>성공</b>하지만, 마지막 핸들이 닫힐 때까지 그 이름은
 * "삭제 대기(delete pending)" 로 남는다. 그 동안 같은 이름으로 파일을 만들면
 * {@code AccessDeniedException} 이 난다.</p>
 *
 * <p>그래서 [초기화] 직후 [생성] → [실행] 이 이렇게 깨졌다 — 초기화는 "3건 삭제" 라고 보고했는데
 * 이름이 아직 잡혀 있어 브로커/ESB 가 수신 파일을 못 만들고, 수집기는 오지 않을 파일을
 * 기다리다 <b>수신 파일 대기 타임아웃</b>으로 끝났다. 삭제한 쪽은 성공했다고 믿고 있어서
 * 원인이 로그 어디에도 남지 않았다.</p>
 *
 * <p>여기서는 지운 뒤 <b>그 이름이 실제로 비었는지</b>까지 확인한다. 확인 방법은 만들어 보는
 * 것이다 — 삭제 대기 중이면 생성이 거부되므로, 거부되지 않을 때까지 짧게 기다렸다 다시 본다.</p>
 */
@Log4j2
public final class StaleFiles {

    /** 핸들이 닫히기를 기다리는 총 시간. 이보다 오래 잡혀 있으면 사람이 봐야 할 문제다. */
    private static final long WAIT_MS = 2_000;
    private static final long STEP_MS = 100;

    private StaleFiles() {
    }

    /**
     * 파일 하나를 지우고, <b>이름이 비었는지 확인될 때까지</b> 기다린다.
     *
     * @return 이름이 비었으면 {@code true}. 시간 안에 비지 않으면 {@code false}
     */
    public static boolean delete(Path file) {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        IOException last = null;
        while (true) {
            try {
                Files.deleteIfExists(file);
                if (nameIsFree(file)) {
                    return true;
                }
            } catch (IOException e) {
                last = e;          // 다른 프로세스가 쥐고 있다 — 놓기를 기다린다
            }
            if (System.currentTimeMillis() >= deadline) {
                log.warn("[Cleanup] 파일을 비우지 못했다 — {} ({}) · 다른 프로세스가 열고 있을 수 있다",
                        file, last == null ? "삭제 대기 상태가 풀리지 않음" : last.getMessage());
                return false;
            }
            sleep();
        }
    }

    /**
     * 이름이 실제로 쓸 수 있는 상태인지 본다.
     *
     * <p>{@link Files#exists} 로는 모자란다. 삭제 대기 중인 이름은 존재하지 않는 것으로 보이면서도
     * 생성은 거부한다 — 우리가 알고 싶은 것은 "보이는가" 가 아니라 "쓸 수 있는가" 다.
     * 그래서 빈 파일을 만들어 보고 바로 지운다.</p>
     */
    private static boolean nameIsFree(Path file) {
        try {
            Files.newByteChannel(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close();
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 디렉터리 안의 <b>파일만</b> 지운다(하위 디렉터리는 건드리지 않는다).
     *
     * <p>재귀 삭제를 쓰지 않는 이유: 설정이 잘못돼 엉뚱한 경로가 들어오면 피해가 걷잡을 수 없다.
     * 초기화에 필요한 것은 평평한 파일 목록뿐이다.</p>
     */
    public static Result deleteAllIn(Path dir) {
        return deleteMatching(dir, name -> true);
    }

    /**
     * 위와 같되, 파일명이 조건에 맞는 것만 지운다 — 용도별 초기화(시뮬레이터 / 대시보드 더미)가 서로의 파일을 건드리지 않게.
     */
    public static Result deleteMatching(Path dir, java.util.function.Predicate<String> name) {
        if (!Files.isDirectory(dir)) {
            return new Result(0, List.of());
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile).filter(f -> name.test(f.getFileName().toString())).sorted().toList();
        } catch (IOException e) {
            log.warn("[Cleanup] 디렉터리 조회 실패 — {} ({})", dir, e.getMessage());
            return new Result(0, List.of());
        }
        int deleted = 0;
        List<String> stuck = new ArrayList<>();
        for (Path f : files) {
            if (delete(f)) {
                deleted++;
            } else {
                stuck.add(f.getFileName().toString());
            }
        }
        if (!stuck.isEmpty()) {
            log.warn("[Cleanup] {} — {}건 삭제, {}건 남음 {} · 이 상태로 다시 돌리면 수신 대기가 타임아웃 날 수 있다",
                    dir, deleted, stuck.size(), stuck);
        }
        return new Result(deleted, stuck);
    }

    /**
     * 지운 결과.
     *
     * @param deleted 지워진 건수
     * @param stuck   끝내 지우지 못한 파일명 — <b>비어 있지 않으면 초기화가 끝난 것이 아니다</b>
     */
    public record Result(int deleted, List<String> stuck) {

        public boolean clean() {
            return stuck.isEmpty();
        }
    }

    /** 디렉터리에 지금 무엇이 들어 있는지 — 타임아웃 났을 때 사람이 볼 목록. */
    public static List<String> names(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .limit(20)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(STEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("파일 정리 대기 중 인터럽트", e);
        }
    }
}
