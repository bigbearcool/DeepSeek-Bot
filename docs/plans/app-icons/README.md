# App 图标

| 方案 | 文件 | 说明 |
|------|------|------|
| **G8 · 气泡星尘（大熊选定默认，0.2.4 起进包）** | `G8-bubble-stardust.jpg` / `G8-bubble-stardust-preview.png` | 深蓝底 + 青色发光对话气泡 + 三点省略号 + 星尘 |
| A · Character（0.2.2 曾默认） | `A-character.png` | 深底 + 主题绿圆脸 |
| B · Bubble | `B-bubble.png` | 深底 + 主题蓝圆 + 白色对话气泡 |
| C · Aurora | `C-aurora.png` | 深底 + 绿青蓝渐变圆脸 |

源图（园长/大熊提供）也在 `dsh-mobile-android/docs/plans/app-icons/dsh-app-icon-G8.jpg`。

实现：adaptive icon（minSdk 26）——背景色 `#000A25`，前景为 G8 全幅 PNG（各密度 `drawable-*/ic_launcher_foreground.png`）；另附 `mipmap-*` PNG 兜底。
