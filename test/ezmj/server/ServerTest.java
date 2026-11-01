package ezmj.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** 真的開一台服務器，用 TCP 連上去打。 */
final class ServerTest {

    /** 電腦不停頓，結果畫面也很短。 */
    private static final Timing FAST = new Timing(0, 30, 30, 300, true);

    private ServerTest() {
    }

    private interface Scenario {
        void run(int port) throws Exception;
    }

    static void run() {
        T.section("連線");
        test("要先登入才能做事", port -> {
            try (TestClient client = new TestClient(port)) {
                client.send("type", "createRoom");
                T.eq("notLoggedIn", client.next("error").get("code"), "錯誤代碼");
            }
        });
        test("ping 不用登入", port -> {
            try (TestClient client = new TestClient(port)) {
                client.send("type", "ping");
                client.next("pong");
            }
        });
        test("協定版本不對就斷線", port -> {
            try (TestClient client = new TestClient(port)) {
                client.send("type", "hello", "protocol", 999, "name", "甲");
                T.eq("protocolMismatch", client.next("error").get("code"), "錯誤代碼");
                closes(client);
            }
        });
        test("壞掉的訊息回錯誤，連線還在", port -> {
            try (TestClient client = new TestClient(port)) {
                client.sendRaw("這不是 JSON");
                T.eq("badMessage", client.next("error").get("code"), "不是 JSON");
                client.sendRaw("[1,2]");
                T.eq("badMessage", client.next("error").get("code"), "不是物件");
                client.send("type", 3);
                T.eq("badMessage", client.next("error").get("code"), "type 不是字串");
                client.send("type", "ping");
                client.next("pong");
            }
        });
        test("太長的一行就斷線", port -> {
            try (TestClient client = new TestClient(port)) {
                try {
                    client.sendRaw("x".repeat(70_000));
                } catch (IOException cutOff) {
                    // 服務器可能在還沒寫完時就斷線
                }
                closes(client);
            }
        });
        test("名字不合規定要拒絕", port -> {
            try (TestClient client = new TestClient(port)) {
                client.send("type", "hello", "protocol", Protocol.VERSION, "name", "   ");
                T.eq("badName", client.next("error").get("code"), "空白");
                client.send("type", "hello", "protocol", Protocol.VERSION, "name", "一".repeat(17));
                T.eq("badName", client.next("error").get("code"), "太長");
            }
        });
        test("失效的 token 要拒絕", port -> {
            try (TestClient client = new TestClient(port)) {
                client.send("type", "hello", "protocol", Protocol.VERSION, "token", "nope");
                T.eq("unknownToken", client.next("error").get("code"), "錯誤代碼");
            }
        });

        T.section("房間");
        test("開房間與加入，座位依序排", port -> {
            try (TestClient host = TestClient.login(port, "甲"); TestClient guest = TestClient.login(port, "乙")) {
                String code = createRoom(host);
                guest.send("type", "joinRoom", "code", code.toLowerCase());
                Map<String, Object> room = guest.next("room");
                T.eq(1L, room.get("yourSeat"), "第二位坐座位 1");
                T.eq(2, ((List<?>) room.get("players")).size(), "兩位玩家");
                T.eq(2, ((List<?>) until(host, "room", m -> ((List<?>) m.get("players")).size() == 2).get("players")).size(), "房主也看到");
            }
        });
        test("房間設定不合理要拒絕", port -> {
            try (TestClient host = TestClient.login(port, "甲")) {
                host.send("type", "createRoom", "waitSeconds", 0);
                T.eq("badOptions", host.next("error").get("code"), "不能不限時");
                host.send("type", "createRoom", "botLevels", List.of("PRO"));
                T.eq("badOptions", host.next("error").get("code"), "要三家");
                host.send("type", "createRoom", "speed", "超快");
                T.eq("badOptions", host.next("error").get("code"), "不認得");
            }
        });
        test("找不到房間與房間已滿", port -> {
            try (TestClient host = TestClient.login(port, "一"); TestClient b = TestClient.login(port, "二");
                 TestClient c = TestClient.login(port, "三"); TestClient d = TestClient.login(port, "四");
                 TestClient late = TestClient.login(port, "五")) {
                late.send("type", "joinRoom", "code", "AAAAAA");
                T.eq("noSuchRoom", late.next("error").get("code"), "找不到");
                String code = createRoom(host);
                for (TestClient client : List.of(b, c, d)) {
                    client.send("type", "joinRoom", "code", code);
                    client.next("room");
                }
                late.send("type", "joinRoom", "code", code);
                T.eq("roomFull", late.next("error").get("code"), "已滿");
            }
        });
        test("只有房主可以開局，房主走了由下一位接手", port -> {
            try (TestClient host = TestClient.login(port, "甲"); TestClient guest = TestClient.login(port, "乙")) {
                String code = createRoom(host);
                guest.send("type", "joinRoom", "code", code);
                guest.next("room");
                guest.send("type", "start");
                T.eq("notHost", guest.next("error").get("code"), "不是房主");
                host.send("type", "leave");
                host.next("left");
                Map<String, Object> room = until(guest, "room", m -> Long.valueOf(0).equals(m.get("yourSeat")));
                T.eq(1, ((List<?>) room.get("players")).size(), "只剩一位");
                guest.send("type", "start");
                until(guest, "room", m -> "playing".equals(m.get("state")));
            }
        });
        test("開打之後不能再加入", port -> {
            try (TestClient host = TestClient.login(port, "甲"); TestClient late = TestClient.login(port, "乙")) {
                String code = createRoom(host);
                host.send("type", "start");
                until(host, "room", m -> "playing".equals(m.get("state")));
                late.send("type", "joinRoom", "code", code);
                T.eq("gameInProgress", late.next("error").get("code"), "錯誤代碼");
            }
        });
        test("最後一個人走了房間就關", port -> {
            try (TestClient host = TestClient.login(port, "甲"); TestClient late = TestClient.login(port, "乙")) {
                String code = createRoom(host);
                host.send("type", "leave");
                host.next("left");
                late.send("type", "joinRoom", "code", code);
                T.eq("noSuchRoom", late.next("error").get("code"), "房間沒了");
            }
        });

        T.section("牌局");
        test("四位真人打完一整場，籌碼總和不變，也看不到別人的牌", port -> {
            List<TestClient> clients = seatedClients(port, 4, "chips", 100);
            try {
                clients.get(0).send("type", "start");
                List<Map<String, Object>> results = playAll(clients);
                for (Map<String, Object> gameOver : results) {
                    checkStandings(gameOver, 400);
                }
                T.eq(results.get(0).get("standings"), results.get(1).get("standings"), "大家看到一樣的結果");
                for (TestClient client : clients) {
                    checkNoLeak(client);
                    // 打完回到等人的狀態，可以再開一場
                    until(client, "room", m -> "lobby".equals(m.get("state")));
                }
            } finally {
                closeAll(clients);
            }
        });
        test("沒人坐的座位由電腦打", port -> {
            List<TestClient> clients = seatedClients(port, 2, "chips", 100, "botLevels", List.of("PRO", "AMATEUR", "BEGINNER"));
            try {
                clients.get(0).send("type", "start");
                for (Map<String, Object> gameOver : playAll(clients)) {
                    checkStandings(gameOver, 400);
                }
                for (TestClient client : clients) {
                    checkNoLeak(client);
                }
            } finally {
                closeAll(clients);
            }
        });
        test("不合規則的決定回錯誤，局面不變，牌局繼續", port -> {
            List<TestClient> clients = seatedClients(port, 2);
            try {
                clients.get(0).send("type", "start");
                TestClient mover = firstAskTurn(clients);
                TestClient other = clients.get(0) == mover ? clients.get(1) : clients.get(0);
                Map<String, Object> ask = lastOfType(mover, "askTurn");
                Object waitId = ask.get("waitId");
                Map<String, Object> state = lastOfType(mover, "state");
                String notInHand = tileNotIn(state);

                mover.send("type", "discard", "waitId", waitId, "tile", notInHand);
                T.eq("illegalMove", mover.next("error").get("code"), "手上沒有的牌");
                mover.send("type", "discard", "waitId", waitId, "tile", "8z");
                T.eq("illegalMove", mover.next("error").get("code"), "不存在的牌");
                mover.send("type", "discard", "waitId", waitId, "tile", "xx");
                T.eq("badMessage", mover.next("error").get("code"), "寫不出來的牌");
                mover.send("type", "discard", "waitId", waitId, "tile", "abc");
                T.eq("badMessage", mover.next("error").get("code"), "不是一張牌");
                mover.send("type", "pass", "waitId", waitId);
                T.eq("illegalMove", mover.next("error").get("code"), "輪到自己不能過");
                mover.send("type", "claim", "waitId", waitId, "option", 0);
                T.eq("illegalMove", mover.next("error").get("code"), "輪到自己不能宣告");
                if (!Boolean.TRUE.equals(ask.get("canSelfWin"))) {
                    mover.send("type", "selfWin", "waitId", waitId);
                    T.eq("illegalMove", mover.next("error").get("code"), "不能自摸");
                }
                mover.send("type", "kong", "waitId", waitId, "tile", notInHand);
                T.eq("illegalMove", mover.next("error").get("code"), "不能槓");
                mover.send("type", "listen", "waitId", waitId, "tile", notInHand);
                T.eq("illegalMove", mover.next("error").get("code"), "不會聽牌");

                // 沒輪到的人、過期的編號都直接忽略，不算錯
                other.send("type", "discard", "waitId", waitId, "tile", notInHand);
                mover.send("type", "discard", "waitId", ((Long) waitId) + 100, "tile", notInHand);

                List<?> hand = (List<?>) state.get("hand");
                mover.send("type", "discard", "waitId", waitId, "tile", hand.get(hand.size() - 1));
                Map<String, Object> event = mover.until(m -> "event".equals(m.get("type")) && "DISCARD".equals(m.get("kind")), "打牌事件");
                T.eq(hand.get(hand.size() - 1), event.get("tile"), "打出去的牌");
                T.eq(state.get("seat"), event.get("seat"), "打牌的人");
            } finally {
                closeAll(clients);
            }
        });
        test("倒數到了自動打牌", port -> {
            List<TestClient> clients = seatedClients(port, 1, "waitSeconds", 5);
            try {
                TestClient host = clients.get(0);
                host.send("type", "start");
                untilAskTurn(host);
                Map<String, Object> event = host.until(m -> "event".equals(m.get("type")) && "DISCARD".equals(m.get("kind"))
                        && Long.valueOf(0).equals(m.get("seat")), "自動打牌");
                T.check(event.get("tile") != null, "打出一張牌");
            } finally {
                closeAll(clients);
            }
        });

        T.section("斷線重連");
        test("斷線由電腦代打，重連後補上局面並還給真人", port -> {
            TestClient host = TestClient.login(port, "甲");
            TestClient guest = new TestClient(port);
            try {
                guest.send("type", "hello", "protocol", Protocol.VERSION, "name", "乙");
                String token = (String) guest.next("welcome").get("token");
                String code = createRoom(host);
                guest.send("type", "joinRoom", "code", code);
                guest.next("room");
                host.send("type", "start");
                until(host, "room", m -> "playing".equals(m.get("state")));
                host.send("type", "autoPlay", "on", true);
                guest.close();
                Map<String, Object> room = until(host, "room", m -> !isConnected(m, 1));
                T.check(isConnected(room, 0), "房主還連著");

                try (TestClient back = new TestClient(port)) {
                    back.send("type", "hello", "protocol", Protocol.VERSION, "token", token);
                    Map<String, Object> welcome = back.next("welcome");
                    T.eq(code, welcome.get("room"), "知道自己在哪間房");
                    T.eq("乙", welcome.get("name"), "還是同一位");
                    T.eq(1L, back.next("room").get("yourSeat"), "座位不變");
                    T.check(back.next("state").get("hand") != null, "補上自己的手牌");
                    until(host, "room", m -> isConnected(m, 1));
                    // 代打關掉了才會再問他
                    back.until(m -> "askTurn".equals(m.get("type")) || "askClaim".equals(m.get("type")), "再次輪到真人");
                }
            } finally {
                host.close();
                guest.close();
            }
        });
        test("同一個 token 新連線會取代舊連線，還在等的詢問要重送", port -> {
            TestClient first = new TestClient(port);
            try {
                first.send("type", "hello", "protocol", Protocol.VERSION, "name", "甲");
                String token = (String) first.next("welcome").get("token");
                createRoom(first);
                first.send("type", "start");
                Map<String, Object> ask = untilAskTurn(first);
                try (TestClient second = new TestClient(port)) {
                    second.send("type", "hello", "protocol", Protocol.VERSION, "token", token);
                    second.next("welcome");
                    closes(first);
                    T.eq(ask.get("waitId"), second.until(m -> "askTurn".equals(m.get("type")), "重送").get("waitId"), "同一次等待");
                }
            } finally {
                first.close();
            }
        });
        test("房間裡的人都斷線後，時間到就關房間，token 也失效", port -> {
            TestClient host = new TestClient(port);
            host.send("type", "hello", "protocol", Protocol.VERSION, "name", "甲");
            String token = (String) host.next("welcome").get("token");
            String code = createRoom(host);
            host.send("type", "start");
            until(host, "room", m -> "playing".equals(m.get("state")));
            host.close();
            Thread.sleep(FAST.abandonMs() * 4L);
            try (TestClient later = TestClient.login(port, "乙")) {
                later.send("type", "joinRoom", "code", code);
                T.eq("noSuchRoom", later.next("error").get("code"), "房間關了");
            }
            try (TestClient back = new TestClient(port)) {
                back.send("type", "hello", "protocol", Protocol.VERSION, "token", token);
                T.eq("unknownToken", back.next("error").get("code"), "token 失效");
            }
        });
    }

    /** 每個案例一台新的服務器。 */
    private static void test(String name, Scenario scenario) {
        T.test(name, () -> {
            EzmjServer server = new EzmjServer(0, FAST);
            server.start();
            try {
                scenario.run(server.port());
            } finally {
                server.stop();
            }
        });
    }

    private static String createRoom(TestClient host, Object... options) throws IOException {
        Object[] pairs = new Object[options.length + 2];
        pairs[0] = "type";
        pairs[1] = "createRoom";
        System.arraycopy(options, 0, pairs, 2, options.length);
        host.send(pairs);
        return (String) host.next("room").get("code");
    }

    private static List<TestClient> seatedClients(int port, int count, Object... options) throws IOException {
        List<TestClient> clients = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            clients.add(TestClient.login(port, "玩家" + i));
        }
        String code = createRoom(clients.get(0), options);
        for (int i = 1; i < count; i++) {
            clients.get(i).send("type", "joinRoom", "code", code);
            clients.get(i).next("room");
        }
        return clients;
    }

    private static void closeAll(List<TestClient> clients) throws IOException {
        for (TestClient client : clients) {
            client.close();
        }
    }

    private static Map<String, Object> until(TestClient client, String type, Predicate<Map<String, Object>> wanted) {
        return client.until(m -> type.equals(m.get("type")) && wanted.test(m), type);
    }

    /** 等到輪到自己，中間問要不要宣告的都過，才不用等倒數。 */
    private static Map<String, Object> untilAskTurn(TestClient client) throws IOException {
        while (true) {
            Map<String, Object> ask = client.until(m -> "askTurn".equals(m.get("type")) || "askClaim".equals(m.get("type")), "輪到真人");
            if ("askTurn".equals(ask.get("type"))) {
                return ask;
            }
            client.send("type", "pass", "waitId", ask.get("waitId"));
        }
    }

    private static void closes(TestClient client) {
        client.until(client::isClosedMessage, "連線關閉");
    }

    @SuppressWarnings("unchecked")
    private static boolean isConnected(Map<String, Object> room, int seat) {
        for (Map<String, Object> player : (List<Map<String, Object>>) room.get("players")) {
            if (Long.valueOf(seat).equals(player.get("seat"))) {
                return Boolean.TRUE.equals(player.get("connected"));
            }
        }
        return false;
    }

    /** 每位玩家一條執行緒，同時打到牌局結束，回傳各自收到的 gameOver。 */
    private static List<Map<String, Object>> playAll(List<TestClient> clients) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(clients.size());
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (TestClient client : clients) {
                Callable<Map<String, Object>> player = () -> playUntilOver(client);
                futures.add(pool.submit(player));
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (Future<Map<String, Object>> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 前幾個決定自己做，走過一輪打牌與宣告之後交給代打，不然傻傻打可能一直流局。 */
    private static Map<String, Object> playUntilOver(TestClient client) throws Exception {
        int manual = 5;
        Map<String, Object> state = null;
        while (true) {
            Map<String, Object> message = client.poll();
            if (client.isClosedMessage(message)) {
                throw new AssertionError("牌局中連線被關閉");
            }
            switch ((String) message.get("type")) {
                case "state":
                    state = message;
                    break;
                case "askTurn":
                    if (manual-- <= 0) {
                        client.send("type", "autoPlay", "on", true);
                    } else if (Boolean.TRUE.equals(message.get("canSelfWin"))) {
                        client.send("type", "selfWin", "waitId", message.get("waitId"));
                    } else {
                        List<?> hand = (List<?>) state.get("hand");
                        client.send("type", "discard", "waitId", message.get("waitId"), "tile", hand.get(hand.size() - 1));
                    }
                    break;
                case "askClaim":
                    answerClaim(client, message);
                    break;
                case "roundEnd":
                    if (!Boolean.TRUE.equals(message.get("gameOver"))) {
                        client.send("type", "ready");
                    }
                    break;
                case "error":
                    throw new AssertionError("服務器回錯誤：" + message);
                case "roomClosed":
                    throw new AssertionError("房間被關閉：" + message);
                case "gameOver":
                    return message;
                default:
                    break;
            }
        }
    }

    /** 能胡就胡，其他都過。 */
    private static void answerClaim(TestClient client, Map<String, Object> ask) throws IOException {
        List<?> options = (List<?>) ask.get("options");
        for (int i = 0; i < options.size(); i++) {
            if ("WIN".equals(((Map<?, ?>) options.get(i)).get("kind"))) {
                client.send("type", "claim", "waitId", ask.get("waitId"), "option", i);
                return;
            }
        }
        client.send("type", "pass", "waitId", ask.get("waitId"));
    }

    private static TestClient firstAskTurn(List<TestClient> clients) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (TestClient client : clients) {
                synchronized (client.seen) {
                    for (Map<String, Object> message : client.seen) {
                        if ("askTurn".equals(message.get("type"))) {
                            return client;
                        }
                        if ("askClaim".equals(message.get("type"))) {
                            client.send("type", "pass", "waitId", message.get("waitId"));
                            client.seen.remove(message);
                            break;
                        }
                    }
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("沒有人輪到");
    }

    private static Map<String, Object> lastOfType(TestClient client, String type) {
        synchronized (client.seen) {
            for (int i = client.seen.size() - 1; i >= 0; i--) {
                if (type.equals(client.seen.get(i).get("type"))) {
                    return client.seen.get(i);
                }
            }
        }
        throw new AssertionError("沒有收到 " + type);
    }

    private static String tileNotIn(Map<String, Object> state) {
        List<?> hand = (List<?>) state.get("hand");
        for (String code : List.of("1w", "2w", "3w", "4w", "5w", "6w", "7w", "8w", "9w", "1p", "2p", "3p", "4p", "5p",
                "6p", "7p", "8p", "9p", "1s", "2s", "3s", "4s", "5s", "6s", "7s", "8s", "9s", "1z", "2z", "3z", "4z", "5z", "6z", "7z")) {
            if (!hand.contains(code)) {
                return code;
            }
        }
        throw new AssertionError("手上每種牌都有");
    }

    @SuppressWarnings("unchecked")
    private static void checkStandings(Map<String, Object> gameOver, long totalChips) {
        long total = 0;
        long lastChips = Long.MAX_VALUE;
        long winnings = 0;
        for (Map<String, Object> standing : (List<Map<String, Object>>) gameOver.get("standings")) {
            long chips = (Long) standing.get("chips");
            T.check(chips <= lastChips, "名次依籌碼排");
            lastChips = chips;
            total += chips;
            winnings += (Long) standing.get("winnings");
        }
        T.eq(totalChips, total, "籌碼總和");
        T.eq(0L, winnings, "輸贏總和");
    }

    /** 不管收到什麼訊息，牌局中都不能出現別人的牌。 */
    @SuppressWarnings("unchecked")
    private static void checkNoLeak(TestClient client) {
        long mySeat = -1;
        synchronized (client.seen) {
            for (Map<String, Object> message : client.seen) {
                Object type = message.get("type");
                if ("room".equals(type)) {
                    mySeat = (Long) message.get("yourSeat");
                } else if ("event".equals(type) && "DRAW".equals(message.get("kind"))) {
                    T.check(mySeat == (Long) message.get("seat") || message.get("tile") == null, "看到別人摸的牌：" + message);
                } else if ("state".equals(type)) {
                    T.eq(mySeat, message.get("seat"), "只收到自己的局面");
                    T.check(!message.containsKey("wall"), "看到牌牆");
                    for (Map<String, Object> opponent : (List<Map<String, Object>>) message.get("opponents")) {
                        T.check(!opponent.containsKey("hand"), "看到別家手牌");
                        for (Map<String, Object> meld : (List<Map<String, Object>>) opponent.get("melds")) {
                            T.check(!"CONCEALED_KONG".equals(meld.get("kind")), "看到別家暗槓");
                        }
                    }
                }
            }
        }
    }
}
