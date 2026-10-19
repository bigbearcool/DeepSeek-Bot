/**
 * Pure helpers for the in-app 「手机配对」 panel (and the standalone /ui page).
 * Kept free of React / DOM so Node tests can cover assembly without a browser.
 */
import { APP_SERVER_URL, PAIR_TTL_MS, API_PREFIX } from './constants.js'

export { APP_SERVER_URL, PAIR_TTL_MS, API_PREFIX }

/** Display "123 456" for a 6-digit code. */
export function formatCode(code) {
  const c = String(code || '')
  return c.length === 6 ? `${c.slice(0, 3)} ${c.slice(3)}` : c
}

/** Human countdown from expiresAt (ms epoch) or expiresIn (sec). */
export function formatCountdown(expiresAt, now = Date.now()) {
  const leftMs = expiresAt != null
    ? Math.max(0, Number(expiresAt) - now)
    : 0
  if (!expiresAt || leftMs <= 0) {
    return { text: '已过期 — 请重新生成', expired: true, expiring: false, ratio: 0, leftMs: 0 }
  }
  const sec = Math.ceil(leftMs / 1000)
  const m = Math.floor(sec / 60)
  const s = sec % 60
  const text = `剩余 ${m > 0 ? `${m} 分 ` : ''}${s} 秒`
  return {
    text,
    expired: false,
    expiring: leftMs < 60_000,
    ratio: Math.max(0, Math.min(1, leftMs / PAIR_TTL_MS)),
    leftMs,
  }
}

/**
 * Normalize pair/begin or pair/active + optional qr body into panel state.
 */
export function assemblePairState(beginOrActive, qr = null) {
  const code = beginOrActive?.code ?? null
  const expiresAt = beginOrActive?.expiresAt ?? null
  const payload = qr?.payload
    || (code ? `dshmb:${String(code).trim()}` : '')
  const svg = qr?.svg || ''
  return {
    code,
    expiresAt,
    expiresIn: expiresAt != null ? Math.max(0, Math.floor((expiresAt - Date.now()) / 1000)) : null,
    payload,
    svg,
  }
}

/** Plain-language steps shown in the panel (App URL fixed for phones). */
export function pairHowToSteps(appUrl = APP_SERVER_URL) {
  return [
    '打开手机上的 DS Bot App。',
    `在 App 里填服务器地址：${appUrl}`,
    '输入下面的 6 位配对码，或扫描二维码。',
  ]
}

/**
 * Describe what the in-app entry must do (for tests / docs).
 * openMode: 'in-app' — never window.open to /ui.
 */
export function pairEntrySpec() {
  return {
    label: '手机配对',
    slot: 'sidebar.footer.action',
    id: 'mobile-pair',
    openMode: 'in-app',
    forbidden: ['window.open', 'location.href=/api/mobile-bridge/v1/ui'],
    apis: [
      'POST /auth/pair/begin',
      'GET /auth/pair/active',
      'GET /auth/pair/qr',
      'GET /devices',
      'POST /devices/revoke',
    ],
    appServerUrl: APP_SERVER_URL,
  }
}
