import assert from 'node:assert/strict'
import { test } from 'node:test'
import { displayItems, partsOfBot, textOf } from '../src/chat-format.js'

test('textOf joins text blocks', () => {
  assert.equal(textOf([{ type: 'text', text: 'a' }, { type: 'image' }, { type: 'text', text: 'b' }]), 'a\nb')
})

test('displayItems hides exchange turns', () => {
  const items = displayItems('s1', [{
    type: 'assistant/message',
    time: 1,
    seq: 1,
    data: { turn: 9, message: { content: [{ type: 'text', text: 'secret' }] } },
  }], { s1: [9] })
  assert.deepEqual(items, [])
})

test('displayItems includes turn on bot items', () => {
  const items = displayItems('s1', [{
    type: 'assistant/message',
    time: 2,
    seq: 3,
    data: { turn: 4, message: { content: [{ type: 'text', text: 'hi' }] } },
  }])
  assert.deepEqual(items, [{ kind: 'bot', text: 'hi', time: 2, seq: 3, turn: 4 }])
})

test('displayItems sets turn null when assistant message has no turn', () => {
  const items = displayItems('s1', [{
    type: 'assistant/message',
    time: 1,
    seq: 1,
    data: { message: { content: [{ type: 'text', text: 'x' }] } },
  }])
  assert.equal(items[0].turn, null)
})

test('partsOfBot builds earlier + current', () => {
  const parts = partsOfBot({
    bots: {
      chief: {
        id: 'chief',
        createdAt: 10,
        sessionId: 's2',
        segments: [{ sessionId: 's1', endedAt: 20 }],
      },
    },
  }, 'chief')
  assert.equal(parts.length, 2)
  assert.equal(parts[0].sessionId, 's1')
  assert.equal(parts[1].sessionId, 's2')
  assert.equal(parts[0].startedAt, 10)
  assert.equal(parts[1].startedAt, 20)
})
