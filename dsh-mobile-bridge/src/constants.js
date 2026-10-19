export const NAME = 'dsh-mobile-bridge'
export const VERSION = '0.0.4'
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
export const DEVICE_HOST = '127.0.0.1'
export const TRUSTED_PROXIES = ['127.0.0.1', '::1', '::ffff:127.0.0.1']
export const RATE_LIMIT = { max: 10, windowMs: 60 * 1000 }
