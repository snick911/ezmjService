package ezmj.server;

import java.util.List;
import java.util.Map;
import java.util.Random;

import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.Meld;
import ezmj.model.Tile;

/** 送給某個座位的訊息裡，不能出現他不該看到的牌。 */
final class ViewsTest {

    private ViewsTest() {
    }

    static void run() {
        T.section("每個座位看得到的局面");
        T.test("看得到自己的手牌，別家只有張數", () -> {
            Round round = dealtRound();
            for (int seat = 0; seat < 4; seat++) {
                Map<String, Object> state = Views.state(round, seat, null, false);
                T.eq(round.player(seat).concealed().size(), ((List<?>) state.get("hand")).size(), "自己的手牌");
                for (Object entry : (List<?>) state.get("opponents")) {
                    Map<?, ?> opponent = (Map<?, ?>) entry;
                    T.check(!opponent.containsKey("hand"), "不能有別家的手牌");
                    int other = (Integer) opponent.get("seat");
                    T.eq(round.player(other).concealed().size(), opponent.get("handCount"), "別家的張數");
                }
            }
        });
        T.test("局面裡沒有牌牆的內容", () -> {
            Map<String, Object> state = Views.state(dealtRound(), 0, null, false);
            T.check(!state.containsKey("wall"), "不能有牌牆");
            T.check(state.get("liveTiles") instanceof Integer, "只有剩幾張");
        });
        T.test("別家的暗槓只知道有幾組", () -> {
            Round round = dealtRound();
            Tile tile = Tile.parseAll("1w").get(0);
            round.player(1).addMeld(new Meld(Meld.Type.CONCEALED_KONG, List.of(tile, tile, tile, tile)));
            Map<String, Object> state = Views.state(round, 0, null, false);
            Map<?, ?> opponent = (Map<?, ?>) ((List<?>) state.get("opponents")).get(0);
            T.eq(1, opponent.get("seat"), "下家");
            T.eq(1, opponent.get("concealedKongs"), "暗槓組數");
            T.check(((List<?>) opponent.get("melds")).isEmpty(), "看不到暗槓的內容");
            T.eq(1, ((List<?>) Views.state(round, 1, null, false).get("melds")).size(), "自己看得到");
        });
        T.test("剛摸的牌只有本人看得到", () -> {
            Round round = dealtRound();
            int current = round.current();
            T.eq(round.drawn().code(), Views.state(round, current, null, false).get("drawn"), "本人");
            T.check(Views.state(round, (current + 1) % 4, null, false).get("drawn") == null, "別家");
        });
        T.test("摸牌事件別家看不到是什麼牌", () -> {
            RoundEvent draw = new RoundEvent(RoundEvent.Type.DRAW, 2, Tile.parseAll("3s").get(0), -1);
            T.eq("3s", Views.event(draw, 2).get("tile"), "本人");
            T.check(Views.event(draw, 0).get("tile") == null, "別家");
        });
        T.test("打牌事件大家都看得到", () -> {
            RoundEvent discard = new RoundEvent(RoundEvent.Type.DISCARD, 2, Tile.parseAll("5z").get(0), 2);
            T.eq("5z", Views.event(discard, 0).get("tile"), "別家");
        });
        T.test("聽牌只放在本人的局面", () -> {
            List<Tile> waits = Tile.parseAll("1w4w");
            Round round = dealtRound();
            T.eq(List.of("1w", "4w"), Views.state(round, 0, waits, false).get("listeningWaits"), "本人");
            String others = Json.write(Views.state(round, 1, null, false));
            T.check(others.contains("\"listeningWaits\":null"), "別家不知道");
        });
        T.test("局面都能寫成 JSON", () -> {
            Round round = dealtRound();
            T.check(Json.parse(Json.write(Views.state(round, 0, null, false))) instanceof Map, "可以讀回來");
        });
    }

    private static Round dealtRound() {
        GameConfig config = new GameConfig("甲", 20_000, 100, 20, BotLevel.NORMAL);
        // 種子固定，測試才不會碰巧開局就八仙過海
        GameSession session = new GameSession(config, new Random(1));
        Round round = session.newRound(message -> { });
        round.start();
        return round;
    }
}
