package ezmj.server;

import java.util.Map;

/**
 * 一位登入的玩家。身分靠 token，不綁連線，斷線重連後還是同一位。
 * connection 與 room 不同執行緒都會讀；seat 只在房間的執行緒上用。
 */
final class Member {

    final String token;
    final String name;
    volatile Connection connection;
    volatile Room room;
    int seat;

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
}
