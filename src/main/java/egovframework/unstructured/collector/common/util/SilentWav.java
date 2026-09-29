package egovframework.unstructured.collector.common.util;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 무음 WAV 바이트 생성기 — Mock 구간에서 "진짜 오디오 파일"이 필요할 때 쓴다.
 *
 * <p>0 바이트 더미 파일을 쓰지 않는 이유: 파일 크기 안정성 판정·포맷 판별(매직 넘버)·
 * STT 전송 같은 하류 로직이 <b>실제 파일처럼 동작하는지</b> 확인해야 하기 때문이다.
 * 빈 파일로 테스트하면 그 코드들이 검증되지 않은 채로 남는다.</p>
 *
 * <p>16kHz · 모노 · 16bit PCM — STT 엔진이 흔히 요구하는 형식이다.</p>
 */
public final class SilentWav {

    private static final int SAMPLE_RATE = 16_000;
    private static final short CHANNELS = 1;
    private static final short BITS = 16;

    private SilentWav() {
    }

    /** {@code seconds} 초 길이의 무음 WAV 전체 바이트를 만든다. */
    public static byte[] of(int seconds) {
        int dataSize = SAMPLE_RATE * CHANNELS * (BITS / 8) * Math.max(seconds, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);

        out.writeBytes("RIFF".getBytes());
        out.writeBytes(le32(36 + dataSize));
        out.writeBytes("WAVE".getBytes());

        out.writeBytes("fmt ".getBytes());
        out.writeBytes(le32(16));                                   // subchunk1 size (PCM)
        out.writeBytes(le16((short) 1));                            // audioFormat = PCM
        out.writeBytes(le16(CHANNELS));
        out.writeBytes(le32(SAMPLE_RATE));
        out.writeBytes(le32(SAMPLE_RATE * CHANNELS * (BITS / 8)));  // byteRate
        out.writeBytes(le16((short) (CHANNELS * (BITS / 8))));      // blockAlign
        out.writeBytes(le16(BITS));

        out.writeBytes("data".getBytes());
        out.writeBytes(le32(dataSize));
        out.writeBytes(new byte[dataSize]);                         // 무음(0)

        return out.toByteArray();
    }

    private static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static byte[] le16(short v) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array();
    }
}
