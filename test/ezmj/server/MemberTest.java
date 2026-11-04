package ezmj.server;

/** 聊天的頻率限制，時間由測試給，不用真的等。 */
final class MemberTest {

    private MemberTest() {
    }

    static void run() {
        T.section("聊天頻率");
        T.test("10 秒內最多 5 則，最早那則滿 10 秒就能再送", () -> {
            Member member = new Member("token", "小明");
            for (int i = 0; i < 5; i++) {
                T.check(member.mayChat(i * 1_000L), "第 " + (i + 1) + " 則");
            }
            T.check(!member.mayChat(9_999), "第 6 則太快");
            T.check(member.mayChat(10_000), "第 1 則滿 10 秒了");
            T.check(!member.mayChat(10_500), "第 2 則還沒滿 10 秒");
        });
        T.test("送太快被拒的不算進去", () -> {
            Member member = new Member("token", "小明");
            for (int i = 0; i < 5; i++) {
                member.mayChat(0);
            }
            for (int i = 0; i < 10; i++) {
                T.check(!member.mayChat(5_000), "還在限制內");
            }
            T.check(member.mayChat(10_000), "被拒的那些沒有延長限制");
        });
    }
}
