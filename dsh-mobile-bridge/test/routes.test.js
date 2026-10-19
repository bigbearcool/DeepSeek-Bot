import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { apply } from '../index.js'
import { dispatch, fakeContext } from './fake-ctx.js'

function start({ bots } = {}) {
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
      revision: 3,
    }))
  }
  const prompts = []
  const ctx = fakeContext({
    services: {
      sessionController: {
        prompt: async (payload) => { prompts.push(payload) },
      },
    },
  })
  // Also expose on ctx for adapter lookup via ctx.sessionController fallback
  ctx.sessionController = ctx.get('sessionController')
  apply(ctx, { dshHome, home: join(dshHome, 'mobile-bridge'), botHome })
  return {
    ctx,
    prompts,
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

test('history stub returns empty page', async (t) => {
  const h = start({
    bots: { chief: { id: 'chief', name: 'Chief', sessionId: 'sess-chief' } },
  })
  t.after(h.stop)
  const headers = await authed(h)
  const { status, body } = await h.json('GET', '/api/mobile-bridge/v1/chats/history?botId=chief', { headers })
  assert.equal(status, 200)
  assert.equal(body.stub, true)
  assert.deepEqual(body.items, [])
})

test('SSE stream sends hello', async (t) => {
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
  ac.abort()
})
