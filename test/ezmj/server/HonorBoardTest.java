package ezmj.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ezmj.model.BotLevel;
import ezmj.model.GameRecord;

/** 服務器的榮譽榜：留前十筆、贏錢照底台分榜、重開還在、讀不到存檔時不能蓋掉舊紀錄。 */
final class HonorBoardTest {

    private static final int RETRY_MS = 5;
    private static final String GIST_ID = "abc123";
    private static final String TOKEN = "test-token";

    private HonorBoardTest() {
    }

    private interface GistCase {
        void run(FakeGist gist) throws Exception;
    }

    static void run() {
        T.section("榮譽榜存檔");
        T.test("每個類別只留最好的十筆，由高到低", () -> T.inTempFolder(folder -> {
            HonorBoard board = fileBoard(folder.resolve("honor.properties"));
            for (int winnings = 1; winnings <= 12; winnings++) {
                board.add(List.of(record("玩家" + winnings, winnings)));
            }
            List<?> entries = (List<?>) board.toMessage().get("winnings");
            T.eq(10, entries.size(), "筆數");
            T.eq(12, ((Map<?, ?>) entries.get(0)).get("winnings"), "第一名");
            T.eq(3, ((Map<?, ?>) entries.get(9)).get("winnings"), "第十名");
            board.close();
        }));
        T.test("贏錢金額照底台分榜，winnings 留給舊用戶端，是所有底台合起來的", () -> T.inTempFolder(folder -> {
            HonorBoard board = fileBoard(folder.resolve("honor.properties"));
            board.add(List.of(record("小底", 900, 100, 20), record("大底", 90_000, 1000, 200),
                    record("小底二", 300, 100, 20)));
            List<?> byStakes = (List<?>) board.toMessage().get("winningsByStakes");
            T.eq(2, byStakes.size(), "兩組底台");
            Map<?, ?> small = (Map<?, ?>) byStakes.get(0);
            T.eq(100, small.get("basePay"), "底小的在前面");
            T.eq(20, small.get("taiPay"), "每台");
            T.eq(List.of("小底", "小底二"), names((List<?>) small.get("records")), "同底台的榜");
            T.eq(List.of("大底"), names((List<?>) ((Map<?, ?>) byStakes.get(1)).get("records")), "大底台的榜");
            T.eq(List.of("大底", "小底", "小底二"), names(winnings(board)), "不分底台的");
            board.close();
        }));
        T.test("0.10.0 存的榜沒有分底台，讀進來照底台分好，寫回時兩份都有", () -> T.inTempFolder(folder -> {
            Path file = folder.resolve("honor.properties");
            Files.writeString(file, String.join("\n", List.of(
                    "WINNINGS.0.name=big", "WINNINGS.0.date=2026-11-01", "WINNINGS.0.wins=1", "WINNINGS.0.selfDraws=0",
                    "WINNINGS.0.winnings=9000", "WINNINGS.0.dealIns=0", "WINNINGS.0.botLevel=PRO,PRO,PRO",
                    "WINNINGS.0.basePay=1000", "WINNINGS.0.taiPay=200",
                    "WINS.0.name=small", "WINS.0.date=2026-11-01", "WINS.0.wins=3", "WINS.0.selfDraws=0",
                    "WINS.0.winnings=50", "WINS.0.dealIns=0", "WINS.0.botLevel=PRO,PRO,PRO",
                    "WINS.0.basePay=100", "WINS.0.taiPay=20")));
            HonorBoard board = fileBoard(file);
            List<?> byStakes = (List<?>) board.toMessage().get("winningsByStakes");
            T.eq(2, byStakes.size(), "胡牌榜上有贏錢的那場也排進它的底台");
            T.eq(List.of("small"), names((List<?>) ((Map<?, ?>) byStakes.get(0)).get("records")), "小底台");
            board.add(List.of(record("新", 10, 100, 20)));
            board.close();
            String saved = Files.readString(file);
            T.check(saved.contains("WINNINGS.100x20.1.name") && saved.contains("WINNINGS.0.name"), "兩份都存");
        }));
        T.test("輸錢的場次不會上贏錢榜", () -> T.inTempFolder(folder -> {
            HonorBoard board = fileBoard(folder.resolve("honor.properties"));
            board.add(List.of(record("輸家", -500)));
            T.check(winnings(board).isEmpty(), "贏錢榜要是空的");
            board.close();
        }));
        T.test("重開服務器之後紀錄還在，中文名字也讀得回來", () -> T.inTempFolder(folder -> {
            Path file = folder.resolve("honor.properties");
            HonorBoard first = fileBoard(file);
            first.add(List.of(record("小明", 300)));
            first.close();
            HonorBoard second = fileBoard(file);
            Map<?, ?> entry = (Map<?, ?>) winnings(second).get(0);
            T.eq("小明", entry.get("name"), "名字");
            T.eq(300, entry.get("winnings"), "金額");
            T.eq(List.of("NORMAL", "PRO", "BEGINNER"), entry.get("botLevels"), "電腦等級");
            second.close();
        }));
        T.test("只給檔名也存得起來", () -> {
            Path file = Path.of("honor-board-test.properties");
            try {
                HonorBoard board = fileBoard(file);
                board.add(List.of(record("小明", 300)));
                board.close();
                T.check(Files.exists(file), "存到目前的資料夾");
            } finally {
                Files.deleteIfExists(file);
            }
        });
        T.test("存不了也不會影響牌局，紀錄還在記憶體裡", () -> T.inTempFolder(folder -> {
            Path notFolder = folder.resolve("不是資料夾");
            Files.writeString(notFolder, "");
            HonorBoard board = fileBoard(notFolder.resolve("honor.properties"));
            board.add(List.of(record("小明", 300)));
            T.eq(1, winnings(board).size(), "筆數");
            board.close();
        }));

        T.section("榮譽榜存在 Gist");
        gistTest("存進 Gist，服務器重開後讀得回來", gist -> {
            HonorBoard first = gistBoard(gist);
            first.add(List.of(record("小明", 300)));
            first.close();
            T.eq("Bearer " + TOKEN, gist.authorization, "帶上 token");
            T.check(gist.content.contains("WINNINGS"), "Gist 裡有內容");
            HonorBoard second = gistBoard(gist);
            T.eq("小明", ((Map<?, ?>) winnings(second).get(0)).get("name"), "讀回名字");
            second.close();
        });
        gistTest("Gist 裡還沒有這個檔案就從空的開始，沒有紀錄不會寫入", gist -> {
            gist.content = null;
            HonorBoard board = gistBoard(gist);
            T.check(winnings(board).isEmpty(), "空的榜");
            board.close();
            T.eq(0, gist.writes.get(), "沒有寫入");
        });
        gistTest("打完不用等 Gist 回應，牌桌不會被卡住", gist -> {
            HonorBoard board = gistBoard(gist);
            gist.writeDelayMs = 2_000;
            long start = System.currentTimeMillis();
            board.add(List.of(record("小明", 300)));
            T.check(System.currentTimeMillis() - start < 1_000, "add 要馬上回來");
            T.eq(1, winnings(board).size(), "馬上查得到");
            gist.writeDelayMs = 0;
            board.close();
        });
        gistTest("啟動時讀不到 Gist 不會蓋掉舊紀錄，讀到之後再合併", gist -> {
            seed(gist, "老玩家", 900);
            gist.failReads.set(Integer.MAX_VALUE);
            HonorBoard board = gistBoard(gist);
            board.add(List.of(record("新玩家", 500)));
            T.eq(1, winnings(board).size(), "這段時間查得到新成績");
            Thread.sleep(RETRY_MS * 4L);
            T.eq(0, gist.writes.get(), "讀到之前不能寫入");
            gist.failReads.set(0);
            board.add(List.of(record("再一位", 100)));
            await(() -> gist.content.contains("WINNINGS.2."), "合併後寫回");
            List<?> merged = winnings(board);
            T.eq(3, merged.size(), "三筆都在");
            T.eq("老玩家", ((Map<?, ?>) merged.get(0)).get("name"), "舊紀錄還在第一名");
            board.close();
        });
        gistTest("寫入失敗會再試，之後補寫成功", gist -> {
            HonorBoard board = gistBoard(gist);
            gist.failWrites.set(2);
            board.add(List.of(record("小明", 300)));
            await(() -> gist.content.contains("WINNINGS"), "重試後寫入");
            T.eq(3, gist.writes.get(), "試了三次");
            board.close();
        });
        gistTest("token 失效時榜還能用，log 裡也不會有 token", gist -> {
            gist.rejectAll = true;
            HonorBoard board = gistBoard(gist);
            board.add(List.of(record("小明", 300)));
            T.eq(1, winnings(board).size(), "筆數");
            board.close();
            try {
                new GistStore(gist.base(), GIST_ID, TOKEN).read();
                throw new AssertionError("應該要丟出 IOException");
            } catch (IOException expected) {
                T.check(!expected.getMessage().contains(TOKEN), "錯誤訊息不能有 token");
            }
        });
        gistTest("Gist 回應看不懂時當作讀不到，不能當成空的", gist -> {
            gist.garbled = true;
            try {
                new GistStore(gist.base(), GIST_ID, TOKEN).read();
                throw new AssertionError("應該要丟出 IOException");
            } catch (IOException expected) {
                T.check(expected.getMessage().contains("看不懂"), "錯誤訊息");
            }
        });
    }

    private static void gistTest(String name, GistCase body) {
        T.test(name, () -> {
            FakeGist gist = new FakeGist();
            try {
                body.run(gist);
            } finally {
                gist.stop();
            }
        });
    }

    private static HonorBoard fileBoard(Path file) {
        return new HonorBoard(HonorStore.file(file), RETRY_MS);
    }

    private static HonorBoard gistBoard(FakeGist gist) {
        return new HonorBoard(new GistStore(gist.base(), GIST_ID, TOKEN), RETRY_MS);
    }

    private static void seed(FakeGist gist, String name, int winnings) {
        HonorBoard board = gistBoard(gist);
        board.add(List.of(record(name, winnings)));
        board.close();
        gist.writes.set(0);
    }

    private static List<?> winnings(HonorBoard board) {
        return (List<?>) board.toMessage().get("winnings");
    }

    private static void await(BooleanSupplier done, String description) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等太久：" + description);
            }
            Thread.sleep(5);
        }
    }

    private static GameRecord record(String name, int winnings) {
        return record(name, winnings, 100, 20);
    }

    private static GameRecord record(String name, int winnings, int basePay, int taiPay) {
        return new GameRecord(name, "2026-11-01", 1, 0, winnings, 0,
                List.of(BotLevel.NORMAL, BotLevel.PRO, BotLevel.BEGINNER), basePay, taiPay);
    }

    private static List<Object> names(List<?> entries) {
        List<Object> names = new ArrayList<>();
        for (Object entry : entries) {
            names.add(((Map<?, ?>) entry).get("name"));
        }
        return names;
    }

    /** 在本機假裝是 GitHub，回應的格式和真的 Gist API 一樣，只留用得到的欄位。 */
    private static final class FakeGist {

        private final HttpServer server;
        final AtomicInteger failReads = new AtomicInteger();
        final AtomicInteger failWrites = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        volatile String content = "# EZMJ";
        volatile String authorization;
        volatile boolean rejectAll;
        volatile boolean garbled;
        volatile int writeDelayMs;

        FakeGist() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/gists/" + GIST_ID, this::handle);
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (rejectAll) {
                respond(exchange, 401, "{\"message\":\"Bad credentials\"}");
            } else if (exchange.getRequestMethod().equals("GET")) {
                read(exchange);
            } else if (exchange.getRequestMethod().equals("PATCH")) {
                write(exchange);
            } else {
                respond(exchange, 405, "{}");
            }
        }

        private void read(HttpExchange exchange) throws IOException {
            if (failReads.get() > 0) {
                failReads.decrementAndGet();
                respond(exchange, 503, "{}");
            } else if (garbled) {
                respond(exchange, 200, "<html>");
            } else {
                respond(exchange, 200, body());
            }
        }

        private void write(HttpExchange exchange) throws IOException {
            sleep();
            writes.incrementAndGet();
            if (failWrites.get() > 0) {
                failWrites.decrementAndGet();
                respond(exchange, 500, "{}");
                return;
            }
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<?, ?> files = (Map<?, ?>) ((Map<?, ?>) Json.parse(request)).get("files");
            content = (String) ((Map<?, ?>) files.get(GistStore.FILE_NAME)).get("content");
            respond(exchange, 200, body());
        }

        private void sleep() {
            try {
                Thread.sleep(writeDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private String body() {
            Map<String, Object> files = Protocol.object("other.txt", Protocol.object("content", "別的檔案", "truncated", false));
            if (content != null) {
                files.put(GistStore.FILE_NAME, Protocol.object("content", content, "truncated", false));
            }
            return Json.write(Protocol.object("id", GIST_ID, "public", false, "comments", 0, "files", files));
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
