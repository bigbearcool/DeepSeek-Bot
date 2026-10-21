import { API_PREFIX, AVATAR_SHAPES, FORM_LIMITS, INK_HEX, NAME, PICKER_COLORS, VERSION, appUrlOf, cleanHttpUrl } from './constants.js'
import { DsBotError } from './dsbot-api.js'
import { head } from './diag.js'
import { stat } from 'node:fs/promises'
import { zhMessage } from './messages-zh.js'
import { lanUrl } from './lan.js'
import { bearer, error, html, json, pathOf, readJson } from './http.js'
import { pairPageHtml } from './pair-page.js'
import { pairPayload, qrSvg } from './qr.js'
import { createChatList } from './chat-list.js'
import { MAX_UPLOAD_BYTES } from './attachments.js'
import { randomUUID } from 'node:crypto'

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
  // Phone groups use everyone mode so a plain message (no @) reaches every member —
  // same rule as ds-bot's "everyone" mode; @Name still picks who answers.
  const mode = body.mode === 'admin' ? 'admin' : 'everyone'
  return { members, ...(admin ? { admin } : {}), greet: true, mode, ...(name ? { name } : {}) }
}

/** The fields the phone may change on a Bot, in ds-bot update-bot form. ds-bot checks every value. */
export function updateBotPayload(body = {}) {
  const out = { id: str(body.id) ?? '' }
  for (const key of ['name', 'role', 'instructions', 'model', 'effort']) if (str(body[key]) !== undefined) out[key] = body[key]
  if (str(body.color) !== undefined || body.color === null) out.color = body.color
  if (str(body.shape) !== undefined) out.avatar = { shape: body.shape }
  else if (body.shape === null) out.avatar = null
  return out
}

/** The fields the phone may change on a group chat, in ds-bot update-room form. */
export function updateRoomPayload(body = {}) {
  const ids = v => (Array.isArray(v) ? v.filter(id => typeof id === 'string' && id) : undefined)
  const out = { id: str(body.id) ?? '' }
  if (str(body.name) !== undefined) out.name = body.name
  // ds-bot channel create-room drops mode; phones set everyone via update-room after create.
  if (body.mode === 'admin' || body.mode === 'everyone') out.mode = body.mode
  const add = ids(body.add); if (add?.length) out.add = add
  const remove = ids(body.remove); if (remove?.length) out.remove = remove
  return out
}

/** Message search over group logs and recent Bot chats; small and bounded. */
export function searchHits(q, rows, max = 30) {
  const needle = String(q ?? '').trim().toLowerCase()
  if (!needle) return []
  const out = []
  for (const row of rows) {
    if (typeof row.text !== 'string' || !row.text.toLowerCase().includes(needle)) continue
    const at = row.text.toLowerCase().indexOf(needle)
    const start = Math.max(0, at - 20)
    out.push({ ...row, text: (start > 0 ? '…' : '') + row.text.slice(start, start + 80) })
    if (out.length >= max) break
  }
  return out
}

function publicBot(bot = {}) {
  return {
    id: bot.id, name: bot.name, role: bot.role ?? null, sessionId: bot.sessionId ?? bot.id,
    model: bot.model ?? null, effort: bot.effort || null, agentPreset: bot.agentPreset ?? null, color: bot.color ?? null,
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
  const files = rt.attachments
  const diag = rt.diag ?? { note() {}, snapshot: () => ({}) }
  rt.chatList ??= createChatList({ adapter, dsbot, store, logger: rt.ctx?.logger ?? console })
  const chatList = rt.chatList
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
        const page = await adapter.history({ botId, cursor, limit })
        files?.remember(page.items)
        return json({ ok: true, ...page })
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
          const uploads = Array.isArray(body.attachments) ? body.attachments : []
          const result = await adapter.send({ botId: body.botId, text: body.text, parts: uploads.length > 0 ? sessionId => files.parts(uploads, sessionId) : null })
          files?.used(uploads)
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

    if (method === 'GET' && sub === '/chats/list') {
      return withAuth(request, async (device) => {
        try {
          return json({ ok: true, ...(await chatList.list(device.id)) })
        } catch (err) {
          return error(err.status ?? 500, err.message)
        }
      })
    }

    if (method === 'POST' && sub === '/chats/read') {
      return withAuth(request, async (device) => {
        const body = await readJson(request)
        const kind = body.kind === 'room' ? 'room' : body.kind === 'bot' ? 'bot' : null
        const id = typeof body.id === 'string' ? body.id : ''
        if (!kind || !id) return error(400, 'kind and id required')
        try {
          const seq = Number.isFinite(body.seq) ? body.seq : await chatList.positionOf(kind, id)
          const reads = await store.markRead(device.id, { [`${kind}:${id}`]: seq })
          return json({ ok: true, kind, id, seq: reads[`${kind}:${id}`] ?? seq })
        } catch (err) {
          return error(err.status ?? 500, err.message, zhMessage(err.message) ? { zh: zhMessage(err.message) } : {})
        }
      })
    }

    if (method === 'POST' && sub === '/chats/stop') {
      return withAuth(request, async () => {
        const body = await readJson(request)
        const botId = typeof body.botId === 'string' ? body.botId : ''
        if (!botId) return error(400, 'botId required')
        try {
          return json({ ok: true, ...(await adapter.cancel({ botId })) })
        } catch (err) {
          return error(err.status ?? 500, err.message, { ...(err.code ? { code: err.code } : {}), ...(zhMessage(err.message) ? { zh: zhMessage(err.message) } : {}) })
        }
      })
    }

    if (method === 'POST' && sub === '/rooms/stop') {
      return withAuth(request, async () => dsCall(async () => {
        const body = await readJson(request)
        const roomId = typeof body.roomId === 'string' ? body.roomId : ''
        if (!roomId) throw Object.assign(new Error('roomId required'), { status: 400 })
        let members = typeof body.botId === 'string' && body.botId ? [body.botId] : null
        if (!members) {
          const live = await dsbot.call('state', {})
          members = (live?.work?.rooms?.[roomId] ?? []).map(item => item.botId).filter(Boolean)
        }
        let stopped = 0
        for (const botId of members) {
          if (await dsbot.call('stop-member-work', { roomId, botId })) stopped++
        }
        return { ok: true, roomId, stopped }
      }))
    }

    if (method === 'GET' && sub === '/approvals') {
      return withAuth(request, async (device) => {
        try {
          const view = await chatList.list(device.id)
          return json({ ok: true, approvals: view.approvals ?? [] })
        } catch (err) {
          return error(err.status ?? 500, err.message)
        }
      })
    }

    if (method === 'POST' && sub === '/approvals/answer') {
      return withAuth(request, async () => dsCall(async () => {
        const body = await readJson(request)
        const id = typeof body.id === 'string' ? body.id : ''
        if (!id) throw Object.assign(new Error('id required'), { status: 400 })
        // ds-bot validates the choice ('allow' | 'reject') and whether the request is still open.
        await dsbot.call('approval-answer', { id, choice: body.choice })
        return { ok: true, id, choice: body.choice }
      }))
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

    if (method === 'GET' && sub === '/bots/info') {
      return withAuth(request, async () => {
        const id = new URL(request.url).searchParams.get('id') ?? ''
        const state = await adapter.readState()
        const bot = id ? state?.bots?.[id] : undefined
        if (!bot) return error(404, 'Unknown Bot', { zh: '找不到这个助手' })
        return json({
          ok: true,
          bot: {
            ...publicBot(bot), main: Array.isArray(state.mainBotIds) && state.mainBotIds.includes(bot.id),
            instructions: typeof bot.instructions === 'string' ? bot.instructions : '',
          },
          shapes: AVATAR_SHAPES,
          colors: PICKER_COLORS.map(color => ({ id: color, hex: INK_HEX[color] })),
        })
      })
    }

    if (method === 'POST' && sub === '/bots/update') {
      return withAuth(request, async () => dsCall(async () => {
        const payload = updateBotPayload(await readJson(request))
        if (!payload.id) throw Object.assign(new Error('id required'), { status: 400 })
        const bot = await dsbot.call('update-bot', payload)
        return { ok: true, bot: { ...publicBot(bot), instructions: typeof bot?.instructions === 'string' ? bot.instructions : '' } }
      }))
    }

    if (method === 'POST' && sub === '/rooms/update') {
      return withAuth(request, async () => dsCall(async () => {
        const payload = updateRoomPayload(await readJson(request))
        if (!payload.id) throw Object.assign(new Error('id required'), { status: 400 })
        const value = await dsbot.call('update-room', payload)
        const room = value?.room ?? {}
        return { ok: true, room: { id: room.id ?? payload.id, name: room.name ?? '', members: room.members ?? [], admin: room.admin ?? null, mode: room.mode ?? payload.mode ?? null }, changes: value?.changes ?? null }
      }))
    }

    if (method === 'POST' && sub === '/uploads') {
      return withAuth(request, async () => {
        const url = new URL(request.url)
        const kind = url.searchParams.get('kind') === 'room' ? 'room' : 'bot'
        const id = url.searchParams.get('id') ?? ''
        const state = await adapter.readState()
        const sessionId = kind === 'room'
          ? (state?.rooms?.[id] && state.rooms[id].hidden !== true ? id : null)
          : (state?.bots?.[id] ? (state.bots[id].sessionId ?? id) : null)
        if (!sessionId) return error(404, kind === 'room' ? 'unknown group chat' : 'Unknown Bot', { zh: '找不到这个对话' })
        const declared = Number(request.headers.get('content-length') ?? 0)
        if (declared > MAX_UPLOAD_BYTES) return error(413, 'file too large', { zh: '文件太大了，最多 25 MB' })
        try {
          const bytes = new Uint8Array(await request.arrayBuffer())
          return json({ ok: true, ...(await files.upload({ sessionId, bytes, name: url.searchParams.get('name') })) })
        } catch (err) {
          const remote = err?.code ?? err?.details?.reason
          return error(err.status ?? 400, err.message, { zh: err.zh ?? '这个文件发不出去', ...(remote ? { code: remote } : {}) })
        }
      })
    }

    if (method === 'GET' && sub === '/attachments') {
      return withAuth(request, async () => {
        const id = new URL(request.url).searchParams.get('id') ?? ''
        try {
          const found = id ? await files.read(id) : null
          if (!found) return error(404, 'attachment not found', { zh: '找不到这个附件' })
          return new Response(found.bytes, {
            status: 200,
            headers: {
              'content-type': found.mediaType, 'cache-control': 'private, max-age=86400',
              ...(found.name ? { 'content-disposition': `inline; filename*=UTF-8''${encodeURIComponent(found.name)}` } : {}),
            },
          })
        } catch (err) {
          return error(err.status ?? 500, err.message)
        }
      })
    }

    if (method === 'GET' && sub === '/search') {
      return withAuth(request, async () => {
        const q = new URL(request.url).searchParams.get('q') ?? ''
        if (!q.trim()) return json({ ok: true, hits: [] })
        const state = await adapter.readState()
        const rows = []
        for (const room of Object.values(state?.rooms ?? {})) {
          if (!room?.id || room.hidden === true) continue
          for (const line of [...(room.log ?? [])].reverse()) {
            if (line.kind === undefined && line.text) rows.push({ kind: 'room', id: room.id, name: room.name ?? '', who: line.who === 'User' ? '你' : line.who, text: line.text, seq: line.seq ?? null })
          }
        }
        for (const bot of Object.values(state?.bots ?? {})) {
          let page
          try { page = await adapter.history({ botId: bot.id, limit: 100 }) } catch { continue }
          for (const item of [...(page?.items ?? [])].reverse()) {
            if ((item.kind === 'bot' || item.kind === 'user') && item.text) rows.push({ kind: 'bot', id: bot.id, name: bot.name, who: item.kind === 'user' ? '你' : bot.name, text: item.text, seq: item.seq ?? null })
          }
        }
        return json({ ok: true, hits: searchHits(q, rows) })
      })
    }

    if (method === 'POST' && sub === '/rooms/create') {
      return withAuth(request, async () => dsCall(async () => {
        const body = await readJson(request)
        const payload = createRoomPayload(body)
        // ds-bot's HTTP create-room (channel.js) ignores body.mode and defaults to admin.
        // Phone groups want everyone so a plain message reaches every member; set mode
        // via update-room (which does pass mode) right after create.
        let room = await dsbot.call('create-room', payload)
        if (payload.mode && room?.mode !== payload.mode) {
          try {
            const updated = await dsbot.call('update-room', { id: room.id, mode: payload.mode })
            room = updated?.room ?? { ...room, mode: payload.mode }
          } catch (err) {
            // Keep the room; mode stays whatever create wrote. Surface in diagnostics.
            diag.note('room.mode', { roomId: room.id, wanted: payload.mode, got: room.mode ?? null, error: err.message }, { level: 'warn' })
          }
        }
        return { ok: true, room: { id: room.id, name: room.name, members: room.members ?? [], admin: room.admin ?? null, mode: room.mode ?? payload.mode ?? null } }
      }))
    }

    if (method === 'GET' && sub === '/rooms/history') {
      return withAuth(request, async () => {
        const roomId = new URL(request.url).searchParams.get('roomId') ?? ''
        if (!roomId) return error(400, 'roomId required')
        try {
          const page = await adapter.roomHistory({ roomId })
          files?.remember(page.items)
          return json({ ok: true, ...page })
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
        const uploads = Array.isArray(body.attachments) ? body.attachments.filter(id => typeof id === 'string') : []
        if (!roomId) return error(400, 'roomId required')
        if (!text && uploads.length === 0) return error(400, 'text required')
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
            // Text is forwarded as-is: ds-bot reads "@Name" / "@所有人" from it (shared/mentions.js)
            // to pick who answers. The phone only inserts those strings; it does not send a separate id list.
            if (uploads.length === 0) await dsbot.call('send', { sessionId: roomId, text })
            else {
              // With pictures or files: the same prompt ds-bot's send makes for a group Session,
              // plus the attachment parts, so dsh admits them exactly as for the desktop chat.
              const parts = files.parts(uploads, roomId)
              const sc = rt.ctx?.get?.('sessionController') ?? rt.ctx?.sessionController
              await sc.prompt({ requestId: randomUUID(), sessionId: roomId, mode: 'queue', content: [...(text ? [{ type: 'text', text }] : []), ...parts] }, AbortSignal.timeout(30_000))
              files.used(uploads)
            }
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
    ['/bots/info', ['GET']],
    ['/bots/update', ['POST']],
    ['/rooms/update', ['POST']],
    ['/search', ['GET']],
    ['/uploads', ['POST']],
    ['/attachments', ['GET']],
    ['/rooms/history', ['GET']],
    ['/rooms/send', ['POST']],
    ['/rooms/stop', ['POST']],
    ['/chats/list', ['GET']],
    ['/chats/read', ['POST']],
    ['/chats/stop', ['POST']],
    ['/approvals', ['GET']],
    ['/approvals/answer', ['POST']],
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
