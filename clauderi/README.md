# Lord Claude ! （ClaudeRi）— 由你逐項授權的 Android AI 助理

> 奴才：Hey, Lord Claude！　本王：何事需要驚動本王？

對標 Siri，但每一個能碰到手機資料的「能力」都是獨立開關；沒打開的能力，模型連它存在都不知道
（工具不會送給模型、system prompt 也不會提到）。不涉及任何眼鏡硬體。

## 技術

- Kotlin + Jetpack Compose，minSdk 26 / compileSdk 35，AGP 8.7、Kotlin 2.0
- LLM：Anthropic Java SDK 呼叫 Claude（預設 `claude-sonnet-5`，可改 `claude-opus-5`），可切 OpenAI / DeepSeek / Qwen，或自訂任何 OpenAI 相容端點（Gemini、Groq、OpenRouter 的免費額度都能接）
- 語音辨識：OpenAI `gpt-4o-transcribe`（語言留空自動偵測中英文），或 Android 內建引擎（免費、多數手機可離線）
- 聽寫鍵盤模式：用 Typeless 之類的語音鍵盤打進輸入框，停 2 秒自動送出（你的鍵盤訂閱，不用 API）
- 辨識結果先填進輸入框，2 秒沒動才送出；期間可修改或按「先不要送」
- 對話記憶：`conversation.jsonl` 存手機本地，不設上限；送給模型的則數在設定頁調
- 長期記憶：超出視窗的舊對話在背景壓成 `memory.json`（關於使用者的穩定事實，≤2000 字），每輪放進 system prompt；`remember` 工具可直接寫入；設定頁可看、可編輯、可清除、可關閉。整理用 Haiku 4.5 並走 Batch API（半價、下次對話套用），都可改
- 成本：system prompt 分「穩定」（人設、能力說明，掛 prompt cache）和「易變」（記憶、時間）兩塊；思考強度 low
- 打字和語音共用同一份記憶；每個能力 = 一組 Claude 工具

## 專案結構（`clauderi/`）

```
app/src/main/java/com/andychang/clauderi/
  ClaudeRiApp.kt                     Application，把各層接起來
  data/Settings.kt                   API key、模型、記憶深度、能力開關、通知 App 允許清單（DataStore）
  data/ConversationStore.kt          對話記憶（JSON lines，永久保存）
  data/MemoryStore.kt                長期記憶：背景壓縮舊對話 + remember 工具
  llm/ChatProvider.kt                ToolSpec / ToolCall / ChatProvider 介面
  llm/ClaudeChatProvider.kt          Claude + 手動 tool-use 迴圈（adaptive thinking、prompt cache）
  llm/OpenAiChatProvider.kt          OpenAI 相容端點 + function calling 迴圈
  stt/OpenAiSpeechToText.kt          gpt-4o-transcribe
  stt/AndroidSpeechToText.kt         系統 SpeechRecognizer；SystemRecognizer 找出手機真正的引擎
  audio/MicRecorder.kt               手機麥克風錄 16 kHz PCM，靜音自動停
  audio/Speaker.kt                   系統 TTS 朗讀
  capabilities/Capability.kt         能力介面：promptSection + tools + execute
  capabilities/CapabilityRegistry.kt 只把「已開啟」能力的工具交給模型；執行時再檢查一次
  capabilities/NotificationCapability.kt  通知監聽服務 + list/reply/dismiss 工具 + 新通知朗讀
  capabilities/CalendarCapability.kt      list_calendar_events
  capabilities/ContactsCapability.kt      search_contacts
  capabilities/ActionsCapability.kt       send_message / set_alarm / set_timer / play_music（Intent）
  capabilities/ScreenCapability.kt        無障礙服務 read_screen（預設關）
  capabilities/GmailCapability.kt         IMAP（X-GM-RAW 搜尋）search_email / read_email
  capabilities/CameraCapability.kt        take_photo（照片進 tool_result）/ share_last_photo（分享面板）
  capabilities/PermissionBroker.kt        對話中請求能力：模型呼叫 request_capability，卡片等使用者按允許
  assistant/AssistantEngine.kt       流程：錄音 → STT → 輸入框確認 → LLM(+工具) → 存檔 → TTS
  assistant/ClaudeRiVoiceInteractionService.kt  註冊為預設數位助理（長按 Home）；ProxyRecognitionService 把系統辨識轉給真正的引擎
  ui/                                Compose：對話、能力、設定
```

## 在對話中授權

模型看得到「有哪些能力可以請求」（只有名稱），但拿不到工具。需要時它呼叫 `request_capability`，
對話裡跳出卡片，你按「允許」才打開，行事曆 / 聯絡人的系統權限框直接跳在 App 內，
通知 / 螢幕感知則會帶你到系統設定頁再回來。設定頁可以整個關掉這個功能。

## 第一版能力（照順序）

1. **預設助理**：`VoiceInteractionService` + `ACTION_ASSIST`。到「能力」頁按「前往系統設定」選 ClaudeRi。
2. **通知朗讀與回覆**：開啟後到系統「通知存取權」授權，再逐 App 勾選允許哪些。工具：`list_notifications`、`reply_notification`（用通知本身的快速回覆）、`dismiss_notification`。
3. **行事曆、聯絡人**：各自獨立開關，開啟時才要 `READ_CALENDAR` + `WRITE_CALENDAR` / `READ_CONTACTS`。行事曆可讀可新增（`add_calendar_event`）。
4. **動作**：簡訊（開簡訊 App 填好、由你按送出）、鬧鐘、計時器、播放音樂、導航（Google Maps），全走系統 Intent。
5. **螢幕感知**：無障礙服務，只讀文字不點擊；預設關閉，開啟後還要在系統無障礙設定啟用。
7. **相機與照片**：`take_photo` 開系統相機，照片縮到 1280px 後直接夾在工具結果裡給模型看（Claude 原生支援；OpenAI 相容端點改以下一則 user 訊息附圖）。`share_last_photo` 走系統分享面板，你選 App 和收件人。不需要相機權限，照片只在 App 快取。
8. **網路搜尋**：Claude 的伺服器端 web search 工具（`web_search_20260209`，台北地區設定），手機不跑任何爬蟲；OpenAI 相容後端忽略。
6. **Gmail 信箱**：IMAP + Google 應用程式密碼（不用 OAuth 專案、不會 7 天過期）。工具 `search_email`（Gmail 搜尋語法）、`read_email`、`archive_emails`（從收件匣封存，可逆，執行前模型必須先讓你確認）、`unsubscribe`（開該信的 List-Unsubscribe 連結）。讀信不會標成已讀；不能刪信、不能寄信。

## 在 Android Studio 建置

1. `File → Open`，選 **`clauderi/` 這個資料夾**（不是 repo 根目錄）。等 Gradle sync 跑完（第一次要下載，幾分鐘）。
2. 上方工具列 `Build → Make Project`（Ctrl+F9 / ⌘F9）。有紅字就整段貼給我。
3. 手機開「開發人員選項 → USB 偵錯」，接上電腦，上方裝置選單選你的手機，按綠色 ▶ Run（Shift+F10 / ⌃R）。
4. 第一次開 App：先到「設定」頁填 API key（Anthropic 給 Claude、OpenAI 給語音辨識），按儲存。
5. 到「能力」頁一項一項打開你要的，照畫面提示去系統設定授權。

## 已知限制 / 誠實說明

- **這份程式碼還沒編譯過**：開發環境連不到 Google 的 Maven / SDK 主機。SDK 方法簽名有用 `javap` 對照過 anthropic-java 2.63.0 的 jar，但 Compose / Android API 那邊第一次跑一定會有要調的地方。
- API key 存在 app 私有 DataStore，沒有加密；要上架請改用 Keystore。
- Claude 遇到 `refusal` 目前回一句固定的婉拒語，尚未接 server-side fallback。
- 通知回覆依賴該 App 通知本身有「快速回覆」動作（LINE、WhatsApp、Messages 都有）。
- 螢幕感知在你呼叫助理時，ClaudeRi 自己在前景，所以讀的是「呼叫前最後一個 App」的快照。
