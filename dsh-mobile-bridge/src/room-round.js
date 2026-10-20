/**
 * ds-bot runs a group chat round as one `group_relay` tool call in the group's own
 * Session (src/host/groups.js). Its tool/result text is JSON:
 *   {"replies":[{botId,name,text,time} | {kind:'failed',botId,name,code,text,...} | ...],
 *    "passed":[names], "failed":[names]?}
 * Lines with text are also written to the group log (state.json); a failed model call,
 * a member waiting for an approval, a member that went on working or missed the round
 * are events of the round only and never reach the log. Without reading this result a
 * phone would wait forever when no member could answer.
 */
export const RELAY_TOOLS = new Set(['group_relay', 'run_code'])

const ROUND_KINDS = new Set(['failed', 'approval', 'working', 'missed'])

function textOf(content) {
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content.filter(b => b?.type === 'text').map(b => b.text ?? '').join('')
}

/** Parse a relay tool/result event; null when the event is not a round result. */
export function relayResult(event) {
  if (event?.type !== 'tool/result') return null
  const message = event.data?.message
  const callId = message?.toolCallId ?? message?.source?.callId ?? null
  let text = textOf(message?.content).trim()
  // run_code (PTC preset) wraps the relay's return value; find the JSON object in it.
  if (!text.startsWith('{')) {
    const at = text.indexOf('{"replies"')
    if (at < 0) return null
    text = text.slice(at)
  }
  let value
  try { value = JSON.parse(text) } catch { return null }
  if (!value || !Array.isArray(value.replies)) return null
  return {
    callId,
    replies: value.replies,
    passed: Array.isArray(value.passed) ? value.passed : [],
    failed: Array.isArray(value.failed) ? value.failed : [],
  }
}

export function relayCall(event) {
  if (event?.type !== 'tool/call') return null
  const name = event.data?.name
  if (!RELAY_TOOLS.has(name)) return null
  if (name === 'run_code' && !String(event.data?.arguments ?? '').includes('group_relay')) return null
  return { callId: event.data?.callId ?? null }
}

/** Round-only replies as mobile chat items (kind 'event'); member text replies come from the log. */
export function roundItems(result) {
  const out = []
  for (const reply of result?.replies ?? []) {
    if (!reply || !ROUND_KINDS.has(reply.kind)) continue
    out.push({
      kind: 'event',
      role: reply.kind,
      botId: reply.botId ?? null,
      name: reply.name ?? null,
      ...(reply.kind === 'failed' && reply.status ? { status: reply.status } : {}),
    })
  }
  return out
}

/** Summary sent with the round end event. */
export function roundSummary(result) {
  const replies = result?.replies ?? []
  return {
    replied: replies.filter(r => r && r.kind === undefined && String(r.text ?? '').trim() !== '').length,
    failed: replies.filter(r => r?.kind === 'failed').map(r => r.name ?? null),
    passed: result?.passed ?? [],
  }
}
