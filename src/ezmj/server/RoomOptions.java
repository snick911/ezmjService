package ezmj.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ezmj.model.BotLevel;
import ezmj.model.GameSpeed;

/**
 * 建房間時選定的設定，沒給的欄位用預設值。botLevels 依序是座位 1、2、3，只有沒人坐的座位會用到。
 * 多人桌不能不限時，否則一個人不動全桌都要等，所以 waitSeconds 一定大於 0。
 * 私人房不列在大廳，只能用房號加入。
 */
record RoomOptions(int chips, int basePay, int taiPay, List<BotLevel> botLevels, int waitSeconds, GameSpeed speed,
        String name, boolean privateRoom) {

    private static final int MAX_CHIPS = 10_000_000;
    private static final int MAX_PAY = 100_000;
    private static final int MIN_WAIT_SECONDS = 5;
    private static final int MAX_WAIT_SECONDS = 300;
    private static final int MAX_NAME_LENGTH = 30;

    /** 沒給房間名稱就用房主的名字。 */
    static RoomOptions from(Map<String, Object> message, String hostName) {
        int chips = inRange(message, "chips", 20_000, 1, MAX_CHIPS);
        int basePay = inRange(message, "basePay", 100, 1, MAX_PAY);
        int taiPay = inRange(message, "taiPay", 20, 1, MAX_PAY);
        int waitSeconds = inRange(message, "waitSeconds", 30, MIN_WAIT_SECONDS, MAX_WAIT_SECONDS);
        boolean privateRoom = message.get("private") != null && Protocol.bool(message, "private");
        return new RoomOptions(chips, basePay, taiPay, botLevels(message), waitSeconds, speed(message),
                name(message, hostName), privateRoom);
    }

    Map<String, Object> toMessage() {
        List<String> levels = new ArrayList<>();
        botLevels.forEach(level -> levels.add(level.name()));
        return Protocol.object("chips", chips, "basePay", basePay, "taiPay", taiPay, "botLevels", levels,
                "waitSeconds", waitSeconds, "speed", speed.name(), "name", name, "private", privateRoom);
    }

    /** 字數和玩家名字一樣用 code point 算，表情符號也是一個字。 */
    private static String name(Map<String, Object> message, String hostName) {
        String name = message.get("name") == null ? "" : Protocol.string(message, "name").strip();
        if (name.isEmpty()) {
            return hostName + "的房間";
        }
        if (name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) {
            throw new Rejected("badRoomName", "房間名稱最多 " + MAX_NAME_LENGTH + " 個字");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new Rejected("badRoomName", "房間名稱不能有換行或控制字元");
        }
        return name;
    }

    private static int inRange(Map<String, Object> message, String key, int fallback, int min, int max) {
        int value = Protocol.integer(message, key, fallback);
        if (value < min || value > max) {
            throw new Rejected("badOptions", key + " 要在 " + min + " 到 " + max + " 之間");
        }
        return value;
    }

    private static List<BotLevel> botLevels(Map<String, Object> message) {
        Object value = message.get("botLevels");
        if (value == null) {
            return List.of(BotLevel.NORMAL, BotLevel.NORMAL, BotLevel.NORMAL);
        }
        if (!(value instanceof List) || ((List<?>) value).size() != 3) {
            throw new Rejected("badOptions", "botLevels 要列出三家電腦的等級");
        }
        List<BotLevel> levels = new ArrayList<>();
        for (Object level : (List<?>) value) {
            levels.add(named(BotLevel.class, level, "botLevels"));
        }
        return List.copyOf(levels);
    }

    private static GameSpeed speed(Map<String, Object> message) {
        Object value = message.get("speed");
        return value == null ? GameSpeed.NORMAL : named(GameSpeed.class, value, "speed");
    }

    private static <E extends Enum<E>> E named(Class<E> type, Object value, String key) {
        try {
            return Enum.valueOf(type, String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new Rejected("badOptions", key + " 不認得 " + value);
        }
    }
}
