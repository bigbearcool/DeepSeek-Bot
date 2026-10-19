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
