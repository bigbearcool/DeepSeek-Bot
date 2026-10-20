/**
 * In-process caller for ds-bot's own POST /api/bot endpoint (src/host/channel.js).
 *
 * The bridge never re-implements ds-bot rules: create-bot, create-room, send, state, efforts,
 * folders all go through ds-bot's handler, so names, avatars, models, presets, folders and
 * member checks are validated exactly as the desktop app's are.
 *
 * dsh's connection service keeps exact Fetch routes in `fetchRoutes` (Map path → route).
 * Calling the route's fetch directly skips dsh's browser login, which is right here: the
 * bridge has already checked the device Bearer token before it gets this far.
 */
export const DSBOT_ROUTE = '/api/bot'

/** ds-bot endpoints the phone may reach. Anything else is refused before ds-bot sees it. */
export const DSBOT_ALLOWED = new Set([
  'state', 'models', 'efforts', 'folders', 'folder-list', 'create-bot', 'create-room', 'send',
  // read-only, for GET /diagnostics
  'room-progress',
])

export class DsBotError extends Error {
  constructor(message, status = 400, code = 'bot/error') {
    super(message)
    this.status = status
    this.code = code
  }
}

const STATUS_OF_CODE = {
  'bot/invalid': 400,
  'bot/refused': 400,
  'bot/error': 400,
  'bot/stale': 409,
  'bot/read-only': 409,
  'bot/not-found': 404,
  'bot/internal': 500,
}

export function createDsBotApi({ ctx, route = DSBOT_ROUTE } = {}) {
  function routeOf() {
    try {
      const routes = ctx?.connection?.fetchRoutes
      const found = typeof routes?.get === 'function' ? routes.get(route) : undefined
      if (!found || typeof found.fetch !== 'function') return undefined
      const methods = found.methods
      if (methods && typeof methods.has === 'function' && !methods.has('POST')) return undefined
      return found
    } catch {
      return undefined
    }
  }

  async function call(endpoint, payload = {}, signal) {
    if (!DSBOT_ALLOWED.has(endpoint)) throw new DsBotError(`endpoint ${endpoint} not allowed`, 403, 'bridge/forbidden')
    const found = routeOf()
    if (!found) throw new DsBotError('DS Bot is not running on this computer', 503, 'bridge/unavailable')
    const request = new Request(`http://localhost${route}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ endpoint, payload }),
      ...(signal ? { signal } : {}),
    })
    const response = await found.fetch(request)
    let body
    try { body = await response.json() } catch { body = null }
    if (!body || typeof body !== 'object') throw new DsBotError('DS Bot gave no answer', 502, 'bridge/bad-gateway')
    if (body.ok === true) return body.value
    const code = body.error?.code ?? 'bot/error'
    throw new DsBotError(body.error?.message ?? 'DS Bot refused', STATUS_OF_CODE[code] ?? 400, code)
  }

  /** What the lookup sees, for GET /diagnostics. */
  function probe() {
    const connection = ctx?.connection
    const routes = connection?.fetchRoutes
    const out = {
      connection: connection !== undefined && connection !== null,
      fetchRoutes: routes === undefined ? 'missing' : typeof routes?.get === 'function' ? 'map' : typeof routes,
      routeCount: typeof routes?.size === 'number' ? routes.size : null,
    }
    try {
      const found = typeof routes?.get === 'function' ? routes.get(route) : undefined
      out.dsbotRoute = found ? { fetch: typeof found.fetch === 'function', methods: found.methods ? [...found.methods] : null } : null
    } catch (error) {
      out.dsbotRoute = null
      out.error = String(error?.message ?? error)
    }
    out.usable = routeOf() !== undefined
    return out
  }

  return { call, probe, available: () => routeOf() !== undefined }
}
