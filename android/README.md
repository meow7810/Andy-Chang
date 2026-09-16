# CyanMind — 自己做的 HeyCyan (G300) AI 眼鏡大腦

把 HeyCyan / G300 智慧眼鏡原廠 App 裡的「AI 那一層」整個換掉：

| 層 | 原廠 App | CyanMind |
|---|---|---|
| 語音辨識 | 原廠雲端（常聽錯） | OpenAI `gpt-4o-transcribe`（預設）或 Typeless（可插拔） |
| 對話模型 | Qwen，只記 10 組來回 | Claude（預設 `claude-opus-5`）、OpenAI、DeepSeek 或 Qwen，歷史全部保存在手機，送多少輪自己設定 |
| 互動方式 | 只能講 | 講話 **和** 打字都可以，同一份記憶 |
| 眼鏡韌體 | 原廠 | **完全不動**，不需要改機 |

## 為什麼不用改機

眼鏡本身只做三件事：偵測喚醒詞「Hey Cyan」、當一副藍牙耳機（HFP 麥克風 + A2DP 喇叭）、透過 BLE 收發控制指令。
語音辨識、LLM、TTS 全部在手機 App 裡跑。所以「換掉 App」等於「換掉 AI」。

```
 眼鏡 ──BLE (QCSDK 私有協定)──▶ 喚醒事件 / 電量 / 按鍵        ┐
 眼鏡 ◀─BLE────────────────── AI 狀態燈 (思考中 / 播放中) / 心跳 │  手機 App (CyanMind)
 眼鏡 ──HFP (SCO 16kHz)──────▶ 麥克風音訊 ──▶ STT ──▶ LLM ──▶ TTS ┘
 眼鏡 ◀─A2DP────────────────── 朗讀回覆
                                                    │
                                          網路 (OpenAI / Anthropic / Typeless)
```

## 專案結構（`android/`）

```
app/libs/glasses_sdk_20250723_v01.aar   原廠 Android BLE SDK（QCSDK）
app/src/main/java/com/andychang/cyanmind/
  CyanApp.kt                  Application，把各層接起來
  ble/GlassesManager.kt       掃描 / 連線 / 喚醒事件 / AI 狀態燈 / 心跳
  ble/GlassesBleReceiver.kt   SDK 的 GATT 生命週期回呼
  audio/HfpMicRecorder.kt     開 SCO、從眼鏡麥克風錄 16kHz PCM，靜音自動停
  audio/Speaker.kt            Android TTS 朗讀（音訊自動走到眼鏡 A2DP）
  stt/SpeechToText.kt         STT 介面；OpenAiSpeechToText / TypelessSpeechToText
  llm/ChatProvider.kt         LLM 介面；ClaudeChatProvider / OpenAiChatProvider
  data/ConversationStore.kt   對話記憶（JSON lines，永久保存）
  data/Settings.kt            API key、供應商、角色、記憶深度（DataStore）
  data/Personas.kt            內建角色（知心好友、搞笑達人…）＋自訂 prompt
  assistant/AssistantEngine.kt 流程：喚醒 → 錄音 → STT → LLM → TTS → 眼鏡狀態
  ui/                         Compose：對話（含打字欄）、眼鏡、設定
```

## 建置

1. 用 Android Studio 開啟 `android/` 目錄（AGP 8.7、Kotlin 2.0、compileSdk 35、minSdk 26）。
2. 直接 Run 到實機（BLE 和藍牙音訊模擬器都跑不了）。
3. 手機 **系統藍牙設定** 先跟眼鏡配對一次（這一步負責 HFP/A2DP 音訊）。
4. App 內「眼鏡」分頁掃描並連線（這一步負責 BLE 控制指令）。
5. 「設定」分頁填 API key、選 STT / LLM、選角色，按儲存。
6. 回到「對話」：講「Hey Cyan」或按麥克風開始說話，或直接打字。

## 已知限制 / 誠實說明

- **這份程式碼還沒在實機上編譯與測試過**（開發環境沒有 Android SDK 和眼鏡）。SDK 的呼叫方式全部對照原廠 sample 和 `javap` 反查出來的簽名，但第一次跑一定會有要調的地方。
- **HFP 麥克風音質是先天限制**：藍牙通話協定只有 16 kHz 單聲道，換辨識引擎能改善但不能根治。
- **心跳與狀態碼是推測值**：`syncHeartBeat(1)`、`aiVoicePlay(1..6, 0xF1)` 的語意來自 iOS 版標頭檔註解，Android 韌體實際行為要用實機驗證。
- **Typeless**：wire format 從官方 Python SDK 0.1.0 反推（`POST /v1/transcribe`、`Authorization: Token`），但要先拿到他們的 API key 才能驗證。
- **Claude refusal fallback** 尚未接上（Java SDK 的 `fallbacks` builder 沒查到確定簽名），目前遇到 `refusal` 會回一句固定的婉拒語。
- API key 存在 app 私有 DataStore，沒有加密；要上架請改用 Keystore。
- 原廠 SDK AAR 是私有軟體（"Contact HeyCyan for licensing"），這裡只用於個人開發。

## 下一步

- 實機跑通 BLE 連線 → 喚醒事件 → SCO 錄音 這條最小鏈路。
- Typeless WebSocket 串流（`/v1/transcribe/stream`）做邊講邊辨識。
- 雲端 TTS（更自然的聲音）。
- 對話摘要：超過設定輪數時自動壓縮成長期記憶，而不是直接截斷。
