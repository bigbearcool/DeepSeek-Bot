<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/logo-dark.svg">
    <img src="docs/images/logo.svg" alt="DeepSeek Bot" width="260">
  </picture>
</p>

# DeepSeek Bot

English | [中文](README.zh.md)

> **这是 [Fei2411/DeepSeek-Bot](https://github.com/Fei2411/DeepSeek-Bot) 的 fork。** 上游：https://github.com/Fei2411/DeepSeek-Bot 。下面三节只说明本 fork 和上游的关系；从「Install」开始是上游原文。

## 与上游的差异

以 `git log upstream/dev..feat/341-per-bot-agent-preset` 和实际 diff 为准，本 fork 独有的改动：

- **每个 Bot 单独指定 Agent 预设**（`bc0e7f6`）：Bot 可以从当前 profile 提供的预设里固定一个；改动在 `ds-bot/src/host/roster.js`、`tools.js`、`prompt.js`，详情抽屉显示所用预设，新增测试 `ds-bot/test/bot-agent-preset.test.js`。
- **完整克隆型预设模板**（`2bfc66e`）：新增 `ds-bot/dev/presets/coder.patch.yml`、`researcher.patch.yml`。
- **主 Bot 建 Bot 时按角色匹配预设**（`2dbd991`）。
- **等待卡片适配 DSH 0.2.1-rc.2 的聊天 DOM**（`32c4b37`，`ds-bot/client.js`）。
- 清理新测试里未用的变量（`754993f`）。

## 已合入的上游功能

| 日期 | 合并提交 | 上游 PR |
|---|---|---|
| 2026-10-20 | `f7b8fb5` | #363 Windows 桌面无边框窗口，Win11 启用 Mica；stylesheet 加载后重测 caption 颜色；右侧栏避开 caption 带 |
| 2026-10-20 | `d02f761` | #354 macOS 通用包单独报错（Stop a universal macOS build with its own message）<br>#355 macOS 加 File 菜单，Cmd+W 关窗<br>#358 发布演练把安装包保留为 workflow 产物<br>#359 发布演练打包不再访问 registry<br>#360 首次运行中途退出时什么都不写入<br>#361 会话要等 DSH 列表里有了才打开 |
| 2026-10-19 | `1e7853b` | #345 Bot 的 working_directory 改到自身文件夹之外时，作为新的工作目录<br>#348 架构与当前 Node 不一致时停止桌面包构建 |

## 手机 App

在手机上使用 DS Bot 的配套方案，由两部分组成：

- **dsh-mobile-bridge**（DSH 插件，当前 0.0.10）：给手机提供接口，新建 Bot、建群等直接调用 ds-bot 现有接口，校验和桌面一致。
- **Android App**（当前 0.1.5，Kotlin + Compose）。

**功能**：Bot 单聊和群聊列表；查看历史、发消息、流式回复；「思考中」等待卡片；Markdown 渲染；浅色 / 深色主题；断线自动重连并补回漏掉的消息；在手机上新建 Bot（字段与桌面一致）和发起群聊；配对后直接进入上次的聊天。

**配对方式**：

- 插件另开一个**设备监听端口 3081**，只开放手机需要的接口；除健康检查、配对和换票外都要求设备 token，配对和换票按来源限频，配对面板等电脑端功能在 3081 上不可见。
- 在 DSH 里打开「手机配对」面板，显示**一次性配对码**（带倒计时）和**二维码**；手机扫码或输入配对码完成配对。
- 默认使用局域网地址；需要外网使用时，在设置里填写「**公网访问地址**」（经你自己的反向代理转到 3081），二维码里会带上这个地址。

**安装**：

1. 在运行 DSH 的机器上：`dsh plugin add <dsh-mobile-bridge-x.y.z.tgz>`，启用后重启 DSH。
2. 在 Android 手机上安装 APK，打开后扫码配对。

**已知限制**：

- 群聊历史只显示最近 60 行。
- 还没有在真机上做完整测试。
- 停止生成、未读数、在手机上审批、会话按最近活动排序、附件和图片、工具执行过程展示、Bot 设置编辑、消息通知等还没做。

> 插件和 App 的源码目前**没有发布在本仓库**，这一节只做介绍。

---


![version](https://img.shields.io/badge/version-v0.1_preview-blue) ![DSH](https://img.shields.io/badge/DSH-0.2.1--rc.2-4D6BFE) ![license](https://img.shields.io/badge/license-Apache_2.0-green)

DeepSeek Bot (DS Bot) is a bot plugin for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (DSH). It turns DSH into bots. You create your own bots, then talk and work with them.

![Say one sentence to Chief, and it sets up the bots and the group chat](docs/images/team.png)

## Install

First install DSH and set up at least one model in it. DS Bot uses the models from DSH.

**DSH Desktop (Windows, macOS)**

1. In the sidebar, select **Plugins** → **Add plugin**.
2. In **Package name or address**, enter `ds-bot`. In mainland China, set **Registry** to **Mainland China mirror**.
3. Select **Install**, then **Enable now**. If DSH says the change takes effect at the next start, quit DSH completely and open it again.

If npm is not reachable, in step 2 you can also enter the full path of `ds-bot-0.1.7.tgz` downloaded from [Releases](https://github.com/Fei2411/DeepSeek-Bot/releases), or `github:Fei2411/DeepSeek-Bot#v0.1.7&path:/ds-bot` (needs Git).

**DSH Web (Windows, macOS, Linux)**

Needs Node.js `22.19` or later, and pnpm.

```sh
npx @deepseek-ai/dsh@0.2.1-rc.2 plugin --profile web add ds-bot
npx @deepseek-ai/dsh@0.2.1-rc.2 web
```

**Docker (NAS, Linux server)**

The [`docker/`](docker/) directory builds an image with DSH Web and DS Bot, for a NAS or any always-on machine. CI is configured to publish a prebuilt linux/amd64 image to GHCR. The container is the Bots' sandbox: they get full access to what you mount into it, and nothing else. See [`docker/README.md`](docker/README.md).

## Setup

I strongly recommend that you set up your other bots and group chats by just talking to a bot.

## Features

### Bot

You can do all your talking and work with one bot, in one window. You don't need to worry about the context behind it. The bot's details page shows its info, and there you can pick the model you want it to use. You can switch models at any time, but I still recommend that you keep one model for a bot for a long time, and switch only when you really need to.

Bots also have memory. A bot remembers what you like and uses it in later work. You can manage its memory on the bot's details page, or just tell the bot.

![Bot details: model, memory, usage](docs/images/details.png)

### Main bot

The main bot is your chief. It has very high rights: it manages the other bots and the group chats. The first time you open DS Bot, there is already a main bot called Chief. Talk to it, and it walks you through the setup.

In short: have a question? Ask the main bot.

### Group chats

DS Bot has group chats. A group chat is a great way to get several bots to work together, but it clearly costs more. So DS Bot does this: every group chat has an admin. The admin answers your messages by default and calls in the other bots for you. You can also @ a bot directly, and it answers you directly: type @ and pick the bot from the list. To give the bots another chat as context, use + → Reference a chat in the message box.

Any bot can hand work to another bot with @. That bot does the work on its own, and the group goes on. While it works, it keeps the group posted, and the group, the sidebar and its own chat show what it is doing. When it is done, it posts the result in the group, and the bot that asked for the work answers. To stop the work, open the group's details.

![Group chat: the admin, Chief, hands the work to Writer and Coder](docs/images/group.png)

### Capabilities

Settings → **Capabilities** is a shared library of MCP servers, skills and packs. Add a capability to a Bot in its settings; it is available from that Bot's next message in its own chat and group chats. A local MCP command that a Bot adds needs your approval before it can run. While other Bots use a capability, only you or a Main Bot can change or delete it.

### Usage

In **Settings** → **Usage**, you can see token usage by time, by bot and by model.

![Usage page](docs/images/usage.png)

## Data

All data stays on your own computer, in `bot/` and `sessions/` in the DSH home folder (`~/.dsh` by default). Uninstalling the plugin does not delete this data.

## Development

The plugin source is in [`ds-bot/`](ds-bot/). For options and internals, see [`ds-bot/README.md`](ds-bot/README.md). For development, tests and how the team works, see [CONTRIBUTING.md](CONTRIBUTING.md). For changes between versions, see [CHANGELOG.md](CHANGELOG.md).

## Acknowledgments

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness): DS Bot is built on the DSH plugin system. Every bot works in DSH sessions and uses the DSH tools and models.
- [esbuild](https://esbuild.github.io/): bundles the plugin's interface code.
- Thanks to these projects for the ideas they published. DS Bot learned from their designs and does not contain their code:
  - [AgentMemoryRepo](https://github.com/AgentMemoryRepo/agentmemoryrepo): the Markdown format of Bot memory.
  - [OpenAI Codex](https://github.com/openai/codex): checkpoints that keep the user's messages next to the summary, and message anchors for chat search.
  - [OpenMausBot](https://github.com/milind-soni/OpenMausBot): group chat rounds and @mention relays.
  - [Hermes Agent](https://github.com/NousResearch/hermes-agent) and [Hermes Bot Mode](https://github.com/NousResearch/Hermes-Bot-Mode): one session per member per group chat, and handoffs that carry the earlier user messages.
  - [OpenCode](https://github.com/sst/opencode), [goose](https://github.com/block/goose), [pi](https://github.com/badlogic/pi-mono) and [Gemini CLI](https://github.com/google-gemini/gemini-cli): when to condense a long chat, and what a handoff note holds.
  - [obelisk](https://github.com/tommy0103/obelisk): chat search with a SQLite full-text index.
- Everyone who tries DS Bot, asks questions and reports bugs.

## License

[Apache License 2.0](LICENSE). Provided "as is", without any warranty. Third-party code and its license are listed in [NOTICE](NOTICE).

## Disclaimer

DS Bot is an independent community project. **It is not an official DeepSeek product**, and it is not affiliated with, endorsed by or sponsored by DeepSeek. "DeepSeek", "DeepSeek Harness" and the other names are trademarks of their owners. They are used here only to say what this plugin works with.

Bots use your own model quota, and they read and write files and run commands when you ask them to. Check important actions yourself.
