package ezmj.server;

import java.util.Map;

/** 房間設定的讀取與檢查：金額範圍、房間名稱與私人房。 */
final class RoomOptionsTest {

    private RoomOptionsTest() {
    }

    static void run() {
        T.section("房間設定");
        T.test("籌碼最多 10000000，底與每台最多 1000000", () -> {
            RoomOptions options = from(Map.of("chips", 10_000_000L, "basePay", 1_000_000L, "taiPay", 1_000_000L));
            T.eq(10_000_000, options.chips(), "籌碼上限");
            T.eq(1_000_000, options.basePay(), "底的上限");
            T.eq(1_000_000, options.taiPay(), "每台的上限");
            T.eq("badOptions", rejection(Map.of("chips", 10_000_001L)), "籌碼超過");
            T.eq("badOptions", rejection(Map.of("chips", 10_000_000L, "basePay", 1_000_001L)), "底超過");
            T.eq("badOptions", rejection(Map.of("chips", 10_000_000L, "taiPay", 1_000_001L)), "每台超過");
        });
        T.test("底和每台不能大於籌碼本金，等於可以", () -> {
            RoomOptions options = from(Map.of("chips", 1_000L, "basePay", 1_000L, "taiPay", 1_000L));
            T.eq(1_000, options.basePay(), "底等於籌碼");
            T.eq(1_000, options.taiPay(), "每台等於籌碼");
            Rejected rejected = rejected(Map.of("chips", 1_000L, "basePay", 1_001L));
            T.eq("badOptions", rejected.code(), "底大於籌碼");
            T.eq("底和每台不能大於籌碼本金", rejected.getMessage(), "可以直接顯示的訊息");
            T.eq("badOptions", rejection(Map.of("chips", 1_000L, "taiPay", 1_001L)), "每台大於籌碼");
            T.eq("badOptions", rejection(Map.of("basePay", 20_001L)), "和預設的籌碼比");
        });
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
        T.test("專家模式預設關，給 true 才開，也帶在設定的訊息", () -> {
            T.eq(false, from(Map.of()).expert(), "預設不是專家模式");
            T.eq(false, from(Map.of()).toMessage().get("expert"), "訊息裡是 false");
            RoomOptions expert = from(Map.of("expert", true));
            T.eq(true, expert.expert(), "專家模式");
            T.eq(true, expert.toMessage().get("expert"), "訊息裡是 true");
            T.eq("badMessage", rejection(Map.of("expert", "yes")), "不是 true 或 false");
        });
    }

    private static RoomOptions from(Map<String, Object> message) {
        return RoomOptions.from(message, "小明");
    }

    private static String rejection(Map<String, Object> message) {
        return rejected(message).code();
    }

    private static Rejected rejected(Map<String, Object> message) {
        try {
            from(message);
        } catch (Rejected rejected) {
            return rejected;
        }
        throw new AssertionError("應該要拒絕 " + message);
    }
}
