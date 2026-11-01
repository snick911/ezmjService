package ezmj.server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 把榮譽榜存在 GitHub Gist 的一個檔案裡。Render 的免費方案不能掛硬碟，容器的檔案在休眠或部署後就沒了，
 * Gist 不受影響。錯誤訊息只有狀態碼，token 不會進到 log。
 */
final class GistStore implements HonorStore {

    static final String GITHUB_API = "https://api.github.com";
    static final String FILE_NAME = "honor-roll.properties";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final URI gist;
    private final String token;

    GistStore(String apiBase, String gistId, String token) {
        this.gist = URI.create(apiBase + "/gists/" + gistId);
        this.token = token;
    }

    @Override
    public String read() throws IOException {
        String body = send(request().GET().build());
        try {
            Map<?, ?> files = (Map<?, ?>) ((Map<?, ?>) Json.parse(body)).get("files");
            Map<?, ?> file = (Map<?, ?>) files.get(FILE_NAME);
            if (file == null) {
                return null;
            }
            if (Boolean.TRUE.equals(file.get("truncated"))) {
                throw new IOException("Gist 的內容被截斷了");
            }
            return (String) file.get("content");
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            throw new IOException("看不懂 Gist 的回應", e);
        }
    }

    @Override
    public void write(String content) throws IOException {
        String body = Json.write(Protocol.object("files",
                Protocol.object(FILE_NAME, Protocol.object("content", content))));
        send(request().method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(gist)
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "ezmj-server")
                .header("X-GitHub-Api-Version", "2022-11-28");
    }

    private String send(HttpRequest request) throws IOException {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等 GitHub 回應時被中斷", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub 回應 " + response.statusCode());
        }
        return response.body();
    }
}
