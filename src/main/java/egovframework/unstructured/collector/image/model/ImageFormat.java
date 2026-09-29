package egovframework.unstructured.collector.image.model;

/**
 * 이미지 형식 — <b>매직 넘버</b>로 판별한다. XVARM 파일명(확장자)은 믿지 않는다.
 *
 * <p>복호화 결과가 여기서 판별되지 않으면 복호화가 틀린 것이다(키가 다르거나, 암호화되지 않은 파일을 복호화했다).
 * 그런 파일을 사진으로 저장·매핑하면 화면에 깨진 사진이 걸리므로 그 건은 실패로 남긴다.</p>
 */
public enum ImageFormat {
    JPG("jpg"), PNG("png"), GIF("gif"), BMP("bmp"), TIF("tif");

    private final String ext;

    ImageFormat(String ext) {
        this.ext = ext;
    }

    public String ext() {
        return ext;
    }

    /** @return 판별된 형식. 이미지가 아니면 null */
    public static ImageFormat detect(byte[] b) {
        if (b == null || b.length < 4) {
            return null;
        }
        if (u(b[0]) == 0xFF && u(b[1]) == 0xD8 && u(b[2]) == 0xFF) {
            return JPG;
        }
        if (u(b[0]) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return PNG;
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return GIF;
        }
        if (b[0] == 'B' && b[1] == 'M') {
            return BMP;
        }
        if ((b[0] == 'I' && b[1] == 'I' && b[2] == 42 && b[3] == 0) || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 42)) {
            return TIF;
        }
        return null;
    }

    private static int u(byte x) {
        return x & 0xFF;
    }
}
