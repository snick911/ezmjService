package ezmj.server;

import java.util.Map;

import ezmj.model.BadWords;

/** 房間設定的讀取與檢查：金額範圍、房間名稱、私人房與專家模式的等待秒數。 */
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
        T.test("隨時補位預設關閉，給了 true 才開，送回去的設定也有", () -> {
            T.eq(false, from(Map.of()).substitute(), "預設關閉");
            RoomOptions options = from(Map.of("substitute", true));
            T.eq(true, options.substitute(), "開啟");
            T.eq(true, options.toMessage().get("substitute"), "訊息裡也有");
            T.eq("badMessage", rejection(Map.of("substitute", "yes")), "不是 true 或 false");
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
        T.test("名稱不能有不雅字眼，常用詞裡剛好有的字不算", () -> {
            Rejected rejected = rejected(Map.of("name", "靠北牌局"));
            T.eq("badRoomName", rejected.code(), "不雅字眼");
            T.eq(BadWords.ROOM_NAME_REJECTED, rejected.getMessage(), "可以直接顯示的訊息");
            T.eq("badRoomName", rejection(Map.of("name", "靠 . 北")), "中間夾著空白與標點");
            T.eq("幹部聚餐", from(Map.of("name", "幹部聚餐")).name(), "幹部不算");
        });
        T.test("補上的預設名稱也檢查不雅字眼", () -> {
            T.check(!BadWords.contains("你娘"), "名字本身沒有");
            Rejected rejected = rejectedFor(Map.of(), "你娘");
            T.eq("badRoomName", rejected.code(), "接上「的房間」才有");
            T.eq(BadWords.ROOM_NAME_REJECTED, rejected.getMessage(), "可以直接顯示的訊息");
            T.eq("週末牌局", RoomOptions.from(Map.of("name", "週末牌局"), "你娘").name(), "自己取了名稱就沒事");
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
        T.test("專家模式每次最多等 10 秒，比較長的改成 10 秒，不拒絕", () -> {
            T.eq(10, from(Map.of("expert", true)).waitSeconds(), "沒給就是 10 秒");
            T.eq(10, from(Map.of("expert", true, "waitSeconds", 30L)).waitSeconds(), "30 秒改成 10 秒");
            T.eq(10, from(Map.of("expert", true, "waitSeconds", 300L)).waitSeconds(), "300 秒改成 10 秒");
            T.eq(5, from(Map.of("expert", true, "waitSeconds", 5L)).waitSeconds(), "5 秒照舊");
            T.eq(10, from(Map.of("expert", true, "waitSeconds", 30L)).toMessage().get("waitSeconds"), "訊息裡也是 10 秒");
            T.eq("badOptions", rejection(Map.of("expert", true, "waitSeconds", 4L)), "比 5 秒短還是拒絕");
            T.eq(30, from(Map.of("waitSeconds", 30L)).waitSeconds(), "不是專家模式照舊");
            T.eq(30, from(Map.of()).waitSeconds(), "不是專家模式預設 30 秒");
        });
    }

    private static RoomOptions from(Map<String, Object> message) {
        return RoomOptions.from(message, "小明");
    }

    private static String rejection(Map<String, Object> message) {
        return rejected(message).code();
    }

    private static Rejected rejected(Map<String, Object> message) {
        return rejectedFor(message, "小明");
    }

    private static Rejected rejectedFor(Map<String, Object> message, String hostName) {
        try {
            RoomOptions.from(message, hostName);
        } catch (Rejected rejected) {
            return rejected;
        }
        throw new AssertionError("應該要拒絕 " + message + "，房主 " + hostName);
    }
}
