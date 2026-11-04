package ezmj.server;

/** 執行全部測試；有失敗時以非零代碼結束，打包腳本會因此中止。 */
public final class AllTests {

    private AllTests() {
    }

    public static void main(String[] args) {
        JsonTest.run();
        ViewsTest.run();
        RoomOptionsTest.run();
        MemberTest.run();
        HonorBoardTest.run();
        ServerTest.run();
        WebSocketTest.run();
        System.exit(T.report() == 0 ? 0 : 1);
    }
}
