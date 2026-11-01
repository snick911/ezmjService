package ezmj.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ezmj.bot.TableView;
import ezmj.engine.Claim;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.RoundResult;
import ezmj.engine.Settlement;
import ezmj.engine.TurnOptions;
import ezmj.model.GameRecord;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Tile;
import ezmj.rules.ScoreItem;

/**
 * 把局面轉成送給某個座位的訊息。這裡是防作弊的關卡：牌局中的局面從 TableView 來，
 * 別家手牌、別家暗槓與牌牆順序在結構上就拿不到，別家手牌只有張數。
 */
final class Views {

    private static final int SEATS = 4;

    private Views() {
    }

    /**
     * 這個座位眼中的局面。listeningWaits 與 autoPlay 只有自己知道：
     * 聽牌模式別家看不到，所以不能廣播。
     */
    static Map<String, Object> state(Round round, int seat, List<Tile> listeningWaits, boolean autoPlay) {
        TableView view = TableView.of(round, seat);
        List<Object> opponents = new ArrayList<>();
        for (TableView.Opponent opponent : view.opponents()) {
            opponents.add(Protocol.object(
                    "seat", opponent.seat(),
                    "handCount", round.player(opponent.seat()).concealed().size(),
                    "melds", melds(opponent.openMelds()),
                    "concealedKongs", opponent.concealedKongs(),
                    "flowers", codes(opponent.flowers()),
                    "discards", codes(opponent.discards()),
                    "chips", opponent.chips()));
        }
        Tile drawn = round.current() == seat ? round.drawn() : null;
        return Protocol.object(
                "type", "state",
                "seat", seat,
                "phase", round.phase().name(),
                "current", round.current(),
                "dealer", view.dealer(),
                "streak", view.streak(),
                "roundWind", view.roundWind(),
                "seatWind", view.seatWind(),
                "liveTiles", view.liveTiles(),
                "hand", codes(view.hand()),
                "drawn", code(drawn),
                "melds", melds(view.melds()),
                "flowers", codes(view.flowers()),
                "discards", codes(view.discards()),
                "chips", view.chips(),
                "claimTile", code(view.claimTile()),
                "discarder", view.discarder(),
                "robbing", view.robbing(),
                "listeningWaits", listeningWaits == null ? null : codes(listeningWaits),
                "autoPlay", autoPlay,
                "opponents", opponents);
    }

    /** 摸到什麼牌只有摸牌的人知道。其他事件都是攤在桌上的，暗槓的 tile 本來就是 null。 */
    static Map<String, Object> event(RoundEvent event, int viewer) {
        boolean hidden = event.type() == RoundEvent.Type.DRAW && event.seat() != viewer;
        return Protocol.object(
                "type", "event",
                "kind", event.type().name(),
                "seat", event.seat(),
                "tile", hidden ? null : code(event.tile()),
                "from", event.from());
    }

    static Map<String, Object> askTurn(int waitId, TurnOptions options, Map<Tile, List<Tile>> readyDiscards) {
        Map<String, Object> ready = new LinkedHashMap<>();
        readyDiscards.forEach((tile, waits) -> ready.put(tile.code(), codes(waits)));
        return Protocol.object(
                "type", "askTurn",
                "waitId", waitId,
                "canSelfWin", options.canSelfWin(),
                "concealedKongs", codes(options.concealedKongs()),
                "addKongs", codes(options.addKongs()),
                "readyDiscards", ready);
    }

    /** 回覆時用 options 的索引指定要哪一種宣告。 */
    static Map<String, Object> askClaim(int waitId, Round round, List<Claim> claims) {
        List<Object> options = new ArrayList<>();
        for (Claim claim : claims) {
            options.add(Protocol.object(
                    "kind", claim.type().name(),
                    "tile", claim.tile().code(),
                    "fromHand", codes(claim.fromHand())));
        }
        return Protocol.object(
                "type", "askClaim",
                "waitId", waitId,
                "tile", round.claimTile().code(),
                "from", round.discarder(),
                "robbing", round.isRobbing(),
                "options", options);
    }

    /** 一局結束，這時才攤開四家的牌。 */
    static Map<String, Object> roundEnd(Round round, Settlement settlement, boolean gameOver, int nextRoundMs) {
        RoundResult result = round.result();
        List<Object> items = new ArrayList<>();
        if (!result.isDrawnGame()) {
            for (ScoreItem item : result.score().items()) {
                items.add(Protocol.object("name", item.name(), "tai", item.tai()));
            }
        }
        List<Object> chips = new ArrayList<>();
        List<Object> hands = new ArrayList<>();
        for (int seat = 0; seat < SEATS; seat++) {
            Player player = round.player(seat);
            chips.add(player.chips());
            hands.add(Protocol.object(
                    "seat", seat,
                    "hand", codes(player.concealed()),
                    "melds", melds(player.melds()),
                    "flowers", codes(player.flowers())));
        }
        return Protocol.object(
                "type", "roundEnd",
                "drawnGame", result.isDrawnGame(),
                "winner", result.winner(),
                "from", result.from(),
                "selfDraw", result.isSelfDraw(),
                "winTile", code(result.winTile()),
                "items", items,
                "deltas", ints(settlement.deltas()),
                "tais", ints(settlement.tais()),
                "chips", chips,
                "hands", hands,
                "gameOver", gameOver,
                "nextRoundMs", nextRoundMs);
    }

    /** names 依座位排列。籌碼相同時座位小的排前面。 */
    static Map<String, Object> gameOver(GameSession session, String[] names) {
        List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3));
        order.sort((a, b) -> Integer.compare(session.player(b).chips(), session.player(a).chips()));
        List<Object> standings = new ArrayList<>();
        for (int rank = 0; rank < SEATS; rank++) {
            int seat = order.get(rank);
            GameRecord record = session.record(seat, "");
            standings.add(Protocol.object(
                    "rank", rank + 1,
                    "seat", seat,
                    "name", names[seat],
                    "chips", session.player(seat).chips(),
                    "winnings", record.winnings(),
                    "wins", record.wins(),
                    "selfDraws", record.selfDraws(),
                    "dealIns", record.dealIns()));
        }
        return Protocol.object(
                "type", "gameOver",
                "reason", session.roundWind() > 4 ? "roundsComplete" : "bankrupt",
                "rounds", session.roundsPlayed(),
                "drawnGames", session.drawnGames(),
                "standings", standings);
    }

    private static List<Object> melds(List<Meld> melds) {
        List<Object> list = new ArrayList<>();
        for (Meld meld : melds) {
            list.add(Protocol.object("kind", meld.type().name(), "tiles", codes(meld.tiles())));
        }
        return list;
    }

    private static List<String> codes(List<Tile> tiles) {
        List<String> list = new ArrayList<>();
        for (Tile tile : tiles) {
            list.add(tile.code());
        }
        return list;
    }

    private static String code(Tile tile) {
        return tile == null ? null : tile.code();
    }

    private static List<Integer> ints(int[] values) {
        List<Integer> list = new ArrayList<>();
        for (int value : values) {
            list.add(value);
        }
        return list;
    }
}
