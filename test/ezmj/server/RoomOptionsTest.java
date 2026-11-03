package ezmj.server;

import java.util.Map;

/** 房間名稱與私人房的讀取與檢查。 */
final class RoomOptionsTest {

    private RoomOptionsTest() {
    }

    static void run() {
        T.section("房間設定");
        T.test("沒給名稱就用房主的名字，也不是私人房", () -> {
            RoomOptions options = RoomOptions.from(Map.of(), "小明");
            T.eq("小明的房間", options.name(), "預設名稱");
            T.eq(false, options.privateRoom(), "預設公開");
            T.eq("小明的房間", from(Map.of("name", "   ")).name(), "只有空白也用預設");
        });
        T.test("名稱去掉前後空白，最多 30 個字", () -> {
            T.eq("週末牌局", from(Map.of("name", "  週末牌局 ")).name(), "去掉空白");
            T.eq("一".repeat(30), from(Map.of("name", "一".repeat(30))).name(), "剛好 30 個字");
            T.eq("badRoomName", rejection(Map.of("name", "一".repeat(31))), "31 個字");
        });
        T.test("字數用 code point 算", () -> {
            // 🀄 是一個字，但是兩個 char
            T.eq("🀄".repeat(30), from(Map.of("name", "🀄".repeat(30))).name(), "30 個表情符號");
            T.eq("badRoomName", rejection(Map.of("name", "🀄".repeat(31))), "31 個表情符號");
        });
        T.test("名稱不能有控制字元", () -> {
            T.eq("badRoomName", rejection(Map.of("name", "週末\n牌局")), "換行");
            T.eq("badRoomName", rejection(Map.of("name", "週末\t牌局")), "tab");
        });
        T.test("欄位型別不對要拒絕", () -> {
            T.eq("badMessage", rejection(Map.of("name", 3L)), "名稱不是字串");
            T.eq("badMessage", rejection(Map.of("private", "yes")), "私人房不是 true 或 false");
        });
        T.test("設定的訊息帶上名稱與私人房", () -> {
            Map<String, Object> message = from(Map.of("name", "自己人", "private", true)).toMessage();
            T.eq("自己人", message.get("name"), "名稱");
            T.eq(true, message.get("private"), "私人房");
        });
    }

    private static RoomOptions from(Map<String, Object> message) {
        return RoomOptions.from(message, "小明");
    }

    private static String rejection(Map<String, Object> message) {
        try {
            from(message);
        } catch (Rejected rejected) {
            return rejected.code();
        }
        throw new AssertionError("應該要拒絕 " + message);
    }
}
