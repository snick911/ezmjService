package ezmj.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import ezmj.online.VoiceClip;

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
    /**
     * 客戶端看得懂聽牌後的詢問（createRoom 或 joinRoom 的 supports 有 "listenWin"）：報聽的人等的牌來了，
     * 讓他自己按胡，見 Room.askListeningWin。每次開房間或加入時重設。
     */
    volatile boolean listenWin;
    /**
     * 客戶端聽得懂語音（createRoom 或 joinRoom 的 supports 有 "voice"），別人的語音才轉給他。
     * 一則將近 53 KB，舊的客戶端看不懂，送了只是浪費。每次開房間或加入時重設。
     */
    volatile boolean voice;
    /** 最近送出的聊天時間，跟著人不跟著房間，換房間也不能多送。 */
    private final Deque<Long> chatTimes = new ArrayDeque<>();
    /** 語音的頻率限制，和聊天一樣跟著人。 */
    final VoiceClip.Limiter voiceTimes = new VoiceClip.Limiter();

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
