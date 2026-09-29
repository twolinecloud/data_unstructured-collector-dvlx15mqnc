package egovframework.unstructured.collector.image.sim;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.config.DbKindDetector;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.decrypt.MediaDecryptor;
import egovframework.unstructured.collector.common.util.SampleImage;
import egovframework.unstructured.collector.image.store.ImageFileStore;
import egovframework.unstructured.collector.image.store.InmatePhotoRepository;
import egovframework.unstructured.collector.voice.source.SimulationDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 수용자 이미지 <b>시뮬레이션 데이터</b> — 보라미 3테이블 행 + XVARM 원본 스토리지의 암호화된 더미 사진.
 *
 * <p>개발계 보라미 DB 에는 사진 원장({@code ir.tb_irim_bsif_ds})은 있지만 공통파일기본이 비어 있어 FILEKEY 까지
 * 이어지지 않는다(2026-09-29 확인 — 사진 있는 수용자 972명 중 공통파일 매칭 0). 그래서 음성과 같은 방식으로
 * {@code SIMIMG} 접두의 데이터를 만들었다가 지운다. 실제 수용자 행은 건드리지 않는다.</p>
 *
 * <p>수용자마다 세 행을 만든다 — 사진 2장(순번 1 · 2)과 사진이 아닌 이미지 1장(구분 '2', 순번 3).
 * 파이프라인은 <b>순번 2</b>를 골라야 한다(구분이 '1' 인 것 중 최대). 순번 3 을 고르면 구분 필터가, 1 을 고르면
 * 최신 선택이 틀린 것이다 — 검증이 매핑된 순번으로 이것을 본다.</p>
 *
 * <p>더미 사진은 복호화가 REAL 이면 접견과 같은 RVS 키로 실제로 암호화해 둔다. 원문 해시를 기억해 두었다가
 * 저장된 사진과 비교한다 — 복호화가 정확했는지의 근거다.</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class ImageSimulationService {

    public static final String PREFIX = "SIMIMG";
    private static final String FILE_PREFIX = "SIMIMGF";
    private static final String DOC_PREFIX = "SIMIMGD";
    private static final String USR = "simadm";
    public static final int MAX = 1000;

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;
    private final BoramiTableNames tables;
    private final DbKindDetector db;
    private final VoiceDirState dirs;
    private final VoiceModeState modeState;
    private final VoiceProperties props;
    private final SimulationDataService voiceSim;
    private final InmatePhotoRepository photos;
    private final ImageFileStore store;

    /** 마지막 시딩의 원문 해시 — 교정번호 → SHA-256. 저장된 사진과 비교한다. */
    private final Map<String, String> plainSha = new ConcurrentHashMap<>();

    public static String corrNo(int i) {
        return PREFIX + "%05d".formatted(i);
    }

    /** XVARM 원본 스토리지의 사진 폴더. */
    public Path originalDir() {
        return Path.of(dirs.xvarmOriginal(), "image");
    }

    /**
     * 만든다 — 기존 SIM 이미지 데이터를 먼저 지운다. DB 는 한 트랜잭션, 파일은 커밋 뒤에.
     *
     * @param count 수용자 수(1~{@value #MAX})
     */
    public Map<String, Object> seed(int count) {
        if (count < 1 || count > MAX) {
            throw new IllegalArgumentException("수용자 이미지 시뮬레이션은 1~" + MAX + "명입니다: " + count);
        }
        Map<String, Object> cleaned = clean();
        String ensured = voiceSim.ensureXvarmMockTables();   // 개발계 MOCK_DEV — 공통파일기본·XVARM 테이블이 없으면 만든다
        Timestamp now = Timestamp.valueOf(LocalDateTime.now().withNano(0));
        Path dir = originalDir();
        // 복호화가 REAL 이면 접견과 같은 키로 암호화한다 — 키를 못 읽으면 평문으로 두고 메타도 'N' 으로 맞춘다
        MediaDecryptor enc = modeState.decrypt() == VoiceProperties.DecryptMode.REAL ? encryptor() : null;

        List<Object[]> images = new ArrayList<>();
        List<Object[]> cmfi = new ArrayList<>();
        List<Object[]> xvarm = new ArrayList<>();
        Map<Path, String> files = new LinkedHashMap<>();   // 커밋 뒤에 쓸 파일 → 교정번호
        for (int i = 1; i <= count; i++) {
            String corr = corrNo(i);
            for (int sn = 1; sn <= 3; sn++) {
                String fid = FILE_PREFIX + "%05d%d".formatted(i, sn);
                images.add(new Object[] {corr, sn, sn == 3 ? "2" : "1", fid, now, USR, now, USR});
                if (sn <= 2) {
                    String doc = DOC_PREFIX + "%05d%d".formatted(i, sn);
                    Path file = dir.resolve(corr + "_" + sn + ".jpg.enc");
                    cmfi.add(new Object[] {fid, doc, corr + "_" + sn + ".jpg", "01", "A", now, "Y", enc != null ? "Y" : "N", "N",
                            now, USR, now, USR});
                    xvarm.add(new Object[] {doc, slash(file)});
                    if (sn == 2) {
                        files.put(file, corr);   // 최신 사진만 파일을 둔다 — 파이프라인이 받는 것은 이것뿐이다
                    }
                }
            }
        }
        new TransactionTemplate(txManager).executeWithoutResult(st -> {
            jdbc.batchUpdate("INSERT INTO " + tables.irimBsifDs()
                    + " (CORR_NO, IMAGE_SN, IMAGE_SE_CD, IMAGE_CMMN_FILE_ID, CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)"
                    + " VALUES (?,?,?,?,?,?,?,?)", images);
            jdbc.batchUpdate("INSERT INTO " + tables.smsmCmfiBs()
                    + " (CMMN_FILE_ID, DOC_ID, FILE_NM, CORR_WRK_SE_CD, FILE_TY_CD, REG_DT, RPRS_YN, CMMN_FILE_ENC_YN, DEL_YN,"
                    + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", cmfi);
            jdbc.batchUpdate("INSERT INTO " + tables.asysContentElement() + " (ELEMENTID, FILEKEY) VALUES (?,?)", xvarm);
        });

        // ── 파일 — 커밋 뒤(커넥션을 잡고 암호화·쓰기를 하지 않는다) ──
        plainSha.clear();
        int written = 0;
        try {
            Files.createDirectories(dir);
            for (Map.Entry<Path, String> f : files.entrySet()) {
                byte[] plain = SampleImage.jpeg(f.getValue());
                plainSha.put(f.getValue(), sha256(plain));
                Files.write(f.getKey(), enc == null ? plain : enc.encrypt(plain));
                written++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("더미 사진을 쓰지 못했다 — " + dir + " (" + e.getMessage() + ")", e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inmates", count);
        out.put("imageRows", images.size());
        out.put("cmfiRows", cmfi.size());
        out.put("xvarmRows", xvarm.size());
        out.put("files", written);
        out.put("encrypted", enc != null);
        out.put("originalDir", slash(dir));
        out.put("db", db.label());
        out.put("tables", Map.of("image", tables.irimBsifDs(), "cmfi", tables.smsmCmfiBs(), "xvarm", tables.asysContentElement()));
        out.put("ensured", ensured);
        out.put("cleanedBefore", cleaned.get("counts"));
        log.info("[ImageSim] 생성 — 수용자 {}명 · 이미지 {}행 · 파일 {}개{} · {}", count, images.size(), written,
                enc != null ? "(암호화)" : "", db.label());
        return out;
    }

    /** 지운다 — SIM 행(보라미 3테이블 · Admin 매핑) · 더미 원본 · 저장된 SIM 사진. 실제 수용자 행은 손대지 않는다. */
    public Map<String, Object> clean() {
        Map<String, Object> counts = new LinkedHashMap<>();
        new TransactionTemplate(txManager).executeWithoutResult(st -> {
            counts.put("imageRows", safeDelete(tables.irimBsifDs(), "CORR_NO LIKE '" + PREFIX + "%'"));
            counts.put("cmfiRows", safeDelete(tables.smsmCmfiBs(), "CMMN_FILE_ID LIKE '" + FILE_PREFIX + "%'"));
            counts.put("xvarmRows", safeDelete(tables.asysContentElement(), "ELEMENTID LIKE '" + DOC_PREFIX + "%'"));
        });
        try {
            counts.put("photoRows", photos.deleteByPrefix(PREFIX));
        } catch (Exception e) {
            counts.put("photoRows", "skip: " + e.getMessage());   // Admin DB 에 매핑 테이블이 아직 없을 수 있다
        }
        counts.put("originalFiles", deleteFiles(originalDir(), PREFIX));
        int saved = 0;
        Path out = store.outputRoot();
        if (Files.isDirectory(out)) {
            try (Stream<Path> s = Files.list(out)) {
                for (Path d : s.filter(p -> p.getFileName().toString().startsWith(PREFIX)).toList()) {
                    saved += store.deleteCorrDir(d.getFileName().toString());
                }
            } catch (IOException e) {
                log.warn("[ImageSim] 저장 사진 정리 실패 — {} ({})", out, e.getMessage());
            }
        }
        counts.put("savedFiles", saved);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("counts", counts);
        m.put("db", db.label());
        log.info("[ImageSim] 정리 — {}", counts);
        return m;
    }

    /** 저장된 사진이 원문과 같은가 — 교정번호 → 일치 여부. 시딩하지 않은 수용자는 없다. */
    public Map<String, Boolean> verifyPlain(Map<String, String> savedPaths) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        savedPaths.forEach((corr, path) -> {
            String want = plainSha.get(corr);
            if (want == null) {
                return;
            }
            try {
                out.put(corr, want.equals(sha256(Files.readAllBytes(Path.of(path)))));
            } catch (Exception e) {
                out.put(corr, false);
            }
        });
        return out;
    }

    private Object safeDelete(String table, String where) {
        try {
            return jdbc.update("DELETE FROM " + table + " WHERE " + where);
        } catch (Exception e) {
            return "skip: " + e.getMessage();   // 테이블이 아직 없다(개발계 MOCK_DEV 첫 실행)
        }
    }

    private static int deleteFiles(Path dir, String prefix) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().startsWith(prefix)).toList()) {
                Files.deleteIfExists(f);
                n++;
            }
        } catch (IOException e) {
            log.warn("[ImageSim] 더미 원본 정리 실패 — {} ({})", dir, e.getMessage());
        }
        return n;
    }

    private MediaDecryptor encryptor() {
        String keyPath = props.decrypt().rvsKeyPath();
        if (!StringUtils.hasText(keyPath)) {
            log.warn("[ImageSim] 복호화 REAL 인데 키 경로가 없어 평문으로 둡니다 — voice.decrypt.rvs-key-path");
            return null;
        }
        try {
            return MediaDecryptor.fromKeyFile(Path.of(keyPath.trim()));
        } catch (Exception e) {
            log.warn("[ImageSim] 키를 읽지 못해 평문으로 둡니다 — {}", e.getMessage());
            return null;
        }
    }

    static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static String slash(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
