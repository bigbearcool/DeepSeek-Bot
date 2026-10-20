import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { apply } from '../index.js'
import { DSBOT_ROUTE } from '../src/dsbot-api.js'
import { searchHits, updateBotPayload, updateRoomPayload } from '../src/routes.js'
import { dispatch, fakeContext } from './fake-ctx.js'

const P = '/api/mobile-bridge/v1'
const STATE = {
  version: 1, mainBotIds: ['chief'],
  bots: {
    chief: { id: 'chief', name: 'Chief', sessionId: 'sess-chief', color: 'blue', avatar: { shape: 'whale' }, instructions: '只说中文' },
    writer: { id: 'writer', name: 'Writer', sessionId: 'sess-writer' },
  },
  rooms: { 'room-1': { id: 'room-1', name: '周会', members: ['chief'], seq: 2, log: [{ who: 'User', text: '本周预算多少', seq: 1 }, { who: 'Chief', botId: 'chief', text: '预算是 3 万', seq: 2 }] } },
  exchangeTurns: {}, revision: 1,
}

function start() {
  const calls = []
  const reply = value => new Response(JSON.stringify({ ok: true, value }))
  const fail = (message, code) => new Response(JSON.stringify({ ok: false, error: { code, message } }))
  const route = {
    methods: new Set(['POST']), requestBody: 'buffered',
    fetch: async (request) => {
      const { endpoint, payload } = await request.json()
      calls.push({ endpoint, payload })
      if (endpoint === 'update-bot') {
        if (payload.name === '') return fail('A Bot needs a name', 'bot/invalid')
        if (payload.avatar && payload.avatar.shape === 'nope') return fail('Unknown shape', 'bot/invalid')
        return reply({ ...STATE.bots[payload.id], ...payload })
      }
      if (endpoint === 'update-room') {
        if ((payload.add ?? []).includes('ghost')) return fail('Unknown Bot ghost', 'bot/refused')
        return reply({ room: { id: payload.id, name: '周会', members: ['chief', ...(payload.add ?? [])] }, changes: { added: payload.add ?? [] } })
      }
      return fail(`Unknown endpoint ${endpoint}`, 'bot/not-found')
    },
  }
  const root = mkdtempSync(join(tmpdir(), 'mb-b3-'))
  const dshHome = join(root, 'dsh'); const botHome = join(dshHome, 'bot')
  mkdirSync(botHome, { recursive: true })
  writeFileSync(join(botHome, 'state.json'), JSON.stringify(STATE))
  const sessionController = { prompt: async () => {}, page: async () => ({ hasMore: false, records: [] }) }
  const ctx = fakeContext({ services: { sessionController }, fetchRoutes: new Map([[DSBOT_ROUTE, route]]) })
  ctx.sessionController = sessionController
  apply(ctx, { dshHome, home: join(dshHome, 'mobile-bridge'), botHome, devicePort: false, quietDiag: true })
  const json = async (m, p, o) => { const r = await dispatch(ctx.routes, m, `${P}${p}`, o); return { status: r.status, body: await r.json() } }
  return { calls, json, stop: async () => { await ctx.dispose(); rmSync(root, { recursive: true, force: true }) } }
}

async function authed(h) {
  const begin = await h.json('POST', '/auth/pair/begin')
  const c = await h.json('POST', '/auth/pair/complete', { body: { code: begin.body.code, deviceId: 'dev-3' } })
  const t = await h.json('POST', '/auth/token', { body: { deviceId: 'dev-3', refreshToken: c.body.refreshToken } })
  return { Authorization: `Bearer ${t.body.accessToken}` }
}

test('updateBotPayload keeps only the editable fields, in ds-bot form', () => {
  assert.deepEqual(updateBotPayload({ id: 'a', name: 'N', role: '', instructions: 'x', color: 'red', shape: 'star', model: 'm', main: true }),
    { id: 'a', name: 'N', role: '', instructions: 'x', model: 'm', color: 'red', avatar: { shape: 'star' } })
  assert.deepEqual(updateBotPayload({ id: 'a', color: null, shape: null }), { id: 'a', color: null, avatar: null })
  assert.deepEqual(updateRoomPayload({ id: 'r', add: ['b', 3, ''], remove: [], members: ['x'], mode: 'free' }), { id: 'r', add: ['b'] })
})

test('searchHits finds text case-insensitively and trims around the match', () => {
  const hits = searchHits('BUDGET', [{ text: 'a'.repeat(40) + ' budget here' }, { text: 'none' }])
  assert.equal(hits.length, 1)
  assert.ok(hits[0].text.startsWith('…'))
  assert.deepEqual(searchHits('  ', [{ text: 'x' }]), [])
})

test('new endpoints need the device token (401)', async (t) => {
  const h = start(); t.after(h.stop)
  assert.equal((await h.json('GET', '/bots/info?id=chief')).status, 401)
  assert.equal((await h.json('POST', '/bots/update', { body: { id: 'chief', name: 'X' } })).status, 401)
  assert.equal((await h.json('POST', '/rooms/update', { body: { id: 'room-1', add: ['writer'] } })).status, 401)
  assert.equal((await h.json('GET', '/search?q=预算')).status, 401)
  assert.equal(h.calls.length, 0)
})

test('bots/info returns instructions, the 20 shapes and 12 colors; unknown is 404', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const ok = await h.json('GET', '/bots/info?id=chief', { headers })
  assert.equal(ok.status, 200)
  assert.equal(ok.body.bot.instructions, '只说中文')
  assert.equal(ok.body.bot.shape, 'whale')
  assert.equal(ok.body.bot.main, true)
  assert.equal(ok.body.shapes.length, 20)
  assert.equal(ok.body.colors.length, 12)
  assert.equal((await h.json('GET', '/bots/info?id=nope', { headers })).status, 404)
})

test('bots/update goes through ds-bot update-bot and passes its refusals on', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const ok = await h.json('POST', '/bots/update', { headers, body: { id: 'chief', shape: 'star', color: 'red', model: 'evil' } })
  assert.equal(ok.status, 200)
  assert.equal(ok.body.bot.shape, 'star')
  assert.deepEqual(h.calls.at(-1), { endpoint: 'update-bot', payload: { id: 'chief', model: 'evil', color: 'red', avatar: { shape: 'star' } } })
  const bad = await h.json('POST', '/bots/update', { headers, body: { id: 'chief', shape: 'nope' } })
  assert.equal(bad.status, 400)
  assert.ok(JSON.stringify(bad.body).includes('bot/invalid'))
  assert.equal((await h.json('POST', '/bots/update', { headers, body: { name: 'x' } })).status, 400)
})

test('rooms/update adds members through ds-bot update-room', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const ok = await h.json('POST', '/rooms/update', { headers, body: { id: 'room-1', add: ['writer'] } })
  assert.equal(ok.status, 200)
  assert.deepEqual(ok.body.room.members, ['chief', 'writer'])
  assert.deepEqual(h.calls.at(-1).payload, { id: 'room-1', add: ['writer'] })
  assert.equal((await h.json('POST', '/rooms/update', { headers, body: { id: 'room-1', add: ['ghost'] } })).status, 400)
})

test('search finds group messages', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const r = await h.json('GET', '/search?q=' + encodeURIComponent('预算'), { headers })
  assert.equal(r.status, 200)
  assert.equal(r.body.hits.length, 2)
  assert.equal(r.body.hits[0].kind, 'room')
  assert.equal(r.body.hits[0].who, 'Chief')
})
