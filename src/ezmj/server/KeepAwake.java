package ezmj.server;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 讓 Render 免費方案的服務不要休眠：每隔一段時間從外面連一次自己的公開網址。
 * Render 15 分鐘沒有收到 HTTP 請求或 WebSocket 訊息就讓服務休眠，而休眠後偶爾叫不醒：
 * 請求一直卡著，服務也沒有啟動，只能手動重新部署。請求要經過 Render 的入口才算流量，所以要連公開網址，不能連 localhost。
 * 代價是一直醒著，每月會用掉免費方案 750 小時裡的 720 到 744 小時，見 README 的「部署到 Render」。
 */
final class KeepAwake implements AutoCloseable {

    /** Render 15 分鐘沒有流量才休眠，留 5 分鐘給偶爾慢一點的請求。 */
    static final long INTERVAL_MS = 10 * 60_000L;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final URI url;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ezmj-keep-awake");
        thread.setDaemon(true);
        return thread;
    });
    /** 只在 timer 的執行緒上用。失敗每次都記，恢復時記一次，正常時不記，免得 log 被洗掉。 */
    private boolean failing;

    /** 要是有主機名稱的 http 或 https 網址，不然丟出 IllegalArgumentException，訊息可以直接顯示。 */
    static URI parse(String text) {
        URI url;
        try {
            url = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("不是合法的網址：" + text);
        }
        String scheme = url.getScheme();
        if (url.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("要是 http 或 https 的完整網址：" + text);
        }
        return url;
    }

    /** 第一次在 intervalMs 之後，服務剛啟動時本來就有流量。 */
    KeepAwake(URI url, long intervalMs) {
        this.url = url;
        timer.scheduleWithFixedDelay(this::ping, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** 任何例外都要接住：scheduleWithFixedDelay 的工作丟出例外之後就不會再執行了。 */
    private void ping() {
        try {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "ezmj-server keep-awake")
                    .GET()
                    .build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 200) {
                throw new IOException("回應 HTTP " + status);
            }
            if (failing) {
                failing = false;
                EzmjServer.log("保持清醒的請求恢復正常");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            failing = true;
            EzmjServer.log("保持清醒的請求失敗，下次照常再試：" + e);
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
