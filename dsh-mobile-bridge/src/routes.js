import { API_PREFIX, NAME, VERSION, appUrlOf, cleanHttpUrl } from './constants.js'
import { lanUrl } from './lan.js'
import { bearer, error, html, json, pathOf, readJson } from './http.js'
import { pairPageHtml } from './pair-page.js'
import { pairPayload, qrSvg } from './qr.js'

/** Paths never served on the device listener (host-only, behind dsh login on 3080). */
export const HOST_ONLY_PATHS = new Set([
  '/ui', '/pair', '/auth/pair/begin', '/auth/pair/active', '/auth/pair/qr', '/devices/revoke',
  '/settings',
])
/** Paths reachable on the device listener without a bearer token. */
export const DEVICE_PUBLIC_PATHS = new Set(['/health', '/auth/pair/complete', '/auth/token'])
export const RATE_LIMITED_PATHS = new Set(['/auth/pair/complete', '/auth/token'])

/**
 * Build the request handler.
 * surface 'web'    — dsh connection routes (3080, protected by dsh login).
 * surface 'device' — standalone listener (3081): host-only paths 404, bearer required except
 *                    DEVICE_PUBLIC_PATHS, pair/complete + token rate-limited by client IP.
 */
export function createHandler(rt, { surface = 'web', limiter = null } = {}) {
  const { store, adapter, feed } = rt
  const resolveUrls = async () => {
    const s = await store.getSettings()
    const publicUrl = cleanHttpUrl(s.publicUrl) || appUrlOf(rt.config)
    const ds = rt.deviceServer
    const lan = ds?.listening ? lanUrl({ host: ds.host, port: ds.port }) : ''
    return { publicUrl, lanUrl: lan, appUrl: publicUrl || lan, source: publicUrl ? 'public' : lan ? 'lan' : 'none' }
  }
  const device = surface === 'device'

  const withAuth = async (request, next) => {
    const token = bearer(request)
    if (!token) return error(401, 'missing bearer token')
    try {
      const device = await store.requireAccess(token)
      return next(device)
    } catch (err) {
      return error(err.status ?? 401, err.message)
    }
  }

  const originOf = (request) => {
    try {
      const url = new URL(request.url)
      return url.origin
    } catch {
      return ''
    }
  }

  const ensurePair = async () => {
    const active = await store.activePair()
    if (active) return active
    return store.beginPair()
  }

  const handle = async (request, info = {}) => {
    const method = request.method.toUpperCase()
    const sub = pathOf(request, API_PREFIX)
    if (sub === null) return error(404, 'not found')

    if (device) {
      if (HOST_ONLY_PATHS.has(sub)) return error(404, `no route ${method} ${sub}`)
      if (RATE_LIMITED_PATHS.has(sub) && limiter) {
        const verdict = limiter.hit(`${sub}|${info.clientIp ?? 'unknown'}`)
        if (!verdict.ok) {
          return json({ ok: false, error: { message: 'too many requests' } }, 429,
            { 'retry-after': String(Math.ceil(verdict.retryAfterMs / 1000)) })
        }
      }
      if (sub === '/devices' && method === 'GET') {
        return withAuth(request, async () => json({ ok: true, devices: await store.listDevices() }))
      }
    }

    if (sub === '/settings') {
      if (method === 'GET') return json({ ok: true, ...(await resolveUrls()) })
      if (method === 'POST') {
        const body = await readJson(request)
        const raw = String(body.publicUrl ?? '').trim()
        const publicUrl = cleanHttpUrl(raw)
        if (raw && !publicUrl) return error(400, '地址需以 http:// 或 https:// 开头')
        await store.setSettings({ publicUrl })
        return json({ ok: true, ...(await resolveUrls()) })
      }
    }

    if (method === 'GET' && sub === '/health') {
      return json({ ok: true, name: NAME, version: VERSION })
    }

    // Host-local pairing UI (open in dsh Web / desktop browser on LAN).
    if (method === 'GET' && (sub === '/ui' || sub === '/pair')) {
      const pairing = await ensurePair()
      const devices = await store.listDevices()
      const origin = originOf(request)
      return html(pairPageHtml({
        code: pairing.code,
        expiresAt: pairing.expiresAt,
        expiresIn: pairing.expiresIn,
        devices,
        origin,
        appUrl: (await resolveUrls()).appUrl,
      }))
    }

    if (method === 'POST' && sub === '/auth/pair/begin') {
      const pairing = await store.beginPair()
      return json({ ok: true, ...pairing })
    }

    if (method === 'GET' && sub === '/auth/pair/active') {
      const pairing = await store.activePair()
      if (!pairing) return json({ ok: true, active: false })
      return json({ ok: true, active: true, ...pairing })
    }

    if (method === 'GET' && sub === '/auth/pair/qr') {
      const pairing = await store.activePair()
      if (!pairing) return error(404, 'no active pairing code')
      const origin = originOf(request)
      void origin
      const urls = await resolveUrls()
      const payload = pairPayload(pairing.code, { appUrl: urls.appUrl })
      return json({
        ...urls,
        ok: true,
        code: pairing.code,
        expiresAt: pairing.expiresAt,
        expiresIn: pairing.expiresIn,
        payload,
        svg: qrSvg(payload, { size: 200 }),
      })
    }

    if (method === 'POST' && sub === '/auth/pair/complete') {
      const body = await readJson(request)
      try {
        const result = await store.completePair(body)
        return json({ ok: true, ...result })
      } catch (err) {
        return error(err.status ?? 400, err.message)
      }
    }

    if (method === 'POST' && sub === '/auth/token') {
      const body = await readJson(request)
      try {
        const result = await store.issueAccess(body)
        return json({ ok: true, ...result })
      } catch (err) {
        return error(err.status ?? 401, err.message)
      }
    }

    if (method === 'GET' && sub === '/team') {
      return withAuth(request, async () => json({ ok: true, ...(await adapter.team()) }))
    }

    if (method === 'GET' && sub === '/chats/history') {
      return withAuth(request, async () => {
        const url = new URL(request.url)
        const botId = url.searchParams.get('botId') ?? ''
        const cursor = url.searchParams.get('cursor')
        const limit = Number(url.searchParams.get('limit') ?? 50)
        if (!botId) return error(400, 'botId required')
        return json({ ok: true, ...(await adapter.history({ botId, cursor, limit })) })
      })
    }

    if (method === 'POST' && sub === '/chats/send') {
      return withAuth(request, async () => {
        const body = await readJson(request)
        try {
          const result = await adapter.send({ botId: body.botId, text: body.text })
          if (result.stub) return json({ ok: false, ...result }, result.status ?? 501)
          return json({ ok: true, ...result })
        } catch (err) {
          return error(err.status ?? 500, err.message)
        }
      })
    }

    if (method === 'GET' && sub === '/stream') {
      return withAuth(request, async (device) => {
        const url = new URL(request.url)
        const botId = url.searchParams.get('botId') || null
        // Default: live feed from session/agent events. ?mode=test keeps hello+test ticks.
        const test = url.searchParams.get('mode') === 'test'
        return feed.openStream(request, { device, botId, test })
      })
    }

    if (method === 'GET' && sub === '/devices') {
      // Host-facing list for kick-device UI (pairing screen). No bearer yet — TODO: bind to local session.
      return json({ ok: true, devices: await store.listDevices() })
    }

    if (method === 'POST' && sub === '/devices/revoke') {
      const body = await readJson(request)
      const deviceId = String(body.deviceId ?? '').trim()
      if (!deviceId) return error(400, 'deviceId required')
      await store.revoke(deviceId)
      return json({ ok: true, deviceId })
    }

    return error(404, `no route ${method} ${sub}`)
  }
  return handle
}

export function installRoutes(rt) {
  const { ctx } = rt
  const handle = createHandler(rt, { surface: 'web' })

  // One registration per concrete path: DSH fetch routes are exact matches (like ds-bot).
  const paths = [
    ['/health', ['GET']],
    ['/ui', ['GET']],
    ['/pair', ['GET']],
    ['/auth/pair/begin', ['POST']],
    ['/auth/pair/active', ['GET']],
    ['/auth/pair/qr', ['GET']],
    ['/auth/pair/complete', ['POST']],
    ['/auth/token', ['POST']],
    ['/team', ['GET']],
    ['/chats/history', ['GET']],
    ['/chats/send', ['POST']],
    ['/stream', ['GET']],
    ['/devices', ['GET']],
    ['/devices/revoke', ['POST']],
    ['/settings', ['GET', 'POST']],
  ]

  for (const [suffix, methods] of paths) {
    const path = `${API_PREFIX}${suffix}`
    ctx.effect(() => ctx.connection.fetch.register({
      path,
      methods,
      requestBody: 'buffered',
      fetch: handle,
    }), `${NAME}: ${methods.join(',')} ${path}`)
  }
}
