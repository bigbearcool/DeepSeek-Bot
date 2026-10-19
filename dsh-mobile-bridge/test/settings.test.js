import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { apply } from '../index.js'
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


test('default listener binds 0.0.0.0 (127.0.0.1 still reachable); deviceLan:false → loopback', async (t) => {
  const a = await start({ deviceHost: undefined })
  t.after(a.stop)
  assert.equal(a.rt.deviceServer.host, '0.0.0.0')
  assert.equal((await a.dev('GET', '/health')).status, 200)
  const b = await start({ deviceHost: undefined, deviceLan: false })
  t.after(b.stop)
  assert.equal(b.rt.deviceServer.host, '127.0.0.1')
})

test('settings: host-only, validates, persists, drives QR + appUrl', async (t) => {
  const h = await start()
  t.after(h.stop)
  assert.equal((await h.dev('GET', '/settings')).status, 404)
  assert.equal((await h.dev('POST', '/settings', { body: { publicUrl: 'https://evil.example' } })).status, 404)
  const bad = await h.web('POST', '/settings', { body: { publicUrl: 'ftp://x' } })
  assert.equal(bad.status, 400)
  const ok = await (await h.web('POST', '/settings', { body: { publicUrl: 'https://my.example.com:8443/' } })).json()
  assert.equal(ok.publicUrl, 'https://my.example.com:8443')
  assert.equal(ok.appUrl, 'https://my.example.com:8443')
  assert.equal(ok.source, 'public')
  const begin = await (await h.web('POST', '/auth/pair/begin')).json()
  const qr = await (await h.web('GET', '/auth/pair/qr')).json()
  assert.equal(qr.payload, `dshmb:${begin.code}@https://my.example.com:8443`)
  const again = await (await h.web('GET', '/settings')).json()
  assert.equal(again.publicUrl, 'https://my.example.com:8443')
  const cleared = await (await h.web('POST', '/settings', { body: { publicUrl: '' } })).json()
  assert.equal(cleared.publicUrl, '')
  assert.notEqual(cleared.source, 'public')
})

test('LAN direct peer: spoofed XFF ignored by limiter (peer not trusted)', async (t) => {
  const { clientIpOf } = await import('../src/device-server.js')
  assert.equal(clientIpOf('192.168.1.50', { 'x-forwarded-for': '1.2.3.4' }), '192.168.1.50')
  assert.equal(clientIpOf('192.168.1.2', { 'x-forwarded-for': '8.8.8.8' }, ['192.168.1.2']), '8.8.8.8')
  void t
})
