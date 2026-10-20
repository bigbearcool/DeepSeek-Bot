import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { displayItems, partsOfBot, roomItems } from './chat-format.js'

const HISTORY_TIMEOUT_MS = 20_000
const PAGE_SIZE = 60

/**
 * Read-only team + chat history from ds-bot state + sessionController.page,
 * send via sessionController.prompt when available.
 */
export function createDsBotAdapter({ dshHome, botHome, ctx, logger = console } = {}) {
  const statePath = join(botHome ?? join(dshHome, 'bot'), 'state.json')

  async function readState() {
    try {
      return JSON.parse(await readFile(statePath, 'utf8'))
    } catch (error) {
      if (error?.code === 'ENOENT') return null
      throw error
    }
  }

  function sessionController() {
    return ctx?.get?.('sessionController') ?? ctx?.sessionController
  }

  async function team() {
    const state = await readState()
    if (state === null) {
      return {
        available: false,
        reason: 'ds-bot state.json not found; is ds-bot installed and opened once?',
        bots: [],
        rooms: [],
        mainBotIds: [],
        revision: null,
      }
    }
    const bots = Object.values(state.bots ?? {}).map(bot => ({
      id: bot.id,
      name: bot.name,
      role: bot.role ?? null,
      sessionId: bot.sessionId ?? bot.id,
      model: bot.model ?? null,
      agentPreset: bot.agentPreset ?? null,
      color: bot.color ?? null,
      shape: bot.avatar?.shape ?? null,
      main: Array.isArray(state.mainBotIds) && state.mainBotIds.includes(bot.id),
    }))
    const rooms = Object.values(state.rooms ?? {}).filter(room => room?.id && room.hidden !== true).map(room => roomView(state, room))
    return {
      available: true,
      bots,
      rooms,
      mainBotIds: state.mainBotIds ?? [],
      revision: state.revision ?? null,
      statePath,
    }
  }

  function roomView(state, room) {
    const last = (room.log ?? []).filter(line => line.kind === undefined).at(-1)
    const names = (room.members ?? []).map(id => state.bots?.[id]?.name).filter(Boolean)
    return {
      id: room.id,
      name: room.name ?? '',
      members: room.members ?? [],
      memberNames: names,
      admin: room.admin ?? null,
      mode: room.mode ?? null,
      createdAt: room.createdAt ?? null,
      ...(last ? { preview: last.who === 'User' ? last.text : `${last.who}：${last.text}` } : {}),
    }
  }

  /** Map hidden member Session id → { roomId, botId } (room.sessions), for live replies. */
  async function groupOwners() {
    const state = await readState()
    const map = new Map()
    for (const room of Object.values(state?.rooms ?? {})) {
      for (const [botId, sessionId] of Object.entries(room?.sessions ?? {})) {
        map.set(sessionId, { roomId: room.id, botId, name: state.bots?.[botId]?.name ?? null })
      }
    }
    return map
  }

  /** Latest lines of a group chat (ds-bot keeps the last 60 in state.json). */
  async function roomHistory({ roomId } = {}) {
    const state = await readState()
    if (state === null) return { available: false, roomId, items: [], seq: null, reason: 'ds-bot state.json not found' }
    const room = state.rooms?.[roomId]
    if (!room) {
      const err = new Error('unknown group chat')
      err.status = 404
      throw err
    }
    return { available: true, roomId, room: roomView(state, room), items: roomItems(room.log), seq: room.seq ?? null }
  }

  /** Map sessionId → botId for own chats (current + earlier segments). */
  async function chatOwners() {
    const state = await readState()
    const map = new Map()
    if (!state?.bots) return map
    for (const bot of Object.values(state.bots)) {
      for (const part of partsOfBot(state, bot.id)) {
        map.set(part.sessionId, bot.id)
      }
    }
    return map
  }

  async function partPage(state, parts, index, cursor) {
    const part = parts[index]
    const sc = sessionController()
    if (!sc?.page) {
      return {
        items: [],
        cursor: null,
        stub: true,
        reason: 'sessionController.page not available',
      }
    }
    // throughSeq: prefer live agent seq (same as ds-bot partPage); else a high bound.
    let throughSeq = Number.MAX_SAFE_INTEGER - 1
    if (typeof sc.resolveAgent === 'function') {
      try {
        const resolved = await sc.resolveAgent(part.sessionId)
        if (resolved && !('error' in resolved)) {
          const seq = resolved.agent?.session?.seq
          if (Number.isFinite(seq)) throughSeq = Number(seq) - 1
        }
      } catch {
        /* keep bound */
      }
    }
    const page = await sc.page({
      address: { kind: 'session', sessionId: part.sessionId },
      throughSeq,
      ...(Number.isInteger(cursor?.beforeSeq) ? { beforeSeq: cursor.beforeSeq } : {}),
      maxMessages: PAGE_SIZE,
    }, AbortSignal.timeout(HISTORY_TIMEOUT_MS))
    const events = (page.records ?? []).map(record => record.event ?? record)
    const items = displayItems(part.sessionId, events, state.exchangeTurns ?? {})
    const more = page.hasMore && events.length > 0
    if (!more && index > 0) {
      items.unshift({ kind: 'divider', time: part.startedAt })
    }
    const nextCursor = more
      ? { part: index, beforeSeq: events[0]?.seq }
      : index > 0
        ? { part: index - 1 }
        : null
    return { items, cursor: nextCursor, sessionId: part.sessionId, stub: false }
  }

  async function history({ botId, cursor, limit = PAGE_SIZE } = {}) {
    void limit
    const state = await readState()
    if (state === null) {
      return {
        available: false,
        botId,
        items: [],
        nextCursor: null,
        stub: true,
        reason: 'ds-bot state.json not found',
      }
    }
    const parts = partsOfBot(state, botId)
    if (parts.length === 0) {
      return { available: true, botId, items: [], nextCursor: null, stub: false, reason: 'unknown bot' }
    }
    let parsed = cursor
    if (typeof cursor === 'string' && cursor !== '') {
      try { parsed = JSON.parse(cursor) } catch { parsed = null }
    }
    const index = Number.isInteger(parsed?.part) ? parsed.part : parts.length - 1
    if (index < 0 || index >= parts.length) {
      return {
        available: true,
        botId,
        sessionId: parts.at(-1).sessionId,
        items: [],
        nextCursor: null,
        stub: false,
      }
    }
    try {
      const page = await partPage(state, parts, index, parsed)
      return {
        available: true,
        botId,
        sessionId: parts.at(-1).sessionId,
        items: page.items,
        nextCursor: page.cursor,
        stub: page.stub === true,
        ...(page.reason ? { reason: page.reason } : {}),
      }
    } catch (error) {
      logger.warn?.(`[mobile-bridge] history failed: ${error?.message ?? error}`)
      return {
        available: true,
        botId,
        sessionId: parts.at(-1).sessionId,
        items: [],
        nextCursor: null,
        stub: true,
        reason: String(error?.message ?? error),
      }
    }
  }

  async function send({ botId, text } = {}) {
    const trimmed = String(text ?? '').trim()
    if (!trimmed) {
      const err = new Error('text required')
      err.status = 400
      throw err
    }
    const snapshot = await team()
    const bot = snapshot.bots.find(item => item.id === botId)
    if (!bot) {
      const err = new Error(snapshot.available ? 'unknown bot' : snapshot.reason)
      err.status = snapshot.available ? 404 : 503
      throw err
    }
    const sc = sessionController()
    if (sc?.prompt) {
      await sc.prompt({
        requestId: randomUUID(),
        sessionId: bot.sessionId,
        mode: 'queue',
        content: [{ type: 'text', text: trimmed }],
      }, AbortSignal.timeout(30_000))
      return { ok: true, botId, sessionId: bot.sessionId, via: 'sessionController.prompt' }
    }
    return {
      ok: false,
      stub: true,
      status: 501,
      botId,
      sessionId: bot.sessionId,
      reason: 'sessionController.prompt not available in this host',
    }
  }

  /** Newest events of one Session (dsh sessionController.page), for the list's preview/unread. */
  async function tail(sessionId, maxMessages = 20) {
    const sc = sessionController()
    if (!sc?.page) return { events: [] }
    // Same bound as ds-bot's own partPage: the live Session's seq (page refuses a bound
    // past the persisted cursor).
    let throughSeq
    if (typeof sc.resolveAgent === 'function') {
      const resolved = await sc.resolveAgent(sessionId)
      const seq = resolved && !('error' in resolved) ? resolved.agent?.session?.seq : undefined
      if (Number.isFinite(seq)) throughSeq = Number(seq) - 1
    }
    if (throughSeq === undefined) throughSeq = Number.MAX_SAFE_INTEGER - 1
    if (throughSeq < 0) return { events: [] }
    const page = await sc.page({ address: { kind: 'session', sessionId }, throughSeq, maxMessages }, AbortSignal.timeout(HISTORY_TIMEOUT_MS))
    return { events: (page.records ?? []).map(record => record.event ?? record) }
  }

  /**
   * Stop a Bot's running reply in its own chat: dsh sessionController.cancel, the same call
   * dsh's own stop button makes (pending inbox kept). Not attached = nothing is running.
   */
  async function cancel({ botId } = {}) {
    const state = await readState()
    const bot = state?.bots?.[botId]
    if (!bot) throw Object.assign(new Error('Unknown Bot'), { status: 404 })
    const sessionId = bot.sessionId ?? bot.id
    const sc = sessionController()
    if (typeof sc?.cancel !== 'function') throw Object.assign(new Error('stop is not available in this host'), { status: 501 })
    try {
      const result = await sc.cancel({ sessionId })
      return { stopped: result?.accepted !== false, botId, sessionId }
    } catch (error) {
      if (error?.code === 'session/not-found') return { stopped: false, botId, sessionId, idle: true }
      throw Object.assign(new Error(error?.message ?? String(error)), { status: 409, code: error?.code })
    }
  }

  return { statePath, team, history, send, chatOwners, groupOwners, roomHistory, readState, tail, cancel }
}
