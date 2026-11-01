package ezmj.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ezmj.model.Tile;

/** 連線協定的共用工具：組訊息與讀欄位。訊息內容見 README 的「連線協定」。 */
final class Protocol {

    /** 不相容的修改才加一，客戶端在 hello 帶上，對不起來就拒絕連線。 */
    static final int VERSION = 1;

    private Protocol() {
    }

    /** 依序是鍵、值、鍵、值。 */
    static Map<String, Object> object(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static Map<String, Object> error(String code, String message) {
        return object("type", "error", "code", code, "message", message);
    }

    static String string(Map<String, Object> message, String key) {
        Object value = message.get(key);
        if (!(value instanceof String)) {
            throw new Rejected("badMessage", "欄位 " + key + " 要是字串");
        }
        return (String) value;
    }

    static int integer(Map<String, Object> message, String key) {
        Object value = message.get(key);
        if (!(value instanceof Long) || (Long) value != ((Long) value).intValue()) {
            throw new Rejected("badMessage", "欄位 " + key + " 要是整數");
        }
        return ((Long) value).intValue();
    }

    /** 沒有這個欄位時回傳預設值。 */
    static int integer(Map<String, Object> message, String key, int fallback) {
        return message.containsKey(key) ? integer(message, key) : fallback;
    }

    static boolean bool(Map<String, Object> message, String key) {
        Object value = message.get(key);
        if (!(value instanceof Boolean)) {
            throw new Rejected("badMessage", "欄位 " + key + " 要是 true 或 false");
        }
        return (Boolean) value;
    }

    /** 這裡只確定寫得出來，是不是真的有這張牌要和手牌或選項比對。 */
    static Tile tile(Map<String, Object> message, String key) {
        String code = string(message, key);
        if (code.length() != 2) {
            throw new Rejected("badMessage", "欄位 " + key + " 不是一張牌");
        }
        try {
            List<Tile> tiles = Tile.parseAll(code);
            return tiles.get(0);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new Rejected("badMessage", "欄位 " + key + " 不是一張牌");
        }
    }
}
