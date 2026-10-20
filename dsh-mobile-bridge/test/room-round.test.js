// Regression for the 0.0.9 "group chat waits forever" bug, on real ds-bot formats
// (test/fixtures/dsbot-room-real.json was captured from ds-bot running inside dsh).
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { apply, dshHomeOf } from '../index.js'
import { DSBOT_ROUTE } from '../src/dsbot-api.js'
import { relayCall, relayResult, roundItems, roundSummary } from '../src/room-round.js'
import { roomItems } from '../src/chat-format.js'
import { dispatch, fakeContext } from './fake-ctx.js'

const P = '/api/mobile-bridge/v1'
const REAL = JSON.parse(readFileSync(new URL('./fixtures/dsbot-room-real.json', import.meta.url), 'utf8'))
const ROOM = REAL.roomSessionId
const JIA = 'session-03954678-fe66-4ef8-ad53-8e60850db22b'

function start({ state = REAL.state } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'mb-round-'))
  const dshHome = join(root, 'dsh')
  const botHome = join(dshHome, 'bot')
  mkdirSync(botHome, { recursive: true })
  writeFileSync(join(botHome, 'state.json'), JSON.stringify(state))
  const calls = []
  const route = {
    methods: new Set(['POST']),
    requestBody: 'buffered',
    fetch: async (request) => {
      const { endpoint, payload } = await request.json()
      calls.push({ endpoint, payload })
      const value = endpoint === 'state' ? { revision: state.revision, readOnly: null, rooms: Object.values(state.rooms), work: { rooms: {} } }
        : endpoint === 'room-progress' ? [] : true
      return new Response(JSON.stringify({ ok: true, value }))
    },
  }
  const sessionController = { prompt: async () => {}, page: async () => ({ hasMore: false, records: [] }) }
  const ctx = fakeContext({ services: { sessionController }, fetchRoutes: new Map([[DSBOT_ROUTE, route]]) })
  const rt = apply(ctx, { dshHome, home: join(dshHome, 'mobile-bridge'), botHome, devicePort: false, quietDiag: true })
  const call = (m, p, o) => dispatch(ctx.routes, m, `${P}${p}`, o)
  return {
    ctx, rt, calls, botHome, call,
    json: async (m, p, o) => { const r = await call(m, p, o); return { status: r.status, body: await r.json() } },
    write: s => writeFileSync(join(botHome, 'state.json'), JSON.stringify(s)),
    stop: async () => { await ctx.dispose(); rmSync(root, { recursive: true, force: true }) },
  }
}

async function authed(h) {
  const begin = await h.json('POST', '/auth/pair/begin')
  const c = await h.json('POST', '/auth/pair/complete', { body: { code: begin.body.code, deviceId: 'dev-r' } })
  const t = await h.json('POST', '/auth/token', { body: { deviceId: 'dev-r', refreshToken: c.body.refreshToken } })
  return { Authorization: `Bearer ${t.body.accessToken}` }
}

async function readUntil(reader, pattern, ms = 1500) {
  const dec = new TextDecoder()
  let text = ''
  const until = Date.now() + ms
  while (Date.now() < until) {
    const next = await Promise.race([reader.read(), new Promise(r => setTimeout(() => r({ timeout: true }), until - Date.now()))])
    if (next.timeout || next.done) break
    text += dec.decode(next.value)
    if (pattern.test(text)) break
  }
  return text
}

/** State after the round: ds-bot logged the user's line and nothing else (member failed). */
function afterFailedRound() {
  const s = structuredClone(REAL.state)
  const room = s.rooms[ROOM]
  room.seq += 1
  room.log.push({ who: 'User', text: '第四句', seq: room.seq })
  s.revision += 1
  return s
}

test('real relay results parse: success, failure, and non-relay events', () => {
  assert.deepEqual(relayCall(REAL.relayCall), { callId: REAL.relayCall.data.callId })
  const ok = relayResult(REAL.relayResultOk)
  assert.equal(ok.callId, REAL.relayCall.data.callId)
  assert.deepEqual(roundSummary(ok), { replied: 1, failed: [], passed: [] })
  assert.deepEqual(roundItems(ok), [])
  const bad = relayResult(REAL.relayResultFailed)
  assert.deepEqual(roundSummary(bad), { replied: 0, failed: ['阿甲'], passed: [] })
  assert.deepEqual(roundItems(bad), [{ kind: 'event', role: 'failed', botId: JIA, name: '阿甲' }])
  assert.equal(relayResult({ type: 'tool/result', data: { message: { content: [{ type: 'text', text: 'plain' }] } } }), null)
  assert.equal(relayCall({ type: 'tool/call', data: { name: 'read_file', callId: 'x' } }), null)
})

test('real state.json group log reads as chat items', () => {
  const items = roomItems(REAL.state.rooms[ROOM].log)
  assert.ok(items.some(i => i.kind === 'user' && i.text === '大家好'))
  assert.ok(items.some(i => i.kind === 'member' && i.botId === JIA && i.name === '阿甲'))
})

test('bug 0.0.9: a round whose member fails ends the wait (failed event + round end)', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const ac = new AbortController(); t.after(() => ac.abort())
  const res = await h.call('GET', `/stream?roomId=${ROOM}`, { headers, signal: ac.signal })
  const r = res.body.getReader()
  await readUntil(r, /event: hello/)
  await h.rt.feed.checkRooms() // baseline
  await h.json('POST', '/rooms/send', { headers, body: { roomId: ROOM, text: '第四句', clientMessageId: 'c-4' } })
  assert.deepEqual(h.calls.find(c => c.endpoint === 'send').payload, { sessionId: ROOM, text: '第四句' })
  h.ctx.emit('session/event', { id: ROOM }, REAL.relayCall)
  assert.match(await readUntil(r, /event: round/), /"phase":"start"/)
  // the member starts writing in its group Session, then its model call fails (no stream end)
  const memberSession = REAL.state.rooms[ROOM].sessions[JIA]
  h.ctx.emit('agent/assistant-stream', { agent: { id: memberSession }, frame: { type: 'start', attemptId: `${memberSession}:1`, turn: 1 } })
  let got = await readUntil(r, /"attemptId"/)
  assert.match(got, /"attemptId"/)
  h.write(afterFailedRound())
  h.ctx.emit('session/event', { id: ROOM }, REAL.relayResultFailed)
  got += await readUntil(r, /event: round[^\n]*\ndata: [^\n]*"phase":"end"/)
  assert.match(got, /"kind":"user","text":"第四句"/)
  assert.match(got, /"role":"failed"/)
  assert.match(got, /"failed":\["阿甲"\]/)
  assert.match(got, /"replied":0/)
  assert.match(got, /"outcome":"round-ended"/)
  // the user's line comes before the end marker
  assert.ok(got.indexOf('第四句') < got.indexOf('"phase":"end"'))
})

test('a successful round: member line from the log, then round end with replied 1', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const ac = new AbortController(); t.after(() => ac.abort())
  const r = (await h.call('GET', `/stream?roomId=${ROOM}`, { headers, signal: ac.signal })).body.getReader()
  await readUntil(r, /event: hello/)
  await h.rt.feed.checkRooms()
  const s = structuredClone(REAL.state)
  const room = s.rooms[ROOM]
  room.log.push({ who: 'User', text: '新的', seq: room.seq + 1 }, { who: '阿甲', botId: JIA, text: '好的', seq: room.seq + 2 })
  room.seq += 2
  h.write(s)
  h.ctx.emit('session/event', { id: ROOM }, REAL.relayResultOk)
  const got = await readUntil(r, /"phase":"end"/)
  assert.match(got, /"kind":"member","botId":"session-03954678[^"]*","name":"阿甲","text":"好的"/)
  assert.match(got, /"replied":1/)
})

test('relay events of a Session that is not a group are ignored', async (t) => {
  const h = start(); t.after(h.stop)
  assert.equal(await h.rt.feed.onRoomEvent('some-bot-session', REAL.relayResultFailed), false)
  assert.equal(await h.rt.feed.onRoomEvent(ROOM, REAL.relayResultFailed), true)
})

test('since= catches a reconnecting phone up on lines it missed', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const ac = new AbortController(); t.after(() => ac.abort())
  const r = (await h.call('GET', `/stream?roomId=${ROOM}&since=2`, { headers, signal: ac.signal })).body.getReader()
  const got = await readUntil(r, /"seq":5/)
  assert.doesNotMatch(got, /"seq":2[,}]/)
  assert.match(got, /"seq":3/)
  assert.match(got, /"catchUp":true/)
})

test('GET /diagnostics: token only; shows route, state file, ds-bot revision, room lines and stage marks', async (t) => {
  const h = start(); t.after(h.stop)
  assert.equal((await h.call('GET', '/diagnostics')).status, 401)
  const headers = await authed(h)
  await h.json('POST', '/rooms/send', { headers, body: { roomId: ROOM, text: '诊断', clientMessageId: 'c-d' } })
  h.ctx.emit('session/event', { id: ROOM }, REAL.relayCall)
  await new Promise(r => setTimeout(r, 20))
  await h.rt.feed.onRoomEvent(ROOM, REAL.relayResultFailed)
  const { status, body } = await h.json('GET', `/diagnostics?roomId=${ROOM}`, { headers })
  assert.equal(status, 200)
  assert.equal(body.version, '0.0.13')
  assert.equal(body.dsbot.usable, true)
  assert.deepEqual(body.dsbot.dsbotRoute.methods, ['POST'])
  assert.equal(body.stateFile.exists, true)
  assert.equal(body.sameStateFile, true)
  assert.equal(body.room.seq, REAL.state.rooms[ROOM].seq)
  assert.ok(body.room.last.length > 0)
  const kinds = body.diag.events.map(e => e.kind)
  for (const k of ['send.ok', 'round.start', 'round.end']) assert.ok(kinds.includes(k), k)
  assert.equal(body.diag.counters['send.ok'], 1)
  assert.doesNotMatch(JSON.stringify(body), /Bearer|refreshToken|accessToken/)
})

test('diagnostics flags a state file ds-bot does not write (revision far apart)', async (t) => {
  const stale = structuredClone(REAL.state)
  const h = start({ state: { ...REAL.state, revision: REAL.state.revision + 40 } }); t.after(h.stop)
  h.write(stale)
  const headers = await authed(h)
  const { body } = await h.json('GET', '/diagnostics', { headers })
  assert.equal(body.sameStateFile, false)
})

test('dshHomeOf reads DSH_HOME like ds-bot: ~ expanded, relative resolved, blank = ~/.dsh', () => {
  assert.equal(dshHomeOf('~/x'), join(homedir(), 'x'))
  assert.equal(dshHomeOf('  '), join(homedir(), '.dsh'))
  assert.equal(dshHomeOf(undefined), join(homedir(), '.dsh'))
  assert.ok(dshHomeOf('rel').endsWith('/rel') && dshHomeOf('rel').startsWith('/'))
})
