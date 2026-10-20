/**
 * Conversation list for the phone: every Bot chat and group chat, newest activity first,
 * with the last message as a preview, an unread count per device, whether a reply is
 * running, and the approvals waiting.
 *
 * Sources (nothing re-derived that ds-bot already knows):
 * - group chats: ds-bot's room view (`preview` = last log line without a kind, same
 *   string ds-bot's sidebar shows) and the log `seq`; activity time is the group Session's
 *   last event time.
 * - Bot chats: the Bot's current own-chat Session read through dsh
 *   `sessionController.page` (last displayable message, its event `seq` and time).
 * - running / approvals: ds-bot `state` live part (`work.bots`, `work.rooms`,
 *   `work.approvals`).
 */
import { displayItems } from './chat-format.js'

export const UNREAD_SCAN = 40
const PREVIEW_MAX = 80

const clip = (text) => {
  const one = String(text ?? '').replace(/\s+/g, ' ').trim()
  return one.length > PREVIEW_MAX ? `${one.slice(0, PREVIEW_MAX)}…` : one
}

/** Tool names → plain words for the approval card (no tool ids on screen). */
export function actionWords(tool) {
  const t = String(tool ?? '').toLowerCase()
  if (/bash|shell|pwsh|terminal|exec|command/.test(t)) return '运行一条命令'
  if (/write|edit|replace|patch|delete|remove|move|mkdir/.test(t)) return '修改文件'
  if (/web|fetch|browse|search/.test(t)) return '访问网络'
  if (/read|list|glob|grep/.test(t)) return '查看文件'
  return '做一个需要你同意的操作'
}

/** dsh's sandbox escalation reason is "escalate sandbox to <mode>: <why>"; keep the why. */
export function plainReason(reason) {
  const text = String(reason ?? '').trim()
  const m = /^escalate sandbox to [\w-]+:\s*(.*)$/s.exec(text)
  return clip(m ? m[1] : text)
}

export function approvalView(entry, state) {
  const bot = state?.bots?.[entry.botId]
  const room = entry.roomId ? state?.rooms?.[entry.roomId] : undefined
  const detail = entry.detail?.text ?? entry.detail?.code ?? ''
  return {
    id: entry.id,
    botId: entry.botId ?? null,
    botName: bot?.name ?? null,
    roomId: entry.roomId ?? null,
    roomName: room?.name ?? null,
    action: actionWords(entry.tool),
    reason: plainReason(entry.reason),
    detail: String(detail).slice(0, 600),
    at: entry.at ?? null,
    // false: a Bot's own chat keeps dsh's card on the computer; ds-bot only notes it.
    answerable: entry.answerable === true,
  }
}

/** Unread member messages in a group after `mark` (user's own lines never count). */
export function roomUnread(log, mark) {
  if (!Number.isFinite(mark)) return 0
  return (Array.isArray(log) ? log : []).filter(line => line && line.kind === undefined && line.who !== 'User' && Number(line.seq) > mark).length
}

/** Unread Bot replies after `mark`, from the newest page items. */
export function botUnread(items, mark) {
  if (!Number.isFinite(mark)) return 0
  return items.filter(item => item.kind === 'bot' && Number(item.seq) > mark).length
}

export function createChatList({ adapter, dsbot, store, logger = console }) {
  /** sessionId → { at, value } — the tail of a Session, kept briefly. */
  const tails = new Map()
  const TAIL_TTL_MS = 2000

  async function tailOf(sessionId, exchangeTurns) {
    const hit = tails.get(sessionId)
    if (hit && Date.now() - hit.at < TAIL_TTL_MS) return hit.value
    let value = { items: [], seq: null, time: null }
    try {
      const page = await adapter.tail(sessionId, UNREAD_SCAN)
      const events = page.events
      const items = displayItems(sessionId, events, exchangeTurns ?? {})
      const lastEvent = events.at(-1)
      // Activity = the last message, not a resume/attach record written when a Session wakes.
      const lastMessage = [...events].reverse().find(event => event?.type === 'user/message' || event?.type === 'assistant/message')
      value = { items, seq: Number.isFinite(lastEvent?.seq) ? lastEvent.seq : null, time: lastMessage?.time ?? null }
    } catch (err) {
      logger.warn?.(`[mobile-bridge] list: tail of ${sessionId} failed: ${err?.message ?? err}`)
    }
    tails.set(sessionId, { at: Date.now(), value })
    return value
  }

  async function liveView() {
    if (!dsbot?.available?.()) return null
    try { return await dsbot.call('state', {}) } catch { return null }
  }

  const ms = v => (typeof v === 'number' ? v : typeof v === 'string' ? Date.parse(v) || null : null)

  async function list(deviceId) {
    const state = await adapter.readState()
    if (state === null) return { available: false, chats: [], approvals: [] }
    const live = await liveView()
    const work = live?.work ?? {}
    const marks = await store.readMarks(deviceId)
    const fresh = {}
    const chats = []

    for (const bot of Object.values(state.bots ?? {})) {
      const sessionId = bot.sessionId ?? bot.id
      const key = `bot:${bot.id}`
      const tail = await tailOf(sessionId, state.exchangeTurns)
      const lastText = [...tail.items].reverse().find(item => (item.kind === 'bot' || item.kind === 'user') && item.text)
      const lastSeq = [...tail.items].reverse().find(item => Number.isFinite(item.seq))?.seq ?? tail.seq
      // A chat this phone has never seen starts read: old history is not "new".
      if (!Number.isFinite(marks[key]) && Number.isFinite(lastSeq)) fresh[key] = lastSeq
      const mark = marks[key] ?? fresh[key]
      chats.push({
        kind: 'bot',
        id: bot.id,
        name: bot.name,
        color: bot.color ?? null,
        shape: bot.avatar?.shape ?? null,
        main: Array.isArray(state.mainBotIds) && state.mainBotIds.includes(bot.id),
        preview: lastText ? clip(lastText.kind === 'user' ? `你：${lastText.text}` : lastText.text) : '',
        lastAt: ms(tail.time) ?? ms(lastText?.time) ?? ms(bot.createdAt),
        seq: lastSeq ?? null,
        unread: botUnread(tail.items, mark),
        running: Boolean(work.bots?.[bot.id] && !work.bots[bot.id].roomId),
      })
    }

    for (const room of Object.values(state.rooms ?? {})) {
      if (!room?.id || room.hidden === true) continue
      const key = `room:${room.id}`
      const last = (room.log ?? []).filter(line => line.kind === undefined).at(-1)
      const seq = Number.isFinite(room.seq) ? room.seq : null
      if (!Number.isFinite(marks[key]) && seq !== null) fresh[key] = seq
      const mark = marks[key] ?? fresh[key]
      const tail = await tailOf(room.id, {})
      const working = Array.isArray(work.rooms?.[room.id]) ? work.rooms[room.id] : []
      chats.push({
        kind: 'room',
        id: room.id,
        name: room.name || (room.members ?? []).map(id => state.bots?.[id]?.name).filter(Boolean).join('、'),
        members: room.members ?? [],
        preview: last ? clip(last.who === 'User' ? `你：${last.text}` : `${last.who}：${last.text}`) : '',
        lastAt: ms(tail.time) ?? ms(room.createdAt),
        seq,
        unread: roomUnread(room.log, mark),
        running: working.length > 0,
        workingMembers: working.map(item => item.botId),
      })
    }

    if (Object.keys(fresh).length > 0) await store.markRead(deviceId, fresh, { onlyNew: true })
    chats.sort((a, b) => (b.lastAt ?? 0) - (a.lastAt ?? 0))
    const approvals = (Array.isArray(work.approvals) ? work.approvals : []).map(entry => approvalView(entry, state))
    return { available: true, chats, approvals, revision: state.revision ?? null }
  }

  /** Current position of a chat, to mark it read when the phone opens it. */
  async function positionOf(kind, id) {
    const state = await adapter.readState()
    if (kind === 'room') {
      const room = state?.rooms?.[id]
      if (!room) throw Object.assign(new Error('unknown group chat'), { status: 404 })
      return Number.isFinite(room.seq) ? room.seq : 0
    }
    const bot = state?.bots?.[id]
    if (!bot) throw Object.assign(new Error('Unknown Bot'), { status: 404 })
    tails.delete(bot.sessionId ?? bot.id)
    const tail = await tailOf(bot.sessionId ?? bot.id, state.exchangeTurns)
    return tail.seq ?? 0
  }

  return { list, positionOf, forget: sessionId => tails.delete(sessionId) }
}
