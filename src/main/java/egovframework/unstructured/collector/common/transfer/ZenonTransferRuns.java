package egovframework.unstructured.collector.common.transfer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * <b>송신측 전송 런 장부</b> — 런({@code X-Run-Id})마다 어디까지 보냈는가(마지막으로 받아 준 순번 · 레코드 수 · 마감 여부).
 *
 * <p>왜 필요한가 — 전송 재처리가 <b>이어달리기</b>를 하려면(에이전트 커넥터 '3번 청크부터 이어서 전송') 원배치 런이
 * 몇 번 순번까지 받아들여졌는지 알아야 한다. 재처리는 새 실행 ID 로 돌지만 전송은 원배치 런({@code X-Run-Id} = 원 실행 ID)을
 * 이어 받아 다음 순번부터 보내고 마지막 청크로 마감한다 — 수신단 장부에서 그 런이 빈 순번 없이 닫힌다.</p>
 *
 * <p>PV 파일 {@code {ROOT}/zenon/transfer_runs.json} 에도 남긴다(키 표식 소진 기록과 같은 방식) — 실패와 재처리 사이에 파드가
 * 다시 떠도(개발계는 dev 머지마다 재배포) 이어달리기가 끊기지 않게. 파일을 읽거나 쓰지 못해도 전송은 멈추지 않는다.</p>
 */
@Log4j2
@Component
public class ZenonTransferRuns {

    /** PV 파일 — ROOT 기준 상대 경로. */
    public static final String FILE = "zenon/transfer_runs.json";
    /** 남기는 런 수 — 넘으면 오래된 마감 런부터 버린다. */
    private static final int MAX_RUNS = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 런 하나 — 마지막으로 받아 준 순번 · 레코드 수 · 마감 · 마지막 실패. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class RunState {
        public String runId;
        public int deliveredSeq;
        public long deliveredRecords;
        public boolean closed;
        public Integer failedSeq;
        public String failReason;
        public List<String> execIds = new ArrayList<>();
        public String updatedAt;

        RunState() {
        }

        RunState(String runId) {
            this.runId = runId;
            touch();
        }

        void touch() {
            updatedAt = LocalDateTime.now().withNano(0).toString();
        }

        RunState copy() {
            RunState c = new RunState();
            c.runId = runId;
            c.deliveredSeq = deliveredSeq;
            c.deliveredRecords = deliveredRecords;
            c.closed = closed;
            c.failedSeq = failedSeq;
            c.failReason = failReason;
            c.execIds = new ArrayList<>(execIds);
            c.updatedAt = updatedAt;
            return c;
        }
    }

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    private final Supplier<Path> file;

    @Autowired
    public ZenonTransferRuns(VoiceDirState dirs) {
        this(() -> Path.of(dirs.baseDir(), FILE));
    }

    /** 파일 위치를 직접 준다 — null 이면 메모리만(단위 테스트). */
    public ZenonTransferRuns(Supplier<Path> file) {
        this.file = file;
        load();
    }

    public static ZenonTransferRuns inMemory() {
        return new ZenonTransferRuns((Supplier<Path>) null);
    }

    /** 런 상태 사본 — 없으면 null. */
    public RunState get(String runId) {
        RunState r = runId == null ? null : runs.get(runId);
        if (r == null) {
            return null;
        }
        synchronized (r) {
            return r.copy();
        }
    }

    /** 이어 보낼 수 있는(마감되지 않은) 런인가. */
    public boolean isOpen(String runId) {
        RunState r = get(runId);
        return r != null && !r.closed;
    }

    /** 이 실행이 이 런에 붙었다(새 런을 열거나 원배치 런을 이어 받음). */
    public void attach(String runId, String execId) {
        RunState r = runs.computeIfAbsent(runId, RunState::new);
        synchronized (r) {
            if (execId != null && !r.execIds.contains(execId)) {
                r.execIds.add(execId);
            }
            r.touch();
        }
        trim();
        save();
    }

    /** 순번 하나가 받아들여졌다. */
    public void delivered(String runId, int seq, int records, boolean last) {
        RunState r = runs.computeIfAbsent(runId, RunState::new);
        synchronized (r) {
            r.deliveredSeq = Math.max(r.deliveredSeq, seq);
            r.deliveredRecords += records;
            if (last) {
                r.closed = true;
            }
            if (r.failedSeq != null && r.failedSeq <= seq) {
                r.failedSeq = null;
                r.failReason = null;
            }
            r.touch();
        }
        save();
    }

    /** 순번 하나가 거절됐다 — 런은 그 자리에서 멈춘다(뒤 청크는 보내지 않는다). */
    public void failed(String runId, int seq, String reason) {
        RunState r = runs.computeIfAbsent(runId, RunState::new);
        synchronized (r) {
            r.failedSeq = seq;
            r.failReason = reason;
            r.touch();
        }
        save();
    }

    /** 최근 런(최근 것부터). */
    public List<RunState> recent(int limit) {
        List<RunState> all = new ArrayList<>();
        runs.values().forEach(r -> {
            synchronized (r) {
                all.add(r.copy());
            }
        });
        all.sort(Comparator.comparing((RunState x) -> x.updatedAt == null ? "" : x.updatedAt).reversed());
        return all.subList(0, Math.min(Math.max(1, limit), all.size()));
    }

    /** 런을 지운다 — 이름에 이 글자가 든 것(예: TST)만, null 이면 전부. */
    public int clear(String containing) {
        int before = runs.size();
        if (containing == null) {
            runs.clear();
        } else {
            runs.keySet().removeIf(k -> k.toUpperCase().contains(containing.toUpperCase()));
        }
        int removed = before - runs.size();
        if (removed > 0) {
            save();
        }
        return removed;
    }

    public String filePath() {
        Path f = path();
        return f == null ? null : f.toString().replace('\\', '/');
    }

    private void trim() {
        if (runs.size() <= MAX_RUNS) {
            return;
        }
        runs.values().stream()
                .sorted(Comparator.comparing((RunState x) -> !x.closed).thenComparing(x -> x.updatedAt == null ? "" : x.updatedAt))
                .limit(runs.size() - MAX_RUNS)
                .map(x -> x.runId)
                .toList()
                .forEach(runs::remove);
    }

    // ── PV 파일 ─────────────────────────────────────────────────────────

    private Path path() {
        if (file == null) {
            return null;
        }
        try {
            return file.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void load() {
        Path f = path();
        if (f == null || !Files.isRegularFile(f)) {
            return;
        }
        try {
            Map<String, RunState> saved = JSON.readValue(f.toFile(), new TypeReference<Map<String, RunState>>() { });
            saved.forEach((k, v) -> {
                if (k != null && v != null) {
                    v.runId = k;
                    runs.put(k, v);
                }
            });
            log.info("[Zenon] 전송 런 {}건을 파일에서 되살렸다 — {}", runs.size(), f);
        } catch (IOException | RuntimeException e) {
            log.warn("[Zenon] 전송 런 파일을 읽지 못했다 — 빈 채로 시작한다: {} ({})", f, e.getMessage());
        }
    }

    private synchronized void save() {
        Path f = path();
        if (f == null) {
            return;
        }
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Map<String, RunState> snap = new LinkedHashMap<>();
            runs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                synchronized (e.getValue()) {
                    snap.put(e.getKey(), e.getValue().copy());
                }
            });
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snap);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[Zenon] 전송 런 파일을 쓰지 못했다 — 메모리로만 계속한다: {} ({})", f, e.getMessage());
        }
    }
}
