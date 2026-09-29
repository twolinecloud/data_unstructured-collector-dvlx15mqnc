package egovframework.unstructured.collector.image.store;

import egovframework.unstructured.collector.image.config.AdminDb;
import egovframework.unstructured.collector.image.config.ImageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 수용자 사진 매핑({@code kcais.TB_SRC_INMATE_PHOTO} · PK {@code CORR_NO}) — Admin DB.
 *
 * <p><b>멱등(Idempotent) UPSERT</b> — 배치를 몇 번 다시 돌려도 키 중복 오류 없이 "있으면 갱신 · 없으면 넣기" 다.</p>
 * <ul>
 *   <li><b>PostgreSQL</b>(개발계·운영) — {@code INSERT … ON CONFLICT (corr_no) DO UPDATE … WHERE 기존 순번 <= 새 순번
 *       RETURNING (xmax = 0)} 한 문장. 원자적이라 두 워커·두 배치가 같은 수용자를 동시에 써도 키 중복이 나지 않고,
 *       돌아온 값으로 신규(INSERT)·갱신(UPDATE)을, 행이 안 돌아오면 옛 사진(STALE)을 가린다</li>
 *   <li><b>H2</b>(로컬·테스트) — {@code ON CONFLICT DO UPDATE} 가 없어 행 잠금({@code SELECT … FOR UPDATE}) 후 INSERT/UPDATE.
 *       처음 들어오는 수용자를 두 워커가 동시에 INSERT 하면 한쪽이 키 중복 — 그쪽은 갱신 경로로 한 번 더 한다</li>
 *   <li>늦게 도착한 <b>옛 사진</b>(IMAGE_SN 이 더 작은 것)은 덮지 않는다 — {@link MapResult#STALE}.
 *       갱신은 경로·크기·형식·처리일시·수정일시({@code mod_dtm})를 바꾸고 등록일시({@code reg_dtm})는 남긴다</li>
 *   <li>정형 수집기가 다시 쓰는 {@code TB_SRC_INMATE_BS} 가 아니라 <b>수용자당 한 행</b>인 별도 테이블에 쓴다(배치 순서 꼬임 방지)</li>
 * </ul>
 *
 * <p><b>트랜잭션 경계</b>: 매핑 한 건(UPSERT · 선택적 PHOTO_REF 갱신)만 감싼다 — {@link AdminDb#inTx} 가 돌아오면 커밋(또는
 * 롤백)과 커넥션 반납이 끝나 있다. 파일 I/O 는 호출자가 트랜잭션 밖에서 끝낸 뒤 부른다.</p>
 */
@Log4j2
@Repository
@RequiredArgsConstructor
public class InmatePhotoRepository {

    public enum MapResult { INSERTED, UPDATED, STALE }

    /** 매핑 한 행. */
    public record PhotoRow(String corrNo, int imageSn, String imageCmmnFileId, String docId, String fileKey,
                           String photoPath, long fileSize, String fileExt, String execId) {}

    /** 이미 매핑된 사진 — 변경이 없으면 다시 받지 않는다. */
    public record Mapped(String corrNo, int imageSn, String fileKey, String photoPath) {}

    private final AdminDb db;
    private final ImageProperties props;

    private String photo() {
        return db.table(AdminDb.PHOTO_TABLE);
    }

    private String inmate() {
        return db.table(AdminDb.INMATE_TABLE);
    }

    /**
     * 매핑을 넣거나 갱신한다 — 한 트랜잭션.
     *
     * @return INSERTED · UPDATED · STALE(더 최신 사진이 이미 매핑돼 있어 손대지 않음)
     */
    public MapResult upsert(PhotoRow r) {
        return upsert(r, null);
    }

    /**
     * 매핑을 넣거나 갱신한다 — 한 트랜잭션.
     *
     * @param beforeCommit 같은 트랜잭션에서 UPSERT 뒤 · 커밋 전에 부른다. 여기서 던지면 매핑이 롤백된다
     *                     (6번 탭 실패 주입 — 'DB 매핑' 실패의 롤백 · 커넥션 반납을 실제로 확인한다). 보통은 null
     */
    public MapResult upsert(PhotoRow r, Runnable beforeCommit) {
        try {
            return db.inTx(() -> upsertInTx(r, beforeCommit));
        } catch (DuplicateKeyException e) {
            // (H2 경로) 같은 수용자를 다른 워커·배치가 방금 INSERT 했다 — 이제 행이 있으니 갱신 경로로 한 번 더
            log.debug("[Image] 매핑 INSERT 경합 — {} · 갱신으로 다시 한다", r.corrNo());
            return db.inTx(() -> upsertInTx(r, beforeCommit));
        }
    }

    /** 매핑 방식 — 리포트·상태 화면용. */
    public String upsertMode() {
        return db.isPostgres() ? "INSERT … ON CONFLICT DO UPDATE (PostgreSQL · 한 문장)"
                : "행 잠금 + INSERT/UPDATE (" + (db.isH2() ? "H2" : "범용") + ")";
    }

    private MapResult upsertInTx(PhotoRow r, Runnable beforeCommit) {
        MapResult result = db.isPostgres() ? upsertOnConflict(r) : upsertLocked(r);
        if (result != MapResult.STALE && props.updatePhotoRef()) {
            // 선택 — 수용자 기본의 PHOTO_REF 도 같은 트랜잭션에서. 그 수용자가 아직 없으면 0행(정형 수집기가 나중에 넣는다)
            db.jdbc().update("UPDATE " + inmate() + " SET photo_ref = ? WHERE corr_no = ?", r.photoPath(), r.corrNo());
        }
        if (beforeCommit != null) {
            beforeCommit.run();
        }
        return result;
    }

    /**
     * PostgreSQL — 한 문장 UPSERT. {@code xmax = 0} 이면 이 문장이 새로 넣은 행, 아니면 기존 행을 갱신한 것이다.
     * 기존 순번이 더 크면 {@code WHERE} 가 막아 행이 돌아오지 않는다 — STALE.
     */
    private MapResult upsertOnConflict(PhotoRow r) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now().withNano(0));
        List<Boolean> inserted = db.jdbc().query("INSERT INTO " + photo() + " AS p"
                        + " (corr_no, image_sn, image_cmmn_file_id, doc_id, filekey, photo_path, file_size, file_ext,"
                        + "  proc_dtm, last_batch_exec_id, reg_dtm, mod_dtm) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT (corr_no) DO UPDATE SET image_sn = EXCLUDED.image_sn,"
                        + "     image_cmmn_file_id = EXCLUDED.image_cmmn_file_id, doc_id = EXCLUDED.doc_id,"
                        + "     filekey = EXCLUDED.filekey, photo_path = EXCLUDED.photo_path, file_size = EXCLUDED.file_size,"
                        + "     file_ext = EXCLUDED.file_ext, proc_dtm = EXCLUDED.proc_dtm,"
                        + "     last_batch_exec_id = EXCLUDED.last_batch_exec_id, mod_dtm = EXCLUDED.mod_dtm"
                        + "   WHERE p.image_sn <= EXCLUDED.image_sn"
                        + " RETURNING (xmax = 0) AS inserted",
                (rs, i) -> rs.getBoolean("inserted"),
                r.corrNo(), r.imageSn(), r.imageCmmnFileId(), r.docId(), r.fileKey(), r.photoPath(), r.fileSize(),
                r.fileExt(), now, r.execId(), now, now);
        if (inserted.isEmpty()) {
            return MapResult.STALE;   // 더 최신 사진이 이미 있다 — 덮지 않았다
        }
        return Boolean.TRUE.equals(inserted.get(0)) ? MapResult.INSERTED : MapResult.UPDATED;
    }

    /** H2 · 그 밖 — 행 잠금 후 INSERT/UPDATE. */
    private MapResult upsertLocked(PhotoRow r) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now().withNano(0));
        List<Integer> cur = db.jdbc().queryForList(
                "SELECT image_sn FROM " + photo() + " WHERE corr_no = ? FOR UPDATE", Integer.class, r.corrNo());
        MapResult result;
        if (cur.isEmpty()) {
            db.jdbc().update("INSERT INTO " + photo()
                            + " (corr_no, image_sn, image_cmmn_file_id, doc_id, filekey, photo_path, file_size, file_ext,"
                            + "  proc_dtm, last_batch_exec_id, reg_dtm, mod_dtm) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    r.corrNo(), r.imageSn(), r.imageCmmnFileId(), r.docId(), r.fileKey(), r.photoPath(), r.fileSize(),
                    r.fileExt(), now, r.execId(), now, now);
            result = MapResult.INSERTED;
        } else if (cur.get(0) != null && cur.get(0) > r.imageSn()) {
            return MapResult.STALE;   // 더 최신 사진이 이미 있다 — 덮지 않는다
        } else {
            db.jdbc().update("UPDATE " + photo()
                            + " SET image_sn = ?, image_cmmn_file_id = ?, doc_id = ?, filekey = ?, photo_path = ?, file_size = ?,"
                            + "     file_ext = ?, proc_dtm = ?, last_batch_exec_id = ?, mod_dtm = ? WHERE corr_no = ?",
                    r.imageSn(), r.imageCmmnFileId(), r.docId(), r.fileKey(), r.photoPath(), r.fileSize(), r.fileExt(),
                    now, r.execId(), now, r.corrNo());
            result = MapResult.UPDATED;
        }
        return result;
    }

    /** 이미 매핑된 사진들 — 교정번호 목록 기준. 없는 수용자는 결과에 없다. */
    public Map<String, Mapped> findMapped(List<String> corrNos) {
        Map<String, Mapped> out = new HashMap<>();
        if (corrNos == null || corrNos.isEmpty()) {
            return out;
        }
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(db.jdbc());
        for (int i = 0; i < corrNos.size(); i += 500) {
            List<String> chunk = corrNos.subList(i, Math.min(corrNos.size(), i + 500));
            named.query("SELECT corr_no, image_sn, filekey, photo_path FROM " + photo() + " WHERE corr_no IN (:ids)",
                    new MapSqlParameterSource("ids", chunk),
                    rs -> {
                        out.put(rs.getString("corr_no"), new Mapped(rs.getString("corr_no"), rs.getInt("image_sn"),
                                rs.getString("filekey"), rs.getString("photo_path")));
                    });
        }
        return out;
    }

    /** 접두로 시작하는 매핑된 교정번호 — 재실행 전 스냅샷(무엇이 신규 · 무엇이 갱신이어야 하는지)의 근거. */
    public Set<String> mappedCorrNos(String prefix) {
        return new HashSet<>(db.jdbc().queryForList("SELECT corr_no FROM " + photo() + " WHERE corr_no LIKE ?",
                String.class, prefix + "%"));
    }

    /** 한 수용자의 매핑 — 없으면 빈 맵. */
    public Map<String, Object> find(String corrNo) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("SELECT * FROM " + photo() + " WHERE corr_no = ?", corrNo);
        return rows.isEmpty() ? Map.of() : lower(rows.get(0));
    }

    /**
     * {@code TB_SRC_INMATE_BS.PHOTO_REF} 일괄 반영 — 매핑 테이블의 저장 경로로. 값이 다른 행만 바꾼다.
     *
     * <p>표준 SQL(상관 서브쿼리)이라 PostgreSQL·H2 둘 다 돈다. {@code MOD_DTM} 은 건드리지 않는다 — 정형 수집기의
     * 증분 기준일 수 있다. 정형 수집기가 PHOTO_REF 를 비우며 다시 썼다면 이것을 다시 돌리면 된다.</p>
     *
     * @return 바뀐 수용자 수
     */
    public int syncPhotoRef() {
        return db.inTx(() -> db.jdbc().update("UPDATE " + inmate() + " b SET photo_ref ="
                + " (SELECT p.photo_path FROM " + photo() + " p WHERE p.corr_no = b.corr_no)"
                + " WHERE EXISTS (SELECT 1 FROM " + photo() + " p WHERE p.corr_no = b.corr_no"
                + "   AND (b.photo_ref IS NULL OR b.photo_ref <> p.photo_path))"));
    }

    /** 매핑 현황 — 전체 행 수와 접두(시뮬레이션) 행 수. */
    public Map<String, Object> stats(String simPrefix) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", db.jdbc().queryForObject("SELECT COUNT(*) FROM " + photo(), Long.class));
        m.put("sim", db.jdbc().queryForObject("SELECT COUNT(*) FROM " + photo() + " WHERE corr_no LIKE ?", Long.class,
                simPrefix + "%"));
        return m;
    }

    /** 시뮬레이션 매핑 행을 지운다 — 접두로만. 실제 수용자 행은 손대지 않는다. */
    public int deleteByPrefix(String prefix) {
        if (prefix == null || prefix.length() < 4) {
            throw new IllegalArgumentException("접두가 너무 짧다 — 실제 행을 지울 수 있다: " + prefix);
        }
        return db.inTx(() -> db.jdbc().update("DELETE FROM " + photo() + " WHERE corr_no LIKE ?", prefix + "%"));
    }

    /** 접두로 시작하는 매핑 목록(검증용). */
    public List<Map<String, Object>> listByPrefix(String prefix, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        db.jdbc().queryForList("SELECT corr_no, image_sn, filekey, photo_path, file_size, file_ext, proc_dtm, last_batch_exec_id FROM "
                        + photo() + " WHERE corr_no LIKE ? ORDER BY corr_no", prefix + "%")
                .stream().limit(Math.max(1, limit)).forEach(r -> out.add(lower(r)));
        return out;
    }

    private static Map<String, Object> lower(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        r.forEach((k, v) -> m.put(k.toLowerCase(java.util.Locale.ROOT), v instanceof Timestamp t ? t.toLocalDateTime().toString() : v));
        return m;
    }
}
