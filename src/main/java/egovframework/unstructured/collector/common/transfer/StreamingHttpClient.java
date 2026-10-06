package egovframework.unstructured.collector.common.transfer;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * 전이중(full-duplex) HTTP POST — 요청 본문을 쓰는 <b>동안</b> 응답을 동시에 읽어 교착을 막는다.
 *
 * <p>수신 엔진은 요청을 읽으면서 응답(성공 Ack뿐 아니라 413·abort 같은 에러도)을 흘린다. 다 쓰고 나서
 * 읽는 동기 클라이언트는, 서버가 업로드 도중 응답을 먼저 던지는 순간 <b>소켓 버퍼가 차서 교착</b>난다
 * (양쪽이 서로를 기다림). 그래서 읽기 스레드를 <b>본문 쓰기 전에 먼저</b> 띄워 응답을 계속 비운다
 * — 수신단 참조 구현(StreamingHttpClient)과 동일 전략.
 *
 * <p>gzip + {@code Transfer-Encoding: chunked}(Content-Length 없이 흘려보냄). 평문 http(클러스터 내부
 * 파드↔파드라 TLS 불필요), 요청당 1소켓({@code Connection: close}). 순차 전송({@link AgentConnectorClient})과 짝.
 *
 * <p><b>출처</b>: data-collector {@code pipeline.predict.StreamingHttpClient}(2026-10 dev) 를 <b>그대로</b> 옮겼다 —
 * 전송 양식(gzip · chunked · 유실검증 헤더 · 2xx 판정)을 정형과 하나로 맞추기 위해서다(2026-10-05 지시).
 * 바꾼 것은 패키지와 공개 범위(전송 시뮬레이션이 다른 패키지에서 쓴다)뿐이다.</p>
 */
public final class StreamingHttpClient {

    /** 본문을 평문으로 써 넣는 생성기. gzip 은 이 클라이언트가 씌운다. */
    @FunctionalInterface
    public interface BodyWriter {
        void write(OutputStream plain) throws IOException;
    }

    /** 한 번의 왕복 결과. status<0 또는 writeError/readError 있으면 실패로 본다. */
    public record Result(int status, String tail, String writeError, String readError) {
        public boolean ok2xx() {
            return status >= 200 && status < 300 && readError == null;
        }
    }

    private StreamingHttpClient() {
    }

    /**
     * gzip(옵션) + chunked 로 POST 하면서 응답을 동시에 읽는다.
     *
     * @param headers   추가 헤더(X-Run-Id·X-Seq 등). Host·Transfer-Encoding·Connection 은 여기서 채운다
     * @param gzip      본문 gzip 압축 여부
     * @param tailBytes 응답에서 남겨 둘 꼬리 크기(summary 는 맨 뒤 → 꼬리만 파싱)
     */
    public static Result post(String host, int port, String path, Map<String, String> headers, boolean gzip,
                       BodyWriter body, int connectTimeoutMs, int soTimeoutMs, int tailBytes) {
        int[] status = { -1 };
        String[] errors = new String[2];   // [0]=쓰기, [1]=읽기
        StringBuilder tail = new StringBuilder();

        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            sock.setSoTimeout(soTimeoutMs);
            sock.setTcpNoDelay(true);

            OutputStream sockOut = sock.getOutputStream();
            InputStream sockIn = sock.getInputStream();

            Map<String, String> head = new LinkedHashMap<>(headers);
            head.put("Host", host + ":" + port);
            if (gzip) {
                head.put("Content-Encoding", "gzip");
            }
            head.put("Transfer-Encoding", "chunked");
            head.put("Connection", "close");
            writeRequestHead(sockOut, path, head);

            // 읽기 스레드를 '먼저' 띄운다 — 본문 쓴 뒤 읽으면 그게 교착이다.
            Thread reader = new Thread(() -> {
                try {
                    status[0] = readResponse(sockIn, tail, tailBytes);
                } catch (IOException e) {
                    errors[1] = rootMessage(e);
                }
            }, "transfer-reader");
            reader.setDaemon(true);
            reader.start();

            // 본문 쓰기(이 스레드): chunked → (gzip) → 본문
            ChunkedOutputStream chunked = new ChunkedOutputStream(sockOut);
            BufferedOutputStream buffered = new BufferedOutputStream(chunked, 64 * 1024);
            try {
                if (gzip) {
                    try (GZIPOutputStream gz = new GZIPOutputStream(buffered, 64 * 1024)) {
                        body.write(gz);
                    }
                } else {
                    body.write(buffered);
                }
                buffered.flush();
                chunked.finish();      // 0-length 청크로 본문 끝을 알림
                sockOut.flush();
            } catch (IOException e) {
                // 서버가 상한 초과 등으로 먼저 끊으면 여기로 온다 — 방어(응답 먼저 옴)의 신호이기도 하다.
                errors[0] = rootMessage(e);
            }

            reader.join(soTimeoutMs);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            errors[1] = errors[1] == null ? rootMessage(e) : errors[1];
        }
        return new Result(status[0], tail.toString(), errors[0], errors[1]);
    }

    private static void writeRequestHead(OutputStream out, String path, Map<String, String> headers)
            throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("POST ").append(path).append(" HTTP/1.1\r\n");
        headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** {@code Transfer-Encoding: chunked} 프레이밍 — 쓰기 한 번이 청크 하나. */
    private static final class ChunkedOutputStream extends OutputStream {
        private final OutputStream out;
        private boolean finished;

        ChunkedOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] { (byte) b }, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len <= 0) {
                return; // 0 청크는 본문 끝 신호라 함부로 못 씀
            }
            out.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(b, off, len);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }

        void finish() throws IOException {
            if (!finished) {
                finished = true;
                out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }

    /** 응답을 끝까지 흘려 버리며 꼬리만 남기고 상태 코드를 돌려준다(본문을 계속 비워야 서버가 안 멈춤). */
    private static int readResponse(InputStream in, StringBuilder tailOut, int tailBytes) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null || statusLine.isEmpty()) {
            throw new IOException("응답 상태줄이 비어 있습니다");
        }
        int status = parseStatus(statusLine);

        boolean chunked = false;
        long contentLength = -1;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c < 0) {
                continue;
            }
            String name = line.substring(0, c).trim().toLowerCase();
            String value = line.substring(c + 1).trim();
            if (name.equals("transfer-encoding") && value.toLowerCase().contains("chunked")) {
                chunked = true;
            } else if (name.equals("content-length")) {
                contentLength = Long.parseLong(value);
            }
        }

        Tail tail = new Tail(tailBytes);
        if (chunked) {
            drainChunked(in, tail);
        } else if (contentLength >= 0) {
            drainFixed(in, tail, contentLength);
        } else {
            drainToEof(in, tail);
        }
        tailOut.append(tail.text());
        return status;
    }

    private static int parseStatus(String statusLine) {
        String[] parts = statusLine.split(" ", 3); // "HTTP/1.1 413 Payload Too Large"
        try {
            return Integer.parseInt(parts[1]);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static void drainChunked(InputStream in, Tail tail) throws IOException {
        byte[] buf = new byte[64 * 1024];
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                return; // 서버가 도중에 끊음
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            if (hex.isEmpty()) {
                continue;
            }
            long size;
            try {
                size = Long.parseLong(hex, 16);
            } catch (NumberFormatException e) {
                return;
            }
            if (size == 0) {
                return; // 마지막 청크
            }
            long left = size;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    return;
                }
                tail.push(buf, n);
                left -= n;
            }
            readLine(in); // 청크 뒤 CRLF
        }
    }

    private static void drainFixed(InputStream in, Tail tail, long length) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = length;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                return;
            }
            tail.push(buf, n);
            left -= n;
        }
    }

    private static void drainToEof(InputStream in, Tail tail) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            tail.push(buf, n);
        }
    }

    /** 마지막 N 바이트만 붙들고 있는 굴림 버퍼(summary 는 응답 끝에 있음). */
    private static final class Tail {
        private final int keep;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Tail(int keep) {
            this.keep = Math.max(1, keep);
        }

        void push(byte[] b, int len) {
            buf.write(b, 0, len);
            if (buf.size() > keep * 2) {
                byte[] all = buf.toByteArray();
                buf.reset();
                buf.write(all, all.length - keep, keep);
            }
        }

        String text() {
            byte[] all = buf.toByteArray();
            int from = Math.max(0, all.length - keep);
            return new String(all, from, all.length - from, StandardCharsets.UTF_8);
        }
    }

    /** CRLF 로 끝나는 한 줄(헤더·청크 크기줄은 ASCII). */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream sb = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] b = sb.toByteArray();
                int len = (b.length > 0 && b[b.length - 1] == '\r') ? b.length - 1 : b.length;
                return new String(b, 0, len, StandardCharsets.ISO_8859_1);
            }
            sb.write(c);
        }
        return sb.size() == 0 ? null : sb.toString(StandardCharsets.ISO_8859_1);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
