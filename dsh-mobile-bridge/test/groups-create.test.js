import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { apply } from '../index.js'
import { roomItem, roomItems } from '../src/chat-format.js'
import { createDsBotApi, DSBOT_ROUTE } from '../src/dsbot-api.js'
import { createBotPayload, createRoomPayload, updateBotPayload, updateRoomPayload } from '../src/routes.js'
import { dispatch, fakeContext } from './fake-ctx.js'

const P = '/api/mobile-bridge/v1'

const STATE = {
  version: 1,
  mainBotIds: ['chief'],
  bots: {
    chief: { id: 'chief', name: 'Chief', sessionId: 'sess-chief', color: 'blue', avatar: { shape: 'whale' } },
    writer: { id: 'writer', name: 'Writer', sessionId: 'sess-writer' },
  },
  rooms: {
    'room-1': {
      id: 'room-1', name: '周会', members: ['chief', 'writer'], admin: 'chief', mode: 'admin', seq: 3,
      sessions: { chief: 'g-chief', writer: 'g-writer' },
      log: [
        { who: 'User', text: '大家好', seq: 1 },
        { who: 'Chief', botId: 'chief', text: '你好', seq: 2 },
        { who: 'Writer', botId: 'writer', kind: 'done', text: '', seq: 3 },
      ],
    },
    'room-hidden': { id: 'room-hidden', name: '隐藏', members: ['chief', 'writer'], hidden: true, log: [] },
  },
  exchangeTurns: {},
  revision: 5,
}

/** A stand-in for ds-bot's POST /api/bot route: records calls, refuses like ds-bot does. */
function fakeDsBot() {
  const calls = []
  const reply = (value) => new Response(JSON.stringify({ ok: true, value }), { headers: { 'content-type': 'application/json' } })
  const fail = (message, code) => new Response(JSON.stringify({ ok: false, error: { code, message, details: {} } }))
  const route = {
    methods: new Set(['POST']),
    requestBody: 'buffered',
    fetch: async (request) => {
      const { endpoint, payload } = await request.json()
      calls.push({ endpoint, payload })
      switch (endpoint) {
        case 'models': return reply({ modelsKey: 'k' })
        case 'state': return reply({
          models: [{ ref: 'deepseek/chat', name: 'DeepSeek Chat', providerName: 'DeepSeek' }, { ref: 'x/y', name: 'Y', needsKey: true }],
          defaultModel: 'deepseek/chat',
          agentPresets: [{ value: 'coder', name: 'coder' }, { value: 'bad', name: 'bad', broken: true }],
          colors: ['deepseek', 'blue', 'charcoal'],
          secrets: { SHOULD_NOT_LEAK: true },
        })
        case 'folders': return reply({ team: '/team', picker: 'browse', folders: [{ path: '/w', title: 'w' }] })
        case 'folder-list': return reply({ path: payload.path, entries: [{ name: 'a' }] })
        case 'efforts': return reply({ efforts: [{ id: 'high', name: 'High' }], defaultEffort: 'high' })
        case 'create-bot':
          if (payload.name === 'Chief') return fail('A Bot named Chief already exists', 'bot/refused')
          return reply({ id: 'new-1', name: payload.name, role: payload.role ?? '', model: payload.model, avatar: payload.avatar, instructions: 'secret-ish' })
        case 'create-room':
          if ((payload.members ?? []).length < 2) return fail('A group chat needs at least two Bots', 'bot/refused')
          // Match real channel.js: create-room ignores payload.mode and defaults to admin.
          return reply({ id: 'room-new', name: payload.name ?? 'Chief、Writer', members: payload.members, admin: payload.admin, mode: 'admin' })
        case 'update-room': {
          const room = STATE.rooms[payload.id] ?? { id: payload.id, name: '周报', members: payload.members ?? ['chief', 'writer'], admin: 'chief', mode: 'admin' }
          if (payload.mode) room.mode = payload.mode
          if (payload.name !== undefined) room.name = payload.name
          if (payload.id === 'room-new' || !STATE.rooms[payload.id]) {
            // Fresh room from create: invent a view shaped like channel's update-room reply.
            return reply({ room: { id: 'room-new', name: room.name ?? '周报', members: room.members ?? ['chief', 'writer'], admin: room.admin ?? 'chief', mode: room.mode }, changes: ['every member now replies in turn'] })
          }
          STATE.rooms[payload.id] = room
          return reply({ room: { id: room.id, name: room.name, members: room.members, admin: room.admin, mode: room.mode }, changes: [] })
        }
        case 'send': return reply(true)
        case 'update-bot': {
          const bot = STATE.bots[payload.id]
          if (!bot) return fail('Unknown Bot', 'bot/not-found')
          if (payload.model !== undefined) bot.model = payload.model
          if (payload.name !== undefined) bot.name = payload.name
          return reply({ ...bot, avatar: bot.avatar ?? null })
        }
        default: return fail(`Unknown endpoint ${endpoint}`, 'bot/not-found')
      }
    },
  }
  return { calls, routes: new Map([[DSBOT_ROUTE, route]]) }
}

function start({ withDsBot = true, state = STATE } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'mb-groups-'))
  const dshHome = join(root, 'dsh')
  const botHome = join(dshHome, 'bot')
  mkdirSync(botHome, { recursive: true })
  writeFileSync(join(botHome, 'state.json'), JSON.stringify(state))
  const ds = fakeDsBot()
  const sessionController = { prompt: async () => {}, page: async () => ({ hasMore: false, records: [] }) }
  const ctx = fakeContext({ services: { sessionController }, fetchRoutes: withDsBot ? ds.routes : undefined })
  ctx.sessionController = sessionController
  const rt = apply(ctx, { dshHome, home: join(dshHome, 'mobile-bridge'), botHome, devicePort: false, quietDiag: true })
  const call = (m, p, o) => dispatch(ctx.routes, m, `${P}${p}`, o)
  return {
    ctx, rt, ds, botHome, call,
    json: async (m, p, o) => { const r = await call(m, p, o); return { status: r.status, body: await r.json() } },
    stop: async () => { await ctx.dispose(); rmSync(root, { recursive: true, force: true }) },
  }
}

async function authed(h) {
  const begin = await h.json('POST', '/auth/pair/begin')
  const c = await h.json('POST', '/auth/pair/complete', { body: { code: begin.body.code, deviceId: 'dev-g' } })
  const t = await h.json('POST', '/auth/token', { body: { deviceId: 'dev-g', refreshToken: c.body.refreshToken } })
  return { Authorization: `Bearer ${t.body.accessToken}` }
}

test('roomItem maps ds-bot group log lines', () => {
  assert.deepEqual(roomItem({ who: 'User', text: 'hi', seq: 1 }), { kind: 'user', text: 'hi', seq: 1 })
  assert.deepEqual(roomItem({ who: 'A', botId: 'a', text: 'yo', seq: 2, length: 5000 }), { kind: 'member', botId: 'a', name: 'A', text: 'yo', seq: 2, cut: true })
  assert.deepEqual(roomItem({ who: 'A', botId: 'a', kind: 'stopped', text: '', seq: 3 }), { kind: 'event', role: 'stopped', botId: 'a', name: 'A', seq: 3 })
  assert.equal(roomItem({ who: 'A', kind: 'weird', seq: 4 }), null)
  assert.equal(roomItems(null).length, 0)
})

test('team lists visible group chats with member names and preview', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const { status, body } = await h.json('GET', '/team', { headers })
  assert.equal(status, 200)
  assert.equal(body.rooms.length, 1)
  assert.equal(body.rooms[0].id, 'room-1')
  assert.deepEqual(body.rooms[0].memberNames, ['Chief', 'Writer'])
  assert.equal(body.rooms[0].preview, 'Chief：你好')
  assert.equal(body.bots.find(b => b.id === 'chief').shape, 'whale')
})

test('rooms/history returns the group log; unknown room is 404', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const ok = await h.json('GET', '/rooms/history?roomId=room-1', { headers })
  assert.equal(ok.status, 200)
  assert.deepEqual(ok.body.items.map(i => i.kind), ['user', 'member', 'event'])
  assert.equal((await h.json('GET', '/rooms/history?roomId=nope', { headers })).status, 404)
  assert.equal((await h.json('GET', '/rooms/history?roomId=room-1')).status, 401)
})

test('rooms/send goes through ds-bot send with the group Session, idempotent per clientMessageId', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const req = { headers, body: { roomId: 'room-1', text: ' 开会 ', clientMessageId: 'c-1' } }
  const a = await h.json('POST', '/rooms/send', req)
  const b = await h.json('POST', '/rooms/send', req)
  assert.equal(a.status, 200)
  assert.equal(b.body.duplicate, true)
  const sends = h.ds.calls.filter(c => c.endpoint === 'send')
  assert.equal(sends.length, 1)
  assert.deepEqual(sends[0].payload, { sessionId: 'room-1', text: '开会' })
  // A bot id is not a group chat: refused before ds-bot is asked.
  assert.equal((await h.json('POST', '/rooms/send', { headers, body: { roomId: 'chief', text: 'x' } })).status, 404)
  assert.equal((await h.json('POST', '/rooms/send', { headers, body: { roomId: 'room-1', text: '  ' } })).status, 400)
  assert.equal((await h.json('POST', '/rooms/send', { body: { roomId: 'room-1', text: 'x' } })).status, 401)
})

test('bots/create forwards exactly the ds-bot create-bot fields and surfaces ds-bot refusals', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const body = {
    name: 'Analyst', role: '数据', color: 'blue', avatar: { shape: 'star', extra: 1 }, model: 'deepseek/chat',
    effort: 'high', agentPreset: 'coder', workDir: '/w', instructions: '只说中文', brief: '', main: true, createdBy: 'x',
  }
  const ok = await h.json('POST', '/bots/create', { headers, body })
  assert.equal(ok.status, 200)
  assert.equal(ok.body.bot.id, 'new-1')
  assert.equal(ok.body.bot.instructions, undefined)
  const sent = h.ds.calls.find(c => c.endpoint === 'create-bot').payload
  assert.deepEqual(sent, {
    name: 'Analyst', role: '数据', color: 'blue', model: 'deepseek/chat', effort: 'high', agentPreset: 'coder',
    workDir: '/w', instructions: '只说中文', avatar: { shape: 'star' },
  })
  const clash = await h.json('POST', '/bots/create', { headers, body: { name: 'Chief' } })
  assert.equal(clash.status, 400)
  assert.equal(clash.body.error.message, 'A Bot named Chief already exists')
  assert.equal(clash.body.error.zh, '已经有叫 Chief 的助手了')
  assert.equal((await h.json('POST', '/bots/create', { body: { name: 'z' } })).status, 401)
})

test('rooms/create sends admin first with greet, like the desktop form', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const ok = await h.json('POST', '/rooms/create', { headers, body: { admin: 'chief', members: ['writer', 'chief'], name: ' 周报 ' } })
  assert.equal(ok.status, 200)
  assert.equal(ok.body.room.id, 'room-new')
  assert.deepEqual(h.ds.calls.find(c => c.endpoint === 'create-room').payload, { members: ['chief', 'writer'], admin: 'chief', greet: true, mode: 'everyone', name: '周报' })
  // channel create-room drops mode (stays admin); bridge follows up with update-room.
  assert.deepEqual(h.ds.calls.find(c => c.endpoint === 'update-room')?.payload, { id: 'room-new', mode: 'everyone' })
  assert.equal(ok.body.room.mode, 'everyone')
  const few = await h.json('POST', '/rooms/create', { headers, body: { admin: 'chief', members: [] } })
  assert.equal(few.status, 400)
  assert.match(few.body.error.message, /at least two/)
  assert.equal(few.body.error.zh, '群聊至少要两个助手')
})

test('rooms/update can set reply mode (everyone / admin)', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const ok = await h.json('POST', '/rooms/update', { headers, body: { id: 'room-1', mode: 'everyone' } })
  assert.equal(ok.status, 200)
  assert.equal(ok.body.room.mode, 'everyone')
  assert.deepEqual(h.ds.calls.at(-1), { endpoint: 'update-room', payload: { id: 'room-1', mode: 'everyone' } })
  const bad = await h.json('POST', '/rooms/update', { headers, body: { id: 'room-1', mode: 'weird' } })
  assert.equal(bad.status, 200) // mode ignored when not admin|everyone
  assert.deepEqual(h.ds.calls.at(-1).payload, { id: 'room-1' })
})

test('payload helpers drop non-string values', () => {
  assert.deepEqual(createBotPayload({ name: 5, role: 'r', avatar: { shape: 3 } }), { role: 'r' })
  assert.deepEqual(createRoomPayload({ members: ['a', 1, 'b'] }), { members: ['a', 'b'], greet: true, mode: 'everyone' })
  assert.deepEqual(createRoomPayload({ members: ['a'], admin: 'a', mode: 'admin' }), { members: ['a'], admin: 'a', greet: true, mode: 'admin' })
  assert.deepEqual(updateRoomPayload({ id: 'r', mode: 'everyone', name: 'n' }), { id: 'r', mode: 'everyone', name: 'n' })
  assert.deepEqual(updateRoomPayload({ id: 'r', mode: 'nope' }), { id: 'r' })
})

test('bots/options picks models, presets, colors from ds-bot state without leaking the rest', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const { status, body } = await h.json('GET', '/bots/options', { headers })
  assert.equal(status, 200)
  assert.equal(body.defaultModel, 'deepseek/chat')
  assert.equal(body.models[1].needsKey, true)
  assert.deepEqual(body.agentPresets, [{ value: 'coder', name: 'coder' }])
  assert.equal(body.colors.find(c => c.id === 'blue').hex, '#2A92FE')
  assert.ok(body.shapes.some(s => s.id === 'star'))
  assert.equal(body.folders.folders[0].path, '/w')
  assert.equal(body.secrets, undefined)
  assert.ok(!JSON.stringify(body).includes('SHOULD_NOT_LEAK'))
  const eff = await h.json('GET', '/bots/efforts?ref=deepseek%2Fchat', { headers })
  assert.equal(eff.body.efforts[0].id, 'high')
  const list = await h.json('GET', '/folders?path=%2Fw', { headers })
  assert.equal(list.body.path, '/w')
})

test('without ds-bot loaded, write endpoints answer 503', async (t) => {
  const h = start({ withDsBot: false }); t.after(h.stop)
  const headers = await authed(h)
  const r = await h.json('POST', '/bots/create', { headers, body: { name: 'A' } })
  assert.equal(r.status, 503)
  assert.equal((await h.json('POST', '/rooms/send', { headers, body: { roomId: 'room-1', text: 'x' } })).status, 503)
})

test('dsbot api refuses endpoints outside the allow list', async () => {
  const api = createDsBotApi({ ctx: { connection: { fetchRoutes: fakeDsBot().routes } } })
  await assert.rejects(api.call('delete-bot', { id: 'chief' }), err => err.status === 403)
  await assert.rejects(api.call('secret-set', {}), err => err.status === 403)
  assert.equal(api.available(), true)
  assert.equal(createDsBotApi({ ctx: {} }).available(), false)
})

async function readUntil(reader, pattern, ms = 3000) {
  const dec = new TextDecoder()
  let text = ''
  const end = Date.now() + ms
  while (Date.now() < end) {
    const next = await Promise.race([reader.read(), new Promise(r => setTimeout(() => r({ timeout: true }), end - Date.now()))])
    if (next.timeout || next.done) break
    text += dec.decode(next.value)
    if (pattern.test(text)) break
  }
  return text
}

test('group stream: new log lines and member streaming reach the room stream only', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const acRoom = new AbortController()
  const acBot = new AbortController()
  const room = await h.call('GET', '/stream?roomId=room-1', { headers, signal: acRoom.signal })
  const bot = await h.call('GET', '/stream?botId=chief', { headers, signal: acBot.signal })
  t.after(() => { acRoom.abort(); acBot.abort() })
  const rr = room.body.getReader()
  const br = bot.body.getReader()
  assert.match(await readUntil(rr, /event: hello/), /"roomId":"room-1"/)
  await readUntil(br, /event: hello/)
  await h.rt.feed.checkRooms() // baseline seq
  // Phone sent "新消息"; ds-bot logs it, then Writer replies.
  await h.json('POST', '/rooms/send', { headers, body: { roomId: 'room-1', text: '新消息', clientMessageId: 'c-9' } })
  const next = structuredClone(STATE)
  next.rooms['room-1'].log.push({ who: 'User', text: '新消息', seq: 4 }, { who: 'Writer', botId: 'writer', text: '收到', seq: 5 })
  next.rooms['room-1'].seq = 5
  writeFileSync(join(h.botHome, 'state.json'), JSON.stringify(next))
  h.ctx.emit('agent/assistant-stream', { agent: { id: 'g-writer' }, frame: { type: 'start', attemptId: 'at-1', turn: 1 } })
  await new Promise(r => setTimeout(r, 20))
  h.ctx.emit('agent/assistant-stream', { agent: { id: 'g-writer' }, frame: { type: 'chunk', attemptId: 'at-1', chunk: { type: 'text-delta', text: '收' } } })
  await h.rt.feed.checkRooms()
  const got = await readUntil(rr, /收到/)
  assert.match(got, /"clientMessageId":"c-9"/)
  assert.match(got, /"kind":"member"/)
  assert.match(got, /"attemptId":"at-1"/)
  assert.match(got, /"name":"Writer"/)
  const botText = await readUntil(br, /room-1/, 300)
  assert.doesNotMatch(botText, /room-1/)
})

test('3081 listener: new endpoints need the device token', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'mb-g3081-'))
  const dshHome = join(root, 'dsh')
  mkdirSync(join(dshHome, 'bot'), { recursive: true })
  writeFileSync(join(dshHome, 'bot', 'state.json'), JSON.stringify(STATE))
  const ctx = fakeContext({ fetchRoutes: fakeDsBot().routes })
  const rt = apply(ctx, { dshHome, home: join(dshHome, 'mobile-bridge'), botHome: join(dshHome, 'bot'), devicePort: 0 })
  await rt.deviceServer.ready
  t.after(async () => { await ctx.dispose(); rmSync(root, { recursive: true, force: true }) })
  const base = `http://127.0.0.1:${rt.deviceServer.port}${P}`
  for (const [m, p] of [['GET', '/bots/options'], ['GET', '/bots/efforts?ref=a'], ['GET', '/folders'], ['POST', '/bots/create'],
    ['POST', '/rooms/create'], ['GET', '/rooms/history?roomId=room-1'], ['POST', '/rooms/send'], ['GET', '/stream?roomId=room-1']]) {
    const res = await fetch(base + p, { method: m, ...(m === 'POST' ? { body: '{}', headers: { 'content-type': 'application/json' } } : {}) })
    assert.equal(res.status, 401, `${m} ${p}`)
  }
  const bad = await fetch(base + '/bots/create', { method: 'POST', body: '{"name":"x"}', headers: { Authorization: 'Bearer nope', 'content-type': 'application/json' } })
  assert.equal(bad.status, 401)
})


test('rooms/send keeps @Name in the text for ds-bot to parse (no separate mention ids)', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const text = '@Chief 看一下，也请 @Writer 帮忙'
  const res = await h.json('POST', '/rooms/send', { headers, body: { roomId: 'room-1', text, clientMessageId: 'at-1' } })
  assert.equal(res.status, 200)
  const sends = h.ds.calls.filter(c => c.endpoint === 'send')
  assert.equal(sends.length, 1)
  // trim() drops only ends; @Name in the middle stays for ds-bot mentionSpans
  assert.deepEqual(sends[0].payload, { sessionId: 'room-1', text })
  assert.match(sends[0].payload.text, /@Chief/)
  assert.match(sends[0].payload.text, /@Writer/)
})

test('bots/update forwards model to ds-bot update-bot', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  assert.deepEqual(updateBotPayload({ id: 'chief', model: 'deepseek/reason' }), { id: 'chief', model: 'deepseek/reason' })
  const res = await h.json('POST', '/bots/update', { headers, body: { id: 'chief', model: 'deepseek/reason' } })
  assert.equal(res.status, 200)
  const upd = h.ds.calls.filter(c => c.endpoint === 'update-bot')
  assert.equal(upd.length, 1)
  assert.equal(upd[0].payload.model, 'deepseek/reason')
  assert.equal(upd[0].payload.id, 'chief')
})
