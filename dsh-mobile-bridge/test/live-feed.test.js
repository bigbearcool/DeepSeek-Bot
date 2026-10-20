import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createLiveFeed } from '../src/live-feed.js'

test('claimPending tags echoed user item with clientMessageId once', () => {
  const feed = createLiveFeed({ ctx: {}, adapter: {} })
  feed.notePending('chief', '你好', 'c-1')
  const tagged = feed.claimPending('chief', { kind: 'user', text: '你好' })
  assert.equal(tagged.clientMessageId, 'c-1')
  const again = feed.claimPending('chief', { kind: 'user', text: '你好' })
  assert.equal(again.clientMessageId, undefined)
  const bot = feed.claimPending('chief', { kind: 'bot', text: '你好' })
  assert.equal(bot.clientMessageId, undefined)
})

test('safeOn swallows async handler rejection (does not become unhandled)', async () => {
  const notes = []
  let handler
  const ctx = {
    on(event, fn) { if (event === 'session/event') handler = fn },
  }
  const adapter = {
    async chatOwners() { throw new Error('boom-owners') },
    async readState() { return { revision: 1 } },
  }
  const feed = createLiveFeed({
    ctx,
    adapter,
    diag: { note(kind, detail, opts) { notes.push({ kind, detail, opts }) } },
  })
  assert.equal(typeof handler, 'function')
  // Need a watching phone so the handler does not return before chatOwners.
  const ac = new AbortController()
  const res = feed.openStream(new Request('http://local/stream', { signal: ac.signal }), {
    device: { id: 'dev-1' },
    botId: 'bot-1',
  })
  // Start the stream so clients.size > 0
  const reader = res.body.getReader()
  await reader.read()
  const rejections = []
  const onRej = (reason) => rejections.push(reason)
  process.on('unhandledRejection', onRej)
  try {
    handler({ id: 's1' }, { type: 'user/message' })
    await new Promise(r => setTimeout(r, 40))
  } finally {
    process.off('unhandledRejection', onRej)
    ac.abort()
    try { await reader.cancel() } catch { /* */ }
  }
  assert.equal(rejections.length, 0, 'must not surface unhandledRejection (dsh FailLoud would exit)')
  assert.ok(notes.some(n => n.kind === 'feed.error' && String(n.detail?.error || '').includes('boom-owners')))
})
