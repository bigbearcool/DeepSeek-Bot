/** Slim chat items for mobile, aligned with ds-bot displayItems semantics. */

const REPLY_KIND = 'user-question-reply'
const SOURCE_KIND = 'bot'

export function textOf(content) {
  return (Array.isArray(content) ? content : [])
    .filter(block => block?.type === 'text' && typeof block.text === 'string')
    .map(block => block.text)
    .join('\n')
    .trim()
}

/**
 * @param {string} partId session id (for exchangeTurns lookup)
 * @param {any[]} events
 * @param {Record<string, number[]>} exchangeTurns
 */
export function displayItems(partId, events, exchangeTurns = {}) {
  const hidden = new Set(exchangeTurns[partId] ?? [])
  const items = []
  for (const event of events) {
    if (event.type === 'assistant/message') {
      if (hidden.has(event.data?.turn)) continue
      const content = Array.isArray(event.data?.message?.content) ? event.data.message.content : []
      for (const block of content) {
        if (block?.type === 'text' && typeof block.text === 'string' && block.text.trim() !== '') {
          items.push({ kind: 'bot', text: block.text, time: event.time, seq: event.seq })
        }
      }
      continue
    }
    if (event.type !== 'user/message') continue
    const source = event.data?.source
    if (source?.kind === 'user') {
      const content = Array.isArray(event.data.content) ? event.data.content : []
      const images = content.filter(block => block?.type === 'image').length
      items.push({
        kind: 'user',
        text: textOf(content),
        ...(images > 0 ? { images } : {}),
        time: event.time,
        seq: event.seq,
      })
    } else if (source?.kind === REPLY_KIND) {
      items.push({ kind: 'answer', text: textOf(event.data.content), time: event.time, seq: event.seq })
    } else if (
      source?.kind === SOURCE_KIND
      && source.handoff !== true
      && source.deferred !== true
      && ['request', 'reply', 'report', 'stopped'].includes(source.role)
    ) {
      items.push({
        kind: 'event',
        role: source.role,
        botId: source.senderSessionId ?? null,
        name: source.senderName ?? null,
        time: event.time,
        seq: event.seq,
      })
    }
  }
  return items
}

/** Own-chat parts oldest-first; last is current. From ds-bot state.json shape. */
export function partsOfBot(state, botId) {
  const bot = state?.bots?.[botId]
  if (!bot) return []
  const earlier = bot.segments ?? []
  const current = bot.sessionId ?? botId
  const parts = earlier.map((segment, index) => ({
    sessionId: segment.sessionId,
    number: index + 1,
    startedAt: earlier[index - 1]?.endedAt ?? bot.createdAt,
  }))
  parts.push({
    sessionId: current,
    number: parts.length + 1,
    startedAt: earlier.at(-1)?.endedAt ?? bot.createdAt,
  })
  return parts
}

const ROOM_EVENT_KINDS = new Set(['lookup', 'resumed', 'cut', 'stopped', 'done'])

/**
 * One line of a ds-bot group chat log (state.rooms[id].log) as a mobile chat item.
 * Same reading as ds-bot groups.js: who 'User' is the user, a line without a kind is a
 * member's message, the other kinds are events of the round.
 */
export function roomItem(line) {
  if (!line || typeof line !== 'object') return null
  const seq = Number.isFinite(line.seq) ? line.seq : undefined
  if (line.kind === undefined) {
    if (line.who === 'User' && !line.botId) return { kind: 'user', text: String(line.text ?? ''), seq }
    return {
      kind: 'member',
      botId: line.botId ?? null,
      name: line.who ?? null,
      text: String(line.text ?? ''),
      seq,
      ...(Number.isFinite(line.length) ? { cut: true } : {}),
    }
  }
  if (ROOM_EVENT_KINDS.has(line.kind)) {
    return { kind: 'event', role: line.kind, botId: line.botId ?? null, name: line.who ?? null, seq }
  }
  return null
}

export function roomItems(log) {
  return (Array.isArray(log) ? log : []).map(roomItem).filter(Boolean)
}
