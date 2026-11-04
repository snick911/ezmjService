package ezmj.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final List<String> HONOR_CATEGORIES = List.of("wins", "selfDraws", "winnings", "dealIns");

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
                host.send("type", "createRoom", "chips", 1000, "basePay", 1001);
                Map<String, Object> error = host.next("error");
                T.eq("badOptions", error.get("code"), "底大於籌碼");
                T.eq("底和每台不能大於籌碼本金", error.get("message"), "可以直接顯示的訊息");
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

        T.section("大廳");
        test("不登入也能看房間列表，列出名稱、房主、人數與設定", port -> {
            try (TestClient host = TestClient.login(port, "甲"); TestClient guest = TestClient.login(port, "乙");
                 TestClient visitor = new TestClient(port)) {
                String code = createRoom(host, "name", "週末牌局", "chips", 5000);
                guest.send("type", "joinRoom", "code", code);
                guest.next("room");
                Map<String, Object> entry = listed(visitor, code);
                T.check(entry != null, "列表上有這間");
                T.eq("週末牌局", entry.get("name"), "名稱");
                T.eq("甲", entry.get("host"), "房主");
                T.eq(2L, entry.get("humans"), "真人數");
                Map<?, ?> options = (Map<?, ?>) entry.get("options");
                T.eq(5000L, options.get("chips"), "設定");
                T.eq("週末牌局", options.get("name"), "設定裡的名稱");
                T.eq(false, options.get("private"), "公開");
            }
        });
        test("沒給房間名稱就用房主的名字", port -> {
            try (TestClient host = TestClient.login(port, "甲")) {
                host.send("type", "createRoom");
                Map<?, ?> options = (Map<?, ?>) host.next("room").get("options");
                T.eq("甲的房間", options.get("name"), "預設名稱");
                T.eq(false, options.get("private"), "預設公開");
                T.eq("甲的房間", listRooms(host).get(0).get("name"), "列表上的名稱");
            }
        });
        test("房間名稱超過 30 個字要拒絕", port -> {
            try (TestClient host = TestClient.login(port, "甲")) {
                host.send("type", "createRoom", "name", "一".repeat(31));
                Map<String, Object> error = host.next("error");
                T.eq("badRoomName", error.get("code"), "錯誤代碼");
                T.eq("房間名稱最多 30 個字", error.get("message"), "可以直接顯示的訊息");
                // 沒有開成，可以改了再開
                String code = createRoom(host, "name", "一".repeat(30));
                T.check(listed(host, code) != null, "30 個字可以");
            }
        });
        test("私人房不列出，但能用房號加入", port -> {
            try (TestClient host = TestClient.login(port, "甲"); TestClient guest = TestClient.login(port, "乙")) {
                String code = createRoom(host, "name", "自己人", "private", true);
                T.check(listed(guest, code) == null, "列表上沒有");
                guest.send("type", "joinRoom", "code", code);
                Map<String, Object> room = guest.next("room");
                T.eq(1L, room.get("yourSeat"), "加入了");
                T.eq(true, ((Map<?, ?>) room.get("options")).get("private"), "設定裡是私人房");
                T.check(listed(guest, code) == null, "有人加入後也沒有");
            }
        });
        test("開打、坐滿與關掉的房間不列出，坐滿的有人離開就再列出來", port -> {
            List<TestClient> full = seatedClients(port, 4);
            List<TestClient> playing = seatedClients(port, 1);
            List<TestClient> open = seatedClients(port, 3);
            try (TestClient visitor = new TestClient(port); TestClient leaver = TestClient.login(port, "丙")) {
                String fullCode = roomCode(full.get(0));
                String openCode = roomCode(open.get(0));
                createRoom(leaver);
                leaver.send("type", "leave");
                leaver.next("left");
                playing.get(0).send("type", "start");
                until(playing.get(0), "room", m -> "playing".equals(m.get("state")));
                T.eq(List.of(openCode), listedCodes(visitor), "只剩還能加入的");

                full.get(3).send("type", "leave");
                full.get(3).next("left");
                T.eq(List.of(openCode, fullCode), listedCodes(visitor), "空出位子就再列出來");
                T.eq(3L, listed(visitor, fullCode).get("humans"), "剩三位");
            } finally {
                closeAll(full);
                closeAll(playing);
                closeAll(open);
            }
        });
        test("新開的房間在前面，最多列 50 間", port -> {
            List<TestClient> hosts = new ArrayList<>();
            try {
                List<String> codes = new ArrayList<>();
                for (int i = 0; i < 51; i++) {
                    TestClient host = TestClient.login(port, "房主" + i);
                    hosts.add(host);
                    codes.add(createRoom(host));
                }
                try (TestClient visitor = new TestClient(port)) {
                    List<Object> listed = listedCodes(visitor);
                    T.eq(50, listed.size(), "最多 50 間");
                    T.eq(codes.get(50), listed.get(0), "最新的在最前面");
                    T.eq(codes.get(1), listed.get(49), "最後一間");
                    T.check(!listed.contains(codes.get(0)), "最舊的被擠掉");
                }
            } finally {
                closeAll(hosts);
            }
        });

        T.section("聊天");
        test("房間裡的真人互相收到，發話的人自己也收到", port -> {
            try (TestClient host = TestClient.login(port, "甲"); TestClient guest = TestClient.login(port, "乙")) {
                String code = createRoom(host);
                guest.send("type", "joinRoom", "code", code);
                guest.next("room");
                host.send("type", "chat", "text", "  大家好 ");
                for (TestClient client : List.of(host, guest)) {
                    Map<String, Object> chat = client.next("chat");
                    T.eq(0L, chat.get("seat"), "發話的座位");
                    T.eq("甲", chat.get("name"), "發話的名字");
                    T.eq("大家好", chat.get("text"), "去掉前後空白");
                }
                guest.send("type", "chat", "text", "你好");
                T.eq(1L, host.next("chat").get("seat"), "房主收到客人的");
                T.eq("乙", guest.next("chat").get("name"), "客人自己也收到");
            }
        });
        test("不在房間不能聊天", port -> {
            try (TestClient client = TestClient.login(port, "甲"); TestClient visitor = new TestClient(port)) {
                client.send("type", "chat", "text", "有人嗎");
                T.eq("notInRoom", client.next("error").get("code"), "登入了但不在房間");
                visitor.send("type", "chat", "text", "有人嗎");
                T.eq("notLoggedIn", visitor.next("error").get("code"), "沒有登入");
            }
        });
        test("聊天訊息不合規定要拒絕", port -> {
            try (TestClient host = TestClient.login(port, "甲")) {
                createRoom(host);
                host.send("type", "chat", "text", "一".repeat(101));
                Map<String, Object> error = host.next("error");
                T.eq("badChat", error.get("code"), "101 個字");
                T.eq("聊天訊息要是 1 到 100 個字", error.get("message"), "可以直接顯示的訊息");
                host.send("type", "chat", "text", "   ");
                T.eq("badChat", host.next("error").get("code"), "只有空白");
                host.send("type", "chat", "text", "第一行\n第二行");
                T.eq("badChat", host.next("error").get("code"), "換行");
                host.send("type", "chat", "text", 3);
                T.eq("badMessage", host.next("error").get("code"), "不是字串");
                // 字數用 code point 算，100 個表情符號可以
                host.send("type", "chat", "text", "🀄".repeat(100));
                T.eq("🀄".repeat(100), host.next("chat").get("text"), "100 個字");
            }
        });
        test("連發超過每 10 秒 5 則回 chatTooFast", port -> {
            try (TestClient host = TestClient.login(port, "甲")) {
                createRoom(host);
                for (int i = 1; i <= 6; i++) {
                    host.send("type", "chat", "text", "第 " + i + " 則");
                }
                for (int i = 1; i <= 5; i++) {
                    T.eq("第 " + i + " 則", host.next("chat").get("text"), "前五則照送");
                }
                T.eq("chatTooFast", host.next("error").get("code"), "第 6 則");
            }
        });
        test("牌局中也能聊", port -> {
            List<TestClient> clients = seatedClients(port, 2);
            try {
                TestClient host = clients.get(0);
                TestClient guest = clients.get(1);
                host.send("type", "start");
                until(guest, "room", m -> "playing".equals(m.get("state")));
                guest.send("type", "chat", "text", "快打啦");
                Map<String, Object> chat = until(host, "chat", m -> true);
                T.eq(1L, chat.get("seat"), "客人的座位");
                T.eq("快打啦", chat.get("text"), "內容");
            } finally {
                closeAll(clients);
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
        test("最大的籌碼、底與每台也打得完，金額沒有溢位", port -> {
            List<TestClient> clients = seatedClients(port, 4, "chips", 10_000_000, "basePay", 1_000_000, "taiPay", 1_000_000);
            try {
                clients.get(0).send("type", "start");
                Map<String, Object> gameOver = playAll(clients).get(0);
                checkStandings(gameOver, 40_000_000);
                checkHonorsMatch("winnings", honorRoll(port), gameOver);
            } finally {
                closeAll(clients);
            }
        });
        test("roundStart 與 state 都帶局風：起莊是東局，換莊往下一局，連莊不變", port -> {
            // 底和每台很小，不會有人破產，一定打到換莊
            List<TestClient> clients = seatedClients(port, 1, "basePay", 1, "taiPay", 1);
            try {
                clients.get(0).send("type", "start");
                playAll(clients);
                checkHandWinds(clients.get(0));
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
        test("玩家不能開電腦代打，房間裡或牌局中都一樣", port -> {
            List<TestClient> clients = seatedClients(port, 1);
            try {
                TestClient host = clients.get(0);
                host.send("type", "autoPlay", "on", true);
                T.eq("autoPlayNotAllowed", host.next("error").get("code"), "開局前");
                host.send("type", "start");
                Map<String, Object> first = untilAskTurn(host);
                host.send("type", "autoPlay", "on", true);
                T.eq("autoPlayNotAllowed", host.next("error").get("code"), "牌局中");
                T.eq(false, lastOfType(host, "state").get("autoPlay"), "代打還是關著");
                List<?> hand = (List<?>) lastOfType(host, "state").get("hand");
                host.send("type", "discard", "waitId", first.get("waitId"), "tile", hand.get(hand.size() - 1));
                untilAskTurn(host);
            } finally {
                closeAll(clients);
            }
        });
        test("下一局的等待：一般 15 秒，流局 5 秒", port -> {
            T.eq(15_000, Timing.NORMAL.resultMs(), "一般");
            T.eq(5_000, Timing.NORMAL.drawnResultMs(), "流局");
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
                // 房主不回應，靠倒數讓牌局往下走
                String code = createRoom(host, "waitSeconds", 5);
                guest.send("type", "joinRoom", "code", code);
                guest.next("room");
                host.send("type", "start");
                until(host, "room", m -> "playing".equals(m.get("state")));
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

        T.section("榮譽榜");
        test("還沒有人打完時榜是空的，查榜不用登入", port -> {
            Map<String, Object> honors = honorRoll(port);
            for (String category : HONOR_CATEGORIES) {
                T.check(((List<?>) honors.get(category)).isEmpty(), category + " 要是空的");
            }
        });
        test("打完一場就記下四位真人的成績，用的是他們自己的名字", port -> {
            List<TestClient> clients = seatedClients(port, 4, "chips", 100);
            try {
                clients.get(0).send("type", "start");
                Map<String, Object> gameOver = playAll(clients).get(0);
                Map<String, Object> honors = honorRoll(port);
                T.check(!((List<?>) honors.get("wins")).isEmpty(), "四位都是真人，一定有人胡牌");
                for (String category : HONOR_CATEGORIES) {
                    checkHonorsMatch(category, honors, gameOver);
                }
            } finally {
                closeAll(clients);
            }
        });
        test("中途離開的人不列入，電腦也不列入", port -> {
            List<TestClient> clients = seatedClients(port, 4, "chips", 100);
            try {
                TestClient host = clients.get(0);
                host.send("type", "start");
                until(host, "room", m -> "playing".equals(m.get("state")));
                for (TestClient guest : clients.subList(1, 4)) {
                    guest.send("type", "leave");
                    guest.next("left");
                }
                playAll(List.of(host));
                checkHonorsOnlyFrom(port, Set.of("玩家0"));
            } finally {
                closeAll(clients);
            }
        });
        test("斷線由電腦接手過的人，接回來打完也不列入", port -> {
            TestClient host = TestClient.login(port, "甲");
            TestClient guest = new TestClient(port);
            try {
                guest.send("type", "hello", "protocol", Protocol.VERSION, "name", "乙");
                String token = (String) guest.next("welcome").get("token");
                String code = createRoom(host, "chips", 100);
                guest.send("type", "joinRoom", "code", code);
                guest.next("room");
                host.send("type", "start");
                until(guest, "room", m -> "playing".equals(m.get("state")));
                guest.close();
                awaitSeen(host, m -> "room".equals(m.get("type")) && "playing".equals(m.get("state")) && !isConnected(m, 1));
                try (TestClient back = new TestClient(port)) {
                    back.send("type", "hello", "protocol", Protocol.VERSION, "token", token);
                    back.next("welcome");
                    playAll(List.of(host, back));
                }
                checkHonorsOnlyFrom(port, Set.of("甲"));
            } finally {
                host.close();
                guest.close();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listRooms(TestClient client) throws IOException {
        client.send("type", "listRooms");
        return (List<Map<String, Object>>) client.next("rooms").get("rooms");
    }

    private static List<Object> listedCodes(TestClient client) throws IOException {
        List<Object> codes = new ArrayList<>();
        for (Map<String, Object> entry : listRooms(client)) {
            codes.add(entry.get("code"));
        }
        return codes;
    }

    /** 列表上的這間房間，沒有列出時是 null。 */
    private static Map<String, Object> listed(TestClient client, String code) throws IOException {
        for (Map<String, Object> entry : listRooms(client)) {
            if (code.equals(entry.get("code"))) {
                return entry;
            }
        }
        return null;
    }

    private static String roomCode(TestClient client) {
        return (String) lastOfType(client, "room").get("code");
    }

    private static Map<String, Object> honorRoll(int port) throws IOException {
        try (TestClient client = new TestClient(port)) {
            client.send("type", "honorRoll");
            return client.next("honorRoll");
        }
    }

    /** 不消耗訊息，只看收到過的，打牌的迴圈之後還是收得到每一則。 */
    private static void awaitSeen(TestClient client, Predicate<Map<String, Object>> wanted) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (client.seen) {
                for (Map<String, Object> message : client.seen) {
                    if (wanted.test(message)) {
                        return;
                    }
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("等太久沒有看到要的訊息");
    }

    /** 這個類別的數值大於 0 的真人都要上榜，數值、名字都和 gameOver 一樣。 */
    @SuppressWarnings("unchecked")
    private static void checkHonorsMatch(String category, Map<String, Object> honors, Map<String, Object> gameOver) {
        List<Map<String, Object>> entries = (List<Map<String, Object>>) honors.get(category);
        int expected = 0;
        for (Map<String, Object> standing : (List<Map<String, Object>>) gameOver.get("standings")) {
            if ((Long) standing.get(category) <= 0) {
                continue;
            }
            expected++;
            boolean listed = false;
            for (Map<String, Object> entry : entries) {
                listed |= standing.get("name").equals(entry.get("name")) && standing.get(category).equals(entry.get(category));
            }
            T.check(listed, category + " 沒有 " + standing);
        }
        T.eq(expected, entries.size(), category + " 的筆數");
    }

    @SuppressWarnings("unchecked")
    private static void checkHonorsOnlyFrom(int port, Set<String> names) throws IOException {
        Map<String, Object> honors = honorRoll(port);
        for (String category : HONOR_CATEGORIES) {
            for (Map<String, Object> entry : (List<Map<String, Object>>) honors.get(category)) {
                T.check(names.contains(entry.get("name")), category + " 不該有 " + entry);
            }
        }
    }

    /** 每個案例一台新的服務器。 */
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

    /** 朝胡牌打：打最孤立的牌，能吃碰就吃碰，不然傻傻打可能一直流局。 */
    private static Map<String, Object> playUntilOver(TestClient client) throws Exception {
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
                    if (Boolean.TRUE.equals(message.get("canSelfWin"))) {
                        client.send("type", "selfWin", "waitId", message.get("waitId"));
                    } else {
                        client.send("type", "discard", "waitId", message.get("waitId"), "tile", loneliestTile((List<?>) state.get("hand")));
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

    /** 同樣的牌與前後的牌越少越孤立。牌寫成 1w 這樣，字牌沒有前後。 */
    private static Object loneliestTile(List<?> hand) {
        Object loneliest = null;
        int fewest = Integer.MAX_VALUE;
        for (Object tile : hand) {
            String code = (String) tile;
            int score = 0;
            for (Object other : hand) {
                String otherCode = (String) other;
                if (tile != other) {
                    int distance = Math.abs(code.charAt(0) - otherCode.charAt(0));
                    if (code.charAt(1) == otherCode.charAt(1) && (distance == 0 || (code.charAt(1) != 'z' && distance <= 2))) {
                        score += distance == 0 ? 4 : 3 - distance;
                    }
                }
            }
            if (score < fewest) {
                fewest = score;
                loneliest = tile;
            }
        }
        return loneliest;
    }

    /** 能胡就胡，不然就吃碰槓，手牌才會往胡牌靠近。 */
    private static void answerClaim(TestClient client, Map<String, Object> ask) throws IOException {
        List<?> options = (List<?>) ask.get("options");
        for (int i = 0; i < options.size(); i++) {
            if ("WIN".equals(((Map<?, ?>) options.get(i)).get("kind"))) {
                client.send("type", "claim", "waitId", ask.get("waitId"), "option", i);
                return;
            }
        }
        client.send("type", "claim", "waitId", ask.get("waitId"), "option", 0);
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

    /** 局風依起莊算：起莊是東局，往下一家依序加一，連莊不變。state 要和這局的 roundStart 一樣。 */
    private static void checkHandWinds(TestClient client) {
        Long firstDealer = null;
        long previousDealer = -1;
        Object handWind = null;
        int dealerChanges = 0;
        synchronized (client.seen) {
            for (Map<String, Object> message : client.seen) {
                if ("roundStart".equals(message.get("type"))) {
                    long dealer = (Long) message.get("dealer");
                    if (firstDealer == null) {
                        firstDealer = dealer;
                        T.eq(1L, message.get("handWind"), "第一局是東局");
                    } else if (dealer == previousDealer) {
                        T.eq(handWind, message.get("handWind"), "連莊不變");
                    } else {
                        dealerChanges++;
                        T.eq(Math.floorMod(dealer - firstDealer, 4) + 1L, message.get("handWind"), "換莊往下一局");
                    }
                    previousDealer = dealer;
                    handWind = message.get("handWind");
                } else if ("state".equals(message.get("type"))) {
                    T.eq(handWind, message.get("handWind"), "state 和這局的 roundStart 一樣");
                }
            }
        }
        T.check(dealerChanges > 0, "要換過莊");
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
