/**
 * In-memory diagnostics for the group chat flow, read through the token-protected
 * GET /diagnostics. Every stage of a group message leaves a mark here:
 *   send.ok / send.fail      the phone's message went into ds-bot (or ds-bot refused)
 *   round.start / round.end  ds-bot's group relay ran for the group (its own Session)
 *   poll.line                a new line of the group's log was read from state.json
 *   stream.start / .miss     a member began writing (miss: its Session matched no group)
 *   publish                  an event went out to N phones
 *   state.error              ds-bot's state.json could not be read
 * Nothing here holds message text beyond a short head.
 */
const MAX_EVENTS = 120

export function createDiag({ logger, name = 'dsh-mobile-bridge', print = (line) => console.error(`[mobile-bridge] ${line}`) } = {}) {
  const events = []
  const counters = {}
  const startedAt = Date.now()

  function note(kind, detail = {}, { level = 'debug' } = {}) {
    counters[kind] = (counters[kind] ?? 0) + 1
    events.push({ at: Date.now(), kind, ...detail })
    if (events.length > MAX_EVENTS) events.splice(0, events.length - MAX_EVENTS)
    if (level === 'debug') return
    const line = `${name}: [group] ${kind} ${JSON.stringify(detail)}`
    const log = level === 'warn' ? (logger?.warn ?? logger?.info) : logger?.info
    try { log?.call(logger, line) } catch { /* */ }
    // Like ds-bot: dsh's logger may hide info lines, so the important marks also go to stderr
    // (docker logs / the terminal running dsh), as "[mobile-bridge] [group] ...".
    try { print?.(`[group] ${kind} ${JSON.stringify(detail)}`) } catch { /* */ }
  }

  function snapshot({ roomId } = {}) {
    const list = roomId ? events.filter(e => !e.roomId || e.roomId === roomId) : events
    return { startedAt, counters: { ...counters }, events: list.slice(-60) }
  }

  return { note, snapshot }
}

export const head = (text, n = 24) => {
  const s = String(text ?? '')
  return s.length > n ? `${s.slice(0, n)}…` : s
}
