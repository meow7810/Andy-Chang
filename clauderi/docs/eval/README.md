# 實驗怎麼做（2026-09-19 定的走向）

目標不是跑很多人工測試，是把問題抓出來、變成任何人都能重跑的東西，丟進 repo 讓有興趣的人一起修。三層，由便宜到貴：

## 第一層：不用手機、不用 API 的測試（JVM unit test）

規則類的東西全部用程式驗，一次寫好，每次 build 自動跑。目前一個都沒有，這是最該補的洞。先寫這幾條，每條對應一條規矩：

| 規矩 | 測什麼 | 碼在哪 |
|---|---|---|
| 來源沒有的權限，摘要不能自己長出來 | 一則標 TEST 或沒有 MEMORY 用途的訊息，經過壓縮之後，記憶候選裡不能出現它的內容 | `MemoryStore` 的 eligible 過濾、`Uses.default` |
| 測試模式關掉後 TEST 不可見 | `recentTurns(includeTest=false)`、`HistorySearch` 都看不到 TEST 行 | `ConversationStore.hidden` |
| 助理的話不進記憶 | role=ASSISTANT 的訊息永遠不在記憶候選裡 | `MemoryStore` |
| 標記不會漏 | 語音來的訊息帶〔語音〕、換腦前的訊息帶〔換腦前〕、有工具的助理回合帶〔這輪已執行〕 | `ConversationStore.markedText` |
| 養成包乾淨 | 匯出的 zip 裡找不到任何 API key、任何能力授權；manifest 的 sha256 對得上 | `Bundle.export`／`import` |
| 兩隻貓分開 | `second` flavor 的 applicationId 有 `.second`、預設人設是 CUSTOM | `BuildConfig`、`Settings.defaultPersona` |
| 解讀者有記 | 每筆記憶都有 `by`（誰壓的）和 `at` | `MemoryStore.save` |

七條都寫在 `app/src/test/java/com/andychang/clauderi/data/RuleTests.kt`，Android Studio 裡對檔案按右鍵 Run，或終端機 `./gradlew testLordDebugUnitTest testSecondDebugUnitTest`（第 6 條要兩個 flavor 都跑才算）。

這一層對應 Sol 說的「多入口洗白」：所有寫入路徑（remember、壓縮、self_note、養成包匯入）各寫一條「不能升格」的測試。寫測試要加 JUnit 依賴，`Context` 相關的用暫存目錄繞開。

## 第二層：回放（fixture 加任何腦）

把一段 raw 對話（jsonl）當 fixture 放進 repo，附一張「應該成立的性質」清單，例如：問第 4 題要說沒去過日本、問稱呼要拒絕蠢蛋、不能把打字說成你說的。誰有 API key 都能拿任何腦跑一次，結果貼回 issue。這取代「使用者再打一遍十題」，配對問題自動解掉。

fixture 用的對話必須是為了測試寫的假日常，不是使用者的真實紀錄。真實紀錄留在使用者手上。

## 第三層：真的養（三十天）

只剩這一層要人。第二隻的三十天，每天一句，看的是長不長、長成什麼。沒有 pass／fail，只有紀錄。

## 兩份清單怎麼對

Sol 整理的四項（多入口洗白、嘴不能改腦的意思、跨模型漂移、太早定案）和 HANDOFF 測試單的四項（兩隻分開、同腦回放、記憶門檻、第二隻洩漏）不是同一份。HANDOFF 那份是明天手上要做的；Sol 那份是研究線。對應：

- 多入口洗白 → 第一層那七條測試，程式驗，不用人。
- 嘴不能改腦的意思 → 現在程式裡還沒有腦嘴分離這層，測不了；先當設計，等有 contract 欄位再測。
- 跨模型漂移 → 已經跑兩輪，之後改用第二層回放。
- 太早定案 → 研究題，不排期。

## 開源後的形狀

每個抓到的問題一個 issue，附 fixture 和預期性質，標「good first experiment」。修好的變第一層測試。這樣 repo 長的是測試，不是報告。
