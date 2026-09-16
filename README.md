# Andy-Chang

兩個獨立的 Android 專案，各自用 Android Studio 開啟自己的資料夾：

| 資料夾 | 專案 | 說明 |
|---|---|---|
| [`clauderi/`](clauderi/) | **ClaudeRi** | 由使用者逐項授權的 AI 助理，對標 Siri。長按 Home 呼叫、通知朗讀與回覆、行事曆、聯絡人、傳訊息 / 鬧鐘 / 音樂、螢幕感知。每個能力一個開關，沒開的模型看不到。**目前主要開發中。** |
| [`android/`](android/) | CyanMind | HeyCyan G300 智慧眼鏡的替代 AI 大腦（需要眼鏡硬體）。 |

兩邊共用的設計：Claude（Anthropic Java SDK）為主、可切 OpenAI / DeepSeek / Qwen；對話記憶存手機本地不設上限；打字與語音共用同一份記憶。

各專案的建置步驟、能力說明和已知限制都在各自資料夾的 README。
