package ezmj.server;

import static ezmj.server.ServerTest.answerClaim;
import static ezmj.server.ServerTest.checkHonorsOnlyFrom;
import static ezmj.server.ServerTest.closeAll;
import static ezmj.server.ServerTest.createRoom;
import static ezmj.server.ServerTest.isConnected;
import static ezmj.server.ServerTest.lastOfType;
import static ezmj.server.ServerTest.listRooms;
import static ezmj.server.ServerTest.listed;
import static ezmj.server.ServerTest.loneliestTile;
import static ezmj.server.ServerTest.playUntilOver;
import static ezmj.server.ServerTest.seatedClients;
import static ezmj.server.ServerTest.test;
import static ezmj.server.ServerTest.until;
import static ezmj.server.ServerTest.untilAskTurn;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 隨時補位：牌局中加入，替換電腦在打的座位。 */
final class SubstituteTest {

    private static final List<String> SUPPORTS = List.of("expert", "substitute");
    /** 結果畫面停很久，要等房主送 ready 才開下一局，才排得出「這局已經結束才加入」。 */
    private static final Timing LONG_RESULT = new Timing(0, 10_000, 10_000, 300, true);

    private SubstituteTest() {
    }

    static void run() {
        T.section("隨時補位");
        test("預設關閉；開房時給了，房間與大廳的設定都看得到", port -> {
            try (TestClient plainHost = TestClient.login(port, "甲"); TestClient host = TestClient.login(port, "乙");
                 TestClient visitor = new TestClient(port)) {
                createRoom(plainHost);
                T.eq(false, options(lastOfType(plainHost, "room")).get("substitute"), "預設關閉");
                String code = createRoom(host, "substitute", true);
                T.eq(true, options(lastOfType(host, "room")).get("substitute"), "房間的設定");
                Map<String, Object> entry = listed(visitor, code);
                T.eq(true, ((Map<?, ?>) entry.get("options")).get("substitute"), "大廳的設定");
                T.eq(false, entry.get("playing"), "還在等人");
                T.eq(3L, entry.get("openSeats"), "還有三個空位");
            }
        });
        test("不是隨時補位的房間，會補位的客戶端也不能中途加入", port -> {
            try (TestClient host = TestClient.login(port, "甲"); TestClient late = TestClient.login(port, "乙")) {
                String code = createRoom(host);
                host.send("type", "start");
                until(host, "room", m -> "playing".equals(m.get("state")));
                late.send("type", "joinRoom", "code", code, "supports", SUPPORTS);
                T.eq("gameInProgress", late.next("error").get("code"), "錯誤代碼");
            }
        });
        test("開打的補位房間只列給會補位的客戶端，排在等人的房間後面；舊客戶端加入被拒", port -> {
            try (TestClient waitingHost = TestClient.login(port, "丙"); TestClient host = TestClient.login(port, "甲");
                 TestClient old = TestClient.login(port, "乙"); TestClient visitor = new TestClient(port)) {
                // 等人的房間比較早開，照樣排在前面
                String waitingCode = createRoom(waitingHost, "substitute", true);
                String code = createRoom(host, "substitute", true, "waitSeconds", 5);
                host.send("type", "start");
                until(host, "room", m -> "playing".equals(m.get("state")));
                T.eq(List.of(waitingCode), codes(listRooms(visitor)), "舊客戶端看不到開打的");
                List<Map<String, Object>> rooms = listRoomsSupporting(visitor);
                T.eq(List.of(waitingCode, code), codes(rooms), "等人的在前面");
                Map<String, Object> entry = rooms.get(1);
                T.eq(true, entry.get("playing"), "牌局中");
                T.eq(1L, entry.get("humans"), "一位真人");
                T.eq(3L, entry.get("openSeats"), "三家電腦可以換");
                T.eq("甲", entry.get("host"), "房主");

                old.send("type", "joinRoom", "code", code);
                Map<String, Object> error = old.next("error");
                T.eq("substituteNotSupported", error.get("code"), "沒說會補位");
                T.check(((String) error.get("message")).contains("補位"), "說明是補位");
            }
        });
        test("等那個座位的電腦打出牌才換人，全桌都收到 substituted，座位與名字換成補位的人", port -> {
            try (TestClient host = TestClient.login(port, "甲"); TestClient joiner = TestClient.login(port, "乙")) {
                String code = createRoom(host, "substitute", true, "waitSeconds", 5);
                host.send("type", "start");
                // 房主在想的時候，座位 1 的電腦要等房主打完才輪得到
                Map<String, Object> ask = untilAskTurn(host);
                String botName = name(lastOfType(host, "room"), 1);
                joiner.send("type", "joinRoom", "code", code, "supports", SUPPORTS);
                Map<String, Object> waiting = joiner.next("substituteWaiting");
                T.eq(1L, waiting.get("seat"), "從座位小的換起");
                T.eq(true, waiting.get("computer"), "換的是電腦");
                T.eq(botName, waiting.get("replacing"), "電腦的名字");

                host.send("type", "discard", "waitId", ask.get("waitId"), "tile", firstInHand(host));
                Map<String, Object> room = joiner.poll();
                T.eq("room", room.get("type"), "上場前什麼都收不到，第一則就是房間");
                T.eq("playing", room.get("state"), "牌局中");
                T.eq(1L, room.get("yourSeat"), "坐在保留的座位");
                Map<String, Object> substituted = untilSubstituted(joiner);
                T.eq(1L, substituted.get("seat"), "座位");
                T.eq("乙", substituted.get("name"), "補位的人");
                T.eq(botName, substituted.get("replaced"), "換掉的電腦");
                T.eq(true, substituted.get("computer"), "原本是電腦");

                T.eq(substituted, host.next("substituted"), "房主也收到");
                checkSwappedAfterDiscard(host, 1);
                Map<String, Object> hostRoom = lastOfType(host, "room");
                T.eq("乙", name(hostRoom, 1), "名字換成補位的人");
                T.eq(true, human(hostRoom, 1), "是真人了");
                T.check(isConnected(hostRoom, 1), "連著");
                // 之後就由他自己決定
                joiner.until(m -> "askTurn".equals(m.get("type")) || "askClaim".equals(m.get("type")), "輪到補位的人");
            }
        });
        test("這局已經結束才加入的，下一局開始前上場", LONG_RESULT, port -> {
            try (TestClient host = TestClient.login(port, "甲"); TestClient joiner = TestClient.login(port, "乙")) {
                String code = createRoom(host, "substitute", true, "basePay", 1, "taiPay", 1);
                host.send("type", "start");
                Map<String, Object> roundEnd = playUntilRoundEnd(host);
                T.eq(false, roundEnd.get("gameOver"), "還有下一局");
                joiner.send("type", "joinRoom", "code", code, "supports", SUPPORTS);
                joiner.next("substituteWaiting");
                Thread.sleep(100);
                T.eq(2, joiner.seen.size(), "結果畫面還開著，只有 welcome 與 substituteWaiting");
                host.send("type", "ready");
                T.eq("room", joiner.poll().get("type"), "先是房間");
                T.eq("substituted", joiner.poll().get("type"), "接著是換人");
                T.eq("roundStart", joiner.poll().get("type"), "然後整局都是他打");
                T.eq("state", joiner.next("state").get("type"), "發牌後的局面");
            }
        });
        test("補位的人打到整場結束也不列入榮譽榜，開局時的真人照樣列入", port -> {
            try (TestClient host = TestClient.login(port, "甲"); TestClient joiner = TestClient.login(port, "乙")) {
                String code = createRoom(host, "substitute", true, "basePay", 1, "taiPay", 1);
                host.send("type", "start");
                Map<String, Object> ask = untilAskTurn(host);
                joiner.send("type", "joinRoom", "code", code, "supports", SUPPORTS);
                joiner.next("substituteWaiting");
                host.send("type", "discard", "waitId", ask.get("waitId"), "tile", firstInHand(host));
                List<Map<String, Object>> results = playBoth(host, joiner);
                Map<String, Object> standing = standing(results.get(1), "乙");
                T.check(standing != null, "最後的排名有補位的人");
                T.check(((Long) standing.get("wins")) > 0 || ((Long) standing.get("dealIns")) > 0
                        || ((Long) standing.get("winnings")) > 0, "打了好幾局，總有胡牌、放槍或贏錢");
                checkHonorsOnlyFrom(port, Set.of("甲"));
            }
        });
        test("還沒上場不能做決定；離開或斷線就取消，座位留給下一位", port -> {
            try (TestClient host = TestClient.login(port, "甲"); TestClient first = TestClient.login(port, "乙");
                 TestClient second = TestClient.login(port, "丙"); TestClient third = TestClient.login(port, "丁");
                 TestClient visitor = new TestClient(port)) {
                String code = createRoom(host, "substitute", true, "waitSeconds", 5);
                host.send("type", "start");
                Map<String, Object> ask = untilAskTurn(host);

                first.send("type", "joinRoom", "code", code, "supports", SUPPORTS);
                T.eq(1L, first.next("substituteWaiting").get("seat"), "保留座位 1");
                T.eq(2L, listedSupporting(visitor, code).get("openSeats"), "保留的不算空位");
                first.send("type", "chat", "text", "你好");
                T.eq("notSeatedYet", first.next("error").get("code"), "還沒上場不能聊天");
                first.send("type", "pass", "waitId", 1);
                T.eq("notSeatedYet", first.next("error").get("code"), "也不能替電腦決定");
                first.send("type", "leave");
                first.next("left");
                T.eq(3L, listedSupporting(visitor, code).get("openSeats"), "離開就空出來");

                second.send("type", "joinRoom", "code", code, "supports", SUPPORTS);
                T.eq(1L, second.next("substituteWaiting").get("seat"), "又是座位 1");
                second.close();
                long deadline = System.currentTimeMillis() + 5_000;
                while ((Long) listedSupporting(visitor, code).get("openSeats") != 3L) {
                    T.check(System.currentTimeMillis() < deadline, "斷線就空出來");
                    Thread.sleep(10);
                }

                third.send("type", "joinRoom", "code", code, "supports", SUPPORTS);
                T.eq(1L, third.next("substituteWaiting").get("seat"), "還是座位 1");
                host.send("type", "discard", "waitId", ask.get("waitId"), "tile", firstInHand(host));
                T.eq(1L, third.next("room").get("yourSeat"), "上場了");
                T.eq("丁", host.next("substituted").get("name"), "換上的是最後這位");
            }
        });
        test("電腦都有人等了就沒有空位；斷線的真人會回來，他的座位不算", port -> {
            List<TestClient> seated = seatedClients(port, 2, "substitute", true, "waitSeconds", 5);
            try (TestClient a = TestClient.login(port, "甲"); TestClient b = TestClient.login(port, "乙");
                 TestClient c = TestClient.login(port, "丙"); TestClient visitor = new TestClient(port)) {
                TestClient host = seated.get(0);
                String code = (String) lastOfType(host, "room").get("code");
                host.send("type", "start");
                until(host, "room", m -> "playing".equals(m.get("state")));
                a.send("type", "joinRoom", "code", code, "supports", SUPPORTS);
                T.eq(2L, a.next("substituteWaiting").get("seat"), "座位 2");
                b.send("type", "joinRoom", "code", code, "supports", SUPPORTS);
                T.eq(3L, b.next("substituteWaiting").get("seat"), "座位 3");
                T.check(listedSupporting(visitor, code) == null, "沒有空位就不列出");
                seated.get(1).close();
                until(host, "room", m -> !isConnected(m, 1));
                c.send("type", "joinRoom", "code", code, "supports", SUPPORTS);
                T.eq("noOpenSeat", c.next("error").get("code"), "沒有電腦可以換");
            } finally {
                closeAll(seated);
            }
        });
        test("離開的人留下的座位也能補，告訴他原本是誰的座位", port -> {
            List<TestClient> seated = seatedClients(port, 2, "substitute", true, "waitSeconds", 5);
            try (TestClient joiner = TestClient.login(port, "乙")) {
                TestClient host = seated.get(0);
                String code = (String) lastOfType(host, "room").get("code");
                host.send("type", "start");
                until(host, "room", m -> "playing".equals(m.get("state")));
                seated.get(1).send("type", "leave");
                seated.get(1).next("left");
                joiner.send("type", "joinRoom", "code", code, "supports", SUPPORTS);
                Map<String, Object> waiting = joiner.next("substituteWaiting");
                T.eq(1L, waiting.get("seat"), "離開的人的座位");
                T.eq(false, waiting.get("computer"), "原本是真人");
                T.eq("玩家1", waiting.get("replacing"), "原本那位的名字");
            } finally {
                closeAll(seated);
            }
        });
        test("隨便什麼時候加入補位，牌局都打得完：不是打完就是坐進房間等下一場", port -> {
            Random random = new Random(7);
            for (int game = 0; game < 4; game++) {
                try (TestClient host = TestClient.login(port, "甲" + game); TestClient joiner = TestClient.login(port, "乙" + game)) {
                    String code = createRoom(host, "substitute", true, "chips", 100, "basePay", 10, "taiPay", 10);
                    host.send("type", "start");
                    ExecutorService pool = Executors.newSingleThreadExecutor();
                    try {
                        Future<Map<String, Object>> hostResult = pool.submit(() -> playUntilOver(host));
                        Thread.sleep(random.nextInt(40));
                        joiner.send("type", "joinRoom", "code", code, "supports", SUPPORTS);
                        Map<String, Object> outcome = playAsSubstitute(joiner);
                        hostResult.get(60, TimeUnit.SECONDS);
                        T.check("gameOver".equals(outcome.get("type")) || "lobby".equals(outcome.get("state"))
                                || "error".equals(outcome.get("type")), "打完、坐進房間，或已經打完不能補位：" + outcome);
                    } finally {
                        pool.shutdownNow();
                    }
                }
            }
        });
    }

    private static Map<?, ?> options(Map<String, Object> room) {
        return (Map<?, ?>) room.get("options");
    }

    private static List<Object> codes(List<Map<String, Object>> rooms) {
        List<Object> codes = new ArrayList<>();
        rooms.forEach(entry -> codes.add(entry.get("code")));
        return codes;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listRoomsSupporting(TestClient client) throws IOException {
        client.send("type", "listRooms", "supports", List.of("substitute"));
        return (List<Map<String, Object>>) client.next("rooms").get("rooms");
    }

    private static Map<String, Object> listedSupporting(TestClient client, String code) throws IOException {
        for (Map<String, Object> entry : listRoomsSupporting(client)) {
            if (code.equals(entry.get("code"))) {
                return entry;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> player(Map<String, Object> room, int seat) {
        for (Map<String, Object> player : (List<Map<String, Object>>) room.get("players")) {
            if (Long.valueOf(seat).equals(player.get("seat"))) {
                return player;
            }
        }
        throw new AssertionError("房間裡沒有座位 " + seat);
    }

    private static String name(Map<String, Object> room, int seat) {
        return (String) player(room, seat).get("name");
    }

    private static boolean human(Map<String, Object> room, int seat) {
        return Boolean.TRUE.equals(player(room, seat).get("human"));
    }

    private static Object firstInHand(TestClient client) {
        return ((List<?>) lastOfType(client, "state").get("hand")).get(0);
    }

    /**
     * 補位的人上場到 substituted 之間只會有自己的局面與報聽的機會，不會有事件、倒數或詢問：
     * 換人在房間的執行緒上一次做完，中間插不進別的訊息。已經坐著的人會收到倒數，不能用這個。
     */
    private static Map<String, Object> untilSubstituted(TestClient client) throws InterruptedException {
        while (true) {
            Map<String, Object> message = client.poll();
            Object type = message.get("type");
            if ("substituted".equals(type)) {
                return message;
            }
            T.check("state".equals(type) || "canListen".equals(type) || "room".equals(type),
                    "substituted 之前不該有 " + message);
        }
    }

    /** 換人要在那個座位打出牌之後，或是這局已經結束（下一局開始前換）。 */
    private static void checkSwappedAfterDiscard(TestClient client, int seat) {
        synchronized (client.seen) {
            int at = -1;
            for (int i = 0; i < client.seen.size(); i++) {
                if ("substituted".equals(client.seen.get(i).get("type"))) {
                    at = i;
                    break;
                }
            }
            T.check(at > 0, "收到 substituted");
            for (int i = at - 1; i >= 0; i--) {
                Map<String, Object> message = client.seen.get(i);
                if ("roundEnd".equals(message.get("type"))) {
                    return;
                }
                if ("event".equals(message.get("type"))) {
                    T.eq("DISCARD", message.get("kind"), "換人前最後一個事件是打牌");
                    T.eq((long) seat, message.get("seat"), "是那個座位打的");
                    return;
                }
            }
            throw new AssertionError("換人之前沒有事件");
        }
    }

    /** 打到這局結束，不送 ready。 */
    private static Map<String, Object> playUntilRoundEnd(TestClient client) throws Exception {
        Map<String, Object> state = null;
        while (true) {
            Map<String, Object> message = client.poll();
            switch ((String) message.get("type")) {
                case "state":
                    state = message;
                    break;
                case "askTurn":
                    if (Boolean.TRUE.equals(message.get("canSelfWin"))) {
                        client.send("type", "selfWin", "waitId", message.get("waitId"));
                    } else {
                        client.send("type", "discard", "waitId", message.get("waitId"), "tile",
                                loneliestTile((List<?>) state.get("hand")));
                    }
                    break;
                case "askClaim":
                    answerClaim(client, message);
                    break;
                case "roundEnd":
                    return message;
                case "error":
                case "roomClosed":
                    throw new AssertionError("牌局出錯：" + message);
                default:
                    break;
            }
        }
    }

    /** 兩位同時打到整場結束，回傳各自收到的 gameOver。 */
    private static List<Map<String, Object>> playBoth(TestClient first, TestClient second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> a = pool.submit(() -> playUntilOver(first));
            Future<Map<String, Object>> b = pool.submit(() -> playUntilOver(second));
            return List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 補位的人照 playUntilOver 打，回傳 gameOver。整場在他上場前就打完的話，他會坐進等人的房間，回傳那則 lobby 的 room；
     * 加入時已經打完、房間關了，回傳那則錯誤。
     */
    private static Map<String, Object> playAsSubstitute(TestClient client) throws Exception {
        Map<String, Object> first = client.until(m -> "substituteWaiting".equals(m.get("type"))
                || "error".equals(m.get("type")) || "room".equals(m.get("type")), "加入的回應");
        if (!"substituteWaiting".equals(first.get("type"))) {
            return first;
        }
        Map<String, Object> room = client.next("room");
        if ("lobby".equals(room.get("state"))) {
            return room;
        }
        return playUntilOver(client);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> standing(Map<String, Object> gameOver, String name) {
        for (Map<String, Object> standing : (List<Map<String, Object>>) gameOver.get("standings")) {
            if (name.equals(standing.get("name"))) {
                return standing;
            }
        }
        return null;
    }
}
