package ezmj.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 台灣十六張麻將的多人連線服務器。牌局規則全部由服務器上的引擎決定，客戶端只送決定、只收看得到的局面。
 * 協定是 TCP 上一行一則 JSON，訊息內容見 README 的「連線協定」。
 */
public final class EzmjServer implements Connection.Handler {

    static final int DEFAULT_PORT = 7788;
    private static final String DEFAULT_HONOR_FILE = "honor-roll.properties";
    private static final int HONOR_RETRY_MS = 60_000;
    private static final int MAX_CONNECTIONS = 2_000;
    private static final int MAX_ROOMS = 500;
    private static final int MAX_LISTED_ROOMS = 50;
    private static final int MAX_NAME_LENGTH = 16;
    private static final int TOKEN_BYTES = 24;
    private static final int ROOM_CODE_LENGTH = 6;
    /** 拿掉容易看錯的 0、O、1、I、L。 */
    private static final String ROOM_CODE_LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final int requestedPort;
    private final Timing timing;
    private final HonorBoard honorBoard;
    private final SecureRandom random = new SecureRandom();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Map<String, Member> members = new ConcurrentHashMap<>();
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final AtomicLong roomsOpened = new AtomicLong();
    private ServerSocket serverSocket;

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("EZMJ Server " + ServerVersion.get());
            return;
        }
        if (args.length > 1) {
            System.err.println("用法：java -jar ezmj-server.jar [port]");
            System.exit(2);
        }
        int port = args.length == 1 ? Integer.parseInt(args[0]) : portFromEnvironment();
        EzmjServer server = new EzmjServer(port, Timing.NORMAL, honorStoreFromEnvironment());
        // 部署或休眠時主機會要程式結束，剛打完還沒存到的成績要趕上
        Runtime.getRuntime().addShutdownHook(new Thread(server.honorBoard()::close, "ezmj-shutdown"));
        server.start();
        log("EZMJ Server " + ServerVersion.get() + " 在 port " + server.port() + " 等待連線");
    }

    /** Render 這類主機用環境變數 PORT 指定要聽哪個 port。 */
    private static int portFromEnvironment() {
        String port = System.getenv("PORT");
        return port == null || port.isBlank() ? DEFAULT_PORT : Integer.parseInt(port.strip());
    }

    /**
     * 有設定 Gist 就存 Gist，不然存檔案。只設定一半一定是打錯了，
     * 這時不能改存檔案，不然在 Render 上紀錄會默默留不住，所以直接停止。
     */
    private static HonorStore honorStoreFromEnvironment() {
        String gistId = environment("EZMJ_GIST_ID");
        String gistToken = environment("EZMJ_GIST_TOKEN");
        if ((gistId == null) != (gistToken == null)) {
            System.err.println("EZMJ_GIST_ID 與 EZMJ_GIST_TOKEN 要一起設定");
            System.exit(2);
        }
        if (gistId != null) {
            log("榮譽榜存在 Gist " + gistId);
            return new GistStore(GistStore.GITHUB_API, gistId, gistToken);
        }
        String file = environment("EZMJ_HONOR_FILE");
        Path path = Path.of(file == null ? DEFAULT_HONOR_FILE : file);
        log("榮譽榜存在檔案 " + path.toAbsolutePath());
        return HonorStore.file(path);
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** port 給 0 時由系統挑一個沒人用的。 */
    EzmjServer(int port, Timing timing, HonorStore honorStore) {
        this.requestedPort = port;
        this.timing = timing;
        this.honorBoard = new HonorBoard(honorStore, HONOR_RETRY_MS);
    }

    /** accept 的執行緒不是 daemon，main 回傳後程式還會繼續跑。 */
    void start() throws IOException {
        serverSocket = new ServerSocket(requestedPort);
        new Thread(this::acceptLoop, "ezmj-accept").start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    void stop() throws IOException {
        serverSocket.close();
        rooms.values().forEach(Room::shutdown);
        connections.forEach(Connection::close);
        honorBoard.close();
    }

    static void log(String message) {
        System.out.println(Instant.now() + " " + message);
    }

    static void log(String message, Throwable failure) {
        log(message);
        failure.printStackTrace(System.out);
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                accept(serverSocket.accept());
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    log("接受連線失敗", e);
                }
            }
        }
    }

    private void accept(Socket socket) throws IOException {
        if (connections.size() >= MAX_CONNECTIONS) {
            socket.close();
            return;
        }
        Connection connection = new Connection(socket, this);
        connections.add(connection);
        try {
            connection.start();
        } catch (IOException e) {
            connections.remove(connection);
            socket.close();
            throw e;
        }
    }

    @Override
    public void message(Connection connection, Map<String, Object> message) {
        String type = Protocol.string(message, "type");
        if (type.equals("ping")) {
            connection.send(Protocol.object("type", "pong"));
        } else if (type.equals("honorRoll")) {
            connection.send(honorBoard.toMessage());
        } else if (type.equals("listRooms")) {
            connection.send(roomList());
        } else if (type.equals("hello")) {
            hello(connection, message);
        } else {
            Member member = connection.member();
            if (member == null) {
                throw new Rejected("notLoggedIn", "要先送 hello");
            }
            inSession(member, type, message);
        }
    }

    private void inSession(Member member, String type, Map<String, Object> message) {
        if (type.equals("createRoom")) {
            createRoom(member, message);
        } else if (type.equals("joinRoom")) {
            joinRoom(member, message);
        } else {
            Room room = member.room;
            if (room == null) {
                throw new Rejected("notInRoom", "還沒進入房間");
            }
            room.handle(member, type, message);
        }
    }

    private void hello(Connection connection, Map<String, Object> message) {
        if (connection.member() != null) {
            throw new Rejected("alreadyLoggedIn", "這條連線已經登入了");
        }
        int protocol = Protocol.integer(message, "protocol");
        if (protocol != Protocol.VERSION) {
            connection.sendAndClose(Protocol.error("protocolMismatch",
                    "服務器的協定版本是 " + Protocol.VERSION + "，請更新程式"));
            return;
        }
        if (message.containsKey("token")) {
            resume(connection, Protocol.string(message, "token"));
        } else {
            register(connection, normalizedName(Protocol.string(message, "name")));
        }
    }

    private void register(Connection connection, String name) {
        Member member = new Member(newToken(), name);
        members.put(member.token, member);
        connection.send(welcome(member));
        member.connection = connection;
        connection.setMember(member);
    }

    /** 同一個 token 有新連線時，舊連線就不要了，可能只是還沒發現已經斷了。 */
    private void resume(Connection connection, String token) {
        Member member = members.get(token);
        if (member == null) {
            throw new Rejected("unknownToken", "這個 token 已經失效，請重新登入");
        }
        Connection previous;
        synchronized (member) {
            connection.send(welcome(member));
            previous = member.connection;
            member.connection = connection;
            connection.setMember(member);
        }
        if (previous != null) {
            previous.close();
        }
        Room room = member.room;
        if (room != null) {
            room.reconnected(member);
        }
    }

    private static Map<String, Object> welcome(Member member) {
        Room room = member.room;
        return Protocol.object(
                "type", "welcome",
                "protocol", Protocol.VERSION,
                "version", ServerVersion.get(),
                "token", member.token,
                "name", member.name,
                "room", room == null ? null : room.code());
    }

    private static String normalizedName(String raw) {
        String name = raw.strip();
        int length = name.codePointCount(0, name.length());
        if (length == 0 || length > MAX_NAME_LENGTH || name.chars().anyMatch(Character::isISOControl)) {
            throw new Rejected("badName", "名字要是 1 到 " + MAX_NAME_LENGTH + " 個字");
        }
        return name;
    }

    private void createRoom(Member member, Map<String, Object> message) {
        requireNoRoom(member);
        RoomOptions options = RoomOptions.from(message, member.name);
        if (rooms.size() >= MAX_ROOMS) {
            throw new Rejected("serverFull", "服務器的房間已滿");
        }
        String code = newRoomCode();
        Room room = new Room(this, code, roomsOpened.incrementAndGet(), options, timing);
        rooms.put(code, room);
        room.join(member);
        log("開房間 " + code + "，目前 " + rooms.size() + " 間");
    }

    private void joinRoom(Member member, Map<String, Object> message) {
        requireNoRoom(member);
        Room room = rooms.get(Protocol.string(message, "code").toUpperCase());
        if (room == null) {
            throw new Rejected("noSuchRoom", "找不到這個房間");
        }
        room.join(member);
    }

    /** 大廳上還能加入的房間，新開的在前面。 */
    private Map<String, Object> roomList() {
        List<Room.Listing> listings = new ArrayList<>();
        for (Room room : rooms.values()) {
            Room.Listing listing = room.listing();
            if (listing != null) {
                listings.add(listing);
            }
        }
        listings.sort(Comparator.comparingLong(Room.Listing::opened).reversed());
        List<Object> entries = new ArrayList<>();
        for (Room.Listing listing : listings.subList(0, Math.min(listings.size(), MAX_LISTED_ROOMS))) {
            entries.add(listing.entry());
        }
        return Protocol.object("type", "rooms", "rooms", entries);
    }

    private static void requireNoRoom(Member member) {
        if (member.room != null) {
            throw new Rejected("alreadyInRoom", "已經在房間裡了");
        }
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String newRoomCode() {
        while (true) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < ROOM_CODE_LENGTH; i++) {
                code.append(ROOM_CODE_LETTERS.charAt(random.nextInt(ROOM_CODE_LETTERS.length())));
            }
            if (!rooms.containsKey(code.toString())) {
                return code.toString();
            }
        }
    }

    @Override
    public void closed(Connection connection) {
        connections.remove(connection);
        Member member = connection.member();
        if (member == null) {
            return;
        }
        synchronized (member) {
            if (member.connection != connection) {
                return;
            }
            member.connection = null;
        }
        Room room = member.room;
        if (room == null) {
            forget(member);
        } else {
            room.disconnected(member);
        }
    }

    /** 這位玩家不用再等他重連了。還連著的話 token 留著，等他斷線時才清。 */
    void forget(Member member) {
        if (member.connection == null) {
            members.remove(member.token);
        }
    }

    HonorBoard honorBoard() {
        return honorBoard;
    }

    void roomClosed(Room room) {
        rooms.remove(room.code());
        log("關房間 " + room.code() + "，目前 " + rooms.size() + " 間");
    }
}
