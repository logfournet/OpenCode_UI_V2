# OpenCode UI V2 (plugin IntelliJ)

Plugin JetBrains kết nối IDE với **OpenCode CLI 2.x** chạy dưới dạng **background
service** (`opencode serve --service`). Giao diện giống plugin 1.x: click icon
**OpenCode** → mở tab **OpenCode TUI** trong editor area, kèm link click được
`@path#Lx-y`, gửi file/selection làm context, và diff viewer Accept/Reject.

> Thay thế plugin cũ OpenCode_UI 1.1.0 — bản cũ tự spawn server riêng bằng
> `--hostname/--port` theo từng project nên hay bị **"server dead"**. Bản V2 chỉ
> **kết nối tới background service duy nhất** (đọc `service.json`) nên không còn
> hiện tượng đó; có auto-reconnect khi service tạm restart.

## Tính năng

- Tự tìm service đang chạy: đọc `service.json`
  (Windows: `%LOCALAPPDATA%\opencode\state\service.json`,
  hoặc `~/.local/state/opencode/service.json`) → URL + password (Basic Auth).
- Click icon **OpenCode** (hoặc `Ctrl+\` / `Cmd+Esc`) → mở **tab OpenCode TUI**
  trong editor area, chạy lệnh `opencode` (TUI tự kết nối background service;
  session theo thư mục project).
- Nhiều IntelliJ / nhiều project dùng chung 1 service, mỗi project một session.
- Phím tắt:
  - `Ctrl+\` (macOS `Cmd+Esc`) — mở/focus tab OpenCode TUI.
  - `Ctrl+Alt+K` — gửi file/selection làm context: `@path` hoặc `@path#Lx-y`
    (paste vào TUI input).
- Click `@path#Lx-y` trong TUI → mở file + highlight dòng.
- Theo dõi `GET /api/event` (SSE, tự reconnect) + poll `/api/session/active`
  để biết session chạy xong turn.
- Khi turn kết thúc: `GET /api/session/{id}/diff?from=<userMsgId>` → mở diff
  viewer native; nút **Accept** (ghi file + `git add`) / **Reject** (khôi phục
  "before", dựng bằng reverse-apply unified patch lên nội dung hiện tại).
- **Auto-reconnect**: ≥3 lần poll lỗi liên tiếp → tự re-discover `service.json`
  và kết nối lại, không cần bấm nút.

## Build

Dùng **IntelliJ Platform Gradle Plugin 2.10.2** (JDK 17 target).

```powershell
cd newplugin
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12"
.\gradlew.bat buildPlugin
```

Kết quả:

- `build\distributions\OpenCode_UI_V2-2.0.0.zip` ← file cài đặt (cấu trúc `OpenCode_UI_V2/lib/*.jar`)
- `build\libs\OpenCode_UI_V2-2.0.0.jar`

Bản build sẵn cũng có trong thư mục `dist/` của repo.

## Cài đặt

1. IntelliJ: **File → Settings → Plugins → ⚙️ → Install Plugin from Disk…**
2. Chọn `OpenCode_UI_V2-2.0.0.zip` (hoặc file trong `dist/`).
3. **Restart IDE.**
4. Bấm icon **OpenCode** (hoặc `Ctrl+\`):
   - Plugin tự đọc `service.json` → kết nối service đang chạy → mở TUI.
   - Chọn code → `Ctrl+Alt+K` để gửi context.
   - Khi OpenCode sửa file, diff viewer mở ra với Accept/Reject.

## Cấu trúc

```
src/main/java/ai/opencode/ide/jetbrains/
  api/        OpenCodeApiClient (java.net.http + Gson), OpenCodeEventStream (SSE)
  patch/      UnifiedPatch   (reverse-apply unified diff → nội dung "before")
  session/    SessionManager (snapshot file bị đổi trong turn), FileDiff, SessionInfo
  diff/       DiffViewerService, DiffHandler (Accept/Reject), ResolvedDiff
  service/    OpenCodeService (kết nối, auto-reconnect, session tracking, terminal TUI)
  ui/         OpenCodeToolWindowFactory (icon → mở terminal TUI)
  terminal/   OpenCodeTerminalVirtualFile/FileEditor/Provider/LinkFilter/Listener
  actions/    QuickLaunch, SendSelection, Interrupt, OpenDiffs
src/main/resources/META-INF/plugin.xml, icons/opencode.svg
```

## Ghi chú API 2.0.26 (đã xác minh trực tiếp trên server thật)

- Basic auth `opencode:<password>` (password từ `service.json`); thiếu auth → 401.
- Envelope mọi endpoint `/api/*`: `{"data": {...}}`.
- `POST /api/session/{id}/prompt` body `{"text": "..."}` (bắt buộc `text`),
  response `data.id` = user message id.
- `GET /api/session/{id}/diff?from=<userMsgId>` → `data[]`:
  `{file, patch, additions, deletions, status: added|deleted|modified}` —
  KHÔNG còn `before/after` như v1 → viewer tự dựng "before" từ patch.
- `GET /api/session/active` → `{"data": {"ses_...": {"type":"running"}}}`.
- SSE `/api/event`: `data: {"id":"evt_...","type":"session.tool.success","data":{...}}`;
  luồng volatile → plugin tự reconnect và poll dự phòng.
