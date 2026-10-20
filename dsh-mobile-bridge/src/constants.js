export const NAME = 'dsh-mobile-bridge'
export const VERSION = '0.0.16'
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

/**
 * ds-bot's avatar characters (src/client/characters.js PICKER_SHAPES, ds-bot 0.1.9) with
 * the labels the phone shows. ds-bot itself validates any shape it is sent (cleanAvatar).
 */
export const AVATAR_SHAPES = [
  ['star', '星星'], ['moon', '月亮'], ['gumdrop', '软糖'], ['peanut', '花生'], ['bunny', '兔子'],
  ['kitty', '小猫'], ['acorn', '橡果'], ['whale', '鲸鱼'], ['wave', '浪花'], ['squircle', '圆方'],
  ['zed', '小牛'], ['hex', '花朵'], ['saucer', '飞碟'], ['beacon', '灯塔'], ['plus', '十字'],
  ['magnet', '磁铁'], ['pebbles', '石子'], ['cushion', '靠垫'], ['ring', '圆环'], ['gem', '宝石'],
].map(([id, label]) => ({ id, label }))
/** ds-bot inks (src/client/inks.js): first value is the color drawn in light mode. */
export const INK_HEX = {
  deepseek: '#4D6BFE', charcoal: '#141416', graphite: '#2D2D2D', rose: '#EC2F6B', sky: '#0AA8D6',
  tangerine: '#FF6900', cobalt: '#082DFF', black: '#000000', brown: '#A27952', red: '#FF3E51',
  orange: '#FF781C', yellow: '#FFAF38', green: '#00C972', cyan: '#1CC3B0', blue: '#2A92FE',
  violet: '#A97EFE', magenta: '#FF5EB1', gray: '#959595', aurora: '#00B8D4',
}
/** ds-bot CHARACTER_COLORS (src/client/characters.js), in picker order: the 12 colors a Bot can take. */
export const PICKER_COLORS = ['black', 'brown', 'red', 'orange', 'yellow', 'green', 'cyan', 'blue', 'violet', 'magenta', 'gray', 'aurora']
/** Limits the desktop form shows (create-bot.js maxLength 40, roster.js role 24, ROOM_NAME_MAX 60). */
export const FORM_LIMITS = { name: 40, role: 24, roomName: 60, roomMinMembers: 2, roomMaxMembers: 12 }
