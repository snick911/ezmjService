package ezmj.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 不依賴測試框架的小工具：案例執行與斷言。 */
final class T {

    private static int passed;
    private static final List<String> FAILURES = new ArrayList<>();

    private T() {
    }

    interface Case {
        void run() throws Exception;
    }

    interface InFolder {
        void run(Path folder) throws Exception;
    }

    /** 給一個空的暫存資料夾，用完連裡面的檔案一起刪掉。 */
    static void inTempFolder(InFolder body) throws Exception {
        Path folder = Files.createTempDirectory("ezmj-server-test");
        try {
            body.run(folder);
        } finally {
            try (Stream<Path> paths = Files.walk(folder)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
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
