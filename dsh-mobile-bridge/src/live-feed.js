import { displayItems } from './chat-format.js'

const KEEPALIVE_MS = 20_000
const LIVE_DELAY_MS = 150
const MAX_QUEUED = 512
const encoder = new TextEncoder()

/**
 * SSE feed aligned with ds-bot /api/bot/feed events:
 * hello, revision (n/a without ds-bot rt), live, message, stream
 * plus optional test ticks when mode=test.
 */
export function createLiveFeed({ ctx, adapter }) {
  /** @type {Set<{controller, botId: string|null, test: boolean}>} */
  const clients = new Set()

  const send = (entry, chunk) => {
    if ((entry.controller.desiredSize ?? 0) < -MAX_QUEUED) {
      leave(entry)
      return
    }
    try { entry.controller.enqueue(chunk) } catch { clients.delete(entry) }
  }

  const publish = (event, data, { botId } = {}) => {
    const chunk = encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    for (const entry of clients) {
      if (entry.botId && botId && entry.botId !== botId) continue
      if (entry.botId && data?.botId && entry.botId !== data.botId) continue
      send(entry, chunk)
    }
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
      if (clients.size === 0) return
      const owners = await adapter.chatOwners()
      const botId = owners.get(session.id)
      if (botId === undefined) return
      const state = await adapter.readState()
      for (const item of displayItems(session.id, [event], state?.exchangeTurns ?? {})) {
        publish('message', { botId, sessionId: session.id, item: claimPending(botId, item) }, { botId })
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

  function openStream(request, { device, botId = null, test = false } = {}) {
    let entry
    let tick
    let keepalive
    const body = new ReadableStream({
      async start(controller) {
        entry = { controller, botId, test }
        clients.add(entry)
        let revision = null
        try {
          const state = await adapter.readState()
          revision = state?.revision ?? null
        } catch { /* */ }
        controller.enqueue(encoder.encode(
          `event: hello\ndata: ${JSON.stringify({ deviceId: device.id, botId, mode: test ? 'test' : 'live', revision })}\n\n`,
        ))
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
      },
    })
    request.signal.addEventListener('abort', () => {
      clearInterval(keepalive)
      clearInterval(tick)
      if (entry) leave(entry)
    }, { once: true })
    return new Response(body, {
      status: 200,
      headers: {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-store',
      },
    })
  }

  return { openStream, publish, notePending, claimPending, clientCount: () => clients.size }
}
