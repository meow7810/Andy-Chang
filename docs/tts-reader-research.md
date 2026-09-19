# 文件有聲閱讀器：Phase 1 現成方案調查（2026-09）

## 結論

**不需要自己寫 App。** 三個平台各有一個現成工具已覆蓋約 85–90% 的需求，
而且都是「用系統 TTS、離線、記住每份文件進度、一次性付費或免費」：

| 你的平台 | 建議直接用 | 費用 | 契合度 |
|---|---|---|---|
| Android | **@Voice**（Hyperionics，舊名 @Voice Aloud Reader） | 免費 + 一次性 Premium License 去廣告（約 US$7–15，未能直接查證店內即時價格） | ~80–85% |
| iPhone / iPad | **Speech Central**（LabSii） | 一次性約 US$9–10，無訂閱 | ~85% |
| Windows | **Balabolka**（Cross+A） | 免費 | ~85% |

系統內建功能（iOS 朗讀螢幕、Android 選取朗讀、Word 大聲朗讀、Edge 朗讀）
**全部不及格**，主因是：鎖屏就停、不記位置、長文件中途停、或不吃 DOCX/TXT。

只有在下面兩種情況才值得進 Phase 2/3 做 MVP：
1. 你實測後覺得 @Voice / Speech Central 的 UI 太複雜，無法接受「開檔 → Play → 放口袋」以外還要碰任何設定。
2. 你想要 Podcast 風格的極簡介面，並且願意為此付出一個週末以上的開發時間。

---

## 逐項需求比較

✅ 有  ⚠️ 有但有限制或未經直接驗證  ❌ 無

| 需求 | @Voice (Android) | Speech Central (iOS) | Balabolka (Windows) | Moon+ Reader Pro (Android) | Voice Dream (iOS) | 系統內建（iOS 朗讀螢幕 / Android 選取朗讀） |
|---|---|---|---|---|---|---|
| 匯入 DOCX | ✅ 原生 | ✅ | ✅ | ✅ | ✅ | ❌ 只讀畫面上的字 |
| 匯入 TXT | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| EPUB / PDF | ✅ / ✅ | ✅ / ✅（含 OCR） | ✅ / ✅ | ✅ / ✅ | ✅ / ✅ | ❌ |
| 原文逐字朗讀（無 AI 改寫） | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 繁體中文 TTS | ✅ 用 Google/Samsung 系統語音 | ✅ 官方列出繁中；用 iOS 系統語音 | ✅ 用 Windows 中文(台灣) Hanhan/Yating/Zhiwei | ✅ 系統語音 | ⚠️ 只確認簡中 | ✅ |
| 英文 TTS | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Play / Pause / 前進 / 後退 | ✅ 句 / 段 | ✅ 句 / 段 / 章 + 鎖屏「倒退 30 秒」 | ⚠️ 句 / 段 / 書籤，無「倒退 N 秒」 | ✅ | ✅ | ⚠️ 只有基本控制 |
| 語速 / 換聲音 | ✅ / ✅ | ⚠️ 固定倍率 1.5x/2x/3x / ✅ | ✅ / ✅ | ✅ / ✅ | ✅ / ✅ | ✅ / ⚠️ |
| 記住每份文件進度、關掉重開可續播 | ✅ | ✅（可 iCloud 同步） | ✅ | ✅ | ✅ | ❌ 完全不記 |
| 長文件分段自動接續 | ⚠️ 依設計是逐句餵給系統 TTS，應無上限，但數十萬字的極端情況無人實測 | ⚠️ 同上 | ✅ 為整本書設計 | ⚠️ 論壇有「TTS 常中途停」的抱怨 | ✅ | ❌ 常在翻頁 / 章節處停住 |
| 螢幕關閉繼續播 | ✅ | ✅ | 不適用（電腦） | ✅ | ✅ | ❌ iOS 鎖屏即停且不記位置；Android「背景朗讀」標示為實驗性 |
| 鎖定畫面 / 耳機控制 | ✅ 含藍牙、車機按鍵 | ✅ 含 AirPods 手勢自訂 | 不適用 | ✅ | ✅ | ❌ |
| 顯示 / 點選目前段落 | ✅ / ✅ 雙擊句子從該處開始 | ✅ / ⚠️ 段落層級 | ✅ / ✅ 游標處開始 | ✅ / ✅ | ✅ / ✅ | ⚠️ / ❌ |
| Sleep Timer | ✅ 含搖晃重置 | ✅ | ✅ 定時器 | ✅ | ✅ | ❌ |
| 書籤 | ✅ 可用耳機鍵加書籤 | ✅ 段落層級 | ✅ | ✅ | ✅ | ❌ |
| 文件內搜尋 | ⚠️ 未確認 | ⚠️ 有，但有無障礙相關抱怨 | ✅ | ✅ | ✅ | ❌ |
| 完全離線 | ✅ | ✅ 預設；雲端語音為選配 | ✅ | ✅ | ✅ | ✅ |
| 隱私（文件不上傳） | ✅ 免費版有廣告網路請求 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 收費模式 | 免費 + 一次性去廣告 | 一次性 ~US$9–10 | 免費 | 一次性 ~US$6–12 | **訂閱 ~US$60–80/年** | 免費 |
| 操作複雜度 | 中：功能多、UI 偏工具風 | 中：功能多、UI 不算精緻 | 中：老派 Windows 工具 | 高：完整電子書閱讀器，TTS 是附屬模式 | 低 | 低 |
| 維護狀態 | 2026-06 仍在更新（v40.x） | 活躍，開發者部落格持續更新 | 持續更新 | 活躍 | 活躍 | — |

### 被淘汰的候選（及原因）

- **Speechify、NaturalReader、Readwise Reader、ElevenReader**：訂閱制（US$100–140/年）且好聲音都走雲端，文件會上傳，違反隱私原則。
- **Voice Dream Reader**：功能最完整，但 2024 年起新用戶只能訂閱（約 US$60–80/年），且未能確認繁中支援。
- **Capti Voice**：有繁中，但評論指出鎖屏後無法從控制中心暫停，且改為訂閱 + 代幣制。
- **Apple Books + 朗讀螢幕**：Books 不吃 DOCX；朗讀螢幕鎖屏即停、不記位置，論壇上要靠技巧才能「幾乎」連續朗讀。
- **Google Play 圖書「朗讀」**：自行上傳的 EPUB 常出現「無法播放語音朗讀」，功能由出版商授權門控。
- **Word 手機版「大聲朗讀」**：內建方案中最接近（鎖屏可續播、有鎖屏控制），但不記播放位置、長文件中途停的抱怨多，且不吃 TXT。
- **Microsoft Edge 朗讀**：不吃 DOCX/TXT，2019 年起不支援 EPUB，自然語音需連網。
- **Lithium、Sumatra PDF**：無 TTS。
- **開源專案**（KOReader、Librera、Episteme、Lector、以及 GitHub 上五六個 0–10 星的小專案）：
  KOReader / Librera 能讀 DOCX 且記位置，但 TTS 是外掛性質、UI 為電子書閱讀器設計；
  小專案沒有使用者基礎，不建議當日常工具。Lector 隱私最好但不支援 DOCX。
- **ebook2audiobook / epub2tts / OpenReader**：是「批次轉 MP3」或自架伺服器的管線，不是隨按隨聽的閱讀器。

---

## 各平台建議與 10 分鐘實測清單

### Android → @Voice

1. 安裝 Google Play 的 **@Voice: Text to Speech Reader**（package `com.hyperionics.avar`）。
2. 免費版 2025 年評論抱怨廣告有假關閉鍵、會跳轉商店；建議直接買 **@Voice Premium License**（`com.hyperionics.avarLic`，一次性）。
3. 設定 → 無障礙 → 文字轉語音 → Google 文字轉語音 → 安裝語音資料 → 下載「中文（台灣）」離線語音。
4. **Samsung 注意**：Android 15 / One UI 7 起，Samsung 自家語音不再開放給第三方 App，請改用 Google 文字轉語音引擎。
5. 實測：丟一份你最長的 DOCX，按 Play，鎖屏放口袋 30 分鐘，看是否不中斷；強制關閉 App 再開，看是否回到同一句。

### iPhone → Speech Central

1. App Store 安裝 **Speech Central**，一次性解鎖約 US$9–10。
2. 設定 → 輔助使用 → 朗讀內容 → 語音 → 中文（台灣）→ 下載「進階」或「優質」語音。
3. 實測同上；另外確認「點段落從該處開始」與長中文文件連續播放兩項（研究中未能直接查證）。

### Windows → Balabolka

1. 從 cross-plus-a.com 下載 Balabolka（免費）。
2. 設定 → 時間與語言 → 語音 → 新增語音 → 中文（台灣）。
3. 若想在手機上聽：Balabolka 可批次匯出 MP3，再用 Smart AudioBook Player（Android）或 BookPlayer（iOS，開源）播放，兩者都記位置、有睡眠計時。代價是轉出後不能再改語速/聲音、沒有文字高亮。

---

## TTS 引擎：系統語音夠不夠？

**夠。** Google 文字轉語音的離線「中文（台灣）」語音、iOS 的進階/優質語音、Windows 11 的中文(台灣)語音都能長時間聆聽，且零整合成本。

如果之後覺得不夠自然，**最省事的升級路徑**（不用改任何 App）：
- Android：安裝 **sherpa-onnx TTS Engine APK**（k2-fsa 專案），載入 `vits-melo-tts-zh_en`（中英混讀，約 346 MB）或 `kokoro-multi-lang-v1_1` 模型，然後在系統設定把它選成預設 TTS 引擎。@Voice、KOReader 等任何用系統 TTS 的 App 立刻受惠。中階以上手機可即時合成。
- 不建議：edge-tts（需連網、違反 Microsoft 條款灰色地帶）、XTTS / F5-TTS / CosyVoice / Fish Speech / GPT-SoVITS / Qwen3-TTS（需要 GPU，手機跑不動即時；部分授權禁商用）。
- Piper 只有簡中語音，沒有繁中；Kokoro 的中文資料以簡中為主，繁體字輸入需驗證。

---

## 若之後仍決定做 MVP（Phase 2 預先結論）

- 平台：**Android 原生 Kotlin**。理由：這個 repo 已有 Android 專案與 `Speaker.kt`（Android TTS）可沿用；Android `TextToSpeech` + `MediaSessionService` 的背景播放/鎖屏控制最直接；不需 Apple 開發者帳號。
- 架構：DOCX 用 Apache POI 或直接解壓 `word/document.xml` 抽 `<w:t>`；TXT 直接讀；以句號/換行切成句子，一次只餵 1–2 句給 `TextToSpeech.speak()`，用 `UtteranceProgressListener` 串接下一句；Room 存 `(docId, sentenceIndex)`；前景服務 + MediaSession；語速用 `setSpeechRate`，聲音用 `getVoices()`。
- 預估：一個週末可做出可用版本。但這只有在你實測現成方案後仍不滿意時才值得做。

---

## 研究方法與限制

- 本次環境無法直接開啟 Google Play、App Store、PTT 等網站（網路代理阻擋），資料來自搜尋引擎摘要、官方網站、GitHub、MobileRead / AppleVis / Reddit 論壇。
- 未能直接驗證：@Voice Premium 的即時價格；@Voice 與 Speech Central 對「數十萬字單一文件」的表現；Speech Central 的點段落跳讀。這三項都列入上面的實測清單。
- 台灣社群（PTT、奇奇筆記等）另有推薦「靜讀天下」（Moon+ Reader 中文名，台灣腔語音）、「TXT 听书」、「Aloud! 說書人」（iOS，App Store 台灣區有繁中頁面，免費 + 內購）；這些與上表結論一致，Aloud! 未能查到定價細節。

## 主要來源

- @Voice：https://hyperionics.com/atVoice/ 、https://hyperionics.com/atVoice/pricing.asp 、https://accessibleandroid.com/app/voice-aloud-reader/
- Speech Central：https://speechcentral.net/ 、https://speechcentral.net/2025/05/13/elevenreader-introduces-100-year-subscription-with-strict-limits-speech-central-offers-unlimited-use-for-10-lifetime/ 、https://www.applevis.com/apps/mac/utilities/speech-central-text-speech
- Balabolka：https://www.cross-plus-a.com/balabolka.htm 、https://www.callscotland.org.uk/blog/using-balabolka-to-read-e-books/
- Voice Dream 訂閱爭議：https://www.perkins.org/resource/voice-dream-reader-subscription-controversy/ 、https://mjtsai.com/blog/2024/04/08/voice-dream-reader-switches-to-subscriptions/
- Moon+ Reader TTS 問題：https://www.mobileread.com/forums/showthread.php?t=313072
- iOS 朗讀螢幕限制：https://www.applevis.com/forum/ios-ipados/apple-books-speak-screen 、https://discussions.apple.com/thread/254777900
- Android 選取朗讀：https://support.google.com/accessibility/android/answer/7349565
- Word 手機版鎖屏朗讀：https://office-watch.com/2022/read-aloud-from-locked-screens-on-iphone-and-android/ 、https://learn.microsoft.com/en-us/answers/questions/5580777/read-aloud-keeps-stopping-mid-sentence
- Play 圖書自行上傳無法朗讀：https://support.google.com/googleplay/thread/43933727/google-play-books-read-aloud-unavailable
- Samsung TTS 在 One UI 7 的變化：https://speechcentral.net/2026/03/22/samsung-tts-missing-on-android-15-one-ui-7-8-whats-really-happening/
- sherpa-onnx TTS Engine APK：https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html 、https://github.com/k2-fsa/sherpa-onnx
- Kokoro 中文：https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh ；MeloTTS：https://github.com/myshell-ai/MeloTTS
- Android 上 Kokoro 效能實測：https://eist.app/blog/neural-tts-speed-across-800-android-devices
- KOReader：https://github.com/koreader/koreader ；Librera：https://github.com/foobnix/LibreraReader ；Episteme：https://github.com/Aryan-Raj3112/episteme ；Lector：https://github.com/QuantEmber/lector
- BookPlayer（iOS 開源有聲書播放器）：https://github.com/TortugaPower/BookPlayer
- 台灣社群討論：https://www.ptt.cc/bbs/CFantasy/M.1653362950.A.E8A.html 、https://kikinote.net/137279 、https://manread.substack.com/p/app-android-ios
