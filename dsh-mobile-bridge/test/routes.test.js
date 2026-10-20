import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { apply } from '../index.js'
import { dispatch, fakeContext } from './fake-ctx.js'

function start({ bots, page } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'mb-routes-'))
  const dshHome = join(root, 'dsh')
  const botHome = join(dshHome, 'bot')
  mkdirSync(botHome, { recursive: true })
  if (bots) {
    writeFileSync(join(botHome, 'state.json'), JSON.stringify({
      version: 1,
      mainBotIds: ['chief'],
      bots,
      rooms: {},
      exchangeTurns: {},
      revision: 3,
    }))
  }
  const prompts = []
  const pageCalls = []
  const sessionController = {
    prompt: async (payload) => { prompts.push(payload) },
    resolveAgent: async (sessionId) => ({ agent: { id: sessionId, session: { seq: 100 } } }),
    page: async (req) => {
      pageCalls.push(req)
      if (typeof page === 'function') return page(req)
      return {
        hasMore: false,
        records: [
          {
            event: {
              type: 'user/message',
              seq: 10,
              time: 1_700_000_000_000,
              data: { source: { kind: 'user' }, content: [{ type: 'text', text: '你好世界' }] },
            },
          },
          {
            event: {
              type: 'assistant/message',
              seq: 11,
              time: 1_700_000_000_100,
              data: { turn: 1, message: { content: [{ type: 'text', text: '收到' }] } },
            },
          },
        ],
      }
    },
  }
  const ctx = fakeContext({ services: { sessionController } })
  ctx.sessionController = sessionController
  apply(ctx, { dshHome, home: join(dshHome, 'mobile-bridge'), botHome, devicePort: false, quietDiag: true })
  return {
    ctx,
    prompts,
    pageCalls,
    root,
    async call(method, path, opts) {
      return dispatch(ctx.routes, method, path, opts)
    },
    async json(method, path, opts) {
      const res = await dispatch(ctx.routes, method, path, opts)
      return { status: res.status, body: await res.json() }
    },
    stop: async () => {
      await ctx.dispose()
      rmSync(root, { recursive: true, force: true })
    },
  }
}

async function authed(harness) {
  const begin = await harness.json('POST', '/api/mobile-bridge/v1/auth/pair/begin')
  const complete = await harness.json('POST', '/api/mobile-bridge/v1/auth/pair/complete', {
    body: { code: begin.body.code, deviceId: 'dev-1' },
  })
  const token = await harness.json('POST', '/api/mobile-bridge/v1/auth/token', {
    body: { deviceId: 'dev-1', refreshToken: complete.body.refreshToken },
  })
  return { Authorization: `Bearer ${token.body.accessToken}` }
}

test('health is open', async (t) => {
  const h = start()
  t.after(h.stop)
  const { status, body } = await h.json('GET', '/api/mobile-bridge/v1/health')
  assert.equal(status, 200)
  assert.equal(body.name, 'dsh-mobile-bridge')
  assert.equal(body.version, '0.0.10')
  assert.equal(body.ok, true)
})

test('team lists bots from ds-bot state.json', async (t) => {
  const h = start({
    bots: {
      chief: { id: 'chief', name: 'Chief', role: 'Lead', sessionId: 'sess-chief' },
      writer: { id: 'writer', name: 'Writer', sessionId: 'sess-writer' },
    },
  })
  t.after(h.stop)
  const headers = await authed(h)
  const { status, body } = await h.json('GET', '/api/mobile-bridge/v1/team', { headers })
  assert.equal(status, 200)
  assert.equal(body.available, true)
  assert.equal(body.bots.length, 2)
  assert.equal(body.bots.find(b => b.id === 'chief').main, true)
})

test('team without bearer is 401', async (t) => {
  const h = start({ bots: { a: { id: 'a', name: 'A', sessionId: 'a' } } })
  t.after(h.stop)
  const { status } = await h.json('GET', '/api/mobile-bridge/v1/team')
  assert.equal(status, 401)
})

test('send uses sessionController when present', async (t) => {
  const h = start({
    bots: { chief: { id: 'chief', name: 'Chief', sessionId: 'sess-chief' } },
  })
  t.after(h.stop)
  const headers = await authed(h)
  const { status, body } = await h.json('POST', '/api/mobile-bridge/v1/chats/send', {
    headers,
    body: { botId: 'chief', text: '你好' },
  })
  assert.equal(status, 200)
  assert.equal(body.ok, true)
  assert.equal(body.via, 'sessionController.prompt')
  assert.equal(h.prompts.length, 1)
  assert.equal(h.prompts[0].sessionId, 'sess-chief')
  assert.equal(h.prompts[0].content[0].text, '你好')
})

test('history returns displayItems from sessionController.page', async (t) => {
  const h = start({
    bots: { chief: { id: 'chief', name: 'Chief', sessionId: 'sess-chief', createdAt: 1 } },
  })
  t.after(h.stop)
  const headers = await authed(h)
  const { status, body } = await h.json('GET', '/api/mobile-bridge/v1/chats/history?botId=chief', { headers })
  assert.equal(status, 200)
  assert.equal(body.available, true)
  assert.equal(body.stub, false)
  assert.equal(body.sessionId, 'sess-chief')
  assert.equal(body.items.length, 2)
  assert.equal(body.items[0].kind, 'user')
  assert.equal(body.items[0].text, '你好世界')
  assert.equal(body.items[1].kind, 'bot')
  assert.equal(body.items[1].text, '收到')
  assert.equal(h.pageCalls[0].address.sessionId, 'sess-chief')
  assert.equal(h.pageCalls[0].throughSeq, 99)
})

test('history pages earlier segment via cursor', async (t) => {
  const h = start({
    bots: {
      chief: {
        id: 'chief',
        name: 'Chief',
        sessionId: 'sess-2',
        createdAt: 1,
        segments: [{ sessionId: 'sess-1', endedAt: 2 }],
      },
    },
    page: async (req) => ({
      hasMore: false,
      records: [{
        event: {
          type: 'user/message',
          seq: 1,
          time: 100,
          data: { source: { kind: 'user' }, content: [{ type: 'text', text: `in-${req.address.sessionId}` }] },
        },
      }],
    }),
  })
  t.after(h.stop)
  const headers = await authed(h)
  const first = await h.json('GET', '/api/mobile-bridge/v1/chats/history?botId=chief', { headers })
  // Current part complete + earlier parts exist → divider marks part start (same as ds-bot).
  assert.equal(first.body.items[0].kind, 'divider')
  assert.equal(first.body.items[1].text, 'in-sess-2')
  assert.deepEqual(first.body.nextCursor, { part: 0 })
  const cursor = encodeURIComponent(JSON.stringify(first.body.nextCursor))
  const second = await h.json('GET', `/api/mobile-bridge/v1/chats/history?botId=chief&cursor=${cursor}`, { headers })
  assert.equal(second.body.items[0].text, 'in-sess-1')
  assert.equal(second.body.nextCursor, null)
})

test('SSE stream live mode hello', async (t) => {
  const h = start({ bots: { chief: { id: 'chief', name: 'Chief', sessionId: 's' } } })
  t.after(h.stop)
  const headers = await authed(h)
  const ac = new AbortController()
  const res = await h.call('GET', '/api/mobile-bridge/v1/stream', { headers, signal: ac.signal })
  assert.equal(res.status, 200)
  assert.match(res.headers.get('content-type'), /text\/event-stream/)
  const reader = res.body.getReader()
  const { value } = await reader.read()
  const text = new TextDecoder().decode(value)
  assert.match(text, /event: hello/)
  assert.match(text, /"mode":"live"/)
  ac.abort()
})

test('SSE stream forwards session message events', async (t) => {
  const h = start({
    bots: { chief: { id: 'chief', name: 'Chief', sessionId: 'sess-chief', createdAt: 1 } },
  })
  t.after(h.stop)
  const headers = await authed(h)
  const ac = new AbortController()
  const res = await h.call('GET', '/api/mobile-bridge/v1/stream?botId=chief', { headers, signal: ac.signal })
  const reader = res.body.getReader()
  await reader.read() // hello
  // Give the subscription a tick, then emit
  await Promise.resolve()
  h.ctx.emit('session/event', { id: 'sess-chief' }, {
    type: 'user/message',
    seq: 20,
    time: 99,
    data: { source: { kind: 'user' }, content: [{ type: 'text', text: 'live ping' }] },
  })
  // wait briefly for async handler
  let got = ''
  for (let i = 0; i < 20 && !got.includes('live ping'); i++) {
    const { value, done } = await Promise.race([
      reader.read(),
      new Promise(r => setTimeout(() => r({ value: undefined, done: false }), 50)),
    ])
    if (done) break
    if (value) got += new TextDecoder().decode(value)
  }
  assert.match(got, /event: message/)
  assert.match(got, /live ping/)
  ac.abort()
})

test('SSE test mode still ticks', async (t) => {
  const h = start({ bots: { chief: { id: 'chief', name: 'Chief', sessionId: 's' } } })
  t.after(h.stop)
  const headers = await authed(h)
  const ac = new AbortController()
  const res = await h.call('GET', '/api/mobile-bridge/v1/stream?mode=test', { headers, signal: ac.signal })
  const reader = res.body.getReader()
  const { value } = await reader.read()
  const text = new TextDecoder().decode(value)
  assert.match(text, /"mode":"test"/)
  ac.abort()
})

test('pair UI page returns HTML with code and QR', async (t) => {
  const h = start()
  t.after(h.stop)
  const res = await h.call('GET', '/api/mobile-bridge/v1/ui')
  assert.equal(res.status, 200)
  assert.match(res.headers.get('content-type'), /text\/html/)
  const body = await res.text()
  assert.match(body, /手机配对/)
  assert.match(body, /dsh-mobile-bridge/)
  assert.match(body, /重新生成/)
  assert.match(body, /复制配对码/)
  assert.match(body, /已配对设备/)
  assert.doesNotMatch(body, /dshmb\.bigbear\.cool/)
  assert.match(body, /约 5 分钟/)
  assert.match(body, /<svg[\s\S]*role="img"/)
  // 6 hex code appears (possibly spaced) in the page
  assert.match(body, /[0-9a-f]{3}\s?[0-9a-f]{3}/i)
})

test('pair/active and pair/qr expose current code', async (t) => {
  const h = start()
  t.after(h.stop)
  const begin = await h.json('POST', '/api/mobile-bridge/v1/auth/pair/begin')
  assert.equal(begin.status, 200)
  assert.match(begin.body.code, /^[0-9a-f]{6}$/)
  assert.equal(begin.body.expiresIn, 300)

  const active = await h.json('GET', '/api/mobile-bridge/v1/auth/pair/active')
  assert.equal(active.body.active, true)
  assert.equal(active.body.code, begin.body.code)

  const qr = await h.json('GET', '/api/mobile-bridge/v1/auth/pair/qr')
  assert.equal(qr.body.code, begin.body.code)
  assert.match(qr.body.payload, new RegExp(`dshmb:${begin.body.code}`))
  assert.match(qr.body.svg, /<svg/)
})

test('beginPair replaces previous unused code', async (t) => {
  const h = start()
  t.after(h.stop)
  const a = await h.json('POST', '/api/mobile-bridge/v1/auth/pair/begin')
  const b = await h.json('POST', '/api/mobile-bridge/v1/auth/pair/begin')
  assert.notEqual(a.body.code, b.body.code)
  const complete = await h.json('POST', '/api/mobile-bridge/v1/auth/pair/complete', {
    body: { code: a.body.code, deviceId: 'old' },
  })
  assert.equal(complete.status, 400)
  const ok = await h.json('POST', '/api/mobile-bridge/v1/auth/pair/complete', {
    body: { code: b.body.code, deviceId: 'new' },
  })
  assert.equal(ok.status, 200)
})

test('devices revoke marks device revoked', async (t) => {
  const h = start()
  t.after(h.stop)
  const headers = await authed(h)
  const listed = await h.json('GET', '/api/mobile-bridge/v1/devices')
  assert.equal(listed.body.devices.length, 1)
  const revoked = await h.json('POST', '/api/mobile-bridge/v1/devices/revoke', {
    body: { deviceId: 'dev-1' },
  })
  assert.equal(revoked.status, 200)
  const after = await h.json('GET', '/api/mobile-bridge/v1/devices')
  assert.equal(after.body.devices[0].revoked, true)
  const team = await h.json('GET', '/api/mobile-bridge/v1/team', { headers })
  assert.equal(team.status, 401)
})

test('send with same clientMessageId is idempotent (prompt once)', async (t) => {
  const h = start({
    bots: { chief: { id: 'chief', name: 'Chief', sessionId: 'sess-chief' } },
  })
  t.after(h.stop)
  const headers = await authed(h)
  const req = { headers, body: { botId: 'chief', text: '只发一次', clientMessageId: 'c-123' } }
  const a = await h.json('POST', '/api/mobile-bridge/v1/chats/send', req)
  const b = await h.json('POST', '/api/mobile-bridge/v1/chats/send', req)
  assert.equal(a.status, 200)
  assert.equal(a.body.clientMessageId, 'c-123')
  assert.equal(b.status, 200)
  assert.equal(b.body.duplicate, true)
  assert.equal(h.prompts.length, 1)
})
