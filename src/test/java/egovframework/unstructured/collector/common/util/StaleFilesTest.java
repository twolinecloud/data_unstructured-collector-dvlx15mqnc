package egovframework.unstructured.collector.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 남은 파일 정리 — <b>"지웠다"가 "그 이름을 다시 쓸 수 있다"와 같은 말이어야 한다.</b>
 *
 * <p>윈도우에서 [초기화] → [생성] → [실행] 이 수신 대기 타임아웃으로 끝나던 원인을 잡는 자리다.
 * 삭제 자체는 성공했다고 보고되는데 이름이 삭제 대기로 잡혀 있어, 브로커가 같은 이름으로
 * 파일을 만들지 못하고 수집기는 오지 않을 파일을 기다렸다.</p>
 */
class StaleFilesTest {

    @TempDir
    Path dir;

    private Path file(String name, String body) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, body);
        return f;
    }

    @Test
    @DisplayName("지운 뒤 같은 이름으로 다시 만들 수 있다 — 이것이 '지웠다'의 정의다")
    void deleteFreesTheName() throws IOException {
        Path f = file("mock_meet_001.m4a", "old");

        assertThat(StaleFiles.delete(f)).isTrue();
        assertThat(f).doesNotExist();

        // 브로커가 같은 이름으로 새로 만드는 동작 — 여기서 막히면 수신 대기가 타임아웃 난다
        Files.writeString(f, "new");
        assertThat(Files.readString(f)).isEqualTo("new");
    }

    @Test
    @DisplayName("없는 파일을 지우는 것은 성공이다 — 이름이 비어 있으면 목적은 이룬 것")
    void deletingMissingFileIsFine() {
        assertThat(StaleFiles.delete(dir.resolve("없는파일.m4a"))).isTrue();
    }

    @Test
    @DisplayName("폴더를 비운다 — 건수를 세고, 남은 것이 없으면 clean")
    void clearsDirectory() throws IOException {
        file("a.m4a", "1");
        file("b.m4a", "2");
        Files.createDirectory(dir.resolve("하위폴더"));

        StaleFiles.Result r = StaleFiles.deleteAllIn(dir);

        assertThat(r.deleted()).isEqualTo(2);
        assertThat(r.clean()).isTrue();
        assertThat(r.stuck()).isEmpty();
        assertThat(dir.resolve("하위폴더")).as("하위 디렉터리는 건드리지 않는다").exists();
    }

    @Test
    @DisplayName("열려 있는 파일은 stuck 으로 올라온다 — 지운 척하지 않는다")
    void reportsFilesItCouldNotFree() throws IOException {
        file("free.m4a", "1");
        Path locked = file("locked.m4a", "2");

        // 다른 프로그램이 쥐고 있는 상태를 흉내 낸다.
        //   윈도우: 이름이 삭제 대기로 잡혀 새로 만들 수 없다 → stuck
        //   리눅스: unlink 가 이름을 즉시 비워 준다 → 정상 삭제
        try (FileChannel ch = FileChannel.open(locked, StandardOpenOption.READ)) {
            assertThat(ch.isOpen()).isTrue();
            StaleFiles.Result r = StaleFiles.deleteAllIn(dir);

            assertThat(r.deleted() + r.stuck().size()).as("둘 중 하나로는 반드시 분류된다").isEqualTo(2);
            assertThat(dir.resolve("free.m4a")).doesNotExist();
            if (!r.clean()) {
                assertThat(r.stuck()).containsExactly("locked.m4a");
            }
        }
    }

    @Test
    @DisplayName("폴더 현황 — 타임아웃 메시지에 실어 보낼 목록")
    void listsWhatIsThere() throws IOException {
        file("b.m4a", "1");
        file("a.m4a", "2");

        assertThat(StaleFiles.names(dir)).containsExactly("a.m4a", "b.m4a");
        assertThat(StaleFiles.names(dir.resolve("없는폴더"))).isEmpty();
    }
}
