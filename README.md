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
- 榮譽榜預設存成檔案，路徑看環境變數 `EZMJ_HONOR_FILE`，沒有指定就是目前資料夾的 `honor-roll.properties`。同時設定 `EZMJ_GIST_ID` 與 `EZMJ_GIST_TOKEN` 就改存 GitHub Gist，只設定一個會直接停止。

## 部署到 Render

用 `Dockerfile` 建置，服務類型是 Web Service，連接 GitHub 後每次 push 到 `main` 都會自動部署。建置時會跑全部測試，失敗的話舊版本繼續運作。

- 引擎固定在 `Dockerfile` 的 `EZMJ_COMMIT`，更新 `ezmj` 不會觸發部署。要換引擎就改這個值再 push，新的 commit 要先 push 到 GitHub。
- 每次部署都會結束進行中的牌局，token 也會失效。不影響服務器的 commit，例如只改文件，在訊息裡加 `[skip render]` 就不會部署。
- Render 只把 HTTP 轉進來，所以線上的客戶端要用 `wss://ezmj-server.onrender.com/ws`，TCP 只能用在自己架的服務器。
- 目前用免費方案，不能掛硬碟，容器的檔案系統在重新部署或服務休眠之後就清空了，所以榮譽榜存在 GitHub Gist：
  1. 到 <https://gist.github.com> 建立一個 secret gist，檔名 `honor-roll.properties`，內容隨便打一行 `# EZMJ`。網址最後一段就是 Gist ID。
  2. 到 GitHub 的 Settings → Developer settings 建立 token，只給 Gists 的讀寫權限。
  3. 在 Render 的 Environment 設定 `EZMJ_GIST_ID` 與 `EZMJ_GIST_TOKEN`。token 不要寫進程式或 commit。
- 服務器啟動時從 Gist 讀榜，有新紀錄就在背景寫回，失敗會每分鐘再試，最多五次。讀不到 Gist 時絕對不會寫回，不會蓋掉舊紀錄；這段時間打完的成績等讀到之後再合併。一直寫不進去的話，那些成績在下次休眠或部署後就沒了，已經寫進 Gist 的不受影響。

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
| `EzmjServer` | 程式進入點、接受連線、登入與重連、開房間與加入、大廳的房間列表 |
| `Connection` | 一條連線，TCP 或 WebSocket 都是一則訊息一則 JSON。讀寫各一條執行緒，慢的客戶端不會卡住牌桌 |
| `WebSocket` | 同一個 port 上的 HTTP：健康檢查、升級成 WebSocket、讀寫訊框 |
| `Room` | 一間房間：等人、開局，把 `ezmj.table.TableFlow` 接到連線上。狀態都在房間自己的一條執行緒上，大廳列表只讀它在那裡做好的摘要 `Room.Listing` |
| `Views` | 把局面轉成送給某個座位的訊息，防作弊的關卡 |
| `RoomOptions` | 房間設定與檢查 |
| `HonorBoard` | 榮譽榜：記下打完的成績、在背景同步、組成訊息。排序與內容格式用引擎的 `ezmj.model.HonorRoll` |
| `HonorStore`、`GistStore` | 榮譽榜存在哪裡：檔案或 GitHub Gist |
| `Protocol`、`Json` | 組訊息、讀欄位、最小的 JSON |

## 連線協定

協定改了要連網頁版 [`ezmj_web`](https://github.com/snick911/ezmj_web) 一起看。網頁的 `PROTOCOL` 來自 `ezmj` 的 `OnlineClient.PROTOCOL`，重新編譯就跟上，但新訊息的處理要另外改。

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
| `honorRoll` | | 回 `honorRoll`，不用登入 |
| `listRooms` | | 回 `rooms`，不用登入。服務器不會主動推送列表，客戶端在大廳畫面時每 5 秒或按「重新整理」送一次 |
| `hello` | `protocol`，`name` 或 `token` | 登入。第一次帶 `name`（1 到 16 字），斷線重連帶 `token` |
| `createRoom` | 下面的房間設定，都可以省略 | 開房間並坐到座位 0 |
| `joinRoom` | `code` | 用房號加入，私人房也一樣。開打之後不能加入 |
| `leave` | | 離開房間。牌局中離開就回不來了，座位由電腦打 |
| `start` | | 開局，只有房主可以。不到四人時由電腦補滿 |
| `autoPlay` | `on` | 不再提供，一律回錯誤 `autoPlayNotAllowed`。多人遊戲不能使用電腦代打 |
| `ready` | | 看完這局的結果。連著的人都送了就立刻開下一局 |
| `chat` | `text` | 牌桌聊天，要在房間裡，等人或牌局中都可以。`text` 去掉前後空白後 1 到 100 個字（用 code point 算），不能有控制字元，不合格回錯誤 `badChat`。每人每 10 秒最多 5 則，超過回錯誤 `chatTooFast`，被拒的不算 |
| `discard` | `waitId`、`tile` | 打牌 |
| `declareListen` | `listenId` | 報聽，`listenId` 來自 `canListen`。編號過期了（已經摸牌、吃碰槓或這局結束）就忽略，不回錯誤。報聽之後全桌都知道，服務器替你自動摸打、自動胡，不再吃碰槓，也不能槓 |
| `listen` | `waitId`、`tile` | 舊的做法：打出這張牌並立刻報聽，之後和 `declareListen` 一樣。留給還沒更新的客戶端，照樣接受 |
| `selfWin` | `waitId` | 自摸 |
| `kong` | `waitId`、`tile` | 暗槓或加槓 |
| `claim` | `waitId`、`option` | 宣告 `askClaim` 的第幾個選項，從 0 起算 |
| `pass` | `waitId` | 不宣告 |

房間設定：

| 欄位 | 預設 | 範圍 |
|---|---|---|
| `chips` | 20000 | 1 到 10000000 |
| `basePay` | 100 | 1 到 1000000，不能大於 `chips` |
| `taiPay` | 20 | 1 到 1000000，不能大於 `chips` |
| `botLevels` | 三家 `NORMAL` | 三個 `BEGINNER`、`NORMAL`、`AMATEUR`、`PRO`，依序是座位 1、2、3 |
| `waitSeconds` | 30 | 5 到 300。多人桌不能不限時 |
| `speed` | `NORMAL` | `SLOW`、`NORMAL`、`FAST`，電腦出牌與等宣告的停頓 |
| `name` | 「<房主名字>的房間」 | 房間名稱。去掉前後空白後最多 30 個字（用 code point 算），不能有控制字元，不合格回錯誤 `badRoomName`。只有空白等於沒給 |
| `private` | `false` | `true` 是私人房：不列在大廳，只能用房號加入 |

金額超出範圍、底或每台大於籌碼本金（等於可以），或其他設定不合理時回錯誤 `badOptions`，`message` 可以直接顯示。

決定要帶上詢問裡的 `waitId`。編號已經過期的決定會被忽略，例如倒數到了才送到的。

等宣告時，電腦與報聽的人自動做的宣告在等宣告開始 2 秒後才算數。已經決定的宣告比還沒決定的人能做的都大時直接成立，例如電腦要碰、你只能吃；這時你的 `askClaim` 就過期了，不另外通知，晚到的回答和逾時一樣被忽略，接著的 `state` 會告訴你局面往下走了。

### 服務器送出

| `type` | 欄位 | 說明 |
|---|---|---|
| `pong` | | |
| `honorRoll` | `wins`、`selfDraws`、`winnings`、`dealIns` | 榮譽榜，四個類別各自由高到低最多十筆，數值相同時先達成的在前面，數值是 0 以下的不列入。每筆有 `name`、`date`、`wins`、`selfDraws`、`winnings`、`dealIns`、`botLevels`、`basePay`、`taiPay`。`date` 是台灣日期，`botLevels` 是房間設定，有真人坐的座位用不到。只記打到結束的真人，中途離開或斷線由電腦接手過的座位不列入 |
| `welcome` | `protocol`、`version`、`token`、`name`、`room` | 登入成功。`room` 是目前所在的房號或 null。token 要留著重連用，不能給別人 |
| `error` | `code`、`message` | 要求不被接受，局面不變。`message` 可以直接顯示 |
| `rooms` | `rooms` | 回 `listRooms`。只列還在等人（`lobby`）、不是私人房、真人不到四位的房間，新開的在前面，最多 50 間。每項有 `code`、`name`、`host`（座位 0 的名字）、`humans`（目前坐著的真人數）、`options`（和 `room` 的 `options` 一樣） |
| `room` | `code`、`state`（`lobby`、`playing`）、`yourSeat`、`options`、`players` | 房間有變化時送。`options` 是房間設定，`name` 已經補上預設，一定有值。`players` 每項有 `seat`、`name`、`human`、`connected`，電腦還有 `gender` |
| `chat` | `seat`、`name`、`text` | 房間裡有人聊天，送給房間裡連著的每一位真人，發話的人自己也會收到。`seat` 與 `name` 是發話的人。不保留歷史，斷線期間的訊息不補送 |
| `left` | | 已經離開房間 |
| `roomClosed` | `reason`（`empty`、`abandoned`、`serverStopping`、`serverError`） | 房間關閉 |
| `roundStart` | `number`、`dealer`、`roundWind`、`handWind`、`streak`、`dealMs` | 開新的一局，`dealMs` 內不會行牌，用來演發牌。`roundWind` 是圈風，`handWind` 是局風，都是 1 到 4 代表東、南、西、北，例如「東風南局」是 1 與 2。局風依起莊算：起莊那位當莊是東局，下一家當莊是南局，連莊時不變。起莊是隨機的，客戶端只看莊家算不出來 |
| `state` | 見下面 | 局面每走一步都送一次完整的局面 |
| `event` | `kind`、`seat`、`tile`、`from` | 給動畫與音效用。`kind` 是 `DISCARD`、`CHOW`、`PONG`、`KONG`、`CONCEALED_KONG`、`ADD_KONG`、`WIN`、`SELF_WIN`、`DRAWN_GAME`、`DRAW`、`FLOWER`、`LISTEN`。別家的 `DRAW` 沒有 `tile`，流局的 `seat` 是 -1，`LISTEN` 是 `seat` 報聽了，沒有 `tile` |
| `canListen` | `listenId` | 只送給你：你剛打出牌，剩下的手牌已經聽牌，可以用 `declareListen` 報聽。到你下一次摸牌、吃碰槓或這局結束就過期，過期不另外通知。電腦接手中不會送 |
| `askTurn` | `waitId`、`canSelfWin`、`concealedKongs`、`addKongs`、`readyDiscards` | 輪到你。`readyDiscards` 是「打哪張牌會聽哪些牌」 |
| `askClaim` | `waitId`、`tile`、`from`、`robbing`、`options` | 別人打出或加槓的牌可以宣告。`options` 每項有 `kind`（`WIN`、`KONG`、`PONG`、`CHOW`）、`tile`、`fromHand` |
| `countdown` | `seconds` | 這次等待還剩幾秒，全桌都會收到 |
| `roundEnd` | `drawnGame`、`winner`、`from`、`selfDraw`、`winTile`、`items`、`deltas`、`tais`、`chips`、`hands`、`gameOver`、`nextRoundMs` | 一局結束。陣列都依座位排列，`items` 是牌型與台數（每項有 `name`、`tai`，報聽的人胡牌時有「聽牌」1 台），`hands` 攤開四家的牌。沒有人送 `ready` 的話，`nextRoundMs` 後開下一局（一般 15 秒，流局 5 秒） |
| `gameOver` | `reason`（`roundsComplete`、`bankrupt`）、`rounds`、`drawnGames`、`standings` | 整場結束，接著房間回到 `lobby`，可以再開一場。`standings` 依名次排，每項有 `rank`、`seat`、`name`、`chips`、`winnings`、`wins`、`selfDraws`、`dealIns` |

`state` 的欄位：

| 欄位 | 說明 |
|---|---|
| `seat` | 這份局面是哪個座位看到的，也就是你 |
| `phase`、`current` | `TURN`、`CLAIMS`、`FINISHED`，以及輪到的座位 |
| `dealer`、`streak`、`roundWind`、`handWind`、`seatWind`、`liveTiles` | 莊家、連莊數、圈風、局風（和 `roundStart` 一樣）、自己的門風、牌牆剩幾張 |
| `hand`、`drawn`、`melds`、`flowers`、`discards`、`chips` | 自己的手牌、剛摸的牌（已經算在 `hand` 裡）、面子、花牌、牌河、籌碼 |
| `claimTile`、`discarder`、`robbing` | 等宣告的牌、打出的人、是不是搶槓 |
| `listeningWaits`、`autoPlay` | 自己報聽後等的牌（沒有報聽是 null），別家不會知道；留給舊版客戶端的欄位，連線中的玩家一定是 false |
| `opponents` | 另外三家，從下家起算。每項有 `seat`、`handCount`、`melds`（不含暗槓）、`concealedKongs`（暗槓組數）、`flowers`、`discards`、`chips`、`listening`（報聽了沒，聽哪些牌看不到） |

`melds` 每項有 `kind`（`CHOW`、`PONG`、`OPEN_KONG`、`CONCEALED_KONG`）與 `tiles`。

### 斷線重連

牌局中斷線，座位由電腦代打，其他人會收到 `connected` 變成 false 的 `room`。用同一個 `token` 送 `hello` 就接回來：依序收到 `welcome`、`room`、`state`，還能報聽的話接著是 `canListen`，如果這局已經結束還會收到 `roundEnd`，如果正在等你決定還會收到詢問。斷線的這段時間由電腦接手，報聽的機會也由它決定。接回來就換回玩家自己打。

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
