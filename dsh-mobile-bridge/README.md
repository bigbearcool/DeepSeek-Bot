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
