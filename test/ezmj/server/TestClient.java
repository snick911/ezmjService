package ezmj.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** 測試用的客戶端，走真的 TCP 連線。收到的訊息都留在 seen，用來檢查有沒有洩漏。 */
final class TestClient implements AutoCloseable {

    private static final Map<String, Object> CLOSED = Map.of("type", "(連線已關閉)");

    private final Socket socket;
    private final OutputStream out;
    private final BlockingQueue<Map<String, Object>> incoming = new LinkedBlockingQueue<>();
    final List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());

    TestClient(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        out = socket.getOutputStream();
        Thread reader = new Thread(this::readLoop, "test-client");
        reader.setDaemon(true);
        reader.start();
    }

    /** 連上之後登入，回傳 welcome。 */
    static TestClient login(int port, String name) throws IOException {
        TestClient client = new TestClient(port);
        client.send("type", "hello", "protocol", Protocol.VERSION, "name", name);
        client.next("welcome");
        return client;
    }

    @SuppressWarnings("unchecked")
    private void readLoop() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                Map<String, Object> message = (Map<String, Object>) Json.parse(line);
                seen.add(message);
                incoming.add(message);
            }
        } catch (IOException e) {
            // 測試自己關的
        }
        incoming.add(CLOSED);
    }

    void send(Object... pairs) throws IOException {
        sendRaw(Json.write(Protocol.object(pairs)));
    }

    void sendRaw(String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 下一則訊息，等太久就失敗。 */
    Map<String, Object> poll() throws InterruptedException {
        Map<String, Object> message = incoming.poll(20, TimeUnit.SECONDS);
        if (message == null) {
            throw new AssertionError("等太久沒有收到訊息");
        }
        return message;
    }

    /** 跳過其他訊息，直到這種訊息。 */
    Map<String, Object> next(String type) {
        return until(message -> type.equals(message.get("type")), type);
    }

    Map<String, Object> until(Predicate<Map<String, Object>> wanted, String description) {
        try {
            while (true) {
                Map<String, Object> message = poll();
                if (wanted.test(message)) {
                    return message;
                }
                if (message == CLOSED) {
                    throw new AssertionError("等 " + description + " 時連線關閉了");
                }
            }
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    boolean isClosedMessage(Map<String, Object> message) {
        return message == CLOSED;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
