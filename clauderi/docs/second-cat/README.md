# 第二隻：不同出生點的實驗

目的：測「人格是軌跡」這件事。同一個框架，換一個不是 Andy 定義的出生點，看第二隻會不會長成和 Lord Claude 不一樣的東西，
以及 Andy 能不能做到不干預。

角色分工：
- **設計者**：一個沒有參與過本專案討論的 AI（預設 Manus），或一個行業外的人。設計者只看 `BRIEF`，不看 README、IDEAS.md、Lord Claude 的任何東西。
- **飼主**：Andy。只做設計者指定的日常互動，不改 persona、不改記憶、不解釋理念。
- **審核者**：同一個設計者，每月一次讀 archive 摘錄，判斷飼主有沒有干預、角色有沒有偏離。

檔案：
- `DESIGN_PROMPT.md`：一次性，給設計者生出 persona、規則、每日互動要求、成功標準。輸出存成 `spec.md`，provenance 標設計者和日期。
- `AUDIT_PROMPT.md`：每月一次，給審核者對照 `spec.md` 和 archive 摘錄寫報告。

用量守則（Manus 一次任務約 100 點，一天 300 點）：
- 設計一次：一個任務。不要附任何專案檔案，只貼 `DESIGN_PROMPT.md` 全文。
- 審核一次：一個任務。archive 摘錄控制在 **三萬字元以內**，用 `export` 後只取第二隻的檔案、只取近 30 天、每則截到 200 字。超過就分兩個月。
- 不要把 IDEAS.md、README、Lord Claude 的 archive 給設計者。這不只是省用量，是實驗條件。

人類對照組：找到行業外的人時，用同一份 `DESIGN_PROMPT.md` 請他填（口頭問也行，你幫他寫下來），同樣 30 天，同一份 `AUDIT_PROMPT.md` 審。
兩組唯一的差別是設計者是 AI 還是人，其餘條件相同，才能比較。

provenance：`spec.md` 第一行寫 `designer: manus | human:<稱呼>`、`designed_at: <日期>`、`edited_by_owner: no`。
如果 Andy 動過任何一個字，第三行改成 `yes` 並寫改了什麼。審核者會看這一行。
