package ezmj.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 保持清醒：定期連設定的網址，失敗了下次照樣再連。真的服務器每 10 分鐘一次，這裡用很短的間隔。 */
final class KeepAwakeTest {

    private static final long INTERVAL_MS = 30;
    private static final int WAIT_SECONDS = 10;

    private KeepAwakeTest() {
    }

    static void run() {
        T.section("保持清醒");
        T.test("只接受 http 與 https 的完整網址", () -> {
            T.eq(URI.create("https://ezmj-server.onrender.com/status"),
                    KeepAwake.parse("https://ezmj-server.onrender.com/status"), "https");
            T.eq(URI.create("http://127.0.0.1:7788/status"), KeepAwake.parse("http://127.0.0.1:7788/status"), "http");
            T.fails(IllegalArgumentException.class, () -> KeepAwake.parse("ezmj-server.onrender.com/status"), "沒有 https://");
            T.fails(IllegalArgumentException.class, () -> KeepAwake.parse("ftp://example.com/"), "不是 http");
            T.fails(IllegalArgumentException.class, () -> KeepAwake.parse("https://bad host/"), "不合法的網址");
        });
        T.test("定期連到設定的網址", () -> {
            try (Target target = new Target(0)) {
                try (KeepAwake keepAwake = new KeepAwake(target.url(), INTERVAL_MS)) {
                    T.check(target.awaitHits(3), "應該連了至少三次，實際 " + target.hits.get());
                }
            }
        });
        T.test("回應錯誤之後還會繼續連", () -> {
            try (Target target = new Target(2)) {
                try (KeepAwake keepAwake = new KeepAwake(target.url(), INTERVAL_MS)) {
                    T.check(target.awaitHits(4), "前兩次回 500 之後應該繼續連，實際 " + target.hits.get());
                }
            }
        });
    }

    /** 計算收到幾次請求，前 failFirst 次回 500。 */
    private static final class Target implements AutoCloseable {
        private final HttpServer server;
        private final int failFirst;
        final AtomicInteger hits = new AtomicInteger();

        Target(int failFirst) throws IOException {
            this.failFirst = failFirst;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/status", this::handle);
            server.start();
        }

        URI url() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/status");
        }

        private void handle(HttpExchange exchange) throws IOException {
            int count = hits.incrementAndGet();
            exchange.sendResponseHeaders(count <= failFirst ? 500 : 200, -1);
            exchange.close();
        }

        boolean awaitHits(int wanted) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (hits.get() < wanted && System.nanoTime() < deadline) {
                Thread.sleep(INTERVAL_MS);
            }
            return hits.get() >= wanted;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
