package ezmj.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import ezmj.model.BotLevel;
import ezmj.model.GameRecord;
import ezmj.model.HonorRoll;

/**
 * 服務器的榮譽榜：真人打完一場的成績，每個類別留下最好的十筆，贏錢金額照底台分榜，排序與格式都用引擎的 HonorRoll。
 * 每間房間有自己的執行緒，又可能同時打完，所以記憶體裡的榜要同步。
 * 存取 HonorStore 可能要等網路，都放在另一條執行緒上，不能卡住牌桌。
 * 還沒讀到存過的紀錄之前絕對不寫回，不然舊紀錄會被蓋掉；這段時間打完的成績等讀到之後再併進去。
 */
final class HonorBoard {

    private static final int SYNC_ATTEMPTS = 5;
    private static final int CLOSE_WAIT_SECONDS = 5;

    private final HonorStore store;
    private final int retryMs;
    private final ScheduledThreadPoolExecutor worker;
    private HonorRoll roll = new HonorRoll();
    private boolean loaded;
    private boolean dirty;
    /** 最近一次同步失敗了，下一次成功才清掉。 */
    private boolean syncFailing;

    /** 先試著讀一次才回來，這樣服務器開始接連線時榜就是好的。 */
    HonorBoard(HonorStore store, int retryMs) {
        this.store = store;
        this.retryMs = retryMs;
        this.worker = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "ezmj-honor");
            thread.setDaemon(true);
            return thread;
        });
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        sync(SYNC_ATTEMPTS);
    }

    synchronized void add(List<GameRecord> records) {
        boolean changed = false;
        for (GameRecord record : records) {
            changed |= !roll.add(record).isEmpty();
        }
        if (changed) {
            dirty = true;
            try {
                worker.execute(() -> sync(SYNC_ATTEMPTS));
            } catch (RejectedExecutionException closed) {
                // 服務器正在關閉，close 會再存最後一次
            }
        }
    }

    /** winnings 是所有底台合起來的，留給 0.10.0 以前的用戶端；新的看 winningsByStakes，不同底台不放在一起比。 */
    synchronized Map<String, Object> toMessage() {
        List<Object> byStakes = new ArrayList<>();
        for (HonorRoll.Stakes stakes : roll.stakes()) {
            byStakes.add(Protocol.object(
                    "basePay", stakes.basePay(),
                    "taiPay", stakes.taiPay(),
                    "records", entries(roll.top(stakes))));
        }
        return Protocol.object(
                "type", "honorRoll",
                "wins", entries(roll.top(HonorRoll.Category.WINS)),
                "selfDraws", entries(roll.top(HonorRoll.Category.SELF_DRAWS)),
                "winnings", entries(roll.top(HonorRoll.Category.WINNINGS)),
                "dealIns", entries(roll.top(HonorRoll.Category.DEAL_INS)),
                "winningsByStakes", byStakes);
    }

    /** 讀不到存過的紀錄，或新紀錄存不進去，而且之後還沒成功過。這時成績只在記憶體裡，休眠或部署就沒了。 */
    synchronized boolean syncFailing() {
        return syncFailing;
    }

    /** 等手上的存檔做完，再把還沒存到的存最後一次。 */
    void close() {
        worker.shutdown();
        try {
            worker.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sync(1);
    }

    /** 存不了只記在 log，成績還留在記憶體裡，等一下再試，之後有新紀錄也會再存一次，牌局不受影響。 */
    private void sync(int attempts) {
        try {
            loadSaved();
            save();
            synchronized (this) {
                syncFailing = false;
            }
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                syncFailing = true;
            }
            EzmjServer.log("榮譽榜同步失敗，還會再試 " + (attempts - 1) + " 次：" + e);
            if (attempts > 1 && !worker.isShutdown()) {
                worker.schedule(() -> sync(attempts - 1), retryMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** 啟動後才打完的成績不會在存檔裡，所以併進去時不會重複。不在前十名的一定也進不了合併後的前十名。 */
    private void loadSaved() throws IOException {
        synchronized (this) {
            if (loaded) {
                return;
            }
        }
        String saved = store.read();
        synchronized (this) {
            HonorRoll merged = parse(saved);
            roll.allRecords().forEach(merged::add);
            roll = merged;
            loaded = true;
        }
    }

    private void save() throws IOException {
        String content;
        synchronized (this) {
            if (!dirty) {
                return;
            }
            content = render(roll);
            dirty = false;
        }
        try {
            store.write(content);
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                dirty = true;
            }
            throw e;
        }
    }

    /** HonorRoll 只會讀寫檔案，所以經過一個暫存檔。 */
    private static HonorRoll parse(String content) throws IOException {
        if (content == null) {
            return new HonorRoll();
        }
        Path temp = Files.createTempFile("ezmj-honor", ".properties");
        try {
            Files.writeString(temp, content);
            return HonorRoll.load(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String render(HonorRoll roll) throws IOException {
        Path temp = Files.createTempFile("ezmj-honor", ".properties");
        try {
            roll.save(temp);
            return Files.readString(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static List<Object> entries(List<GameRecord> records) {
        List<Object> list = new ArrayList<>();
        for (GameRecord record : records) {
            List<String> levels = new ArrayList<>();
            for (BotLevel level : record.botLevels()) {
                levels.add(level.name());
            }
            list.add(Protocol.object(
                    "name", record.name(),
                    "date", record.date(),
                    "wins", record.wins(),
                    "selfDraws", record.selfDraws(),
                    "winnings", record.winnings(),
                    "dealIns", record.dealIns(),
                    "botLevels", levels,
                    "basePay", record.basePay(),
                    "taiPay", record.taiPay()));
        }
        return list;
    }
}
