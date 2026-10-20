/** Slim chat items for mobile, aligned with ds-bot displayItems semantics. */
import { publicRef } from './attachments.js'

/** Attachment refs of one message's content; `_ref` is kept for the bridge only (attachments.remember). */
export function attachmentsOf(content) {
  const out = []
  for (const block of Array.isArray(content) ? content : []) {
    const ref = publicRef(block)
    if (ref) out.push({ ...ref, _ref: block.attachment })
  }
  return out
}

// ds-bot groups.js / images.js lines that stand for a file or a picture in the group log.
const FILE_LINE = /^\[File "(.*)" \((\d+) bytes\) attached\]$/
const IMAGE_OPEN = /^\[Image ("(.*)"|sha256:([0-9a-f]{8}))(:.*\]|, read by .*:\])$/
const IMAGE_CLOSE = /^\[End of image ("(.*)"|sha256:([0-9a-f]{8}))\]$/

/** A group log line's text without file lines and picture readings, and the pictures it named. */
export function splitRoomText(text) {
  const lines = String(text ?? '').split('\n')
  const keep = []
  const images = []
  let inside = false
  for (const line of lines) {
    if (inside) { if (IMAGE_CLOSE.test(line)) inside = false; continue }
    if (FILE_LINE.test(line)) continue
    const open = IMAGE_OPEN.exec(line)
    if (open) {
      images.push(open[2] !== undefined ? { name: open[2] } : { prefix: open[3] })
      if (!open[4].startsWith(':')) inside = true
      continue
    }
    keep.push(line)
  }
  return { text: keep.join('\n').trim(), images }
}

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
      const attachments = attachmentsOf(content)
      items.push({
        kind: 'user',
        text: textOf(content),
        ...(images > 0 ? { images } : {}),
        ...(attachments.length > 0 ? { attachments } : {}),
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
export function roomItem(line, pictures = []) {
  if (!line || typeof line !== 'object') return null
  const seq = Number.isFinite(line.seq) ? line.seq : undefined
  if (line.kind === undefined) {
    const { text, images } = splitRoomText(line.text)
    const files = (Array.isArray(line.files) ? line.files : []).map(file => attachmentsOf([{ type: 'file', attachment: file }])[0]).filter(Boolean)
    // A picture the group log names by label; its ref comes from the group's own Session.
    const shots = images.map((label) => {
      const ref = pictures.find(p => (label.name !== undefined ? p.name === label.name : String(p.attachmentId).startsWith(`sha256:${label.prefix}`)))
      return ref ? attachmentsOf([{ type: 'image', attachment: ref }])[0] : { type: 'image', id: null, name: label.name ?? null }
    })
    const attachments = [...shots, ...files]
    const extra = attachments.length > 0 ? { attachments } : {}
    if (line.who === 'User' && !line.botId) return { kind: 'user', text, seq, ...extra }
    return {
      kind: 'member',
      botId: line.botId ?? null,
      name: line.who ?? null,
      text,
      ...extra,
      seq,
      ...(Number.isFinite(line.length) ? { cut: true } : {}),
    }
  }
  if (ROOM_EVENT_KINDS.has(line.kind)) {
    return { kind: 'event', role: line.kind, botId: line.botId ?? null, name: line.who ?? null, seq }
  }
  return null
}

export function roomItems(log, pictures = []) {
  return (Array.isArray(log) ? log : []).map(line => roomItem(line, pictures)).filter(Boolean)
}
