# dsh-mobile-bridge

Mobile remote-control plugin for DeepSeek Harness (see `docs/plans/2026-10-20-mobile-native-dsh-bridge.md`).

## What it does (0.0.6)

- Health check
- **Web 入口（应用内）**：dsh 侧栏底部「手机配对」一点即在应用内弹出配对面板（6 位码、倒计时、二维码、复制/重新生成、已配对设备）；**不再**新开标签跳到 `/ui`
- **Host pairing UI**（后备）：`GET /ui` 仍保留独立 HTML 页，供直接打开；默认入口走应用内面板
- Device pairing + short-lived access tokens
- Read-only Bot list from ds-bot `state.json`
- Chat history via `sessionController.page` (same part/cursor semantics as ds-bot `chatPage`, slim `displayItems` for phone)
- Send via `sessionController.prompt` when available
- Live SSE feed from Cordis `session/event` / `agent/assistant-stream` / `agent/status` (hello / live / message / stream); optional `?mode=test` for hello + test ticks

It does **not** turn off Bot tools and does **not** add Mesh/remote-desktop panels.

## Load locally

In a dsh profile (next to `ds-bot`):

```json
{
  "dependencies": {
    "ds-bot": "link:/path/to/DeepSeek-Bot/ds-bot",
    "dsh-mobile-bridge": "link:/path/to/DeepSeek-Bot/dsh-mobile-bridge"
  },
  "dsh": {
    "profile": {
      "bundles": ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "ds-bot", "dsh-mobile-bridge"]
    }
  }
}
```

Then `pnpm install` in the profile and start dsh as usual.

## Pairing (host UI)

**推荐**：登录 dsh Web 后，在左侧栏底部点 **「手机配对」**。会在应用内直接弹出配对面板（不新开标签）：

- 大号 6 位配对码（约 5 分钟有效）+ 倒计时
- 可扫二维码（`dshmb:<code>`）
- 复制配对码 / 重新生成
- 已配对设备列表（可取消配对）
- App 服务器地址说明：`https://dshmb.bigbear.cool:8443`

独立页仍可直接打开（需已登录 dsh，作后备）：

`http://<本机或局域网>:port/api/mobile-bridge/v1/ui`

（或 `/api/mobile-bridge/v1/pair`）

手机用 `POST /auth/pair/complete` 兑换配对码。3081 设备口不提供 `/ui`。

## Routes

Prefix: `/api/mobile-bridge/v1`

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/health` | no | `{ ok, name, version }` |
| GET | `/ui` or `/pair` | dsh login (3080) | Host HTML pairing screen；3081 不提供 |
| POST | `/auth/pair/begin` | dsh login (3080) | Create / rotate a short pairing code |
| GET | `/auth/pair/active` | dsh login (3080) | Current unused code (if any) |
| GET | `/auth/pair/qr` | dsh login (3080) | `{ code, payload, svg }` for the active code |
| POST | `/auth/pair/complete` | no | Phone redeems code → device + refresh token |
| POST | `/auth/token` | no | refresh → access token |
| GET | `/team` | Bearer | Bots from `$DSH_HOME/bot/state.json` |
| GET | `/chats/history?botId=&cursor=` | Bearer | Real page; `nextCursor` for earlier parts |
| POST | `/chats/send` | Bearer | Tries `sessionController.prompt`; else `501` stub |
| GET | `/stream` | Bearer | Live SSE (default). `?botId=` filter. `?mode=test` for ticks |
| GET | `/devices` | no* / Bearer on 3081 | List paired devices (*host UI on 3080) |
| POST | `/devices/revoke` | dsh login (3080) | `{ deviceId }` kick device |

## Tests

```sh
cd dsh-mobile-bridge && npm test
```

## Device listener (0.0.4+)

dsh 0.2.1-rc.2 fetch routes all sit behind dsh web login, so phones get 401. The plugin
therefore also listens on its own port, same paths under `/api/mobile-bridge/v1/*`.

| config key | default | meaning |
|---|---|---|
| `devicePort` | `3081` | port; `false` disables the listener; `0` = random (tests) |
| `deviceHost` | `127.0.0.1` | bind address (keep loopback, expose via Caddy) |
| `trustedProxies` | `['127.0.0.1','::1','::ffff:127.0.0.1']` | peers whose `X-Forwarded-For`/`X-Real-IP` are trusted |
| `rateLimit` | `{ max: 10, windowMs: 60000 }` | per-IP, per-route fixed window for `pair/complete` and `token` |

On 3081:
- open: `GET health` (name+version only), `POST auth/pair/complete`, `POST auth/token` (the last two rate-limited, 429 + `retry-after`)
- device Bearer required: `GET team`, `GET chats/history`, `POST chats/send`, `GET stream` (SSE), `GET devices`
- 404: `ui`, `pair`, `auth/pair/begin`, `auth/pair/active`, `auth/pair/qr`, `devices/revoke` — host-only, stay on dsh web (3080, dsh login). Revoke is host-only on purpose: a stolen token must not be able to manage devices; the owner kicks devices from the pairing UI.

Client IP: socket peer, unless the peer is in `trustedProxies`; then `X-Forwarded-For` is walked
right-to-left and the first untrusted hop is used, else `X-Real-IP`, else the peer. Headers from
untrusted peers are ignored. Behind Caddy on the same host every request comes from 127.0.0.1,
so Caddy must forward XFF (it does by default) or all phones share one bucket.

Lifecycle: listener closes (incl. open SSE sockets) on plugin stop/reload. If the port is busy,
an error is logged (`EADDRINUSE … device API disabled`) and dsh/3080 keep running.

Caddy (phone-facing site → 3081, pairing UI stays on the dsh site):

```
mobile.example.com {
	reverse_proxy 127.0.0.1:3081 {
		flush_interval -1   # SSE
	}
}
```

## 0.0.7 网络与地址

- 设备接口默认监听 `0.0.0.0:3081`（局域网免配置；本机 127.0.0.1:3081 仍可用，同机反代不受影响）。`deviceLan: false` 只听 127.0.0.1；`devicePort` 改端口；`deviceHost` 指定地址。
- 「手机配对」弹窗可填写并保存「公网访问地址」（仅 dsh 登录的网页端可改）。填了用于二维码；没填就用自动探测的局域网地址 `http://<局域网IP>:<端口>`。
- 二维码：`dshmb:<code>@<地址>`；地址太长放不进二维码时退回 `dshmb:<code>`。
- 反代在另一台机器时，把那台机器的 IP 加进 `trustedProxies`，否则限频按反代 IP 计数。

## 0.0.9 — group chats, new Bot, new group chat (device token required)

All writes go through ds-bot's own `POST /api/bot` handler in-process (found in dsh's
connection `fetchRoutes`), so ds-bot validates exactly as on the desktop:

| Device endpoint | ds-bot endpoint / source |
| --- | --- |
| `GET /team` (now with `rooms`) | ds-bot `state.json` (rooms, hidden ones left out) |
| `GET /rooms/history?roomId=` | `state.json` `rooms[id].log` (ds-bot keeps the last 60) |
| `POST /rooms/send` | `send` with the group's Session id |
| `GET /stream?roomId=` | group log lines + members' `agent/assistant-stream` from `rooms[id].sessions` |
| `GET /bots/options` | `models` + `state` (models, defaultModel, agentPresets, colors) + `folders` |
| `GET /bots/efforts?ref=` | `efforts` |
| `GET /folders[?path=]` | `folders` / `folder-list` |
| `POST /bots/create` | `create-bot` (name, role, color, avatar{shape,image}, model, effort, agentPreset, workDir, instructions, brief) |
| `POST /rooms/create` | `create-room` (admin first, `greet: true`, optional name) — same as the desktop form |

Only these ds-bot endpoints are reachable (`DSBOT_ALLOWED`); without ds-bot loaded they answer 503.

## Group chat diagnostics (0.0.10)

`GET /api/mobile-bridge/v1/diagnostics[?roomId=<group id>]` with a device `Authorization: Bearer <access token>`
(401 without). It answers, in one JSON:

- `dsbot`: whether ds-bot's in-process `/api/bot` route is found through `ctx.connection.fetchRoutes` (`usable`).
- `stateFile` vs `dsbotState`: the state.json the bridge reads (path, size, mtime, revision) and the revision ds-bot
  itself reports; `sameStateFile: false` means the bridge reads a file ds-bot does not write.
- `room`: the group's seq, member Sessions and last 6 log lines (short heads only); `roomProgress`: the round now running.
- `feed`: phones connected / watching which group, poll running, last seq published, rounds open.
- `diag.events`: the last marks of every stage: `send.ok|send.fail` → `round.start` → `stream.start` → `poll.line` →
  `publish` → `round.end` (with `replied`, `failed`, `passed`). The stage after the last mark is where it stops.

The important marks also go to stderr as `[mobile-bridge] [group] ...` (docker logs / the terminal running dsh).

## Conversation list, unread, stop, approvals (0.0.11)

All require the device Bearer token (401 without one).

| Endpoint | Reuses |
|---|---|
| `GET /chats/list` | ds-bot state.json rooms (preview, log seq) + dsh `sessionController.page` (Bot chat tail) + ds-bot `state` live `work` (running, approvals) |
| `POST /chats/read` `{kind:'bot'\|'room', id, seq?}` | per-device read marks in the bridge store (`devices[id].reads`) |
| `POST /chats/stop` `{botId}` | dsh `sessionController.cancel({sessionId})` |
| `POST /rooms/stop` `{roomId, botId?}` | ds-bot `stop-member-work` for each member at work |
| `GET /approvals` | ds-bot `work.approvals` (tool names shown as plain words) |
| `POST /approvals/answer` `{id, choice:'allow'\|'reject'}` | ds-bot `approval-answer` (validation in ds-bot) |

A Bot's own-chat approval is `answerable: false`: ds-bot leaves it to dsh's own card.

## Bot profile, group members, search (0.0.12)

Every route needs the device Bearer token (401 without it). Writes go through ds-bot's own
`POST /api/bot` handler, so ds-bot does all the checking; the bridge only picks which fields pass.

| Route | What it does |
|---|---|
| `GET /bots/info?id=` | One Bot: name, role, color, shape, instructions, main; plus the 20 shapes and 12 colors. 404 for an unknown id. |
| `POST /bots/update` | `{ id, name?, role?, instructions?, color?, shape? }` → ds-bot `update-bot`. `shape` becomes `avatar: { shape }`; `shape: null` / `color: null` reset to the default look. Other fields (model, main, …) are dropped. |
| `POST /rooms/update` | `{ id, name?, add?: [botId], remove?: [botId] }` → ds-bot `update-room`. |
| `GET /search?q=` | Message search over visible group logs and the last 100 items of each Bot chat; at most 30 hits `{ kind, id, name, who, text, seq }`. |

## Pictures and files (0.0.13)

All need the device token (401 without it).

- `POST /uploads?kind=bot|room&id=<botId|roomId>&name=<file name>` — raw bytes (`application/octet-stream`, at most 25 MB, 413 above). Pictures (PNG/JPEG/GIF/WebP, recognised by their bytes) are held for 10 minutes and later sent as the same inline `image` prompt part the desktop sends; any other file goes through dsh's own `fileUploads.uploadStream` (the service behind `/api/session/uploadFileBinary`) and is cited by its receipt, bound to that Bot's current session or to the group. Returns `{ uploadId, type, name, bytes }`.
- `POST /chats/send` and `/rooms/send` take `attachments: [uploadId…]`; text may be empty then. An upload made for another chat, already used or expired is refused with 400 「附件已过期，请重新添加」. A group message with attachments is queued on the group's session the way the desktop does; without attachments it still goes through ds-bot `send`.
- Message items (history, live stream) carry `attachments: [{ type, id, name, bytes, mediaType, width, height }]`. Group lines show files and pictures from ds-bot's `[File …]` / `[Image …]` lines; pictures are matched to the group session's stored refs.
- `GET /attachments?id=<id>` — the bytes, only for an id this bridge has already shown to a phone.
