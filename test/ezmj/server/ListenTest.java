package ezmj.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 報聽：打出牌後聽牌才有報聽的機會（canListen），報了全桌都知道。牌是隨機的，所以每個案例都朝聽牌打，
 * 打到出現要的局面為止；底和每台很小，不會有人破產，一場有很多局可以等。
 */
final class ListenTest {

    private static final Object[] SMALL_STAKES = {"basePay", 1, "taiPay", 1};
    private static final int ATTEMPTS = 5;

    private ListenTest() {
    }

    static void run() {
        T.section("報聽");
        ServerTest.test("打出後聽牌就收到 canListen，報聽後全桌收到 LISTEN，別家看得到", port -> {
            List<TestClient> clients = ServerTest.seatedClients(port, 2, SMALL_STAKES);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Seeker host = new Seeker(clients.get(0));
                // 客人在座位 1，下一個要決定的真人一定是座位 0 的房主。打出會聽牌的牌之前先讓房主停下來，
                // 牌局就停在等房主，報聽的機會不會在報之前過期
                Seeker guest = new Seeker(clients.get(1), () -> host.paused = true);
                pool.submit(host::playToEnd);
                clients.get(0).send("type", "start");
                for (int attempt = 1; ; attempt++) {
                    Map<String, Object> offer = guest.playUntil(is("canListen"), "報聽的機會");
                    Map<String, Object> discard = guest.lastEvent;
                    T.check(isEvent(discard, "DISCARD", 1), "剛打出牌：" + discard);
                    T.eq(guest.readyDiscard, discard.get("tile"), "打出的是會聽牌的牌");
                    guest.client.send("type", "declareListen", "listenId", offer.get("listenId"));
                    Map<String, Object> result = guest.playUntil(m -> isEvent(m, "LISTEN", 1) || is("roundEnd").test(m), "報聽");
                    if (is("roundEnd").test(result)) {
                        // 電腦剛好胡了這張，這局結束，報聽的機會也過期了，等下一次
                        T.check(attempt < ATTEMPTS, "一直沒能報聽");
                        host.paused = false;
                        guest.respond(result);
                        continue;
                    }
                    Map<String, Object> mine = guest.playUntil(is("state"), "報聽後的局面");
                    T.check(mine.get("listeningWaits") != null, "自己知道聽哪些牌");
                    break;
                }
                ServerTest.awaitSeen(clients.get(0), m -> isEvent(m, "LISTEN", 1));
                ServerTest.awaitSeen(clients.get(0), m -> is("state").test(m) && listening(m, 1));
                checkListeningSeen(clients.get(0), 1);
            } finally {
                pool.shutdownNow();
                ServerTest.closeAll(clients);
            }
        });
        ServerTest.test("過期的 listenId 不算數", port -> {
            List<TestClient> clients = ServerTest.seatedClients(port, 1, SMALL_STAKES);
            try {
                TestClient client = clients.get(0);
                Seeker me = new Seeker(client);
                client.send("type", "start");
                Object stale = me.playUntil(is("canListen"), "報聽的機會").get("listenId");
                // 先不報，等到下一次輪到自己：已經摸過牌或吃碰過，這個機會過期了
                me.playUntil(is("askTurn"), "下一次輪到自己");
                client.send("type", "declareListen", "listenId", stale);
                // 房間的訊息依序處理，聊天傳回來時報聽一定處理過了
                client.send("type", "chat", "text", "報聽");
                while (true) {
                    Map<String, Object> message = client.poll();
                    if (is("chat").test(message)) {
                        break;
                    }
                    T.check(!isEvent(message, "LISTEN", 0), "過期的編號不能報聽");
                    T.check(!is("error").test(message), "過期的編號只是忽略，不算錯：" + message);
                }
            } finally {
                ServerTest.closeAll(clients);
            }
        });
        ServerTest.test("舊的 listen 照樣可用：打出這張並報聽", port -> {
            List<TestClient> clients = ServerTest.seatedClients(port, 1, SMALL_STAKES);
            try {
                TestClient client = clients.get(0);
                Seeker me = new Seeker(client);
                client.send("type", "start");
                Map<String, Object> ask = me.playUntil(m -> is("askTurn").test(m) && !Boolean.TRUE.equals(m.get("canSelfWin"))
                        && !((Map<?, ?>) m.get("readyDiscards")).isEmpty(), "打了會聽牌的時候");
                Map<?, ?> ready = (Map<?, ?>) ask.get("readyDiscards");
                Object tile = ready.keySet().iterator().next();
                client.send("type", "listen", "waitId", ask.get("waitId"), "tile", tile);
                T.eq(tile, me.playUntil(m -> isEvent(m, "DISCARD", 0), "打牌事件").get("tile"), "打出的牌");
                me.playUntil(m -> {
                    T.check(!is("canListen").test(m), "已經報聽了，不會再給報聽的機會");
                    return isEvent(m, "LISTEN", 0);
                }, "報聽事件");
                Map<String, Object> state = me.playUntil(is("state"), "報聽後的局面");
                T.eq(new HashSet<>((List<?>) ready.get(tile)), new HashSet<>((List<?>) state.get("listeningWaits")), "聽的牌");
            } finally {
                ServerTest.closeAll(clients);
            }
        });
        ServerTest.test("重連時補送還有效的 canListen，用新的連線可以報聽", port -> {
            TestClient host = TestClient.login(port, "甲");
            TestClient guest = new TestClient(port);
            List<TestClient> opened = new ArrayList<>(List.of(host, guest));
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                guest.send("type", "hello", "protocol", Protocol.VERSION, "name", "乙");
                String token = (String) guest.next("welcome").get("token");
                String code = ServerTest.createRoom(host, SMALL_STAKES);
                guest.send("type", "joinRoom", "code", code);
                guest.next("room");
                Seeker other = new Seeker(host);
                pool.submit(other::playToEnd);
                host.send("type", "start");
                Seeker me = new Seeker(guest, () -> other.paused = true);
                for (int attempt = 1; ; attempt++) {
                    Object listenId = me.playUntil(is("canListen"), "報聽的機會").get("listenId");
                    // 同一個 token 開新的連線：舊的被換掉，不算斷線，不會交給代打
                    TestClient back = new TestClient(port);
                    opened.add(back);
                    back.send("type", "hello", "protocol", Protocol.VERSION, "token", token);
                    back.next("welcome");
                    back.next("room");
                    me = new Seeker(back, () -> other.paused = true);
                    // room、state、canListen 是同一件事送的，中間不會插進別的
                    Map<String, Object> state = back.poll();
                    T.eq("state", state.get("type"), "先補局面");
                    me.respond(state);
                    Map<String, Object> resent = back.poll();
                    if (is("canListen").test(resent)) {
                        T.eq(listenId, resent.get("listenId"), "同一次機會");
                        back.send("type", "declareListen", "listenId", listenId);
                        me.playUntil(m -> isEvent(m, "LISTEN", 1), "用新的連線報聽");
                        break;
                    }
                    // 電腦剛好胡了這張，這局結束，機會也過期了，等下一次
                    T.check(attempt < ATTEMPTS, "一直等不到還有效的機會");
                    other.paused = false;
                    me.respond(resent);
                }
            } finally {
                pool.shutdownNow();
                ServerTest.closeAll(opened);
            }
        });
        ServerTest.test("報聽的人胡了，roundEnd 的 items 有「聽牌」1 台，沒報聽的沒有", port -> {
            List<TestClient> clients = ServerTest.seatedClients(port, 4, SMALL_STAKES);
            ExecutorService pool = Executors.newFixedThreadPool(clients.size());
            try {
                int listeningWins = 0;
                for (int game = 1; listeningWins == 0; game++) {
                    T.check(game <= 3, "打了三場都沒有報聽的人胡牌");
                    int from = clients.get(0).seen.size();
                    List<Future<?>> playing = new ArrayList<>();
                    for (TestClient client : clients) {
                        Seeker seeker = new Seeker(client);
                        seeker.declares = true;
                        playing.add(pool.submit(seeker::playToEnd));
                    }
                    clients.get(0).send("type", "start");
                    for (Future<?> player : playing) {
                        player.get(120, TimeUnit.SECONDS);
                    }
                    listeningWins += checkListeningItems(clients.get(0), from);
                }
            } finally {
                pool.shutdownNow();
                ServerTest.closeAll(clients);
            }
        });
    }

    private static Predicate<Map<String, Object>> is(String type) {
        return message -> type.equals(message.get("type"));
    }

    private static boolean isEvent(Map<String, Object> message, String kind, int seat) {
        return message != null && "event".equals(message.get("type")) && kind.equals(message.get("kind"))
                && Long.valueOf(seat).equals(message.get("seat"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> opponent(Map<String, Object> state, int seat) {
        for (Map<String, Object> opponent : (List<Map<String, Object>>) state.get("opponents")) {
            if (Long.valueOf(seat).equals(opponent.get("seat"))) {
                return opponent;
            }
        }
        throw new AssertionError("局面裡沒有座位 " + seat);
    }

    private static boolean listening(Map<String, Object> state, int seat) {
        return Boolean.TRUE.equals(opponent(state, seat).get("listening"));
    }

    /** 別家看到的：報聽之前是 false，之後是 true；聽哪些牌從頭到尾都看不到。 */
    private static void checkListeningSeen(TestClient viewer, int seat) {
        boolean declared = false;
        boolean shown = false;
        synchronized (viewer.seen) {
            for (Map<String, Object> message : viewer.seen) {
                if (isEvent(message, "LISTEN", seat)) {
                    declared = true;
                } else if (is("state").test(message)) {
                    Map<String, Object> opponent = opponent(message, seat);
                    T.check(!opponent.containsKey("listeningWaits"), "看到別家聽哪些牌");
                    T.check(opponent.get("listening") instanceof Boolean, "每項都有 listening");
                    if (!declared) {
                        T.check(!listening(message, seat), "還沒報聽就顯示報聽");
                    }
                    shown |= declared && listening(message, seat);
                }
            }
        }
        T.check(shown, "報聽之後別家看得到");
    }

    /** 從 seen 的第 from 則往後看每一局的結果，回傳報聽的人胡牌的局數。 */
    @SuppressWarnings("unchecked")
    private static int checkListeningItems(TestClient viewer, int from) {
        int listeningWins = 0;
        Set<Long> listening = new HashSet<>();
        synchronized (viewer.seen) {
            for (Map<String, Object> message : viewer.seen.subList(from, viewer.seen.size())) {
                Object type = message.get("type");
                if ("roundStart".equals(type)) {
                    listening.clear();
                } else if ("event".equals(type) && "LISTEN".equals(message.get("kind"))) {
                    listening.add((Long) message.get("seat"));
                } else if ("roundEnd".equals(type) && !Boolean.TRUE.equals(message.get("drawnGame"))) {
                    boolean item = false;
                    for (Map<String, Object> score : (List<Map<String, Object>>) message.get("items")) {
                        item |= "聽牌".equals(score.get("name")) && Long.valueOf(1).equals(score.get("tai"));
                    }
                    boolean listened = listening.contains((Long) message.get("winner"));
                    T.eq(listened, item, "報聽的人胡牌才有「聽牌」：" + message.get("items"));
                    if (listened) {
                        listeningWins++;
                    }
                }
            }
        }
        return listeningWins;
    }

    /**
     * 朝聽牌打的玩家：能自摸就自摸，打了會聽牌就打那張，不然打最孤立的；宣告照 ServerTest，能胡就胡。
     * paused 時先不回應詢問，牌局就停在等他。canListen 只在 declares 時才報。
     */
    private static final class Seeker {

        final TestClient client;
        /** 要打出會聽牌的牌之前執行。 */
        private final Runnable beforeReadyDiscard;
        volatile boolean paused;
        boolean declares;
        /** 最近打出的、會聽牌的牌。 */
        Object readyDiscard;
        /** playUntil 收到的最近一則 event。 */
        Map<String, Object> lastEvent;
        private Map<String, Object> state;

        Seeker(TestClient client) {
            this(client, () -> { });
        }

        Seeker(TestClient client, Runnable beforeReadyDiscard) {
            this.client = client;
            this.beforeReadyDiscard = beforeReadyDiscard;
        }

        /** 一直回應到 wanted 的訊息為止並回傳它，wanted 先看過每一則。整場打完還沒等到就失敗。 */
        Map<String, Object> playUntil(Predicate<Map<String, Object>> wanted, String description) throws Exception {
            while (true) {
                Map<String, Object> message = client.poll();
                if (client.isClosedMessage(message)) {
                    throw new AssertionError("等" + description + "時連線關閉了");
                }
                if (wanted.test(message)) {
                    return message;
                }
                if (is("gameOver").test(message)) {
                    throw new AssertionError("整場打完都沒有等到" + description);
                }
                respond(message);
            }
        }

        /** 給另一條執行緒用：打到整場結束或連線關閉。 */
        Map<String, Object> playToEnd() throws Exception {
            while (true) {
                Map<String, Object> message = client.poll();
                if (client.isClosedMessage(message) || is("gameOver").test(message)) {
                    return message;
                }
                respond(message);
            }
        }

        void respond(Map<String, Object> message) throws Exception {
            switch ((String) message.get("type")) {
                case "state":
                    state = message;
                    break;
                case "event":
                    lastEvent = message;
                    break;
                case "askTurn":
                    awaitResume();
                    turn(message);
                    break;
                case "askClaim":
                    awaitResume();
                    ServerTest.answerClaim(client, message);
                    break;
                case "canListen":
                    if (declares) {
                        client.send("type", "declareListen", "listenId", message.get("listenId"));
                    }
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
                default:
                    break;
            }
        }

        private void turn(Map<String, Object> ask) throws IOException {
            Object waitId = ask.get("waitId");
            if (Boolean.TRUE.equals(ask.get("canSelfWin"))) {
                client.send("type", "selfWin", "waitId", waitId);
                return;
            }
            Map<?, ?> ready = (Map<?, ?>) ask.get("readyDiscards");
            Object tile;
            if (ready.isEmpty()) {
                tile = ServerTest.loneliestTile((List<?>) state.get("hand"));
            } else {
                tile = ready.keySet().iterator().next();
                beforeReadyDiscard.run();
                readyDiscard = tile;
            }
            client.send("type", "discard", "waitId", waitId, "tile", tile);
        }

        private void awaitResume() throws InterruptedException {
            while (paused) {
                Thread.sleep(5);
            }
        }
    }
}
