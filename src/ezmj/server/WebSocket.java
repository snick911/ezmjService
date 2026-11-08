package ezmj.server;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 和 TCP 共用同一個 port 的 HTTP 與 WebSocket（RFC 6455）。Render 這類主機只把 HTTP 轉進來，
 * TLS 也在它們那邊處理好了，這裡看到的都是明文。只做服務器用得到的部分：健康檢查、狀態，以及升級成 WebSocket。
 */
final class WebSocket {

    static final String PATH = "/ws";
    static final String HEALTH_PATH = "/healthz";
    static final String STATUS_PATH = "/status";

    static final int CONTINUATION = 0;
    static final int TEXT = 1;
    static final int CLOSE = 8;
    static final int PING = 9;
    static final int PONG = 10;

    private static final int MAX_HEAD_BYTES = 8 * 1024;
    private static final int MAX_CONTROL_BYTES = 125;
    private static final int MASK_BYTES = 4;
    private static final String VERSION = "13";
    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocket() {
    }

    /** 一個訊框。payload 已經解開遮罩。 */
    record Frame(boolean last, int opcode, byte[] payload) {
    }

    /** 要回給 HTTP 請求的內容，upgraded 表示接下來這條連線改走 WebSocket。 */
    record Answer(byte[] response, boolean upgraded) {
    }

    /**
     * 看第一則訊息是不是 HTTP，讀過的位元組會放回去，所以 in 要支援 mark。
     * 一邊讀一邊比，對不上就停：TCP 的訊息可能很短，不能硬等滿四個位元組。JSON 不會是這樣開頭的。
     */
    static boolean startsHttp(InputStream in) throws IOException {
        String get = "GET ";
        String head = "HEAD";
        in.mark(get.length());
        try {
            StringBuilder seen = new StringBuilder();
            while (seen.length() < get.length()) {
                int b = in.read();
                if (b < 0) {
                    return false;
                }
                seen.append((char) b);
                if (!get.startsWith(seen.toString()) && !head.startsWith(seen.toString())) {
                    return false;
                }
            }
            return true;
        } finally {
            in.reset();
        }
    }

    /** 讀一個 HTTP 請求的開頭，決定怎麼回答。status 只在要求狀態時才呼叫。 */
    static Answer answer(InputStream in, Supplier<Map<String, Object>> status) throws IOException {
        String[] lines = readHead(in).split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length != 3) {
            return plain("400 Bad Request", false, "");
        }
        boolean headOnly = request[0].equals("HEAD");
        String path = request[1].split("\\?", 2)[0];
        if (path.equals(HEALTH_PATH)) {
            return plain("200 OK", headOnly, "");
        }
        if (path.equals(STATUS_PATH)) {
            return json(status.get(), headOnly);
        }
        if (!path.equals(PATH)) {
            return plain("404 Not Found", headOnly, "");
        }
        Map<String, String> headers = headers(lines);
        if (headOnly || !"websocket".equalsIgnoreCase(headers.get("upgrade"))) {
            return plain("426 Upgrade Required", headOnly, "Upgrade: websocket\r\n");
        }
        if (!VERSION.equals(headers.get("sec-websocket-version"))) {
            return plain("426 Upgrade Required", false, "Sec-WebSocket-Version: " + VERSION + "\r\n");
        }
        String key = headers.get("sec-websocket-key");
        if (key == null) {
            return plain("400 Bad Request", false, "");
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept(key) + "\r\n\r\n";
        return new Answer(response.getBytes(StandardCharsets.ISO_8859_1), true);
    }

    /** 回應之後就關閉連線，內文只有狀態。 */
    private static Answer plain(String status, boolean headOnly, String extraHeaders) {
        return respond(status, headOnly, extraHeaders + "Content-Type: text/plain\r\n",
                (status + "\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    /** 內容本來就公開，網頁也要能直接讀，所以允許任何來源；每次都是當下的值，不能被快取。 */
    private static Answer json(Map<String, Object> body, boolean headOnly) {
        return respond("200 OK", headOnly,
                "Content-Type: application/json; charset=utf-8\r\n"
                        + "Cache-Control: no-store\r\n"
                        + "Access-Control-Allow-Origin: *\r\n",
                Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static Answer respond(String status, boolean headOnly, String headers, byte[] body) {
        String head = "HTTP/1.1 " + status + "\r\n"
                + headers
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.writeBytes(head.getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly) {
            response.writeBytes(body);
        }
        return new Answer(response.toByteArray(), false);
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = readByte(in);
            if (head.size() >= MAX_HEAD_BYTES) {
                throw new IOException("HTTP 開頭太長");
            }
            head.write(b);
            matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    private static Map<String, String> headers(String[] lines) {
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).strip());
            }
        }
        return headers;
    }

    static String accept(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + ACCEPT_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 讀一個客戶端的訊框。不合規定或太長都丟出 IOException，呼叫端當作斷線。 */
    static Frame readFrame(InputStream in, int maxPayload) throws IOException {
        int first = readByte(in);
        int second = readByte(in);
        if ((first & 0x70) != 0) {
            throw new IOException("沒有協商過的 RSV 位元");
        }
        if ((second & 0x80) == 0) {
            throw new IOException("客戶端的訊框一定要有遮罩");
        }
        long length = second & 0x7F;
        if (length == 126) {
            length = readNumber(in, 2);
        } else if (length == 127) {
            length = readNumber(in, 8);
        }
        if (length < 0 || length > maxPayload) {
            throw new IOException("訊框太長");
        }
        boolean last = (first & 0x80) != 0;
        int opcode = first & 0x0F;
        if (opcode >= CLOSE && (!last || length > MAX_CONTROL_BYTES)) {
            throw new IOException("控制訊框不能分段，也不能超過 " + MAX_CONTROL_BYTES + " 位元組");
        }
        byte[] mask = readExactly(in, MASK_BYTES);
        byte[] payload = readExactly(in, (int) length);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % MASK_BYTES];
        }
        return new Frame(last, opcode, payload);
    }

    /** 服務器送出的訊框，不分段也不加遮罩。 */
    static byte[] frame(int opcode, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 10);
        out.write(0x80 | opcode);
        if (payload.length <= MAX_CONTROL_BYTES) {
            out.write(payload.length);
        } else if (payload.length <= 0xFFFF) {
            out.write(126);
            writeNumber(out, payload.length, 2);
        } else {
            out.write(127);
            writeNumber(out, payload.length, 8);
        }
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private static long readNumber(InputStream in, int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            value = value << 8 | readByte(in);
        }
        return value;
    }

    private static void writeNumber(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = bytes - 1; i >= 0; i--) {
            out.write((int) (value >>> (8 * i)));
        }
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length < length) {
            throw new EOFException();
        }
        return bytes;
    }
}
