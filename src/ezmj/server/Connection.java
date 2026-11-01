package ezmj.server;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 一條客戶端連線，一則 UTF-8 JSON 是 TCP 上的一行，或 WebSocket 的一則文字訊息，看第一則訊息決定。
 * 讀與寫各一條執行緒：牌桌只把訊息放進佇列，對方網路再慢也不會卡住其他人；佇列滿了就斷線。
 */
final class Connection {

    /** 客戶端的訊息都很短，超過就視為惡意並斷線。 */
    private static final int MAX_LINE_BYTES = 64 * 1024;
    /** 這段時間什麼都沒收到就斷線，客戶端閒置時要送 ping。 */
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_QUEUED = 1024;
    /** 佇列裡的這個記號表示前面的都送完就關閉。 */
    private static final byte[] END = new byte[0];

    interface Handler {
        /** 在這條連線的讀取執行緒上呼叫，同一條連線的訊息依序處理。 */
        void message(Connection connection, Map<String, Object> message);

        /** 只會呼叫一次。 */
        void closed(Connection connection);
    }

    private final Socket socket;
    private final Handler handler;
    private final BlockingQueue<byte[]> outgoing = new LinkedBlockingQueue<>(MAX_QUEUED);
    private final Thread reader;
    private final Thread writer;
    private volatile Member member;
    /** 讀取執行緒在處理第一則訊息之前決定，之後才會有東西要送。 */
    private volatile boolean webSocket;

    Connection(Socket socket, Handler handler) {
        this.socket = socket;
        this.handler = handler;
        this.reader = new Thread(this::readLoop, "ezmj-read");
        this.writer = new Thread(this::writeLoop, "ezmj-write");
        reader.setDaemon(true);
        writer.setDaemon(true);
    }

    void start() throws IOException {
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        reader.start();
        writer.start();
    }

    Member member() {
        return member;
    }

    void setMember(Member member) {
        this.member = member;
    }

    void send(Map<String, Object> message) {
        String json = Json.write(message);
        enqueue(webSocket
                ? WebSocket.frame(WebSocket.TEXT, json.getBytes(StandardCharsets.UTF_8))
                : (json + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** 送出這則訊息後關閉。 */
    void sendAndClose(Map<String, Object> message) {
        send(message);
        if (webSocket) {
            enqueue(WebSocket.frame(WebSocket.CLOSE, new byte[0]));
        }
        enqueue(END);
    }

    private void enqueue(byte[] bytes) {
        if (!outgoing.offer(bytes)) {
            close();
        }
    }

    void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // 本來就要關了
        }
        writer.interrupt();
    }

    private void readLoop() {
        try (InputStream in = new BufferedInputStream(socket.getInputStream())) {
            if (!WebSocket.startsHttp(in)) {
                readLines(in);
            } else if (answerHttp(in)) {
                webSocket = true;
                readFrames(in);
            }
        } catch (IOException e) {
            // 對方斷線、太久沒有訊息或不合規定的訊框，都當作斷線
        } finally {
            close();
            handler.closed(this);
        }
    }

    private void readLines(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                dispatch(line.toString(StandardCharsets.UTF_8));
                line.reset();
            } else if (line.size() >= MAX_LINE_BYTES) {
                return;
            } else {
                line.write(b);
            }
        }
    }

    /** 這時還沒有任何訊息要送，寫入執行緒閒著，可以直接寫。回傳這條連線是不是改走 WebSocket。 */
    private boolean answerHttp(InputStream in) throws IOException {
        WebSocket.Answer answer = WebSocket.answer(in);
        OutputStream out = socket.getOutputStream();
        out.write(answer.response());
        out.flush();
        return answer.upgraded();
    }

    private void readFrames(InputStream in) throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        boolean fragmented = false;
        while (true) {
            WebSocket.Frame frame = WebSocket.readFrame(in, MAX_LINE_BYTES);
            int opcode = frame.opcode();
            if (opcode == WebSocket.PING) {
                enqueue(WebSocket.frame(WebSocket.PONG, frame.payload()));
            } else if (opcode == WebSocket.CLOSE) {
                // 回完就由寫入執行緒關閉，這裡繼續讀到連線關閉為止
                enqueue(WebSocket.frame(WebSocket.CLOSE, frame.payload()));
                enqueue(END);
            } else if (opcode == (fragmented ? WebSocket.CONTINUATION : WebSocket.TEXT)) {
                text.writeBytes(frame.payload());
                if (text.size() > MAX_LINE_BYTES) {
                    return;
                }
                fragmented = !frame.last();
                if (frame.last()) {
                    dispatch(text.toString(StandardCharsets.UTF_8));
                    text.reset();
                }
            } else if (opcode != WebSocket.PONG) {
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatch(String line) {
        if (line.isBlank()) {
            return;
        }
        try {
            Object parsed = Json.parse(line);
            if (!(parsed instanceof Map)) {
                throw new Rejected("badMessage", "訊息要是 JSON 物件");
            }
            handler.message(this, (Map<String, Object>) parsed);
        } catch (Rejected rejected) {
            send(Protocol.error(rejected.code(), rejected.getMessage()));
        } catch (IllegalArgumentException badJson) {
            send(Protocol.error("badMessage", "訊息不是合法的 JSON"));
        } catch (RuntimeException bug) {
            EzmjServer.log("處理訊息時發生錯誤", bug);
            send(Protocol.error("internal", "服務器發生錯誤"));
        }
    }

    private void writeLoop() {
        try (OutputStream out = socket.getOutputStream()) {
            while (true) {
                byte[] line = outgoing.take();
                if (line == END) {
                    return;
                }
                out.write(line);
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // 連線已經斷了，close 會讓讀取執行緒收尾
        } finally {
            close();
        }
    }
}
