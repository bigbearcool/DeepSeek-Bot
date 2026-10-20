export const NAME = 'dsh-mobile-bridge'
export const VERSION = '0.0.8'
export const API_PREFIX = '/api/mobile-bridge/v1'

/** Access token lifetime (ms). */
export const ACCESS_TTL_MS = 30 * 60 * 1000
/** Refresh token lifetime (ms). */
export const REFRESH_TTL_MS = 30 * 24 * 60 * 60 * 1000
/** Pairing code lifetime (ms). */
export const PAIR_TTL_MS = 5 * 60 * 1000

export const STORE_FILE = 'devices.json'

/** Standalone device listener (bypasses dsh web login; device bearer enforced in-plugin). */
export const DEVICE_PORT = 3081
/** Default: all interfaces (LAN + 127.0.0.1 so a same-host reverse proxy keeps working). */
export const DEVICE_HOST = '0.0.0.0'
export const DEVICE_HOST_LOCAL = '127.0.0.1'
export const TRUSTED_PROXIES = ['127.0.0.1', '::1', '::ffff:127.0.0.1']
export const RATE_LIMIT = { max: 10, windowMs: 60 * 1000 }

/** Server address the phone App should use (shown on the host pairing page). */
/** No hard-coded public address: user sets 「公网访问地址」, else LAN address is used. */
export const APP_SERVER_URL = ''

/** Static fallback from plugin config.publicUrl (http/https only). */
export function cleanHttpUrl(value) {
  const raw = typeof value === 'string' ? value.trim().replace(/\/+$/, '') : ''
  return /^https?:\/\/[^\s/]+[^\s]*$/i.test(raw) ? raw : ''
}
export function appUrlOf(config = {}) {
  return cleanHttpUrl(config?.publicUrl) || APP_SERVER_URL
}
