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
