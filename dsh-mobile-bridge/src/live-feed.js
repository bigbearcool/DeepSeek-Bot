import { displayItems, roomItem } from './chat-format.js'
import { relayCall, relayResult, roundItems, roundSummary } from './room-round.js'

const KEEPALIVE_MS = 20_000
const LIVE_DELAY_MS = 150
const MAX_QUEUED = 512
/** How often group chat logs are checked while a phone has a group chat open. */
export const ROOM_POLL_MS = 1000
const encoder = new TextEncoder()

/**
 * SSE feed aligned with ds-bot /api/bot/feed events:
 * hello, revision (n/a without ds-bot rt), live, message, stream
 * plus optional test ticks when mode=test.
 */
export function createLiveFeed({ ctx, adapter, diag = { note() {} }, files = null }) {
  // Attachment refs in live items become openable, and the bridge-only part is dropped.
  const shown = item => { if (item?.attachments) files?.remember([item]); if (item?.attachments) item.attachments = item.attachments.map(({ _ref, ...rest }) => rest); return item }
  /** @type {Set<{controller, botId: string|null, test: boolean}>} */
  const clients = new Set()

  const send = (entry, chunk) => {
    if ((entry.controller.desiredSize ?? 0) < -MAX_QUEUED) {
      leave(entry)
      return
    }
    try { entry.controller.enqueue(chunk) } catch { clients.delete(entry) }
  }

  /**
   * Filtering: a phone watching a group chat (roomId) gets only that group's events; a
   * phone watching a Bot gets only that Bot's own-chat events (never group events, whose
   * botId names the member speaking). An unfiltered stream gets everything.
   */
  const publish = (event, data, { botId } = {}) => {
    const chunk = encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    const roomId = data?.roomId ?? null
    let sent = 0
    for (const entry of clients) {
      if (entry.roomId) {
        if (roomId !== entry.roomId) continue
      } else if (entry.botId) {
        if (roomId) continue
        if (botId && entry.botId !== botId) continue
        if (data?.botId && entry.botId !== data.botId) continue
      }
      send(entry, chunk)
      sent++
    }
    if (roomId && event !== 'stream') diag.note('publish', { roomId, event, phones: sent, ...(data?.item ? { item: data.item.kind, role: data.item.role, seq: data.item.seq } : {}), ...(data?.phase ? { phase: data.phase } : {}) })
  }

  const leave = (entry) => {
    clients.delete(entry)
    try { entry.controller.close() } catch { /* */ }
  }

  let liveTimer
  const bumpLive = () => {
    if (clients.size === 0 || liveTimer !== undefined) return
    liveTimer = setTimeout(() => {
      liveTimer = undefined
      publish('live', {})
    }, LIVE_DELAY_MS)
  }

  const writing = new Map()
  /** botId -> [{ text, clientMessageId, at }] awaiting their user/message echo. */
  const pending = new Map()
  const notePending = (botId, text, clientMessageId) => {
    if (!botId || !clientMessageId) return
    const now = Date.now()
    const list = (pending.get(botId) ?? []).filter(p => now - p.at < 120_000)
    list.push({ text, clientMessageId, at: now })
    pending.set(botId, list)
  }
  const claimPending = (botId, item) => {
    if (item?.kind !== 'user') return item
    const list = pending.get(botId)
    if (!list?.length) return item
    const i = list.findIndex(p => p.text === (item.text ?? '').trim())
    if (i < 0) return item
    const [hit] = list.splice(i, 1)
    return { ...item, clientMessageId: hit.clientMessageId }
  }

  // Subscribe once for the process; fan-out to connected phones.
  if (typeof ctx.on === 'function') {
    ctx.on('session/event', async (session, event) => {
      if (event?.type === 'tool/call' || event?.type === 'tool/result') {
        if (await onRoomEvent(session?.id, event)) return
      }
      if (clients.size === 0) return
      const owners = await adapter.chatOwners()
      const botId = owners.get(session.id)
      if (botId === undefined) return
      const state = await adapter.readState()
      for (const item of displayItems(session.id, [event], state?.exchangeTurns ?? {})) {
        publish('message', { botId, sessionId: session.id, item: claimPending(botId, shown(item)) }, { botId })
      }
      if (event.type === 'tool/call') bumpLive()
    })

    ctx.on('agent/status', async ({ agent }) => {
      if (clients.size === 0) return
      const owners = await adapter.chatOwners()
      if (owners.has(agent.id)) bumpLive()
    })

    ctx.on('agent/assistant-stream', async ({ agent, frame }) => {
      if (clients.size === 0) return
      const owners = await adapter.chatOwners()
      const botId = owners.get(agent.id)
      if (botId === undefined) return
      const state = await adapter.readState()
      const hidden = new Set(state?.exchangeTurns?.[agent.id] ?? [])

      if (frame.type === 'start') {
        if (hidden.has(frame.turn)) return
        writing.set(frame.attemptId, { botId, sessionId: agent.id })
        publish('stream', { botId, sessionId: agent.id, attemptId: frame.attemptId, phase: 'start' }, { botId })
        return
      }
      const target = writing.get(frame.attemptId)
      if (target === undefined) return
      if (frame.type === 'chunk' && frame.chunk?.type === 'text-delta') {
        publish('stream', { ...target, attemptId: frame.attemptId, text: frame.chunk.text }, { botId: target.botId })
      } else if (frame.type === 'end') {
        writing.delete(frame.attemptId)
        publish('stream', { ...target, attemptId: frame.attemptId, phase: 'end' }, { botId: target.botId })
      }
    })
  }

  // ---- group chats ----
  /** roomId → last log seq already published. */
  const roomSeen = new Map()
  let roomTimer
  const roomClients = () => [...clients].filter(e => e.roomId).length
  /** roomId → round now running (relay callId) and when it began; for diagnostics. */
  const rounds = new Map()
  let checking = null
  let lastStateError = null
  /** One check at a time: overlapping reads of state.json would publish a line twice. */
  function checkRooms() {
    if (checking) return checking.then(() => runCheck())
    checking = runCheck().finally(() => { checking = null })
    return checking
  }
  async function runCheck() {
    const watched = new Set([...clients].map(e => e.roomId).filter(Boolean))
    if (watched.size === 0) return
    let state
    try {
      state = await adapter.readState()
      lastStateError = null
    } catch (error) {
      const message = String(error?.message ?? error)
      if (message !== lastStateError) diag.note('state.error', { error: message }, { level: 'warn' })
      lastStateError = message
      return
    }
    for (const roomId of watched) {
      const room = state?.rooms?.[roomId]
      if (!room) continue
      const seen = roomSeen.get(roomId)
      const log = Array.isArray(room.log) ? room.log : []
      if (seen === undefined) {
        roomSeen.set(roomId, room.seq ?? log.at(-1)?.seq ?? 0)
        continue
      }
      for (const line of log) {
        if (!(line.seq > seen)) continue
        diag.note('poll.line', { roomId, seq: line.seq, who: line.botId ? 'member' : line.who === 'User' ? 'user' : 'other', ...(line.kind ? { lineKind: line.kind } : {}) })
        const item = shown(roomItem(line))
        if (item) publish('message', { roomId, item: claimPending(`room:${roomId}`, item) })
      }
      roomSeen.set(roomId, Math.max(seen, room.seq ?? 0, log.at(-1)?.seq ?? 0))
    }
  }

  /** Lines after `since` for a phone that (re)connects, so a dropped stream misses nothing. */
  async function catchUp(entry, roomId, since) {
    let state
    try { state = await adapter.readState() } catch { return }
    const log = state?.rooms?.[roomId]?.log
    if (!Array.isArray(log)) return
    const lines = log.filter(line => line.seq > since)
    for (const line of lines) {
      const item = shown(roomItem(line))
      if (item) send(entry, encoder.encode(`event: message\ndata: ${JSON.stringify({ roomId, item, catchUp: true })}\n\n`))
    }
    if (lines.length) diag.note('catchup', { roomId, since, lines: lines.length })
  }

  /** ds-bot's round in the group's own Session: start, and its outcome (room-round.js). */
  async function onRoomEvent(sessionId, event) {
    if (!sessionId) return false
    const call = relayCall(event)
    const result = call ? null : relayResult(event)
    if (!call && !result) return false
    let state
    try { state = await adapter.readState() } catch { state = null }
    if (!state?.rooms?.[sessionId]) return false
    const roomId = sessionId
    if (call) {
      rounds.set(roomId, { callId: call.callId, at: Date.now() })
      diag.note('round.start', { roomId, callId: call.callId }, { level: 'info' })
      publish('round', { roomId, phase: 'start', callId: call.callId })
      return true
    }
    rounds.delete(roomId)
    const summary = roundSummary(result)
    diag.note('round.end', { roomId, callId: result.callId, ...summary }, { level: summary.failed.length || summary.replied === 0 ? 'warn' : 'info' })
    // Logged lines first, then the round-only events, then the end marker.
    await checkRooms()
    for (const item of roundItems(result)) publish('message', { roomId, item })
    // A member whose model call failed never sends its stream end; close its bubble here.
    for (const [attemptId, owner] of roomWriting) {
      if (owner.roomId !== roomId) continue
      roomWriting.delete(attemptId)
      publish('stream', { roomId, botId: owner.botId, name: owner.name, attemptId, phase: 'end', outcome: 'round-ended' })
    }
    publish('round', { roomId, phase: 'end', callId: result.callId, ...summary })
    return true
  }
  const startRoomPoll = () => {
    if (roomTimer) return
    roomTimer = setInterval(() => { void checkRooms() }, ROOM_POLL_MS)
    roomTimer.unref?.()
  }
  const stopRoomPollIfIdle = () => {
    if (roomClients() > 0 || !roomTimer) return
    clearInterval(roomTimer)
    roomTimer = undefined
    roomSeen.clear()
  }

  // A member writing its reply in its hidden group Session streams into the group chat.
  const roomWriting = new Map()
  if (typeof ctx.on === 'function') {
    ctx.on('agent/assistant-stream', async ({ agent, frame }) => {
      if (roomClients() === 0) return
      if (frame.type === 'start') {
        const owner = (await adapter.groupOwners?.())?.get(agent.id)
        if (!owner) {
          // Not a member's group Session (or state.json does not know it yet).
          const owned = (await adapter.chatOwners?.())?.has?.(agent.id)
          if (!owned) diag.note('stream.miss', { sessionId: agent.id, attemptId: frame.attemptId })
          return
        }
        diag.note('stream.start', { roomId: owner.roomId, botId: owner.botId, attemptId: frame.attemptId })
        roomWriting.set(frame.attemptId, owner)
        publish('stream', { roomId: owner.roomId, botId: owner.botId, name: owner.name, attemptId: frame.attemptId, phase: 'start' })
        return
      }
      const owner = roomWriting.get(frame.attemptId)
      if (!owner) return
      const base = { roomId: owner.roomId, botId: owner.botId, name: owner.name, attemptId: frame.attemptId }
      if (frame.type === 'chunk' && frame.chunk?.type === 'text-delta') publish('stream', { ...base, text: frame.chunk.text })
      else if (frame.type === 'end') {
        roomWriting.delete(frame.attemptId)
        diag.note('stream.end', { roomId: owner.roomId, botId: owner.botId, attemptId: frame.attemptId, outcome: frame.outcome?.kind ?? null })
        publish('stream', { ...base, phase: 'end', ...(frame.outcome?.kind ? { outcome: frame.outcome.kind } : {}) })
        void checkRooms()
      }
    })
  }

  function openStream(request, { device, botId = null, roomId = null, since = null, test = false } = {}) {
    let entry
    let tick
    let keepalive
    const body = new ReadableStream({
      async start(controller) {
        entry = { controller, botId, roomId, test }
        clients.add(entry)
        if (roomId) {
          diag.note('watch', { roomId, deviceId: device?.id ?? null, ...(since !== null ? { since } : {}) })
          startRoomPoll()
          void checkRooms()
        }
        let revision = null
        try {
          const state = await adapter.readState()
          revision = state?.revision ?? null
        } catch { /* */ }
        controller.enqueue(encoder.encode(
          `event: hello\ndata: ${JSON.stringify({ deviceId: device.id, botId, roomId, mode: test ? 'test' : 'live', revision })}\n\n`,
        ))
        if (roomId && Number.isFinite(since)) await catchUp(entry, roomId, since)
        keepalive = setInterval(() => {
          try { controller.enqueue(encoder.encode(': keepalive\n\n')) } catch { /* */ }
        }, KEEPALIVE_MS)
        keepalive.unref?.()
        if (test) {
          tick = setInterval(() => {
            try {
              controller.enqueue(encoder.encode(`event: test\ndata: ${JSON.stringify({ t: Date.now() })}\n\n`))
            } catch { /* */ }
          }, 15_000)
          tick.unref?.()
        }
      },
      cancel() {
        clearInterval(keepalive)
        clearInterval(tick)
        if (entry) clients.delete(entry)
        stopRoomPollIfIdle()
      },
    })
    request.signal.addEventListener('abort', () => {
      clearInterval(keepalive)
      clearInterval(tick)
      if (entry) leave(entry)
      stopRoomPollIfIdle()
    }, { once: true })
    return new Response(body, {
      status: 200,
      headers: {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-store',
      },
    })
  }

  function status() {
    return {
      phones: clients.size,
      roomPhones: roomClients(),
      watching: [...new Set([...clients].map(e => e.roomId).filter(Boolean))],
      polling: roomTimer !== undefined,
      roomSeen: Object.fromEntries(roomSeen),
      rounds: Object.fromEntries(rounds),
      writing: roomWriting.size,
      lastStateError,
    }
  }

  return { openStream, publish, notePending, claimPending, checkRooms, onRoomEvent, status, clientCount: () => clients.size }
}
