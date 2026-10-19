# dsh-mobile-bridge

Skeleton of the mobile remote-control plugin for DeepSeek Harness (see `docs/plans/2026-10-20-mobile-native-dsh-bridge.md`).

## What it does (MVP skeleton)

- Health check
- Device pairing placeholder + short-lived access tokens
- Read-only Bot list from ds-bot `state.json`
- Chat history / send: best-effort via `sessionController` when available; otherwise clear stubs
- SSE stream with hello + test events (feed proxy TODO)

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

## Routes

Prefix: `/api/mobile-bridge/v1`

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/health` | no | `{ ok, name, version }` |
| POST | `/auth/pair/begin` | no | Host creates a short pairing code |
| POST | `/auth/pair/complete` | no | Phone redeems code → device + refresh token |
| POST | `/auth/token` | no | refresh → access token |
| GET | `/team` | Bearer | Bots from `$DSH_HOME/bot/state.json` |
| GET | `/chats/history?botId=` | Bearer | Stub / empty page until wired |
| POST | `/chats/send` | Bearer | Tries `sessionController.prompt`; else `501` stub |
| GET | `/stream` | Bearer | SSE hello + periodic test tick |

## Tests

```sh
cd dsh-mobile-bridge && npm test
```
