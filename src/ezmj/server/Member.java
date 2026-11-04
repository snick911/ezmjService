package ezmj.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * 一位登入的玩家。身分靠 token，不綁連線，斷線重連後還是同一位。
 * connection 與 room 不同執行緒都會讀；seat 只在房間的執行緒上用。
 */
final class Member {

    private static final int CHAT_LIMIT = 5;
    private static final long CHAT_WINDOW_MS = 10_000;

    final String token;
    final String name;
    volatile Connection connection;
    volatile Room room;
    int seat;
    /** 最近送出的聊天時間，跟著人不跟著房間，換房間也不能多送。 */
    private final Deque<Long> chatTimes = new ArrayDeque<>();

    Member(String token, String name) {
        this.token = token;
        this.name = name;
    }

    /** 斷線中就不送，重連時會補上目前的局面。 */
    void send(Map<String, Object> message) {
        Connection current = connection;
        if (current != null) {
            current.send(message);
        }
    }

    /** 每 10 秒最多 5 則。可以送就記下這一則；送太快就回傳 false，這一則不算。 */
    boolean mayChat(long nowMs) {
        synchronized (chatTimes) {
            while (!chatTimes.isEmpty() && nowMs - chatTimes.peekFirst() >= CHAT_WINDOW_MS) {
                chatTimes.removeFirst();
            }
            if (chatTimes.size() >= CHAT_LIMIT) {
                return false;
            }
            chatTimes.addLast(nowMs);
            return true;
        }
    }
}
