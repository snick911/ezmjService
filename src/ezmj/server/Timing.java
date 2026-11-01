package ezmj.server;

/**
 * 服務器的時間設定，單位都是毫秒。
 * dealMs 留給客戶端演發牌；resultMs 與 drawnResultMs 是結果畫面最久停多久；
 * abandonMs 是全桌都斷線後多久關房間；instantBots 讓電腦不停頓，只有測試會用。
 */
record Timing(int dealMs, int resultMs, int drawnResultMs, int abandonMs, boolean instantBots) {

    static final Timing NORMAL = new Timing(3_000, 60_000, 10_000, 120_000, false);
}
