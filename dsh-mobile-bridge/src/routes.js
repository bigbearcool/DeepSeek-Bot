import { API_PREFIX, AVATAR_SHAPES, FORM_LIMITS, INK_HEX, NAME, VERSION, appUrlOf, cleanHttpUrl } from './constants.js'
import { DsBotError } from './dsbot-api.js'
import { head } from './diag.js'
import { stat } from 'node:fs/promises'
import { zhMessage } from './messages-zh.js'
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
export const SEND_DEDUPE_MS = 10 * 60_000
export const RATE_LIMITED_PATHS = new Set(['/auth/pair/complete', '/auth/token'])

const str = v => (typeof v === 'string' ? v : undefined)

/** Exactly the fields ds-bot's create-bot endpoint reads; ds-bot validates every one. */
export function createBotPayload(body = {}) {
  const out = {}
  for (const key of ['name', 'role', 'color', 'model', 'effort', 'agentPreset', 'workDir', 'instructions', 'brief']) {
    const value = str(body[key])
    if (value !== undefined && value.trim() !== '') out[key] = value
  }
  if (body.avatar && typeof body.avatar === 'object') {
    const avatar = {}
    if (str(body.avatar.shape)) avatar.shape = body.avatar.shape
    if (str(body.avatar.image)) avatar.image = body.avatar.image
    if (Object.keys(avatar).length > 0) out.avatar = avatar
  }
  return out
}

/** Same call the desktop "new group chat" form makes: admin first, greet on. */
export function createRoomPayload(body = {}) {
  const ids = Array.isArray(body.members) ? body.members.filter(id => typeof id === 'string') : []
  const admin = str(body.admin)
  const members = admin ? [admin, ...ids.filter(id => id !== admin)] : ids
  const name = str(body.name)?.trim()
  return { members, ...(admin ? { admin } : {}), greet: true, ...(name ? { name } : {}) }
}

function publicBot(bot = {}) {
  return {
    id: bot.id, name: bot.name, role: bot.role ?? null, sessionId: bot.sessionId ?? bot.id,
    model: bot.model ?? null, agentPreset: bot.agentPreset ?? null, color: bot.color ?? null,
    shape: bot.avatar?.shape ?? null, main: false,
  }
}

async function dsCall(run) {
  try {
    return json(await run())
  } catch (err) {
    const zh = zhMessage(err?.message)
    if (err instanceof DsBotError) return error(err.status, err.message, { code: err.code, ...(zh ? { zh } : {}) })
    return error(err?.status ?? 500, err?.message ?? String(err), zh ? { zh } : {})
  }
}

/**
 * Build the request handler.
 * surface 'web'    — dsh connection routes (3080, protected by dsh login).
 * surface 'device' — standalone listener (3081): host-only paths 404, bearer required except
 *                    DEVICE_PUBLIC_PATHS, pair/complete + token rate-limited by client IP.
 */
export function createHandler(rt, { surface = 'web', limiter = null } = {}) {
  const { store, adapter, feed, dsbot } = rt
  const diag = rt.diag ?? { note() {}, snapshot: () => ({}) }
  const resolveUrls = async () => {
    const s = await store.getSettings()
    const publicUrl = cleanHttpUrl(s.publicUrl) || appUrlOf(rt.config)
    const ds = rt.deviceServer
    const lan = ds?.listening ? lanUrl({ host: ds.host, port: ds.port }) : ''
    return { publicUrl, lanUrl: lan, appUrl: publicUrl || lan, source: publicUrl ? 'public' : lan ? 'lan' : 'none' }
  }
  const device = surface === 'device'
  /** Idempotency: same device + clientMessageId within SEND_DEDUPE_MS never re-prompts. */
  const recentSends = new Map()

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

  /**
   * Where a group message stops, in one answer: is ds-bot's route reachable in-process,
   * which state.json the bridge reads and whether ds-bot itself agrees on its revision,
   * the group's latest log lines, a round now running, phones watching, and the recent
   * marks of every stage (diag.js). No message text beyond short heads, no tokens.
   */
  async function diagnostics(roomId) {
    const out = { version: VERSION, surface, now: Date.now() }
    out.dsbot = dsbot?.probe?.() ?? null
    const file = { path: adapter.statePath }
    try {
      const info = await stat(adapter.statePath)
      file.exists = true
      file.size = info.size
      file.mtime = info.mtimeMs
    } catch (err) {
      file.exists = false
      file.error = err?.code ?? String(err?.message ?? err)
    }
    let state = null
    try { state = await adapter.readState() } catch (err) { file.readError = String(err?.message ?? err) }
    if (state) {
      file.revision = state.revision ?? null
      file.rooms = Object.keys(state.rooms ?? {}).length
      file.bots = Object.keys(state.bots ?? {}).length
    }
    out.stateFile = file
    // ds-bot's own view: same revision means the bridge reads the file ds-bot writes.
    if (out.dsbot?.usable) {
      try {
        const live = await dsbot.call('state', {})
        out.dsbotState = {
          revision: live?.revision ?? null,
          readOnly: live?.readOnly ?? null,
          rooms: Array.isArray(live?.rooms) ? live.rooms.length : null,
          roomsWorking: live?.work?.rooms ?? null,
        }
        out.sameStateFile = file.revision !== undefined && live?.revision !== undefined
          ? Math.abs(Number(live.revision) - Number(file.revision)) <= 1 : null
      } catch (err) {
        out.dsbotState = { error: err.message, code: err.code ?? null }
      }
    }
    if (roomId) {
      const room = state?.rooms?.[roomId]
      out.room = room ? {
        id: roomId,
        name: room.name ?? '',
        mode: room.mode ?? null,
        members: room.members ?? [],
        memberSessions: room.sessions ?? {},
        seq: room.seq ?? null,
        last: (room.log ?? []).slice(-6).map(line => ({ seq: line.seq ?? null, who: line.botId ? 'member' : line.who === 'User' ? 'user' : 'other', botId: line.botId ?? null, kind: line.kind ?? null, text: head(line.text, 16) })),
      } : { id: roomId, missing: true }
      if (out.dsbot?.usable) {
        try { out.roomProgress = await dsbot.call('room-progress', { roomId }) } catch (err) { out.roomProgress = { error: err.message } }
      }
    }
    out.feed = feed.status?.() ?? null
    out.diag = diag.snapshot({ roomId })
    return out
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
      return withAuth(request, async (device) => {
        const body = await readJson(request)
        try {
          const clientMessageId = typeof body.clientMessageId === 'string' && body.clientMessageId.length <= 128
            ? body.clientMessageId : null
          const key = clientMessageId ? `${device.id}:${clientMessageId}` : null
          const seen = key ? recentSends.get(key) : undefined
          if (seen) return json({ ...seen, duplicate: true })
          const result = await adapter.send({ botId: body.botId, text: body.text })
          if (result.stub) return json({ ok: false, ...result }, result.status ?? 501)
          const out = { ok: true, ...result, ...(clientMessageId ? { clientMessageId } : {}) }
          if (key) {
            recentSends.set(key, out)
            setTimeout(() => recentSends.delete(key), SEND_DEDUPE_MS).unref?.()
            feed.notePending?.(body.botId, String(body.text ?? '').trim(), clientMessageId)
          }
          return json(out)
        } catch (err) {
          return error(err.status ?? 500, err.message)
        }
      })
    }

    if (method === 'GET' && sub === '/bots/options') {
      return withAuth(request, async () => dsCall(async () => {
        await dsbot.call('models').catch(() => {})
        const view = await dsbot.call('state', {})
        let folders = null
        try { folders = await dsbot.call('folders', {}) } catch { /* optional */ }
        const colors = Array.isArray(view.colors) ? view.colors : []
        return {
          ok: true,
          models: (view.models ?? []).map(m => ({
            ref: m.ref, name: m.name ?? m.id ?? m.ref, providerName: m.providerName ?? m.provider ?? null,
            ...(m.needsKey ? { needsKey: true } : {}),
          })),
          defaultModel: view.defaultModel ?? null,
          agentPresets: Array.isArray(view.agentPresets)
            ? view.agentPresets.filter(p => !p.broken).map(p => ({ value: p.value, name: p.name ?? p.value }))
            : null,
          colors: colors.map(id => ({ id, hex: INK_HEX[id] ?? null })),
          shapes: AVATAR_SHAPES,
          folders,
          limits: FORM_LIMITS,
          readOnly: view.readOnly ?? null,
        }
      }))
    }

    if (method === 'GET' && sub === '/bots/efforts') {
      return withAuth(request, async () => dsCall(async () => {
        const ref = new URL(request.url).searchParams.get('ref') ?? ''
        if (!ref) return { ok: true, efforts: [] }
        const value = await dsbot.call('efforts', { ref })
        return { ok: true, efforts: value?.efforts ?? [], defaultEffort: value?.defaultEffort ?? null }
      }))
    }

    if (method === 'GET' && sub === '/folders') {
      return withAuth(request, async () => dsCall(async () => {
        const path = new URL(request.url).searchParams.get('path')
        if (path === null) return { ok: true, ...(await dsbot.call('folders', {})) }
        return { ok: true, ...(await dsbot.call('folder-list', { path })) }
      }))
    }

    if (method === 'POST' && sub === '/bots/create') {
      return withAuth(request, async () => dsCall(async () => {
        const body = await readJson(request)
        const bot = await dsbot.call('create-bot', createBotPayload(body))
        return { ok: true, bot: publicBot(bot) }
      }))
    }

    if (method === 'POST' && sub === '/rooms/create') {
      return withAuth(request, async () => dsCall(async () => {
        const body = await readJson(request)
        const room = await dsbot.call('create-room', createRoomPayload(body))
        return { ok: true, room: { id: room.id, name: room.name, members: room.members ?? [], admin: room.admin ?? null, mode: room.mode ?? null } }
      }))
    }

    if (method === 'GET' && sub === '/rooms/history') {
      return withAuth(request, async () => {
        const roomId = new URL(request.url).searchParams.get('roomId') ?? ''
        if (!roomId) return error(400, 'roomId required')
        try {
          return json({ ok: true, ...(await adapter.roomHistory({ roomId })) })
        } catch (err) {
          return error(err.status ?? 500, err.message, { zh: zhMessage(err.message) ?? undefined })
        }
      })
    }

    if (method === 'POST' && sub === '/rooms/send') {
      return withAuth(request, async (device) => {
        const body = await readJson(request)
        const roomId = typeof body.roomId === 'string' ? body.roomId : ''
        const text = String(body.text ?? '').trim()
        if (!roomId) return error(400, 'roomId required')
        if (!text) return error(400, 'text required')
        const clientMessageId = typeof body.clientMessageId === 'string' && body.clientMessageId.length <= 128
          ? body.clientMessageId : null
        const key = clientMessageId ? `${device.id}:room:${clientMessageId}` : null
        const seen = key ? recentSends.get(key) : undefined
        if (seen) return json({ ...seen, duplicate: true })
        try {
          // Only rooms ds-bot knows: a bot id or a plain Session id is not a group chat.
          await adapter.roomHistory({ roomId })
        } catch (err) {
          diag.note('send.fail', { roomId, stage: 'room', status: err.status ?? 500, error: err.message }, { level: 'warn' })
          return error(err.status ?? 500, err.message)
        }
        return dsCall(async () => {
          try {
            await dsbot.call('send', { sessionId: roomId, text })
          } catch (err) {
            diag.note('send.fail', { roomId, stage: 'ds-bot', status: err.status ?? 500, code: err.code ?? null, error: err.message }, { level: 'warn' })
            throw err
          }
          diag.note('send.ok', { roomId, deviceId: device.id, text: head(text, 12), clientMessageId }, { level: 'info' })
          const out = { ok: true, roomId, ...(clientMessageId ? { clientMessageId } : {}) }
          if (key) {
            recentSends.set(key, out)
            setTimeout(() => recentSends.delete(key), SEND_DEDUPE_MS).unref?.()
            feed.notePending?.(`room:${roomId}`, text, clientMessageId)
          }
          return out
        })
      })
    }

    if (method === 'GET' && sub === '/stream') {
      return withAuth(request, async (device) => {
        const url = new URL(request.url)
        const botId = url.searchParams.get('botId') || null
        const roomId = url.searchParams.get('roomId') || null
        const sinceRaw = url.searchParams.get('since')
        const since = sinceRaw !== null && /^\d{1,12}$/.test(sinceRaw) ? Number(sinceRaw) : null
        // Default: live feed from session/agent events. ?mode=test keeps hello+test ticks.
        const test = url.searchParams.get('mode') === 'test'
        return feed.openStream(request, { device, botId, roomId, since, test })
      })
    }

    if (method === 'GET' && sub === '/diagnostics') {
      return withAuth(request, async () => json({ ok: true, ...(await diagnostics(new URL(request.url).searchParams.get('roomId') || null)) }))
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
    ['/bots/options', ['GET']],
    ['/bots/efforts', ['GET']],
    ['/bots/create', ['POST']],
    ['/folders', ['GET']],
    ['/rooms/create', ['POST']],
    ['/rooms/history', ['GET']],
    ['/rooms/send', ['POST']],
    ['/stream', ['GET']],
    ['/diagnostics', ['GET']],
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
