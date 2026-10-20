# 手机端功能差距清单（对照 Grok Bot 手机 App）

日期：2026-10-20　｜　基线：App 0.1.5 + 插件 0.0.10　｜　只是清单，本轮不实现

状态：已有 / 部分 / 缺。优先级：P0 = 不做就不好用，下一版就做；P1 = 明显影响体验，紧随其后；P2 = 锦上添花。
「可复用」一栏只列 ds-bot / dsh 已经有的接口或能力，原则和以前一样：复用现成接口和校验，不另写一套；新增写接口必须带设备 token。

| # | 功能 | 状态 | 我们现在 | ds-bot / dsh 已有、可复用 | 优先级 | 理由 |
|---|---|---|---|---|---|---|
| 1 | 会话列表（Bot + 群聊，按最近活动排序、最后一条预览） | 已有（0.1.6 / 0.0.11） | 列表有 Bot 和群聊，群聊有预览；不按最近活动排序，Bot 没有预览 | ds-bot `state`（rooms 的 preview、bots 的 sessionId/parts）、`live.activity`；`chat` 分页取最后一条 | P0 | 进 App 第一眼就是它，找不到最近在聊的对象最影响使用 |
| 2 | 未读数 / 未读红点 | 已有（0.1.6 / 0.0.11） | 无 | ds-bot `state` 的 `revision` + 群聊 `seq`；单聊用 Session 事件 seq。已读位置需要存在插件里（按设备） | P0 | 多个 Bot、多个群同时在干活时，不知道谁回了话就只能挨个点开 |
| 3 | 群聊 | 部分 | 0.1.5：列表、历史（最近 60 行）、发消息、流式回复、成员失败/没人回复时结束等待；能建群 | `update-room`（改名、成员、管理员、模式）、`delete-room`、`stop-member-work`、`room-progress`（本轮进行中谁在说）、`@名字` 规则（shared/mentions.js） | P1 | 主流程通了；群设置、@成员、看更早的记录还缺 |
| 4 | 停止生成 | 已有（0.1.6 / 0.0.11） | 无 | dsh `sessionController.cancel`（单聊）；ds-bot `stop-member-work`（群里停某个成员） | P0 | Bot 跑偏或跑很久时，手机上没有任何办法让它停，只能去电脑上 |
| 5 | 工具执行过程展示（在读文件、跑命令、搜索…） | 部分 | 单聊只显示「思考中」和最终回复；工具调用不展示 | Session 事件 `tool/call`、`tool/result`（插件已在监听）；ds-bot `live.work`（每个 Bot 当前在用的工具）；dsh-agent-tool-presentation 的工具标题 | P1 | 长任务时用户看不到进度，容易以为卡死；但先有「停止」更要紧 |
| 6 | 审批（Bot 要执行需要确认的操作） | 已有（0.1.6 / 0.0.11；助手自己单聊里的请求仍需在电脑上确认） | 无；群里成员等审批时只显示一行提示 | ds-bot `approval-answer`、`live.work.approvals` | P0 | Bot 卡在等审批时手机上既看不到也点不了，整个任务就停在那里 |
| 7 | 附件和图片（发图、发文件、看 Bot 发回的图） | 缺 | 历史里只显示「[图片×N]」 | dsh `sessionController.attachment`、dsh-attachment / dsh-client-file-upload；ds-bot images.js（群里的图片 `imagesForGroup`）、`file-text` | P1 | 拍照提问是手机最自然的用法；要处理上传大小和带宽，工作量较大 |
| 8 | 语音输入 | 缺 | 无 | profile 里已有 `dsh-experimental-voice-input-bundle`、`dsh-experimental-speech-to-text-sensevoice`；ds-bot client/voice.js、speech.js。手机也可以先用系统自带语音键盘 | P2 | 系统键盘的语音输入已经能用，先不必自己做 |
| 9 | 搜索（搜 Bot / 群 / 聊天内容） | 缺 | 无 | `dsh-experimental-session-search`（Session 内容搜索）；名字搜索在手机本地做即可 | P2 | Bot 和群不多时列表够用；内容搜索价值高但依赖实验插件 |
| 10 | Bot 设置编辑（名字、头像、模型、提示词、工作目录…） | 缺 | 只能新建，不能改 | ds-bot `update-bot`（和桌面同一套校验）、`efforts`、`models`、`folders`、`delete-bot`、`duplicate-bot` | P1 | 新建已有，复用同一个表单改成编辑即可，成本低 |
| 11 | 消息通知（Bot 回复、需要审批时推送） | 缺 | 无；App 在后台时收不到提醒 | 插件已有事件流；真正的推送需要推送通道或常驻前台服务，dsh/ds-bot 没有现成推送 | P1 | 很有用，但要选推送方案（国内无 FCM），适合和第二阶段中转服务一起做 |
| 12 | 新建 Bot / 新建群聊 | 已有 | 0.1.4 起可新建 Bot（和桌面同字段）和群聊 | `create-bot`、`create-room` | — | 已完成 |
| 13 | Markdown、代码块 | 部分 | Markwon 渲染；代码块无复制按钮 | — | P2 | 能看，体验可再打磨 |
| 14 | 复制 / 重发 / 长按菜单 | 缺 | 无 | 复制纯本地；重发用现有 send | P2 | 小功能，顺手做 |
| 15 | 更早的聊天记录（分页） | 部分 | 单聊有分页；群聊只有最近 60 行 | 单聊 `chat` / `earlier`；群聊 state.json 只保留 60 行，更早的在各成员 Session 里 | P2 | 大多数时候看最近的就够 |
| 16 | Bot 的问题卡片（Bot 向用户提问、给选项） | 缺 | 无 | ds-bot `state.questions`、`answer-question`、`dismiss-question` | P1 | 和审批类似，Bot 问了问题而手机看不到，任务就停住 |
| 17 | 定时任务 | 缺 | 无 | ds-bot `schedules`、`schedule-create`、`schedule-delete` | P2 | 低频操作 |
| 18 | 用量 / 费用 | 缺 | 无 | ds-bot `usage`、`run-stats` | P2 | 低频 |
| 19 | 记忆查看 | 缺 | 无 | ds-bot `memory-view`、`memory-forget` | P2 | 低频 |
| 20 | 连接状态和诊断 | 部分 | 断线自动重连；0.0.10 起有 `GET /diagnostics`（带 token）给运维看 | ds-bot `diagnostics`、`diagnostics-log` | P2 | 手机上可以再加一个简单的「连接正常/异常」指示 |
| 21 | 已配对设备管理 | 部分 | 只能在电脑的配对面板管理 | 插件 `devices`、`devices/revoke`（目前只在电脑端） | P2 | 电脑上已能管理 |

## P0 小结（已在 App 0.1.6 / 插件 0.0.11 完成）

1. **会话列表按最近活动排序 + 预览**（#1）：复用 ds-bot `state` 的 preview / activity，主要是排序和展示。
2. **未读数**（#2）：插件按设备记录已读位置（群聊用 `seq`，单聊用 Session 事件 seq），列表显示红点和数字。
3. **停止生成**（#4）：单聊走 dsh `sessionController.cancel`，群聊走 ds-bot `stop-member-work`；新接口带设备 token。
4. **审批**（#6）：手机上看到「等你确认」并能允许 / 拒绝，复用 ds-bot `approval-answer`，校验全在 ds-bot。

P0 的共同点：不做的话，Bot 一旦卡住（跑偏、等审批），手机上完全没办法处理，只能回到电脑。

## 不建议现在做

- 自己做语音识别：系统键盘够用。
- 推送通知：和第二阶段中转服务一起设计（国内推送通道、App 后台保活）。

## P0 完成记录（2026-10-20，App 0.1.6 / 插件 0.0.11）

- #1 会话列表：`GET /chats/list`，助手和群聊合成一个列表，按最后一条消息时间排序，显示预览和时间。群聊预览直接用 ds-bot 的 room preview；助手单聊用 dsh `sessionController.page` 取最后几条。
- #2 未读：已读位置存在插件 store 里，按设备分开记（`devices[id].reads`）。群聊用 log `seq`，单聊用 Session 事件 seq。新配对的手机从「全部已读」开始。`POST /chats/read` 在打开对话、退出对话以及对话开着时收到新消息时调用。
- #4 停止生成：单聊 `POST /chats/stop` 调 dsh `sessionController.cancel`；群聊 `POST /rooms/stop` 对 ds-bot `work.rooms` 里正在回复的每个成员调 `stop-member-work`。
- #6 审批：`GET /approvals` 和列表一起读 ds-bot `work.approvals`，`POST /approvals/answer` 调 ds-bot `approval-answer`。限制：助手自己单聊里的请求，ds-bot 只记录、不接管（answerable=false，还是 dsh 自己的确认卡片），手机上显示「需要在电脑上确认」，也可以在手机上停止。
- 所有新接口都要设备 token，测试覆盖了没有 token 或 token 错误时返回 401。
