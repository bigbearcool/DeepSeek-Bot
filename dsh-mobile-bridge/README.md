# dsh-mobile-bridge

Mobile remote-control plugin for DeepSeek Harness (see `docs/plans/2026-10-20-mobile-native-dsh-bridge.md`).

## What it does (0.0.4)

- Health check
- **Host pairing UI** (`GET /ui`): one-time code, countdown, regenerate, QR (`dshmb:<code>`), paired-device list + kick
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

On the machine running dsh / DS Bot, open:

`http://<本机或局域网>:port/api/mobile-bridge/v1/ui`

(or `/api/mobile-bridge/v1/pair`)

The page shows a 6-character code (5 minutes), a QR encoding `dshmb:<code>`, copy buttons, regenerate, and a list of paired devices you can kick. Phone apps redeem the code with `POST /auth/pair/complete`.

## Routes

Prefix: `/api/mobile-bridge/v1`

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/health` | no | `{ ok, name, version }` |
| GET | `/ui` or `/pair` | no | Host HTML pairing screen |
| POST | `/auth/pair/begin` | no | Create / rotate a short pairing code |
| GET | `/auth/pair/active` | no | Current unused code (if any) |
| GET | `/auth/pair/qr` | no | `{ code, payload, svg }` for the active code |
| POST | `/auth/pair/complete` | no | Phone redeems code → device + refresh token |
| POST | `/auth/token` | no | refresh → access token |
| GET | `/team` | Bearer | Bots from `$DSH_HOME/bot/state.json` |
| GET | `/chats/history?botId=&cursor=` | Bearer | Real page; `nextCursor` for earlier parts |
| POST | `/chats/send` | Bearer | Tries `sessionController.prompt`; else `501` stub |
| GET | `/stream` | Bearer | Live SSE (default). `?botId=` filter. `?mode=test` for ticks |
| GET | `/devices` | no* | List paired devices (*host UI; bind to local session later) |
| POST | `/devices/revoke` | no* | `{ deviceId }` kick device |

## Tests

```sh
cd dsh-mobile-bridge && npm test
```

## Device listener (0.0.4)

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
