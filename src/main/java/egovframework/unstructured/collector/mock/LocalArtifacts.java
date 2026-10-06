package egovframework.unstructured.collector.mock;

import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.util.StaleFiles;
import egovframework.unstructured.collector.voice.batch.IdempotencyGuard;
import egovframework.unstructured.collector.voice.stt.SttTempStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 수집기 로컬 산출물 정리 — 멱등 표식 · 수신 파일 · Mock 작업 산출물 · 재처리 보존물(전사 · 복호화 오디오).
 *
 * <p>예전에는 [시뮬레이션 데이터 생성/초기화] 가 이것들을 <b>전부</b> 지웠다. 대시보드 더미(DMY)가 생기면서 용도별로 가른다 —
 * 시뮬레이터 초기화가 대시보드 더미의 멱등 표식까지 지우면 실제 배치가 이미 보낸 건을 다시 처리해 전송 · 이력이 중복된다.
 * 이름 조건({@link DummyTarget#isDashboardLocalName})으로 가르고, 결과 모양은 예전 그대로다(화면 · 성능 시험이 읽는다).</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class LocalArtifacts {

    private final IdempotencyGuard idempotency;
    private final VoiceDirState dirs;
    private final SttTempStore sttTemp;

    /**
     * 이름이 조건에 맞는 로컬 산출물을 지우고 <b>지우지 못한 것까지</b> 결과에 담는다.
     *
     * <p>지운 건수만 돌려주던 때는 "3건 삭제" 라고 보고해 놓고 실제로는 이름이 잡혀 있어, 다음 실행이 수신 대기
     * 타임아웃으로 죽어도 그 연결을 아무도 못 봤다 — {@code stuckFiles} 가 비어 있지 않으면 정리가 끝난 것이 아니다.</p>
     *
     * @param mine 지울 파일명 조건
     */
    public Map<String, Object> clear(Predicate<String> mine) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> stuck = new ArrayList<>();
        out.put("idempotencyMarkers", idempotency.clearMatching(mine));
        for (var e : Map.of("meetFiles", dirs.receiveMeet(),
                "phoneFiles", dirs.receivePhone(),
                "workFiles", Path.of(dirs.work(), "mock_source_stt").toString()).entrySet()) {
            StaleFiles.Result r = StaleFiles.deleteMatching(Path.of(e.getValue()), mine);
            out.put(e.getKey(), r.deleted());
            stuck.addAll(r.stuck());
        }
        // ── 재처리용 중간 산출물 ─────────────────────────────────────────────
        //   이것까지 지워야 '처음부터' 다. 남겨 두면 다음 재처리(FROM_ANALYZE·FROM_SEND)가
        //   지난 시험의 보존물을 집어 가서, 새로 만든 데이터로 돌렸는데 옛 전사가 나온다.
        //   보존물에는 평문 음성·전사(성명·주민번호)가 들어 있으니 PII 잔재이기도 하다.
        //
        //   ① {ROOT}/stt_temp/{execId}/  — 전사 결과(SEND 재처리용)
        out.put("sttTempFiles", sttTemp.clearMatching(mine));
        //   ② {ROOT}/xvram/decoding/decrypted_*  — 복호화 오디오(ANALYZE 재처리용).
        //      이 폴더에는 다른 것도 사니 우리 접두사만 지운다
        int audio = 0;
        Path work = Path.of(dirs.work());
        if (Files.isDirectory(work)) {
            try (Stream<Path> ws = Files.list(work)) {
                for (Path f : ws.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().startsWith("decrypted_"))
                        .filter(f -> mine.test(f.getFileName().toString())).toList()) {
                    if (StaleFiles.delete(f)) {
                        audio++;
                    } else {
                        stuck.add(f.getFileName().toString());
                    }
                }
            } catch (IOException e) {
                log.warn("[Mock] 복호화 보존물 목록 실패 — {} ({})", work, e.getMessage());
            }
        }
        out.put("decryptedAudio", audio);

        if (!stuck.isEmpty()) {
            out.put("stuckFiles", stuck);
            out.put("stuckWarning", "이 파일들을 지우지 못했습니다 — 다른 프로그램(탐색기 미리보기·재생기·백신)이 "
                    + "열고 있으면 같은 이름으로 새 파일을 만들 수 없어 다음 실행이 '수신 파일 대기 타임아웃'으로 "
                    + "끝납니다. 해당 파일을 닫고 초기화를 다시 눌러 주세요");
        }
        return out;
    }

    /** 시뮬레이터 쪽 — 대시보드 더미의 산출물만 남기고 전부(예전 동작). */
    public Map<String, Object> clearExceptDashboard() {
        return clear(name -> !DummyTarget.isDashboardLocalName(name));
    }

    /** 대시보드 더미의 산출물만. */
    public Map<String, Object> clearDashboard() {
        return clear(DummyTarget::isDashboardLocalName);
    }
}
