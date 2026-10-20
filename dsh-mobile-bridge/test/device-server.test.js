import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { apply } from '../index.js'
import { clientIpOf, createRateLimiter } from '../src/device-server.js'
import { dispatch, fakeContext } from './fake-ctx.js'

const P = '/api/mobile-bridge/v1'

async function start(extra = {}) {
  const root = mkdtempSync(join(tmpdir(), 'mb-dev-'))
  const dshHome = join(root, 'dsh')
  const botHome = join(dshHome, 'bot')
  mkdirSync(botHome, { recursive: true })
  writeFileSync(join(botHome, 'state.json'), JSON.stringify({
    version: 1, mainBotIds: ['chief'], bots: { chief: { id: 'chief', name: 'Chief', sessionId: 'sess-chief' } },
    rooms: {}, exchangeTurns: {}, revision: 1,
  }))
  const sessionController = {
    prompt: async () => {},
    resolveAgent: async id => ({ agent: { id, session: { seq: 1 } } }),
    page: async () => ({ hasMore: false, records: [{ event: { type: 'user/message', seq: 1, time: 1, data: { source: { kind: 'user' }, content: [{ type: 'text', text: 'hi' }] } } }] }),
  }
  const ctx = fakeContext({ services: { sessionController } })
  ctx.sessionController = sessionController
  const rt = apply(ctx, { dshHome, home: join(dshHome, 'mobile-bridge'), botHome, devicePort: 0, ...extra })
  await rt.deviceServer.ready
  const base = `http://127.0.0.1:${rt.deviceServer.port}${P}`
  return {
    ctx, rt, base,
    web: (m, p, o) => dispatch(ctx.routes, m, `${P}${p}`, o),
    dev: (m, p, { body, headers = {}, ...o } = {}) => fetch(`${base}${p}`, {
      method: m, headers: { ...(body ? { 'content-type': 'application/json' } : {}), ...headers },
      body: body ? JSON.stringify(body) : undefined, ...o,
    }),
    stop: async () => { await ctx.dispose(); rmSync(root, { recursive: true, force: true }) },
  }
}

async function pairOnDevice(h) {
  const begin = await (await h.web('POST', '/auth/pair/begin')).json()
  const c = await (await h.dev('POST', '/auth/pair/complete', { body: { code: begin.code, deviceId: 'd1' } })).json()
  const t = await (await h.dev('POST', '/auth/token', { body: { deviceId: 'd1', refreshToken: c.refreshToken } })).json()
  return { Authorization: `Bearer ${t.accessToken}` }
}

test('3081: health open, protected routes 401 without token', async (t) => {
  const h = await start(); t.after(h.stop)
  const r = await h.dev('GET', '/health')
  assert.equal(r.status, 200)
  assert.equal((await r.json()).version, '0.0.15')
  for (const [m, p] of [['GET', '/team'], ['GET', '/chats/history?botId=chief'], ['POST', '/chats/send'], ['GET', '/stream'], ['GET', '/devices']]) {
    const res = await h.dev(m, p, m === 'POST' ? { body: {} } : {})
    assert.equal(res.status, 401, `${m} ${p}`)
  }
})

test('3081: host-only pairing routes are 404', async (t) => {
  const h = await start(); t.after(h.stop)
  for (const [m, p] of [['GET', '/ui'], ['GET', '/pair'], ['POST', '/auth/pair/begin'], ['GET', '/auth/pair/active'], ['GET', '/auth/pair/qr'], ['POST', '/devices/revoke']]) {
    assert.equal((await h.dev(m, p, m === 'POST' ? { body: {} } : {})).status, 404, `${m} ${p}`)
  }
})

test('3081: pair via 3080 code, then team/history/devices/stream with token', async (t) => {
  const h = await start(); t.after(h.stop)
  const headers = await pairOnDevice(h)
  assert.equal((await h.dev('GET', '/team', { headers })).status, 200)
  const hist = await h.dev('GET', '/chats/history?botId=chief', { headers })
  assert.equal(hist.status, 200)
  assert.ok(JSON.stringify(await hist.json()).includes('hi'))
  assert.equal((await h.dev('GET', '/devices', { headers })).status, 200)
  const ac = new AbortController()
  const s = await h.dev('GET', '/stream?mode=test', { headers, signal: ac.signal })
  assert.equal(s.status, 200)
  assert.match(s.headers.get('content-type'), /event-stream/)
  const { value } = await s.body.getReader().read()
  assert.match(Buffer.from(value).toString(), /hello/)
  ac.abort()
})

test('3081: pair/complete is rate limited per IP', async (t) => {
  const h = await start({ rateLimit: { max: 3, windowMs: 60_000 } }); t.after(h.stop)
  const codes = []
  for (let i = 0; i < 4; i++) codes.push((await h.dev('POST', '/auth/pair/complete', { body: { code: 'zzzzzz' } })).status)
  assert.deepEqual(codes, [400, 400, 400, 429])
  // different forwarded client via trusted local proxy has its own bucket
  const other = await h.dev('POST', '/auth/pair/complete', { body: { code: 'zzzzzz' }, headers: { 'x-forwarded-for': '203.0.113.9' } })
  assert.equal(other.status, 400)
  // token bucket is separate
  assert.equal((await h.dev('POST', '/auth/token', { body: {} })).status !== 429, true)
})

test('clientIpOf trusts XFF only from trusted proxies', () => {
  assert.equal(clientIpOf('10.0.0.5', { 'x-forwarded-for': '1.2.3.4' }), '10.0.0.5')
  assert.equal(clientIpOf('127.0.0.1', { 'x-forwarded-for': '1.2.3.4' }), '1.2.3.4')
  assert.equal(clientIpOf('::ffff:127.0.0.1', { 'x-forwarded-for': 'spoof, 5.6.7.8, 127.0.0.1' }), '5.6.7.8')
  assert.equal(clientIpOf('127.0.0.1', { 'x-real-ip': '9.9.9.9' }), '9.9.9.9')
  assert.equal(clientIpOf('127.0.0.1', {}), '127.0.0.1')
  const l = createRateLimiter({ max: 1, windowMs: 10, now: () => 0 })
  assert.equal(l.hit('a').ok, true); assert.equal(l.hit('a').ok, false); assert.equal(l.hit('b').ok, true)
})

test('3081: listener closes on dispose', async () => {
  const h = await start()
  const port = h.rt.deviceServer.port
  await h.stop()
  await assert.rejects(fetch(`http://127.0.0.1:${port}${P}/health`))
})

test('3081: port in use logs error and does not throw', async (t) => {
  const blocker = createServer().listen(0, '0.0.0.0')
  await new Promise(r => blocker.once('listening', r))
  t.after(() => blocker.close())
  const port = blocker.address().port
  const errors = []
  const h = await start({ devicePort: port })
  t.after(h.stop)
  assert.equal(h.rt.deviceServer.listening, false)
  assert.equal(h.rt.deviceServer.error.code, 'EADDRINUSE')
  void errors
  // 3080 routes still work
  assert.equal((await h.web('GET', '/health')).status, 200)
})
