<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/logo-dark.svg">
    <img src="docs/images/logo.svg" alt="DeepSeek Bot" width="260">
  </picture>
</p>

# DeepSeek Bot

English | [中文](README.zh.md)

> **这是 [Fei2411/DeepSeek-Bot](https://github.com/Fei2411/DeepSeek-Bot) 的 fork。** 上游：https://github.com/Fei2411/DeepSeek-Bot 。前面的中文部分（这个仓库是做什么的 / 怎么装 / 装好后能做什么 / 与上游的差异 / 已合入的上游功能 / 手机 App）是本 fork 自己写的；从「Install」开始是上游原文。

## 这个仓库是做什么的

这是在上游 DeepSeek-Bot 基础上改的版本，让你在 DeepSeek Harness（DSH）里养一群 Bot，并且能用手机和它们聊天。仓库里主要有这几块：

- **`ds-bot/`**（当前 0.2.0）：DS Bot 插件本体。在 DSH 里创建 Bot、和 Bot 单聊、拉 Bot 进群聊。本 fork 在上游基础上加了「每个 Bot 单独指定 Agent 预设」等改动。
- **`dsh-mobile-bridge/`**（当前 0.0.19）：DSH 插件，给手机开一个专用接口，负责配对和收发消息。
- **`dsh-mobile-android/`**（当前 0.2.4）：Android 手机 App。
- **`dsh-mobile-harmony/`**：纯血鸿蒙（HarmonyOS NEXT）原生 App，功能和 Android 版对齐。
- **`app/`、`docker/`**：上游自带的桌面应用和 Docker 镜像，用法见下面的英文原文。

## 怎么装

**1. 先装 DSH 和 DS Bot**：按下面「Install」一节装好 DSH，配好至少一个模型，再装 `ds-bot`。想用本 fork 的 ds-bot，在仓库里打包：

```sh
cd ds-bot
npm pack            # 生成 ds-bot-0.2.0.tgz
```

在 DSH 的插件页里「添加插件」时填这个 tgz 的完整路径，或者用命令行 `dsh plugin add <tgz 路径>`，启用后重启 DSH。

**2. 装手机桥接插件**：

```sh
cd dsh-mobile-bridge
npm pack            # 生成 dsh-mobile-bridge-0.0.19.tgz
dsh plugin add <dsh-mobile-bridge-0.0.19.tgz 的路径>
```

启用后重启 DSH。插件会另外监听 **3081 端口**给手机用。手机不在同一个局域网时，要用你自己的反向代理把例如 `https://example.com:8443` 转到这台机器的 3081。

**3. 打手机 App**：需要 JDK 21 和 Android SDK。

```sh
cd dsh-mobile-android
./gradlew assembleDebug
```

装包在 `app/build/outputs/apk/debug/app-debug.apk`，传到 Android 手机上装好。

鸿蒙手机：用 DevEco Studio 打开 `dsh-mobile-harmony/`，登录你自己的华为开发者账号开自动签名，编出签名的 HAP 后用 `hdc install -r <hap 路径>` 装到手机（详见下方「手机 App」）。

**4. 配对**：在 DSH 网页左侧栏底部点「**手机配对**」，会弹出一个 6 位配对码（约 5 分钟有效）和二维码。打开手机 App，扫码或输入配对码就连上了。要在外网用，在同一个「手机配对」弹窗下方填写并保存「公网访问地址」（例如 `https://example.com:8443`，只有登录了 DSH 的网页端能改），之后二维码里就会带上这个地址；不填就用自动找到的局域网地址。

## 装好后能做什么

- 在电脑上建 Bot、给每个 Bot 选不同的预设和模型，拉几个 Bot 组群一起干活。
- 用手机看所有 Bot 和群聊，看历史、发消息，回复会一个字一个字地出来，可以中途停止。
- 群聊里输入 `@` 会弹出本群 Bot：不 @ 时所有 Bot 都回，@ 了谁就只有谁回。
- 在手机上新建 Bot、发起群聊、给 Bot 换模型，换完马上生效。
- 在手机上处理 Bot 发来的审批请求。
- 发图片和文件给 Bot，也能把 Bot 发来的文件存到手机上。
- 新消息和待审批会弹系统通知，可以总开关，也可以按会话单独关。
- 会话可以置顶、隐藏，显示未读数；支持浅色和深色主题；断线后会自动重连并补回漏掉的消息。

## 与上游的差异

以 `git log upstream/dev..feat/341-per-bot-agent-preset` 和实际 diff 为准，本 fork 独有的改动：

- **每个 Bot 单独指定 Agent 预设**（`bc0e7f6`）：Bot 可以从当前 profile 提供的预设里固定一个；改动在 `ds-bot/src/host/roster.js`、`tools.js`、`prompt.js`，详情抽屉显示所用预设，新增测试 `ds-bot/test/bot-agent-preset.test.js`。
- **完整克隆型预设模板**（`2bfc66e`）：新增 `ds-bot/dev/presets/coder.patch.yml`、`researcher.patch.yml`。
- **主 Bot 建 Bot 时按角色匹配预设**（`2dbd991`）。
- **等待卡片适配 DSH 0.2.1-rc.2 的聊天 DOM**（`32c4b37`，`ds-bot/client.js`）。
- 清理新测试里未用的变量（`754993f`）。
- **ds-bot 0.1.9：去掉「连接插件」的红色叹号角标**（`c2d8378`，`ds-bot/client.js`、`src/client/sidebar.js`、`src/client/styles.js`）。
- **移除预设里的 `{{cwd}}` 提示词变量**：避免群聊轮次报 `unknown prompt variable`。
- **新增手机端**：`dsh-mobile-bridge/`（DSH 插件）和 `dsh-mobile-android/`（Android App），见下方「手机 App」。
- **新增鸿蒙原生 App**：`dsh-mobile-harmony/`（ArkTS + ArkUI），协议沿用 dsh-mobile-bridge，见下方「手机 App」。

## 已合入的上游功能

| 日期 | 合并提交 | 上游 PR |
|---|---|---|
| 2026-10-20 | `37b58ba` | #370 打开 Bot 对话时直接定位到最新消息<br>#371 从其他会话视图恢复返回聊天的入口<br>#374 窄屏下选中会话后自动收起侧栏，重复导航时保持收起<br>#372 聊天历史加载期间等待卡片保持可用，交接时保留卡片里已填的输入 |
| 2026-10-20 | `053ef42` | #366 可在 DS Bot 设置里修改团队工作区和数据位置（桌面应用可迁移数据目录）<br>#367 发布 0.2.0：版本号、changelog 和桌面应用安装说明<br>#368 普通会话引导使用可用的提问工具<br>#369 转发消息正文里不再带方括号的 Bot 名 |
| 2026-10-20 | `4b722f6` | #365 macOS 应用改为 zip 打包发布（不再使用 dmg） |
| 2026-10-20 | `f7b8fb5` | #363 Windows 桌面无边框窗口，Win11 启用 Mica；stylesheet 加载后重测 caption 颜色；右侧栏避开 caption 带 |
| 2026-10-20 | `d02f761` | #354 macOS 通用包单独报错（Stop a universal macOS build with its own message）<br>#355 macOS 加 File 菜单，Cmd+W 关窗<br>#358 发布演练把安装包保留为 workflow 产物<br>#359 发布演练打包不再访问 registry<br>#360 首次运行中途退出时什么都不写入<br>#361 会话要等 DSH 列表里有了才打开 |
| 2026-10-19 | `1e7853b` | #345 Bot 的 working_directory 改到自身文件夹之外时，作为新的工作目录<br>#348 架构与当前 Node 不一致时停止桌面包构建 |

## 手机 App

在手机上使用 DS Bot 的配套方案，源码在本仓库：

- **dsh-mobile-bridge**（DSH 插件，当前 0.0.19）：给手机提供接口，新建 Bot、建群、切换模型等直接调用 ds-bot 现有接口，校验和桌面一致。
- **dsh-mobile-android**（Android App，当前 0.2.4，Kotlin + Compose）。
- **dsh-mobile-harmony**（鸿蒙原生 App，ArkTS + ArkUI，包名 `com.deepseek.dshmobile.harmony`）。

**功能**：Bot 单聊和群聊；历史、发消息、流式回复、停止生成；Markdown；浅色 / 深色主题；断线重连并补回消息；未读数、置顶、隐藏；在手机上审批；附件和图片，保存到手机；系统通知（总开关和按会话开关）；新建 Bot、发起群聊；给 Bot 切换模型，立即生效。群聊输入 `@` 弹出本群 Bot 选择器：不 @ 时全员回复，@ 了谁就只有谁回复，和电脑端规则一致。新启动图标。

**稳定性**：插件对群聊转发时的状态读取和 DSH 调试组件状态超限做了兜底，避免发群消息时把 DSH 进程带崩。

**配对方式**：

- 插件另开一个**设备监听端口 3081**，只开放手机需要的接口；除健康检查、配对和换票外都要求设备 token，配对和换票按来源限频。
- 在 DSH 里打开「手机配对」面板，显示一次性配对码（带倒计时）和二维码；手机扫码或输入配对码完成配对。
- 默认使用局域网地址；外网使用时在「手机配对」弹窗里填写并保存「公网访问地址」（经你自己的反向代理转到 3081）。

**安装**：

1. 在 `dsh-mobile-bridge/` 下 `npm pack`，在运行 DSH 的机器上 `dsh plugin add <dsh-mobile-bridge-x.y.z.tgz>`，启用后重启 DSH。
2. 在 `dsh-mobile-android/` 下 `./gradlew assembleDebug`，把 APK 装到 Android 手机，打开后扫码配对。

**已知限制**：

- 没有真正的推送：DSH 要开着、手机能连上才收得到通知。
- 置顶、隐藏、未读标记只存在手机上。
- 早先建的「只管理员回复」旧群，不 @ 时仍只管理员回，可在群资料里改成所有成员。
- 资料页没有文档 / 链接 / 媒体标签页，也没有定时任务和用量页；头像不能用照片。

### 鸿蒙版

**功能**：和 Android 0.2.4 对齐。配对（扫码或手填）、会话列表（未读、正在回复）、单聊（历史、流式回复、停止、Markdown）、群聊（@ 选择器、全角 ＠、谁会回复提示）、建群和群设置、图片和文件收发、审批、助手资料和切模型、新建助手、看大图并存到图库、长按复制或引用回复、置顶 / 标为未读 / 隐藏 / 静音、浅色深色、搜索、本地通知。

**怎么编译**：装好 DevEco Studio，用它自带的 JDK、node、ohpm、hvigor，在 `dsh-mobile-harmony/` 下：

```sh
ohpm install
hvigorw assembleHap -p buildMode=debug --no-daemon   # 编出未签名 HAP
hvigorw test -p module=entry@default --no-daemon     # 跑单测
```

**签名**：仓库里不带任何签名文件。装到真机前，要在 DevEco Studio 里登录你自己的华为开发者账号，用「自动签名」。它会把签名配置写进 `build-profile.json5`，这部分只留在你本机，不要提交。之后用 `hdc install -r entry/build/default/outputs/default/entry-default-signed.hap` 安装。

**已知限制**：

- 没有接 Push Kit：通知只在 App 运行时（前台或刚切到后台）能发出。
- 群聊只能看到电脑端保留的最近消息（最近 60 行），不能继续往前翻；单聊可以往前翻。

---


![version](https://img.shields.io/badge/version-v0.2_preview-blue) ![DSH](https://img.shields.io/badge/DSH-0.2.1--rc.2-4D6BFE) ![license](https://img.shields.io/badge/license-Apache_2.0-green)

DeepSeek Bot (DS Bot) is a bot plugin for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (DSH). It turns DSH into bots. You create your own bots, then talk and work with them.

![Say one sentence to Chief, and it sets up the bots and the group chat](docs/images/team.png)

## Install

**Desktop app (Windows, macOS)**

The desktop app brings its own DSH, so you don't need to install DSH first. Download it from [Releases](https://github.com/Fei2411/DeepSeek-Bot/releases):

- Windows (x64): `DeepSeek-Bot-<version>-win-x64.exe`. Run it; it installs for your user only, without administrator rights. If Windows shows "Windows protected your PC", select **More info** → **Run anyway**.
- macOS: `DeepSeek-Bot-<version>-mac-arm64.zip` for an Apple chip, `DeepSeek-Bot-<version>-mac-x64.zip` for Intel. Safari unzips it by itself; drag **DeepSeek Bot** into **Applications** and open it. When macOS says it cannot check the app, open **System Settings** → **Privacy & Security** and select **Open Anyway**.

At the first start the app asks where to keep its data and the Bots' files, and offers to copy a Bot team from your DSH (it only reads your DSH). Set up a model in its settings. The Windows app updates itself; the macOS app tells you when a new version is ready to download.

**As a plugin in DSH**

First install DSH and set up at least one model in it. DS Bot uses the models from DSH.

**DSH Desktop (Windows, macOS)**

1. In the sidebar, select **Plugins** → **Add plugin**.
2. In **Package name or address**, enter `ds-bot`. In mainland China, set **Registry** to **Mainland China mirror**.
3. Select **Install**, then **Enable now**. If DSH says the change takes effect at the next start, quit DSH completely and open it again.

If npm is not reachable, in step 2 you can also enter the full path of `ds-bot-0.2.0.tgz` downloaded from [Releases](https://github.com/Fei2411/DeepSeek-Bot/releases), or `github:Fei2411/DeepSeek-Bot#v0.2.0&path:/ds-bot` (needs Git).

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
