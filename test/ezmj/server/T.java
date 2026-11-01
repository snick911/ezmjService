package ezmj.server;

import java.util.ArrayList;
import java.util.List;

/** 不依賴測試框架的小工具：案例執行與斷言。 */
final class T {

    private static int passed;
    private static final List<String> FAILURES = new ArrayList<>();

    private T() {
    }

    interface Case {
        void run() throws Exception;
    }

    static void test(String name, Case body) {
        try {
            body.run();
            passed++;
        } catch (Throwable failure) {
            FAILURES.add(name + " -> " + failure);
            System.out.println("  失敗: " + name + " -> " + failure);
        }
    }

    static void section(String name) {
        System.out.println("[" + name + "]");
    }

    static int report() {
        System.out.println("通過 " + passed + " 項，失敗 " + FAILURES.size() + " 項");
        return FAILURES.size();
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void eq(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + "：預期 " + expected + "，實際 " + actual);
        }
    }

    static void fails(Class<? extends Throwable> type, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return;
            }
            throw new AssertionError(message + "：預期 " + type.getSimpleName() + "，實際 " + thrown);
        }
        throw new AssertionError(message + "：應該要丟出 " + type.getSimpleName());
    }
}
