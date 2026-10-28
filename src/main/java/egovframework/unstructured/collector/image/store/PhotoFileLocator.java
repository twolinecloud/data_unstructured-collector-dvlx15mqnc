package egovframework.unstructured.collector.image.store;

import egovframework.unstructured.collector.image.model.ImageFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;

/**
 * 수용자 사진 파일 찾기 — 챗봇 연동 API({@code GET /api/v1/image/photos/{corrNo}/file})가 쓴다.
 *
 * <p>챗봇 백엔드 파드는 이 수집기의 PV 를 마운트하지 못한다. 그래서 매핑 테이블({@code TB_SRC_INMATE_PHOTO})의
 * 저장 경로를 이 서비스가 대신 읽어 내준다. 요청 값도 DB 의 경로도 그대로 믿지 않는다 — 세 겹으로 막는다.</p>
 * <ol>
 *   <li><b>교정번호</b> — 영숫자 1~20자({@code CORR_NO} 컬럼 길이)만. 점 · 슬래시 · 역슬래시 · {@code %} · 공백 같은
 *       특수문자가 들어갈 자리가 없어 {@code ..} 같은 상위 폴더 탐색은 형식 검사에서 끝난다 → DB 를 부르기 전에 거절</li>
 *   <li><b>저장 경로</b> — 매핑의 {@code photo_path} 를 {@link File#getCanonicalFile()} 로 풀어({@code ..} 정리)
 *       저장소 루트 아래, 그것도 <b>그 수용자 폴더 바로 아래</b>인지 본다. 저장 규칙({@link ImageFileStore#save})이
 *       {@code {루트}/{교정번호}/{교정번호}_{순번}.{확장자}} 이라 다른 수용자 폴더나 저장소 밖을 가리키면 내주지 않는다</li>
 *   <li><b>실제 위치</b> — {@link Path#toRealPath} 로 심볼릭 링크 · 정션까지 풀어 한 번 더 같은 검사를 한다.
 *       리눅스의 getCanonicalPath 는 링크를 풀지만 윈도는 풀지 않아, 수용자 폴더를 밖으로 잇는 정션이 1차를 통과했다
 *       (2026-10-28 확인). 그 뒤 일반 파일이 아니거나 없으면 없음</li>
 * </ol>
 *
 * <p>파일은 열지 않는다 — 크기 · 수정 시각만 읽어 ETag 를 만든다. 본문은 컨트롤러가 스트리밍한다(힙에 올리지 않음).</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PhotoFileLocator {

    /** 교정번호 — 영숫자 1~20자. 실데이터는 숫자 18자리, 시험 데이터는 {@code SIMIMG} · {@code DMYIMG} 접두. */
    static final Pattern CORR_NO = Pattern.compile("[A-Za-z0-9]{1,20}");

    /** 거절 사유 — 사용자 안내는 짧게, 경로 같은 내부 값은 {@link Rejection#detail()} 로만(서버 로그). */
    public enum Reason {
        INVALID_CORR_NO("교정번호 형식을 확인해 주세요."),
        NOT_MAPPED("해당 수용자의 사진이 없습니다."),
        FILE_MISSING("해당 수용자의 사진이 없습니다."),
        PATH_ESCAPE("허용되지 않은 사진 경로입니다."),
        STORAGE_ERROR("사진 저장소를 읽지 못했습니다. 잠시 후 다시 시도해 주세요."),
        UNSUPPORTED("사진 파일 형식을 확인할 수 없습니다.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /** 사진 파일을 내줄 수 없다 — {@link #getMessage()} 는 응답 안내, {@link #detail()} 는 서버 로그. */
    public static class Rejection extends RuntimeException {
        private final Reason reason;
        private final String detail;

        Rejection(Reason reason, String detail) {
            this(reason, detail, null);
        }

        Rejection(Reason reason, String detail, Throwable cause) {
            super(reason.message(), cause);
            this.reason = reason;
            this.detail = detail;
        }

        public Reason reason() {
            return reason;
        }

        public String detail() {
            return detail;
        }
    }

    /**
     * 내줄 사진 파일.
     *
     * @param path         실제 경로(링크 · 정션까지 푼 것 — 저장소 루트의 그 수용자 폴더 바로 아래로 확인됨)
     * @param contentType  {@code image/jpeg} · {@code image/png} 등
     * @param lastModified 수정 시각(ms) — {@code Last-Modified}
     * @param etag         순번 · 수정 시각 · 크기로 만든 강한 ETag(따옴표 포함) — 파일을 읽지 않고 만든다
     */
    public record PhotoFile(Path path, String contentType, long size, long lastModified, String etag) {}

    private final InmatePhotoRepository photos;
    private final ImageFileStore store;

    /**
     * 교정번호의 사진 파일을 찾는다.
     *
     * @throws Rejection 형식 오류 · 매핑 없음 · 파일 없음 · 경로 이탈 · 저장소 I/O 오류 · 모르는 형식
     * @throws org.springframework.dao.DataAccessException 매핑 조회(Admin DB) 실패
     */
    public PhotoFile locate(String corrNo) {
        if (corrNo == null || !CORR_NO.matcher(corrNo).matches()) {
            throw new Rejection(Reason.INVALID_CORR_NO, "교정번호 형식 위반 — " + printable(corrNo));
        }
        InmatePhotoRepository.Mapped m = photos.findMapped(corrNo)
                .filter(x -> StringUtils.hasText(x.photoPath()))
                .orElseThrow(() -> new Rejection(Reason.NOT_MAPPED, "매핑 없음 — " + corrNo));
        String mappedPath = m.photoPath().trim();
        if (mappedPath.indexOf('\0') >= 0) {
            throw new Rejection(Reason.PATH_ESCAPE, "매핑 경로에 NUL 문자 — " + corrNo + " → " + printable(mappedPath));
        }

        Path base;
        Path baseReal;
        try {
            base = store.outputRoot().toFile().getCanonicalFile().toPath();
            baseReal = base.toRealPath();
        } catch (IOException e) {   // 없음(NoSuchFile) 포함 — 저장소 루트가 없거나 PV 가 응답하지 않는다
            throw new Rejection(Reason.STORAGE_ERROR, "저장소 루트를 읽지 못했다 — " + corrNo + " (" + e + ")", e);
        }
        String dirName = ImageFileStore.safe(corrNo);   // 형식 검사를 통과해 safe() 가 바꿀 글자가 없다

        Path real;
        BasicFileAttributes attrs;
        try {
            // 1차 — 표기상 위치(.. · 중복 구분자 정리). 저장소 밖이거나 그 수용자 폴더 바로 아래가 아니면 거절
            Path file = new File(mappedPath).getCanonicalFile().toPath();
            if (!file.startsWith(base)) {
                throw new Rejection(Reason.PATH_ESCAPE, "저장소 밖 경로 — " + corrNo + " → " + file + " (저장소 " + base + ")");
            }
            if (!base.resolve(dirName).equals(file.getParent())) {
                throw new Rejection(Reason.PATH_ESCAPE, "그 수용자 폴더 밖 경로 — " + corrNo + " → " + file);
            }
            // 2차 — 실제 위치(심볼릭 링크 · 정션까지 해소). 윈도의 getCanonicalPath 는 링크 · 정션을 풀지 않아
            //   수용자 폴더를 저장소 밖으로 잇는 정션이 1차를 통과했다(2026-10-28 확인) — 실제 경로로 한 번 더 본다
            real = file.toRealPath();
            if (!baseReal.resolve(dirName).equals(real.getParent())) {
                throw new Rejection(Reason.PATH_ESCAPE, "링크 · 정션으로 그 수용자 폴더 밖 — " + corrNo + " → " + file + " ⇒ " + real);
            }
            attrs = Files.readAttributes(real, BasicFileAttributes.class);
        } catch (InvalidPathException e) {
            throw new Rejection(Reason.PATH_ESCAPE, "매핑 경로 형식 오류 — " + corrNo + " → " + printable(mappedPath));
        } catch (NoSuchFileException e) {
            throw new Rejection(Reason.FILE_MISSING, "매핑은 있으나 파일이 없다 — " + corrNo + " → " + mappedPath);
        } catch (IOException e) {
            throw new Rejection(Reason.STORAGE_ERROR, "저장소 I/O 오류 — " + corrNo + " → " + mappedPath + " (" + e + ")", e);
        }
        if (!attrs.isRegularFile()) {
            throw new Rejection(Reason.FILE_MISSING, "일반 파일이 아니다 — " + corrNo + " → " + real);
        }
        String contentType = contentTypeOf(real);
        if (contentType == null) {
            throw new Rejection(Reason.UNSUPPORTED, "사진 형식을 알 수 없다 — " + corrNo + " → " + real);
        }
        long modified = attrs.lastModifiedTime().toMillis();
        long size = attrs.size();
        String etag = "\"" + m.imageSn() + "-" + Long.toHexString(modified) + "-" + Long.toHexString(size) + "\"";
        return new PhotoFile(real, contentType, size, modified, etag);
    }

    /**
     * 응답 형식 — 확장자(저장 규칙이 매직 넘버로 정한 것)를 먼저, 모르면 {@link Files#probeContentType}.
     * 이미지 형식({@link ImageFormat})이 아니면 null — 저장소에 다른 파일이 놓여도 이미지가 아닌 형식으로는 내주지 않는다.
     */
    static String contentTypeOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        ImageFormat byExt = dot < 0 ? null : ImageFormat.fromExt(name.substring(dot + 1));
        if (byExt != null) {
            return byExt.mediaType();
        }
        try {
            ImageFormat probed = ImageFormat.fromMediaType(Files.probeContentType(file));
            return probed == null ? null : probed.mediaType();
        } catch (IOException e) {
            log.debug("[Image] 사진 형식 추정 실패 — {} ({})", file, e.getMessage());
            return null;
        }
    }

    /** 로그용 — 거절한 요청 값을 그대로 찍지 않는다(제어 문자 치환 · 길이 제한, 로그 주입 방지). */
    static String printable(String s) {
        if (s == null) {
            return "null";
        }
        String t = s.length() > 40 ? s.substring(0, 40) + "…" : s;
        return t.replaceAll("\\p{Cntrl}", "?");
    }
}
