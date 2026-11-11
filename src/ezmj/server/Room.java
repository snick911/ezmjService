package ezmj.server;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import ezmj.bot.Bot;
import ezmj.bot.ComputerBot;
import ezmj.bot.SeatedBots;
import ezmj.engine.Claim;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.Settlement;
import ezmj.engine.TurnOptions;
import ezmj.model.BadWords;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.GameRecord;
import ezmj.model.Tile;
import ezmj.table.TableFlow;

/**
 * 一間房間：等人、開局，把牌桌流程接到連線上。
 * 房間的狀態與 TableFlow 都只在自己的一條執行緒上動，外面的呼叫一律排進去。
 *
 * 真人從座位 0 往後坐，開局前有人離開就往前補，所以座位 0 一定是真人，也是房主；
 * 引擎的 SeatedBots 只替座位 1 到 3 決定，靠這點才接得上。
 * 玩家不能自己開電腦代打；只有牌局中斷線或離開的人才由電腦接手，座位不變，牌桌才不會卡住。
 *
 * 隨時補位（RoomOptions.substitute）：牌局中電腦在打的座位（沒人坐，或坐的人已經離開），別人可以加入替換。
 * 加入時先保留座位，牌局照走不等他；等那個座位的電腦打出牌（這一手打完）才換人，這局先結束就等下一局開始前換。
 * 整場先打完的話，他就坐進等人的房間。換人靠 TableFlow 的代打：這種房間四個座位對 TableFlow 都是真人，
 * 電腦的座位一開局就是代打中，換人時關掉代打，和斷線重連接回一樣，見 SubstituteBots。
 * 中途補位的座位不列入榮譽榜。
 */
final class Room implements TableFlow.Listener {

    private static final int SEATS = 4;
    private static final int JOIN_TIMEOUT_SECONDS = 10;
    private static final int MAX_CHAT_LENGTH = 100;
    /** 榮譽榜上的日期。主機多半是 UTC，所以不能用系統時區。 */
    private static final ZoneId RECORD_ZONE = ZoneId.of("Asia/Taipei");

    private enum State { LOBBY, PLAYING, CLOSED }

    /** 送給某個座位、還沒回覆的詢問，連線被換掉時要重送。 */
    private record Ask(int waitId, Map<String, Object> message, List<Claim> claims) {
    }

    /**
     * 大廳列表上的一間房間。opened 越大越晚開；playing 是牌局中、可以補位的房間，只列給會補位的客戶端。
     * entry 做好之後就不再改，可以給任何執行緒讀。
     */
    record Listing(long opened, boolean playing, Map<String, Object> entry) {
    }

    private final EzmjServer server;
    private final String code;
    private final long opened;
    private final RoomOptions options;
    private final Timing timing;
    private final ScheduledExecutorService executor;
    private final Member[] seats = new Member[SEATS];
    private final Ask[] asks = new Ask[SEATS];
    private final Set<Integer> readySeats = new HashSet<>();
    /** 這場曾經由電腦接手的座位，就算接回來了成績也不算本人的。 */
    private final Set<Integer> takenOver = new HashSet<>();
    /**
     * 等著補位的人，鍵是要換掉的座位，member.seat 也是那個座位。還沒坐上去，不在 seats 裡，
     * 所以收不到牌桌的訊息，也不能做任何決定，見 dispatch。
     */
    private final Map<Integer, Member> pendingSubstitutes = new HashMap<>();
    /** 這場中途補位坐進來的座位。榮譽榜只看開局時的 people，所以這些本來就不列入。 */
    private final Set<Integer> substituted = new HashSet<>();

    private State state = State.LOBBY;
    private GameSession session;
    private TableFlow flow;
    private Set<Integer> people = Set.of();
    /** 開局時定下的四家名字。引擎只知道房主的名字，其他座位在引擎裡都是電腦的名字，所以不轉發它的 log。 */
    private final String[] names = new String[SEATS];
    /** 上一局的結果，還沒開下一局時才有。 */
    private Map<String, Object> lastRoundEnd;
    private Runnable cancelNextRound = () -> { };
    private Runnable cancelAbandon = () -> { };
    /** 上面的欄位只能在房間的執行緒上讀，大廳列表從連線的執行緒來，只讀這份。不列出時是 null。 */
    private volatile Listing listing;
    /** 牌局進行中，大廳列表要數有幾桌在打，和 listing 一樣從連線的執行緒讀。 */
    private volatile boolean playing;

    /** opened 是開房間的順序，大廳列表用來把新開的排前面。 */
    Room(EzmjServer server, String code, long opened, RoomOptions options, Timing timing) {
        this.server = server;
        this.code = code;
        this.opened = opened;
        this.options = options;
        this.timing = timing;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ezmj-room-" + code);
            thread.setDaemon(true);
            return thread;
        });
    }

    String code() {
        return code;
    }

    /** 可以從任何執行緒呼叫。開打、坐滿、私人房或已經關了的都是 null。 */
    Listing listing() {
        return listing;
    }

    /** 可以從任何執行緒呼叫。私人房與跟電腦打的一人房間也算。 */
    boolean isPlaying() {
        return playing;
    }

    /** 可以從任何執行緒呼叫，設定開房間後就不變。 */
    boolean isExpert() {
        return options.expert();
    }

    /** 開房間的人坐下。 */
    void join(Member member) {
        join(member, false);
    }

    /**
     * 等到坐下（或保留好補位的座位）才回來，房間滿了或已經關了就丟出 Rejected。
     * canSubstitute 是客戶端會中途補位（joinRoom 的 supports 有 "substitute"），沒有的話開打的房間一律不能加入。
     */
    void join(Member member, boolean canSubstitute) {
        Future<?> seating;
        try {
            seating = executor.submit(() -> seat(member, canSubstitute));
        } catch (RejectedExecutionException closed) {
            throw new Rejected("noSuchRoom", "找不到這個房間");
        }
        try {
            seating.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 排進去時房間正好關閉的話，這件事就不會執行，也不會有結果
            seating.cancel(false);
            throw new Rejected("noSuchRoom", "找不到這個房間");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Rejected) {
                throw (Rejected) e.getCause();
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Rejected("internal", "服務器正在關閉");
        }
    }

    /** 客戶端在房間裡送來的訊息。不合規則的回 error，局面不變。 */
    void handle(Member member, String type, Map<String, Object> message) {
        post(() -> {
            if (member.room != this) {
                return;
            }
            try {
                dispatch(member, type, message);
            } catch (Rejected rejected) {
                member.send(Protocol.error(rejected.code(), rejected.getMessage()));
            }
        });
    }

    /** 這位玩家的連線斷了。呼叫前 connection 已經清掉；排到時已經重連的話就不用處理。 */
    void disconnected(Member member) {
        post(() -> {
            if (member.room != this || member.connection != null) {
                return;
            }
            // 還在等補位的就不等了，座位留給別人；重連回來要重新加入
            if (cancelSubstitute(member)) {
                return;
            }
            if (state == State.LOBBY) {
                removeFromLobby(member);
                return;
            }
            flow.setAutoPlay(member.seat, true);
            takenOver.add(member.seat);
            broadcastRoom();
            closeIfAbandoned();
        });
    }

    /** 這位玩家用新的連線回來了，補上他錯過的局面。 */
    void reconnected(Member member) {
        post(() -> {
            if (member.room != this) {
                return;
            }
            // 還沒上場的人沒有局面可補，再告訴他一次在等哪個座位
            if (isPendingSubstitute(member)) {
                member.send(substituteWaitingMessage(member.seat));
                return;
            }
            cancelAbandon.run();
            cancelAbandon = () -> { };
            if (state == State.PLAYING) {
                flow.setAutoPlay(member.seat, false);
            }
            broadcastRoom();
            if (state == State.PLAYING) {
                sendState(member.seat);
                // 上面已經把代打關掉，還有報聽的機會就補送
                int listenId = flow.listenOffer(member.seat);
                if (listenId != 0) {
                    member.send(canListenMessage(listenId));
                }
                if (lastRoundEnd != null) {
                    member.send(lastRoundEnd);
                }
                Ask ask = asks[member.seat];
                if (ask != null && flow.isWaiting(member.seat, ask.waitId())) {
                    member.send(ask.message());
                }
            }
        });
    }

    /** 服務器要關了。 */
    void shutdown() {
        post(() -> close("serverStopping"));
    }

    private void dispatch(Member member, String type, Map<String, Object> message) {
        // 還沒上場：member.seat 是電腦還在打的座位，不能讓他替電腦做任何決定，只能取消
        if (isPendingSubstitute(member)) {
            if (!type.equals("leave")) {
                throw new Rejected("notSeatedYet", "還沒換你上場，請稍等");
            }
            cancelSubstitute(member);
            member.send(Protocol.object("type", "left"));
            return;
        }
        switch (type) {
            case "leave":
                leave(member);
                break;
            case "start":
                startGame(member);
                break;
            case "autoPlay":
                // 舊版客戶端還有這顆按鈕，告訴玩家為什麼沒有作用
                throw new Rejected("autoPlayNotAllowed", "多人遊戲不能使用電腦代打");
            case "ready":
                markReady(member);
                break;
            case "chat":
                chat(member, message);
                break;
            case "declareListen":
                requirePlaying();
                // 過期的編號由 TableFlow 忽略，和晚到的決定一樣不算錯
                flow.declareListening(member.seat, Protocol.integer(message, "listenId"));
                break;
            case "discard":
            case "listen":
            case "selfWin":
            case "kong":
            case "claim":
            case "pass":
                decide(member, type, message);
                break;
            default:
                throw new Rejected("unknownType", "不認得的訊息 " + type);
        }
    }

    private void seat(Member member, boolean canSubstitute) {
        if (state == State.PLAYING && options.substitute()) {
            reserveSeat(member, canSubstitute);
            return;
        }
        if (state != State.LOBBY) {
            throw new Rejected("gameInProgress", "這間房間已經開打了");
        }
        int seat = humanCount();
        if (seat == SEATS) {
            throw new Rejected("roomFull", "房間已滿");
        }
        seats[seat] = member;
        member.seat = seat;
        member.room = this;
        broadcastRoom();
    }

    private int humanCount() {
        int count = 0;
        for (Member member : seats) {
            if (member != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 中途補位：先保留一個電腦在打的座位，牌局照走不等他。換人見 event 與 startNextRound。
     * 不會補位的客戶端（例如還沒有這個功能的 Swing 版）看不懂補位的訊息，所以不讓它加入。
     */
    private void reserveSeat(Member member, boolean canSubstitute) {
        if (!canSubstitute) {
            throw new Rejected("substituteNotSupported", "這間房間正在打，這個版本的程式還不能中途補位，請用網頁版");
        }
        List<Integer> open = openSeats();
        if (open.isEmpty()) {
            throw new Rejected("noOpenSeat", "這桌已經沒有電腦可以替換了");
        }
        int seat = open.get(0);
        pendingSubstitutes.put(seat, member);
        member.seat = seat;
        member.room = this;
        // 列表先跟上，收到 substituteWaiting 時大廳已經不算這個空位
        updateListing();
        member.send(substituteWaitingMessage(seat));
    }

    /** 補位可以換掉的座位：牌局中沒有人坐（電腦在打）、也還沒有人在等的。斷線的人還會回來，不算。 */
    private List<Integer> openSeats() {
        List<Integer> open = new ArrayList<>();
        if (state == State.PLAYING && options.substitute()) {
            for (int seat = 0; seat < SEATS; seat++) {
                if (seats[seat] == null && !pendingSubstitutes.containsKey(seat)) {
                    open.add(seat);
                }
            }
        }
        return open;
    }

    private boolean isPendingSubstitute(Member member) {
        return pendingSubstitutes.get(member.seat) == member;
    }

    /** 不等補位了，座位留給別人。他不是在等補位的就回傳 false。 */
    private boolean cancelSubstitute(Member member) {
        if (!isPendingSubstitute(member)) {
            return false;
        }
        pendingSubstitutes.remove(member.seat);
        member.room = null;
        server.forget(member);
        updateListing();
        return true;
    }

    /** computer 表示這個座位開局時就是電腦；不然是離開的玩家留下、由電腦代打的座位，replacing 是那位玩家的名字。 */
    private Map<String, Object> substituteWaitingMessage(int seat) {
        return Protocol.object("type", "substituteWaiting", "seat", seat, "replacing", names[seat],
                "computer", !people.contains(seat));
    }

    /** 這個座位的電腦打出牌了，在等的人可以上場。這局已經結束的話，留給下一局開始前的 seatSubstitutes。 */
    private void substituteAfterDiscard(int seat) {
        if (state == State.PLAYING && pendingSubstitutes.containsKey(seat) && lastRoundEnd == null
                && flow.round().phase() != Round.Phase.FINISHED) {
            seatSubstitute(seat, true);
        }
    }

    /** 下一局開始前，還在等的人都上場，整局都是他打。 */
    private void seatSubstitutes() {
        for (int seat : List.copyOf(pendingSubstitutes.keySet())) {
            seatSubstitute(seat, false);
        }
    }

    /**
     * 關掉代打就換人，和重連接回一樣。roundInProgress 時補上這局的局面，還能報聽也告訴他；
     * 下一局開始前換的就等 roundStart 與發牌後的局面。最後全桌都收到 substituted，畫面上要寫得明顯。
     */
    private void seatSubstitute(int seat, boolean roundInProgress) {
        Member member = pendingSubstitutes.remove(seat);
        String replaced = names[seat];
        boolean computer = !people.contains(seat);
        seats[seat] = member;
        names[seat] = member.name;
        substituted.add(seat);
        flow.setAutoPlay(seat, false);
        // 全桌都斷線時已經在倒數關房間，現在有人連著坐下了
        cancelAbandon.run();
        cancelAbandon = () -> { };
        broadcastRoom();
        if (roundInProgress) {
            sendState(seat);
            int listenId = flow.listenOffer(seat);
            if (listenId != 0) {
                member.send(canListenMessage(listenId));
            }
        }
        broadcast(Protocol.object("type", "substituted", "seat", seat, "name", member.name, "replaced", replaced,
                "computer", computer));
    }

    private void leave(Member member) {
        if (state == State.LOBBY) {
            removeFromLobby(member);
            member.send(Protocol.object("type", "left"));
            return;
        }
        flow.setAutoPlay(member.seat, true);
        takenOver.add(member.seat);
        seats[member.seat] = null;
        member.room = null;
        member.send(Protocol.object("type", "left"));
        broadcastRoom();
        closeIfAbandoned();
    }

    private void removeFromLobby(Member member) {
        List<Member> staying = new ArrayList<>();
        for (Member other : seats) {
            if (other != null && other != member) {
                staying.add(other);
            }
        }
        member.room = null;
        server.forget(member);
        reseat(staying);
    }

    /** 大家從座位 0 往後重新坐好。沒有人了就關房間。 */
    private void reseat(List<Member> staying) {
        for (int seat = 0; seat < SEATS; seat++) {
            seats[seat] = seat < staying.size() ? staying.get(seat) : null;
            if (seats[seat] != null) {
                seats[seat].seat = seat;
            }
        }
        if (staying.isEmpty()) {
            close("empty");
        } else {
            broadcastRoom();
        }
    }

    private void startGame(Member member) {
        if (state != State.LOBBY) {
            throw new Rejected("gameInProgress", "牌局已經開始了");
        }
        if (member.seat != 0) {
            throw new Rejected("notHost", "只有房主可以開局");
        }
        // 別人要是猜得到亂數就猜得到牌牆，所以用 SecureRandom
        Random random = new SecureRandom();
        GameConfig config = new GameConfig(member.name, options.chips(), options.basePay(), options.taiPay(),
                options.botLevels());
        session = new GameSession(config, random);
        Set<Integer> humans = new HashSet<>();
        for (int seat = 0; seat < SEATS; seat++) {
            if (seats[seat] != null) {
                humans.add(seat);
                names[seat] = seats[seat].name;
            } else {
                names[seat] = session.player(seat).name();
            }
        }
        people = Set.copyOf(humans);
        Bot bot = new SeatedBots(config.botLevels(),
                List.of(session.personality(1), session.personality(2), session.personality(3)),
                config.basePay(), config.taiPay(), random);
        Bot proxy = new ComputerBot(BotLevel.PRO, config.basePay(), config.taiPay(), random);
        // 隨時補位：四個座位都交給 TableFlow 當真人，電腦的座位從開局就代打，換人時關掉代打就好，見 SubstituteBots
        boolean substitute = options.substitute();
        Set<Integer> flowPeople = substitute ? Set.of(0, 1, 2, 3) : people;
        Bot flowProxy = substitute ? new SubstituteBots(people, bot, proxy) : proxy;
        flow = new TableFlow(session, flowPeople, bot, flowProxy, new TableFlow.Pacing() {
            @Override
            public int turnDelayMs() {
                return timing.instantBots() ? 0 : options.speed().turnDelayMs();
            }

            @Override
            public int claimDelayMs() {
                return timing.instantBots() ? 0 : options.speed().claimDelayMs();
            }

            @Override
            public int waitSeconds() {
                return options.waitSeconds();
            }
        }, this::after, this);
        if (substitute) {
            for (int seat = 0; seat < SEATS; seat++) {
                if (!people.contains(seat)) {
                    flow.setAutoPlay(seat, true);
                }
            }
        }
        state = State.PLAYING;
        broadcastRoom();
        flow.startRound();
    }

    private void markReady(Member member) {
        requirePlaying();
        if (lastRoundEnd == null) {
            return;
        }
        readySeats.add(member.seat);
        for (Member other : seats) {
            if (other != null && other.connection != null && !readySeats.contains(other.seat)) {
                return;
            }
        }
        startNextRound();
    }

    /**
     * 等人或牌局中都可以聊。只轉給現在連著的真人，不留歷史，斷線期間的不補送。
     * 有不雅字眼的整則不轉，和客戶端自己擋的一樣；在算頻率之前擋，被拒的不算一則。
     */
    private void chat(Member member, Map<String, Object> message) {
        String text = Protocol.string(message, "text").strip();
        int length = text.codePointCount(0, text.length());
        if (length == 0 || length > MAX_CHAT_LENGTH) {
            throw new Rejected("badChat", "聊天訊息要是 1 到 " + MAX_CHAT_LENGTH + " 個字");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new Rejected("badChat", "聊天訊息不能有換行或控制字元");
        }
        if (BadWords.contains(text)) {
            throw new Rejected("badChat", BadWords.CHAT_REJECTED);
        }
        if (!member.mayChat(System.nanoTime() / 1_000_000)) {
            throw new Rejected("chatTooFast", "說得太快了，請稍等一下");
        }
        broadcast(Protocol.object("type", "chat", "seat", member.seat, "name", member.name, "text", text));
    }

    private void startNextRound() {
        cancelNextRound.run();
        cancelNextRound = () -> { };
        lastRoundEnd = null;
        readySeats.clear();
        seatSubstitutes();
        flow.startRound();
    }

    /**
     * 玩家的決定。比倒數晚到的就忽略，這不是客戶端的錯。其他都先在這裡檢查完才交給 TableFlow，
     * 所以 TableFlow 再丟出例外就是服務器的問題，不是玩家亂送，由 guard 關房間。
     */
    private void decide(Member member, String type, Map<String, Object> message) {
        requirePlaying();
        int seat = member.seat;
        int id = Protocol.integer(message, "waitId");
        if (!flow.isWaiting(seat, id)) {
            return;
        }
        Round round = flow.round();
        boolean turn = round.phase() == Round.Phase.TURN;
        switch (type) {
            case "discard": {
                Tile tile = Protocol.tile(message, "tile");
                requireMove(turn && round.player(seat).countInHand(tile) > 0, "不能打這張牌");
                flow.discard(seat, id, tile);
                break;
            }
            case "listen": {
                // 專家模式沒有打牌前的聽牌，試打哪張會被拒絕也等於提示
                requireMove(!options.expert(), "專家模式要打出牌之後才能聽牌");
                Tile tile = Protocol.tile(message, "tile");
                requireMove(turn && round.readyDiscards(seat).containsKey(tile), "打這張牌不會聽牌");
                flow.declareListening(seat, id, tile);
                break;
            }
            case "selfWin":
                requireMove(turn && round.turnOptions(seat).canSelfWin(), "不能自摸");
                flow.declareSelfWin(seat, id);
                break;
            case "kong": {
                Tile tile = Protocol.tile(message, "tile");
                requireMove(turn && canKong(round.turnOptions(seat), tile), "不能槓這張牌");
                flow.declareKong(seat, id, tile);
                break;
            }
            case "claim": {
                List<Claim> claims = asks[seat].claims();
                int option = Protocol.integer(message, "option");
                requireMove(!turn && option >= 0 && option < claims.size(), "沒有這個選項");
                flow.answerClaim(seat, id, claims.get(option));
                break;
            }
            default:
                requireMove(!turn, "現在不能過");
                flow.answerClaim(seat, id, null);
                break;
        }
    }

    private static boolean canKong(TurnOptions options, Tile tile) {
        return options.concealedKongs().contains(tile) || options.addKongs().contains(tile);
    }

    private static void requireMove(boolean allowed, String message) {
        if (!allowed) {
            throw new Rejected("illegalMove", message);
        }
    }

    private void requirePlaying() {
        if (state != State.PLAYING) {
            throw new Rejected("notPlaying", "牌局還沒開始");
        }
    }

    /** 全桌都不在了：都走了就關房間，只是斷線就等一下有沒有人回來。 */
    private void closeIfAbandoned() {
        boolean anySeated = false;
        for (Member member : seats) {
            if (member != null) {
                anySeated = true;
                if (member.connection != null) {
                    return;
                }
            }
        }
        if (!anySeated) {
            close("empty");
            return;
        }
        cancelAbandon.run();
        cancelAbandon = after(timing.abandonMs(), () -> close("abandoned"));
    }

    private void close(String reason) {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        listing = null;
        playing = false;
        if (flow != null) {
            flow.stop();
        }
        for (int seat = 0; seat < SEATS; seat++) {
            Member member = seats[seat];
            if (member != null) {
                member.send(Protocol.object("type", "roomClosed", "reason", reason));
                member.room = null;
                server.forget(member);
                seats[seat] = null;
            }
        }
        for (Member member : pendingSubstitutes.values()) {
            member.send(Protocol.object("type", "roomClosed", "reason", reason));
            member.room = null;
            server.forget(member);
        }
        pendingSubstitutes.clear();
        server.roomClosed(this);
        executor.shutdownNow();
    }

    /**
     * 打到結束才記錄，電腦、由電腦接手過的座位與中途補位的都不算：people 是開局時的真人，補位的座位不在裡面，
     * 補到離開的人留下的座位也已經在 takenOver 裡。
     * 引擎只知道房主的名字，所以名字要換成開局時定下的。
     */
    private void recordHonors() {
        String date = LocalDate.now(RECORD_ZONE).toString();
        List<GameRecord> records = new ArrayList<>();
        for (int seat = 0; seat < SEATS; seat++) {
            if (people.contains(seat) && !takenOver.contains(seat)) {
                GameRecord record = session.record(seat, date);
                records.add(new GameRecord(names[seat], date, record.wins(), record.selfDraws(), record.winnings(),
                        record.dealIns(), record.botLevels(), record.basePay(), record.taiPay()));
            }
        }
        server.honorBoard().add(records);
    }

    /** 打完回到等人的狀態，可以再開一場；斷線的人就不等了。還在等補位的人來不及上場，直接坐進房間等下一場。 */
    private void finishGame() {
        flow.stop();
        flow = null;
        session = null;
        people = Set.of();
        lastRoundEnd = null;
        readySeats.clear();
        takenOver.clear();
        substituted.clear();
        cancelAbandon.run();
        cancelAbandon = () -> { };
        state = State.LOBBY;
        List<Member> staying = new ArrayList<>();
        for (Member member : seats) {
            if (member == null) {
                continue;
            }
            if (member.connection != null) {
                staying.add(member);
            } else {
                member.room = null;
                server.forget(member);
            }
        }
        // 斷線就取消了，所以還在等的都連著；保留的座位都是空的，加起來不會超過四位
        staying.addAll(pendingSubstitutes.values());
        pendingSubstitutes.clear();
        reseat(staying);
    }

    private void post(Runnable task) {
        try {
            executor.execute(guard(task));
        } catch (RejectedExecutionException closed) {
            // 房間已經關了，裡面的人都收過 roomClosed
        }
    }

    private Runnable after(int delayMs, Runnable action) {
        ScheduledFuture<?> future = executor.schedule(guard(action), delayMs, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    /** 牌局流程出錯之後接不回來，留著只會讓全桌空等，所以關房間。 */
    private Runnable guard(Runnable task) {
        return () -> {
            if (state == State.CLOSED) {
                return;
            }
            try {
                task.run();
            } catch (RuntimeException | Error bug) {
                EzmjServer.log("房間 " + code + " 發生錯誤，關閉房間", bug);
                close("serverError");
            }
        };
    }

    private void broadcast(Map<String, Object> message) {
        for (Member member : seats) {
            if (member != null) {
                member.send(message);
            }
        }
    }

    /** 有人進出、開打或打完都會走到這裡，所以大廳列表也在這裡更新，而且先於通知，收到 room 時列表已經跟上。 */
    private void broadcastRoom() {
        updateListing();
        for (Member member : seats) {
            if (member != null) {
                member.send(roomMessage(member));
            }
        }
    }

    /**
     * 等人中、還有空位的公開房間列出來；牌局中的只有隨時補位、還有電腦可以換的才列，playing 是 true。
     * 補位保留或取消座位時也要呼叫，空位數才對。
     */
    private void updateListing() {
        playing = state == State.PLAYING;
        int humans = humanCount();
        if (options.privateRoom()) {
            listing = null;
        } else if (state == State.LOBBY) {
            listing = humans == 0 || humans == SEATS ? null : listing(false, seats[0].name, humans, SEATS - humans);
        } else if (state == State.PLAYING) {
            // 房主可能已經離開，用開局時定下的名字
            int open = openSeats().size();
            listing = open == 0 ? null : listing(true, names[0], humans, open);
        } else {
            listing = null;
        }
    }

    private Listing listing(boolean inGame, String host, int humans, int openSeats) {
        return new Listing(opened, inGame, Collections.unmodifiableMap(Protocol.object(
                "code", code,
                "name", options.name(),
                "host", host,
                "humans", humans,
                "playing", inGame,
                "openSeats", openSeats,
                "options", Collections.unmodifiableMap(options.toMessage()))));
    }

    private Map<String, Object> roomMessage(Member viewer) {
        List<Object> players = new ArrayList<>();
        for (int seat = 0; seat < SEATS; seat++) {
            Member member = seats[seat];
            boolean human = state == State.PLAYING ? people.contains(seat) || substituted.contains(seat) : member != null;
            if (state != State.PLAYING && member == null) {
                continue;
            }
            players.add(Protocol.object(
                    "seat", seat,
                    "name", state == State.PLAYING ? names[seat] : member.name,
                    "human", human,
                    "connected", member != null && member.connection != null,
                    "gender", human ? null : session.player(seat).gender().name()));
        }
        return Protocol.object(
                "type", "room",
                "code", code,
                "state", state == State.PLAYING ? "playing" : "lobby",
                "yourSeat", viewer.seat,
                "options", options.toMessage(),
                "players", players);
    }

    private void sendState(int seat) {
        Member member = seats[seat];
        if (member != null) {
            member.send(Views.state(flow.round(), seat, flow.listeningWaits(seat), flow.isAutoPlay(seat)));
        }
    }

    private void sendStates() {
        for (int seat = 0; seat < SEATS; seat++) {
            sendState(seat);
        }
    }

    /** 引擎的 log 裡真人是電腦的名字，客戶端從事件自己組文字。 */
    @Override
    public void log(String message) {
    }

    @Override
    public void roundOpened(Round round) {
        broadcast(Protocol.object(
                "type", "roundStart",
                "number", session.roundsPlayed() + 1,
                "dealer", round.dealer(),
                "roundWind", round.roundWind(),
                "handWind", round.handWind(),
                "streak", round.streak(),
                "dealMs", timing.dealMs()));
    }

    @Override
    public int dealt(Round round) {
        if (round.phase() == Round.Phase.FINISHED) {
            return 0;
        }
        sendStates();
        return timing.dealMs();
    }

    /**
     * 補位的人等的那個座位打出牌，就是電腦這一手打完了。現在還在 TableFlow 裡面，所以排到後面再換人，
     * 這時電腦這一手已經走完，換人不會打斷它正在做的決定。
     */
    @Override
    public void event(RoundEvent event) {
        for (Member member : seats) {
            if (member != null) {
                member.send(Views.event(event, member.seat));
            }
        }
        if (event.type() == RoundEvent.Type.DISCARD && pendingSubstitutes.containsKey(event.seat())) {
            int seat = event.seat();
            post(() -> substituteAfterDiscard(seat));
        }
    }

    @Override
    public void refresh(Round round) {
        sendStates();
    }

    @Override
    public void idle(int seat) {
    }

    /** 服務器不存檔，重開服務器牌局就沒了。 */
    @Override
    public void checkpoint() {
    }

    /** 專家模式不告訴玩家打哪張會聽牌，readyDiscards 一律是空的。 */
    @Override
    public void askTurn(int seat, int waitId, TurnOptions turnOptions) {
        Map<Tile, List<Tile>> ready = options.expert() ? Map.of() : flow.round().readyDiscards(seat);
        send(seat, new Ask(waitId, Views.askTurn(waitId, turnOptions, ready), List.of()));
    }

    @Override
    public void askClaim(int seat, int waitId, List<Claim> claims) {
        send(seat, new Ask(waitId, Views.askClaim(waitId, flow.round(), claims), claims));
    }

    private void send(int seat, Ask ask) {
        asks[seat] = ask;
        if (seats[seat] != null) {
            seats[seat].send(ask.message());
        }
    }

    @Override
    public void secondsLeft(int seconds) {
        broadcast(Protocol.object("type", "countdown", "seconds", seconds));
    }

    /** 報聽的機會只給本人。報了才公開，由 LISTEN 事件與 state 的 listening 告訴全桌。 */
    @Override
    public void canListen(int seat, int listenId) {
        if (seats[seat] != null) {
            seats[seat].send(canListenMessage(listenId));
        }
    }

    private static Map<String, Object> canListenMessage(int listenId) {
        return Protocol.object("type", "canListen", "listenId", listenId);
    }

    @Override
    public void thinkingFinished(int seat) {
    }

    @Override
    public void roundFinished(Round round, Settlement settlement) {
        boolean over = session.isOver();
        int nextMs = over ? 0 : round.result().isDrawnGame() ? timing.drawnResultMs() : timing.resultMs();
        Map<String, Object> result = Views.roundEnd(round, settlement, over, nextMs);
        broadcast(result);
        if (over) {
            // 先記錄，客戶端看到 gameOver 馬上查榜才查得到這場
            recordHonors();
            broadcast(Views.gameOver(session, names));
            finishGame();
            return;
        }
        lastRoundEnd = result;
        cancelNextRound = after(nextMs, this::startNextRound);
    }
}
