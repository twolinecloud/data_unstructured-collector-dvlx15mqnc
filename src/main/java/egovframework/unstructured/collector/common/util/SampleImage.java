package egovframework.unstructured.collector.common.util;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 시뮬레이션용 <b>더미 수용자 사진</b>(JPEG) — 실제로 열리는 이미지다.
 *
 * <p>복호화가 맞았는지를 "이미지로 읽히는가" 로 확인하려면 더미도 진짜 이미지여야 한다. 글자는 그리지 않는다 —
 * 글꼴이 없는 컨테이너(slim JDK)에서 그리기가 실패할 수 있다. {@code seed} 로 색을 바꿔 파일마다 내용이 다르게 한다.</p>
 */
public final class SampleImage {

    public static final int WIDTH = 120;
    public static final int HEIGHT = 160;

    private SampleImage() {
    }

    /** {@code seed} 에 따라 배경색이 다른 증명사진 모양 JPEG. */
    public static byte[] jpeg(String seed) {
        int h = seed == null ? 0 : seed.hashCode();
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(200 + (h & 0x1F), 210 + ((h >> 5) & 0x1F), 225 + ((h >> 10) & 0x0F)));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setColor(new Color(90 + ((h >> 14) & 0x3F), 90, 100));
            g.fillOval(35, 25, 50, 60);            // 머리
            g.fillRoundRect(20, 95, 80, 70, 30, 30);   // 어깨
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024)) {
            if (!ImageIO.write(img, "jpg", out)) {
                throw new IllegalStateException("JPEG 인코더가 없다");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
