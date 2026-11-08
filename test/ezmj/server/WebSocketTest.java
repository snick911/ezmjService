package ezmj.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 真的開一台服務器，用 JDK 內建的 HTTP 與 WebSocket 客戶端連上去，和服務器的實作互相驗證。 */
final class WebSocketTest {

    private static final Timing FAST = new Timing(0, 30, 30, 300, true);
    private static final int TIMEOUT_SECONDS = 20;

    private WebSocketTest() {
    }

    private interface Scenario {
        void run(int port) throws Exception;
    }

    static void run() {
        T.section("WebSocket");
        T.test("Sec-WebSocket-Accept 和 RFC 6455 的例子一樣", () ->
                T.eq("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", ezmj.server.WebSocket.accept("dGhlIHNhbXBsZSBub25jZQ=="), "accept"));
        test("健康檢查回 200，其他路徑回 404", port -> {
            T.eq(200, get(port, "/healthz").statusCode(), "健康檢查");
            T.eq(404, get(port, "/").statusCode(), "沒有這個路徑");
            T.eq(426, get(port, "/ws").statusCode(), "沒有要求升級");
        });
        test("狀態：存活時間、線上人數、是否正常", port -> {
            HttpResponse<String> response = get(port, "/status");
            T.eq(200, response.statusCode(), "狀態碼");
            T.eq("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(""), "網頁可以讀");
            T.eq("no-store", response.headers().firstValue("Cache-Control").orElse(""), "不能快取");
            Map<String, Object> idle = json(response);
            T.eq(true, idle.get("ok"), "正常");
            T.eq(List.of(), idle.get("problems"), "沒有問題");
            T.eq(ServerVersion.get(), idle.get("version"), "版號");
            T.check((Long) idle.get("uptimeSeconds") >= 0, "存活時間：" + idle.get("uptimeSeconds"));
            T.check(!Instant.parse((String) idle.get("startedAt")).isAfter(Instant.now()), "啟動時間");
            T.eq(0L, idle.get("online"), "查狀態的這條連線不算");
            T.eq(0L, idle.get("rooms"), "房間數");
            try (Client client = new Client(port)) {
                client.send("{\"type\":\"hello\",\"protocol\":" + Protocol.VERSION + ",\"name\":\"甲\"}");
                client.next("welcome");
                client.send("{\"type\":\"createRoom\"}");
                client.next("room");
                Map<String, Object> busy = json(get(port, "/status"));
                T.eq(1L, busy.get("online"), "連著的玩家");
                T.eq(1L, busy.get("rooms"), "房間數");
                T.eq(0L, busy.get("playingRooms"), "還沒開打");
            }
            HttpResponse<String> head = send(port, HttpRequest.newBuilder(uri(port, "/status"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
            T.eq(200, head.statusCode(), "HEAD 的狀態碼");
            T.eq("", head.body(), "HEAD 沒有內文");
        });
        T.test("榮譽榜存不進去時狀態不正常", () -> {
            HonorStore broken = new HonorStore() {
                @Override
                public String read() throws IOException {
                    throw new IOException("測試用的壞掉的存放處");
                }

                @Override
                public void write(String content) throws IOException {
                    throw new IOException("測試用的壞掉的存放處");
                }
            };
            EzmjServer server = new EzmjServer(0, FAST, broken);
            server.start();
            try {
                Map<String, Object> status = json(get(server.port(), "/status"));
                T.eq(false, status.get("ok"), "不正常");
                T.eq(List.of("honorRollSync"), status.get("problems"), "問題");
            } finally {
                server.stop();
            }
        });
        test("登入、中文名字與 ping", port -> {
            try (Client client = new Client(port)) {
                client.send("{\"type\":\"ping\"}");
                T.eq("pong", client.next("pong").get("type"), "pong");
                client.send("{\"type\":\"hello\",\"protocol\":" + Protocol.VERSION + ",\"name\":\"小明\"}");
                T.eq("小明", client.next("welcome").get("name"), "名字");
            }
        });
        test("分段送出的訊息要接起來", port -> {
            try (Client client = new Client(port)) {
                client.socket.sendText("{\"type\":", false).join();
                client.socket.sendText("\"ping\"}", true).join();
                client.next("pong");
            }
        });
        test("透過 WebSocket 開局，收得到長的局面訊息", port -> {
            try (Client client = new Client(port)) {
                client.send("{\"type\":\"hello\",\"protocol\":" + Protocol.VERSION + ",\"name\":\"甲\"}");
                client.next("welcome");
                client.send("{\"type\":\"createRoom\"}");
                client.next("room");
                client.send("{\"type\":\"start\"}");
                Map<String, Object> state = client.next("state");
                T.check(Json.write(state).length() > 125, "局面要長到用上 16 位元的長度");
            }
        });
        test("協定版本不對，收到錯誤後正常關閉", port -> {
            try (Client client = new Client(port)) {
                client.send("{\"type\":\"hello\",\"protocol\":999,\"name\":\"甲\"}");
                T.eq("protocolMismatch", client.next("error").get("code"), "錯誤代碼");
                client.awaitClosed();
            }
        });
        test("客戶端要求關閉時要回應", port -> {
            try (Client client = new Client(port)) {
                client.socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
                T.eq(WebSocket.NORMAL_CLOSURE, client.awaitClosed(), "狀態碼");
            }
        });
        test("WebSocket 層的 ping 要回 pong", port -> {
            try (Client client = new Client(port)) {
                client.socket.sendPing(ByteBuffer.wrap("hi".getBytes(StandardCharsets.UTF_8))).join();
                T.eq("hi", client.pongs.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS), "pong 內容");
            }
        });
        test("沒有遮罩的訊框就斷線", port -> {
            try (Socket socket = upgraded(port)) {
                OutputStream out = socket.getOutputStream();
                out.write(new byte[] {(byte) 0x81, 2, '{', '}'});
                out.flush();
                T.eq(-1, socket.getInputStream().read(), "應該斷線");
            }
        });
        test("太長的訊框就斷線，不等內容送到", port -> {
            try (Socket socket = upgraded(port)) {
                OutputStream out = socket.getOutputStream();
                out.write(new byte[] {(byte) 0x81, (byte) 0xFF, 0x7F, -1, -1, -1, -1, -1, -1, -1});
                out.flush();
                T.eq(-1, socket.getInputStream().read(), "應該斷線");
            }
        });

        T.section("同一個 port 上的 TCP");
        test("開頭像 HTTP 但不是的訊息，還是當作壞掉的 JSON", port -> {
            try (TestClient client = new TestClient(port)) {
                client.sendRaw("Hello");
                T.eq("badMessage", client.next("error").get("code"), "H 開頭");
                client.sendRaw("GE");
                T.eq("badMessage", client.next("error").get("code"), "G 開頭");
                client.send("type", "ping");
                client.next("pong");
            }
        });
    }

    private static void test(String name, Scenario scenario) {
        T.test(name, () -> T.inTempFolder(folder -> {
            EzmjServer server = new EzmjServer(0, FAST, HonorStore.file(folder.resolve("honor.properties")));
            server.start();
            try {
                scenario.run(server.port());
            } finally {
                server.stop();
            }
        }));
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return send(port, HttpRequest.newBuilder(uri(port, path)).build());
    }

    private static HttpResponse<String> send(int port, HttpRequest request) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(HttpResponse<String> response) {
        return (Map<String, Object>) Json.parse(response.body());
    }

    /** 自己完成升級的 Socket，用來送 JDK 客戶端不會送的壞訊框。 */
    private static Socket upgraded(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(TIMEOUT_SECONDS * 1000);
        String request = "GET /ws HTTP/1.1\r\nHost: test\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new AssertionError("升級時連線關閉了：" + head);
            }
            head.append((char) b);
        }
        T.check(head.toString().startsWith("HTTP/1.1 101"), "要回 101：" + head);
        return socket;
    }

    /** JDK 內建的 WebSocket 客戶端，收到的訊息放進佇列。 */
    private static final class Client implements WebSocket.Listener, AutoCloseable {
        final WebSocket socket;
        final BlockingQueue<String> pongs = new LinkedBlockingQueue<>();
        private final BlockingQueue<Map<String, Object>> incoming = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        Client(int port) {
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), this)
                    .join();
        }

        void send(String json) {
            socket.sendText(json, true).join();
        }

        /** 跳過其他訊息，直到這種訊息。 */
        Map<String, Object> next(String type) throws InterruptedException {
            while (true) {
                Map<String, Object> message = incoming.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (message == null) {
                    throw new AssertionError("等太久沒有收到 " + type);
                }
                if (type.equals(message.get("type"))) {
                    return message;
                }
            }
        }

        int awaitClosed() throws Exception {
            return closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        @Override
        @SuppressWarnings("unchecked")
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                incoming.add((Map<String, Object>) Json.parse(partial.toString()));
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            pongs.add(StandardCharsets.UTF_8.decode(message).toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }

        @Override
        public void close() {
            socket.abort();
        }
    }
}
