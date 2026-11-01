# EZMJ Server 台灣十六張麻將連線服務器

[EZMJ](https://github.com/snick911/ezmj) 的多人連線服務器。牌局規則全部在服務器上跑，客戶端只送決定、只收自己看得到的局面。需要 JDK 17 以上才能建置，不依賴其他套件。

## 啟動

```
java -jar ezmj-server-<版號>.jar [port]
```

沒有指定 port 時看環境變數 `PORT`，再沒有就用 7788。`--version` 顯示版號。

目前的限制：

- 服務器本身不加密，也沒有帳號，玩家的身分只靠服務器發的 token。對外開放要放在有 TLS 的主機後面，例如 Render。
- 牌局只存在記憶體裡，重開服務器牌局就沒了。

## 部署到 Render

用 `Dockerfile` 建置，服務類型是 Web Service，連接 GitHub 後每次 push 到 `main` 都會自動部署。建置時會跑全部測試，失敗的話舊版本繼續運作。

- 引擎固定在 `Dockerfile` 的 `EZMJ_COMMIT`，更新 `ezmj` 不會觸發部署。要換引擎就改這個值再 push，新的 commit 要先 push 到 GitHub。
- 每次部署都會結束進行中的牌局，token 也會失效。不影響服務器的 commit，例如只改文件，在訊息裡加 `[skip render]` 就不會部署。
- Render 只把 HTTP 轉進來，所以線上的客戶端要用 `wss://ezmj-server.onrender.com/ws`，TCP 只能用在自己架的服務器。

## 建置與打包

牌局引擎直接編譯 `ezmj` 專案的原始碼（不含畫面），所以要把它 clone 在隔壁的資料夾，或用 `-Ezmj` 指定位置。Eclipse 裡兩個專案放在同一個 workspace，`.classpath` 已經設好引用。

```
git clone https://github.com/snick911/ezmj.git ..\ezmj
powershell -ExecutionPolicy Bypass -File build.ps1
```

腳本會編譯、執行全部測試（失敗就中止），再把成品放到 `dist\`：

| 檔案 | 說明 |
|---|---|
| `ezmj-server-<版號>.jar` | 可執行 jar，已經包含引擎，需要 Java 17 以上。manifest 的 `Ezmj-Engine-Version` 記下當時用的 `ezmj` 版號 |

版號只改 `src/ezmj/server/version.properties` 一處。

### 打包流程

每次打包前依序做：

1. 看修改內容：`git status` 與 `git diff`，連同還沒 commit 的新檔案，確認這次會包進去的東西。
2. 記下修改內容：寫進 `CHANGELOG.md`，把「未發布」改成 `## <新版號> - <日期>`，再開一個新的「未發布」。
3. 版號增加：改 `src/ezmj/server/version.properties`，依下面的規則決定動哪一碼。
4. 執行 `build.ps1`。

版號是 `主版.次版.修正`，看這次修改裡影響最大的一項：

| 動哪一碼 | 什麼時候 | 例子 |
|---|---|---|
| 主版（1.0.0） | 大改版，或舊的客戶端不能再用 | 協定版本 `Protocol.VERSION` 加一 |
| 次版（0.2.0） | 新功能，或舊客戶端還能用的協定擴充 | 新的訊息、新的房間設定、換新的引擎規則 |
| 修正（0.1.1） | 只有修錯，功能和協定都不變 | 修程式錯誤、調整記錄 |

動了前面的碼，後面的碼歸零。

`CHANGELOG.md` 沒有這個版號的段落，或 `dist\` 已經有這個版號的成品時，腳本會直接停止，不會清除任何檔案。確定要用同版號重新打包，先刪除 `dist\`。

## 測試

測試不依賴框架，直接執行 `test/ezmj/server/AllTests.java` 的 `main` 即可。`ServerTest` 會真的開一台服務器，用 TCP 連上去打完整場。

## 程式結構

全部在 `ezmj.server` 套件：

| 類別 | 職責 |
|---|---|
| `EzmjServer` | 程式進入點、接受連線、登入與重連、開房間與加入 |
| `Connection` | 一條連線，TCP 或 WebSocket 都是一則訊息一則 JSON。讀寫各一條執行緒，慢的客戶端不會卡住牌桌 |
| `WebSocket` | 同一個 port 上的 HTTP：健康檢查、升級成 WebSocket、讀寫訊框 |
| `Room` | 一間房間：等人、開局，把 `ezmj.table.TableFlow` 接到連線上。狀態都在房間自己的一條執行緒上 |
| `Views` | 把局面轉成送給某個座位的訊息，防作弊的關卡 |
| `RoomOptions` | 房間設定與檢查 |
| `Protocol`、`Json` | 組訊息、讀欄位、最小的 JSON |

## 連線協定

同一個 port 接受兩種連線，訊息內容完全一樣：

- TCP：一行一則 UTF-8 JSON，以 `\n` 結尾。
- WebSocket：連到 `/ws`，一則文字訊息是一則 JSON，不用 `\n`。二進位訊息會斷線。

一則訊息最多 64 KB，超過就斷線。60 秒沒有收到任何訊息也會斷線，閒置時客戶端要定期送 `ping`，WebSocket 層的 ping 也算。

`GET /healthz` 回 200，給主機做健康檢查。

牌的記法：`1w`＝一萬、`1p`＝一筒、`1s`＝一條、`1z`-`4z`＝東南西北、`5z`-`7z`＝中發白、`1f`-`8f`＝春夏秋冬梅蘭竹菊。

座位 0 到 3，依 0→1→2→3 行牌。真人從座位 0 往後坐，座位 0 是房主。

### 客戶端送出

| `type` | 其他欄位 | 說明 |
|---|---|---|
| `ping` | | 回 `pong`，不用登入 |
| `hello` | `protocol`，`name` 或 `token` | 登入。第一次帶 `name`（1 到 16 字），斷線重連帶 `token` |
| `createRoom` | 下面的房間設定，都可以省略 | 開房間並坐到座位 0 |
| `joinRoom` | `code` | 用房號加入，開打之後不能加入 |
| `leave` | | 離開房間。牌局中離開就回不來了，座位由電腦打 |
| `start` | | 開局，只有房主可以。不到四人時由電腦補滿 |
| `autoPlay` | `on` | 電腦代打的開關 |
| `ready` | | 看完這局的結果。連著的人都送了就立刻開下一局 |
| `discard` | `waitId`、`tile` | 打牌 |
| `listen` | `waitId`、`tile` | 打這張牌並進入聽牌模式，之後自動摸打、自動胡，不再吃碰槓 |
| `selfWin` | `waitId` | 自摸 |
| `kong` | `waitId`、`tile` | 暗槓或加槓 |
| `claim` | `waitId`、`option` | 宣告 `askClaim` 的第幾個選項，從 0 起算 |
| `pass` | `waitId` | 不宣告 |

房間設定：

| 欄位 | 預設 | 範圍 |
|---|---|---|
| `chips` | 20000 | 1 到 10000000 |
| `basePay` | 100 | 1 到 100000 |
| `taiPay` | 20 | 1 到 100000 |
| `botLevels` | 三家 `NORMAL` | 三個 `BEGINNER`、`NORMAL`、`AMATEUR`、`PRO`，依序是座位 1、2、3 |
| `waitSeconds` | 30 | 5 到 300。多人桌不能不限時 |
| `speed` | `NORMAL` | `SLOW`、`NORMAL`、`FAST`，電腦出牌與等宣告的停頓 |

決定要帶上詢問裡的 `waitId`。編號已經過期的決定會被忽略，例如倒數到了才送到的。

### 服務器送出

| `type` | 欄位 | 說明 |
|---|---|---|
| `pong` | | |
| `welcome` | `protocol`、`version`、`token`、`name`、`room` | 登入成功。`room` 是目前所在的房號或 null。token 要留著重連用，不能給別人 |
| `error` | `code`、`message` | 要求不被接受，局面不變。`message` 可以直接顯示 |
| `room` | `code`、`state`（`lobby`、`playing`）、`yourSeat`、`options`、`players` | 房間有變化時送。`players` 每項有 `seat`、`name`、`human`、`connected`，電腦還有 `gender` |
| `left` | | 已經離開房間 |
| `roomClosed` | `reason`（`empty`、`abandoned`、`serverStopping`、`serverError`） | 房間關閉 |
| `roundStart` | `number`、`dealer`、`roundWind`、`streak`、`dealMs` | 開新的一局，`dealMs` 內不會行牌，用來演發牌 |
| `state` | 見下面 | 局面每走一步都送一次完整的局面 |
| `event` | `kind`、`seat`、`tile`、`from` | 給動畫與音效用。`kind` 是 `DISCARD`、`CHOW`、`PONG`、`KONG`、`CONCEALED_KONG`、`ADD_KONG`、`WIN`、`SELF_WIN`、`DRAWN_GAME`、`DRAW`、`FLOWER`。別家的 `DRAW` 沒有 `tile`，流局的 `seat` 是 -1 |
| `askTurn` | `waitId`、`canSelfWin`、`concealedKongs`、`addKongs`、`readyDiscards` | 輪到你。`readyDiscards` 是「打哪張牌會聽哪些牌」 |
| `askClaim` | `waitId`、`tile`、`from`、`robbing`、`options` | 別人打出或加槓的牌可以宣告。`options` 每項有 `kind`（`WIN`、`KONG`、`PONG`、`CHOW`）、`tile`、`fromHand` |
| `countdown` | `seconds` | 這次等待還剩幾秒，全桌都會收到 |
| `roundEnd` | `drawnGame`、`winner`、`from`、`selfDraw`、`winTile`、`items`、`deltas`、`tais`、`chips`、`hands`、`gameOver`、`nextRoundMs` | 一局結束。陣列都依座位排列，`items` 是牌型與台數，`hands` 攤開四家的牌。沒有人送 `ready` 的話，`nextRoundMs` 後開下一局 |
| `gameOver` | `reason`（`roundsComplete`、`bankrupt`）、`rounds`、`drawnGames`、`standings` | 整場結束，接著房間回到 `lobby`，可以再開一場。`standings` 依名次排，每項有 `rank`、`seat`、`name`、`chips`、`winnings`、`wins`、`selfDraws`、`dealIns` |

`state` 的欄位：

| 欄位 | 說明 |
|---|---|
| `seat` | 這份局面是哪個座位看到的，也就是你 |
| `phase`、`current` | `TURN`、`CLAIMS`、`FINISHED`，以及輪到的座位 |
| `dealer`、`streak`、`roundWind`、`seatWind`、`liveTiles` | 莊家、連莊數、圈風、自己的門風、牌牆剩幾張 |
| `hand`、`drawn`、`melds`、`flowers`、`discards`、`chips` | 自己的手牌、剛摸的牌（已經算在 `hand` 裡）、面子、花牌、牌河、籌碼 |
| `claimTile`、`discarder`、`robbing` | 等宣告的牌、打出的人、是不是搶槓 |
| `listeningWaits`、`autoPlay` | 自己在聽牌模式等的牌（沒有時是 null）、自己的代打開關。別家不會知道 |
| `opponents` | 另外三家，從下家起算。每項有 `seat`、`handCount`、`melds`（不含暗槓）、`concealedKongs`（暗槓組數）、`flowers`、`discards`、`chips` |

`melds` 每項有 `kind`（`CHOW`、`PONG`、`OPEN_KONG`、`CONCEALED_KONG`）與 `tiles`。

### 斷線重連

牌局中斷線，座位由電腦代打，其他人會收到 `connected` 變成 false 的 `room`。用同一個 `token` 送 `hello` 就接回來：依序收到 `welcome`、`room`、`state`，如果這局已經結束還會收到 `roundEnd`，如果正在等你決定還會收到詢問。玩家自己開的代打會保留，斷線造成的代打會關掉。

同一個 token 有新連線時，舊連線會被關閉。還沒開局時斷線就等於離開房間，token 也跟著失效。全桌都斷線超過 2 分鐘就關房間。

### 一個最短的流程

```
→ {"type":"hello","protocol":1,"name":"小明"}
← {"type":"welcome","protocol":1,"version":"0.1.0","token":"…","name":"小明","room":null}
→ {"type":"createRoom"}
← {"type":"room","code":"K7M2QX","state":"lobby","yourSeat":0,…}
→ {"type":"start"}
← {"type":"room",…,"state":"playing",…}
← {"type":"roundStart","number":1,…}
← {"type":"state",…}
← {"type":"askTurn","waitId":1,…}
→ {"type":"discard","waitId":1,"tile":"9s"}
```
