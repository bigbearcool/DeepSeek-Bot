import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { apply } from '../index.js'
import { DSBOT_ROUTE } from '../src/dsbot-api.js'
import { actionWords, botUnread, plainReason, roomUnread } from '../src/chat-list.js'
import { dispatch, fakeContext } from './fake-ctx.js'

const P = '/api/mobile-bridge/v1'

const state = () => ({
  version: 1,
  mainBotIds: ['chief'],
  bots: {
    chief: { id: 'chief', name: 'Chief', sessionId: 'sess-chief', createdAt: 1000 },
    writer: { id: 'writer', name: 'Writer', sessionId: 'sess-writer', createdAt: 1000 },
  },
  rooms: {
    'room-1': {
      id: 'room-1', name: '周会', members: ['chief', 'writer'], admin: 'chief', seq: 3, createdAt: 1000,
      sessions: { chief: 'g-chief', writer: 'g-writer' },
      log: [
        { who: 'User', text: '大家好', seq: 1 },
        { who: 'Chief', botId: 'chief', text: '你好', seq: 2 },
        { who: 'Writer', botId: 'writer', kind: 'done', text: '', seq: 3 },
      ],
    },
  },
  exchangeTurns: {},
  revision: 7,
})

const ev = (seq, time, type, text, kind = 'user') => type === 'bot'
  ? { seq, time, type: 'assistant/message', data: { message: { content: [{ type: 'text', text }] } } }
  : { seq, time, type: 'user/message', data: { source: { kind }, content: [{ type: 'text', text }] } }

function start() {
  const root = mkdtempSync(join(tmpdir(), 'mb-p0-'))
  const dshHome = join(root, 'dsh')
  const botHome = join(dshHome, 'bot')
  mkdirSync(botHome, { recursive: true })
  const st = state()
  const save = () => writeFileSync(join(botHome, 'state.json'), JSON.stringify(st))
  save()
  const sessions = {
    'sess-chief': [ev(1, 5000, 'user', '在吗'), ev(2, 5100, 'bot', '在的')],
    'sess-writer': [ev(1, 9000, 'user', '写首诗'), ev(2, 9100, 'bot', '床前明月光')],
    'room-1': [ev(1, 7000, 'user', 'relay')],
  }
  const work = { bots: {}, rooms: {}, approvals: [] }
  const dsCalls = []
  const reply = value => new Response(JSON.stringify({ ok: true, value }))
  const fail = (message, code) => new Response(JSON.stringify({ ok: false, error: { code, message, details: {} } }))
  const route = {
    methods: new Set(['POST']),
    fetch: async (request) => {
      const { endpoint, payload } = await request.json()
      dsCalls.push({ endpoint, payload })
      if (endpoint === 'state') return reply({ revision: st.revision, work })
      if (endpoint === 'stop-member-work') return reply((work.rooms[payload.roomId] ?? []).some(i => i.botId === payload.botId))
      if (endpoint === 'approval-answer') {
        if (!['allow', 'reject'].includes(payload.choice)) return fail('Choose allow or reject', 'bot/invalid')
        const entry = work.approvals.find(a => a.id === payload.id)
        if (!entry?.answerable) return fail('This approval is no longer open', 'bot/stale')
        work.approvals = work.approvals.filter(a => a.id !== payload.id)
        return reply(true)
      }
      return fail('nope', 'bot/not-found')
    },
  }
  const cancels = []
  const sessionController = {
    prompt: async () => {},
    page: async ({ address, maxMessages }) => ({ hasMore: false, records: (sessions[address.sessionId] ?? []).slice(-maxMessages) }),
    cancel: ({ sessionId }) => {
      cancels.push(sessionId)
      if (sessionId === 'sess-writer') throw Object.assign(new Error('not attached'), { code: 'session/not-found' })
      return { accepted: true }
    },
  }
  const ctx = fakeContext({ services: { sessionController }, fetchRoutes: new Map([[DSBOT_ROUTE, route]]) })
  ctx.sessionController = sessionController
  const rt = apply(ctx, { dshHome, home: join(dshHome, 'mobile-bridge'), botHome, devicePort: false, quietDiag: true })
  const json = async (m, p, o) => { const r = await dispatch(ctx.routes, m, `${P}${p}`, o); return { status: r.status, body: await r.json() } }
  const tick = () => new Promise(r => setTimeout(r, 2100))
  return { st, save, sessions, work, dsCalls, cancels, json, tick, stop: async () => { await ctx.dispose(); rmSync(root, { recursive: true, force: true }) } }
}

async function authed(h, deviceId = 'dev-1') {
  const begin = await h.json('POST', '/auth/pair/begin')
  const c = await h.json('POST', '/auth/pair/complete', { body: { code: begin.body.code, deviceId } })
  const t = await h.json('POST', '/auth/token', { body: { deviceId, refreshToken: c.body.refreshToken } })
  return { Authorization: `Bearer ${t.body.accessToken}` }
}

test('new endpoints refuse without a device token (401)', async (t) => {
  const h = start(); t.after(h.stop)
  for (const [m, p, body] of [
    ['GET', '/chats/list'], ['GET', '/approvals'],
    ['POST', '/chats/read', { kind: 'bot', id: 'chief' }],
    ['POST', '/chats/stop', { botId: 'chief' }],
    ['POST', '/rooms/stop', { roomId: 'room-1' }],
    ['POST', '/approvals/answer', { id: 'a1', choice: 'allow' }],
  ]) {
    const r = await h.json(m, p, body ? { body } : {})
    assert.equal(r.status, 401, `${m} ${p}`)
    const bad = await h.json(m, p, { ...(body ? { body } : {}), headers: { Authorization: 'Bearer nope' } })
    assert.equal(bad.status, 401, `${m} ${p} bad token`)
  }
  assert.equal(h.cancels.length, 0)
  assert.equal(h.dsCalls.filter(c => c.endpoint !== 'state').length, 0)
})

test('list: newest activity first, preview of last message, starts read', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const { status, body } = await h.json('GET', '/chats/list', { headers })
  assert.equal(status, 200)
  assert.deepEqual(body.chats.map(c => `${c.kind}:${c.id}`), ['bot:writer', 'room:room-1', 'bot:chief'])
  assert.equal(body.chats[0].preview, '床前明月光')
  assert.equal(body.chats[1].preview, 'Chief：你好')
  assert.ok(body.chats.every(c => c.unread === 0))
})

test('unread: counts new replies after the mark, per device; opening clears', async (t) => {
  const h = start(); t.after(h.stop)
  const a = await authed(h, 'dev-a')
  await h.json('GET', '/chats/list', { headers: a })
  h.sessions['sess-chief'].push(ev(3, 20000, 'bot', '第一条'), ev(4, 20001, 'bot', '第二条'))
  h.st.rooms['room-1'].log.push({ who: 'Writer', botId: 'writer', text: '新消息', seq: 4 }, { who: 'User', text: '我说的', seq: 5 })
  h.st.rooms['room-1'].seq = 5
  h.save()
  await h.tick()
  let list = (await h.json('GET', '/chats/list', { headers: a })).body.chats
  assert.equal(list[0].id, 'chief')
  assert.equal(list.find(c => c.id === 'chief').unread, 2)
  assert.equal(list.find(c => c.id === 'room-1').unread, 1, 'own lines never count')
  // A second phone paired now starts with nothing unread.
  const b = await authed(h, 'dev-b')
  assert.ok((await h.json('GET', '/chats/list', { headers: b })).body.chats.every(c => c.unread === 0))
  assert.equal((await h.json('POST', '/chats/read', { headers: a, body: { kind: 'bot', id: 'chief' } })).status, 200)
  assert.equal((await h.json('POST', '/chats/read', { headers: a, body: { kind: 'room', id: 'room-1' } })).body.seq, 5)
  list = (await h.json('GET', '/chats/list', { headers: a })).body.chats
  assert.ok(list.every(c => c.unread === 0))
  assert.equal((await h.json('POST', '/chats/read', { headers: a, body: { kind: 'room', id: 'nope' } })).status, 404)
  assert.equal((await h.json('POST', '/chats/read', { headers: a, body: { kind: 'x', id: 'a' } })).status, 400)
})

test('stop: Bot chat via sessionController.cancel; idle chat is not an error', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const r = await h.json('POST', '/chats/stop', { headers, body: { botId: 'chief' } })
  assert.equal(r.status, 200)
  assert.equal(r.body.stopped, true)
  assert.deepEqual(h.cancels, ['sess-chief'])
  const idle = await h.json('POST', '/chats/stop', { headers, body: { botId: 'writer' } })
  assert.equal(idle.status, 200)
  assert.equal(idle.body.stopped, false)
  assert.equal((await h.json('POST', '/chats/stop', { headers, body: { botId: 'ghost' } })).status, 404)
})

test('stop: group via ds-bot stop-member-work for each member at work', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  h.work.rooms['room-1'] = [{ botId: 'writer', since: 1 }]
  const list = (await h.json('GET', '/chats/list', { headers })).body.chats
  assert.equal(list.find(c => c.id === 'room-1').running, true)
  const r = await h.json('POST', '/rooms/stop', { headers, body: { roomId: 'room-1' } })
  assert.equal(r.status, 200)
  assert.equal(r.body.stopped, 1)
  assert.deepEqual(h.dsCalls.filter(c => c.endpoint === 'stop-member-work').map(c => c.payload), [{ roomId: 'room-1', botId: 'writer' }])
})

test('approvals: listed in plain words, answered through ds-bot approval-answer', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  h.work.approvals = [
    { id: 'a1', botId: 'writer', roomId: 'room-1', tool: 'bash', reason: 'run tests', detail: { code: 'npm test' }, at: 1, answerable: true },
    { id: 'a2', botId: 'chief', tool: 'write', reason: '', detail: {}, at: 2, answerable: false },
  ]
  const list = (await h.json('GET', '/approvals', { headers })).body.approvals
  assert.equal(list[0].action, '运行一条命令')
  assert.equal(list[0].roomName, '周会')
  assert.equal(list[0].botName, 'Writer')
  assert.equal(list[1].answerable, false)
  assert.ok(!('tool' in list[0]))
  const bad = await h.json('POST', '/approvals/answer', { headers, body: { id: 'a1', choice: 'maybe' } })
  assert.equal(bad.status, 400)
  assert.equal(bad.body.error?.zh ?? bad.body.zh, '请选择同意或拒绝')
  assert.equal((await h.json('POST', '/approvals/answer', { headers, body: { id: 'a1', choice: 'allow' } })).status, 200)
  const stale = await h.json('POST', '/approvals/answer', { headers, body: { id: 'a1', choice: 'allow' } })
  assert.equal(stale.status, 409)
  assert.deepEqual(h.dsCalls.filter(c => c.endpoint === 'approval-answer').map(c => c.payload.choice), ['maybe', 'allow', 'allow'])
})

test('helpers', () => {
  assert.equal(roomUnread([{ who: 'A', seq: 3 }, { who: 'User', seq: 4 }, { who: 'A', kind: 'done', seq: 5 }], 2), 1)
  assert.equal(roomUnread([{ who: 'A', seq: 3 }], undefined), 0)
  assert.equal(botUnread([{ kind: 'bot', seq: 5 }, { kind: 'user', seq: 6 }], 4), 1)
  assert.equal(actionWords('str_replace_editor'), '修改文件')
  assert.equal(plainReason('escalate sandbox to danger-full-access: 跑一下测试'), '跑一下测试')
  assert.equal(actionWords('mystery'), '做一个需要你同意的操作')
})
