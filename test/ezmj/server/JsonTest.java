package ezmj.server;

import java.util.List;
import java.util.Map;

final class JsonTest {

    private JsonTest() {
    }

    static void run() {
        T.section("JSON");
        T.test("讀寫來回不變", () -> {
            String text = "{\"a\":1,\"b\":[true,false,null],\"c\":{\"d\":\"一萬\"},\"e\":-2.5}";
            T.eq(text, Json.write(Json.parse(text)), "來回");
        });
        T.test("整數是 Long", () -> {
            Map<?, ?> map = (Map<?, ?>) Json.parse("{\"n\":42}");
            T.eq(42L, map.get("n"), "整數");
        });
        T.test("字串的跳脫", () -> {
            T.eq("a\"b\\c\nd\te", Json.parse("\"a\\\"b\\\\c\\nd\\te\""), "讀");
            T.eq("中", Json.parse("\"\\u4e2d\""), "unicode");
            T.eq("\"a\\\"b\\n\\u0001\"", Json.write("a\"b\n\u0001"), "寫");
        });
        T.test("空白可以略過", () -> T.eq(List.of(1L, 2L), Json.parse(" [ 1 , 2 ] \r"), "空白"));
        T.test("格式不對要拒絕", () -> {
            for (String bad : List.of("", "{", "[1,", "{\"a\"}", "{a:1}", "tru", "01", "1 2", "\"abc", "\"\\x\"",
                    "\"\\u12\"", "{\"a\":1,}", "[1,]", "\"a\nb\"")) {
                T.fails(IllegalArgumentException.class, () -> Json.parse(bad), "應該拒絕 " + bad);
            }
        });
        T.test("巢狀太深要拒絕，不能把堆疊用完", () -> {
            String deep = "[".repeat(100_000);
            T.fails(IllegalArgumentException.class, () -> Json.parse(deep), "太深");
        });
    }
}
