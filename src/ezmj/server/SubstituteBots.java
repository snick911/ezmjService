package ezmj.server;

import java.util.List;
import java.util.Set;

import ezmj.bot.Bot;
import ezmj.engine.Claim;
import ezmj.engine.Round;
import ezmj.engine.TurnOptions;

/**
 * 隨時補位的房間交給 TableFlow 的代打。這種房間四個座位對 TableFlow 都是真人，電腦的座位一開局就是代打中，
 * 有人補位時關掉代打就換人，和斷線重連接回一樣，不用改引擎。
 * 所以代打要分座位：開局時就是電腦的座位照房間設定的等級與個性打（seated），和一般房間的電腦一樣；
 * 開局時是真人、後來斷線或離開的座位照舊由職業等級代打（proxy）。補位的人離開時，座位交回原本的那一個。
 */
final class SubstituteBots implements Bot {

    private final Set<Integer> people;
    private final Bot seated;
    private final Bot proxy;

    /** people 是開局時有真人坐的座位。 */
    SubstituteBots(Set<Integer> people, Bot seated, Bot proxy) {
        this.people = Set.copyOf(people);
        this.seated = seated;
        this.proxy = proxy;
    }

    private Bot of(int seat) {
        return people.contains(seat) ? proxy : seated;
    }

    @Override
    public void playTurn(Round round, int seat, TurnOptions options) {
        of(seat).playTurn(round, seat, options);
    }

    @Override
    public Claim chooseClaim(Round round, int seat, List<Claim> options) {
        return of(seat).chooseClaim(round, seat, options);
    }

    @Override
    public boolean declareListening(Round round, int seat) {
        return of(seat).declareListening(round, seat);
    }
}
