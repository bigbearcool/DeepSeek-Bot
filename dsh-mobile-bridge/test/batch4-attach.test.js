import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { apply } from '../index.js'
import { DSBOT_ROUTE } from '../src/dsbot-api.js'
import { cleanName, sniffImage } from '../src/attachments.js'
import { roomItem, splitRoomText } from '../src/chat-format.js'
import { dispatch, fakeContext } from './fake-ctx.js'

const P = '/api/mobile-bridge/v1'
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3])
const STATE = {
  version: 1, mainBotIds: [],
  bots: { a: { id: 'a', name: 'A', sessionId: 'sess-a' }, b: { id: 'b', name: 'B', sessionId: 'sess-b' } },
  rooms: { r: { id: 'r', name: '组', members: ['a', 'b'], seq: 0, log: [] } },
  exchangeTurns: {}, revision: 1,
}

function start({ imageDir } = {}) {
  const prompts = []
  const staged = []
  const pictureRef = { attachmentId: 'sha256:aaaa', mediaType: 'image/png', bytes: 11, width: 1, height: 1, name: 'p.png' }
  const fileRef = { attachmentId: 'sha256:ffff', name: 'r.pdf', bytes: 4 }
  const sessionController = {
    prompt: async (req) => { prompts.push(req) },
    page: async ({ address }) => (address.sessionId === 'sess-a'
      ? { hasMore: false, records: [{ type: 'user/message', seq: 1, time: 1, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '看' }, { type: 'image', attachment: pictureRef }, { type: 'file', attachment: fileRef }] } }] }
      : { hasMore: false, records: [] }),
  }
  const fileUploads = {
    uploadStream: async ({ sessionId, data, name }) => {
      const chunks = []; for await (const c of data) chunks.push(c)
      staged.push({ sessionId, name, size: chunks.reduce((n, c) => n + c.length, 0) })
      return { receiptId: `rcpt-${staged.length}`, file: { attachmentId: 'sha256:new', name, bytes: 4 } }
    },
  }
  const attachments = {
    imageHostPath: ref => (ref.attachmentId === 'sha256:aaaa' ? join(imageDir, 'p.png') : undefined),
    readFileStream: async function* () { yield new Uint8Array([1, 2, 3, 4]) },
  }
  const root = mkdtempSync(join(tmpdir(), 'mb-b4-'))
  const dshHome = join(root, 'dsh'); const botHome = join(dshHome, 'bot')
  mkdirSync(botHome, { recursive: true })
  writeFileSync(join(botHome, 'state.json'), JSON.stringify(STATE))
  const route = { methods: new Set(['POST']), requestBody: 'buffered', fetch: async () => new Response(JSON.stringify({ ok: true, value: true })) }
  const ctx = fakeContext({ services: { sessionController, fileUploads, attachments }, fetchRoutes: new Map([[DSBOT_ROUTE, route]]) })
  ctx.sessionController = sessionController
  apply(ctx, { dshHome, home: join(dshHome, 'mobile-bridge'), botHome, devicePort: false, quietDiag: true })
  const json = async (m, p, o) => { const r = await dispatch(ctx.routes, m, `${P}${p}`, o); return { status: r.status, body: await r.json() } }
  const raw = async (p, bytes, headers = {}) => {
    const r = ctx.routes.find(x => x.path === `${P}/uploads`)
    const res = await r.fetch(new Request(`http://localhost${P}${p}`, { method: 'POST', headers: { 'content-type': 'application/octet-stream', ...headers }, body: bytes }))
    return { status: res.status, body: await res.json() }
  }
  const get = async (p, headers) => dispatch(ctx.routes, 'GET', `${P}${p}`, { headers })
  return { prompts, staged, json, raw, get, stop: async () => { await ctx.dispose(); rmSync(root, { recursive: true, force: true }) } }
}

async function authed(h) {
  const begin = await h.json('POST', '/auth/pair/begin')
  const c = await h.json('POST', '/auth/pair/complete', { body: { code: begin.body.code, deviceId: 'dev-4' } })
  const t = await h.json('POST', '/auth/token', { body: { deviceId: 'dev-4', refreshToken: c.body.refreshToken } })
  return { Authorization: `Bearer ${t.body.accessToken}` }
}

test('sniffImage and cleanName', () => {
  assert.equal(sniffImage(PNG), 'image/png')
  assert.equal(sniffImage(new Uint8Array([0xff, 0xd8, 0xff, 0])), 'image/jpeg')
  assert.equal(sniffImage(new Uint8Array([1, 2, 3])), null)
  assert.equal(cleanName('../../etc/passwd'), 'passwd')
  assert.equal(cleanName('C:\\Users\\x\\a b.pdf'), 'a b.pdf')
})

test('group log: file lines and picture readings leave the text, become attachments', () => {
  const s = splitRoomText('看这个\n[Image "a.png", read by ocr:]\nhello\n[End of image "a.png"]\n[Image sha256:abcdef12: it could not be read right now. Ask the user what it shows if it matters.]\n[File "r.pdf" (12 bytes) attached]')
  assert.equal(s.text, '看这个')
  assert.deepEqual(s.images, [{ name: 'a.png' }, { prefix: 'abcdef12' }])
  const item = roomItem({ who: 'User', text: '[Image "a.png", read by ocr:]\nx\n[End of image "a.png"]', seq: 1 }, [{ attachmentId: 'sha256:1', mediaType: 'image/png', name: 'a.png' }])
  assert.equal(item.attachments[0].id, 'sha256:1')
})

test('uploads and attachments need the device token (401)', async (t) => {
  const h = start(); t.after(h.stop)
  assert.equal((await h.raw('/uploads?kind=bot&id=a', PNG)).status, 401)
  assert.equal((await h.get('/attachments?id=sha256:aaaa')).status, 401)
  assert.equal(h.staged.length, 0)
})

test('a picture goes into the prompt as an image part dsh admits', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const up = await h.raw('/uploads?kind=bot&id=a&name=../p.png', PNG, headers)
  assert.equal(up.status, 200)
  assert.equal(up.body.type, 'image')
  assert.equal(up.body.name, 'p.png')
  const sent = await h.json('POST', '/chats/send', { headers, body: { botId: 'a', text: '看看', attachments: [up.body.uploadId] } })
  assert.equal(sent.status, 200)
  const content = h.prompts.at(-1).content
  assert.deepEqual(content[0], { type: 'text', text: '看看' })
  assert.equal(content[1].type, 'image')
  assert.equal(content[1].mediaType, 'image/png')
  assert.equal(content[1].data, Buffer.from(PNG).toString('base64'))
  // Used once: sending it again is refused.
  assert.equal((await h.json('POST', '/chats/send', { headers, body: { botId: 'a', text: 'x', attachments: [up.body.uploadId] } })).status, 400)
})

test('a file goes through fileUploads for that Session and the prompt cites its receipt', async (t) => {
  const h = start(); t.after(h.stop)
  const headers = await authed(h)
  const up = await h.raw('/uploads?kind=room&id=r&name=report.pdf', new Uint8Array([37, 80, 68, 70]), headers)
  assert.equal(up.body.type, 'file')
  assert.deepEqual(h.staged[0], { sessionId: 'r', name: 'report.pdf', size: 4 })
  // Not for another chat.
  assert.equal((await h.json('POST', '/chats/send', { headers, body: { botId: 'a', text: 'x', attachments: [up.body.uploadId] } })).status, 400)
  const sent = await h.json('POST', '/rooms/send', { headers, body: { roomId: 'r', text: '', attachments: [up.body.uploadId] } })
  assert.equal(sent.status, 200)
  assert.deepEqual(h.prompts.at(-1).content, [{ type: 'file', receiptId: 'rcpt-1' }])
  assert.equal(h.prompts.at(-1).sessionId, 'r')
  assert.equal((await h.raw('/uploads?kind=room&id=nope', PNG, headers)).status, 404)
  assert.equal((await h.raw('/uploads?kind=bot&id=a', new Uint8Array(), headers)).status, 400)
})

test('history shows attachment refs; only those can be opened', async (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'mb-img-'))
  writeFileSync(join(dir, 'p.png'), PNG)
  const h = start({ imageDir: dir }); t.after(async () => { await h.stop(); rmSync(dir, { recursive: true, force: true }) })
  const headers = await authed(h)
  assert.equal((await h.get('/attachments?id=sha256:aaaa', headers)).status, 404)
  const hist = await h.json('GET', '/chats/history?botId=a', { headers })
  const atts = hist.body.items[0].attachments
  assert.deepEqual(atts.map(a => a.type), ['image', 'file'])
  assert.equal(atts[0]._ref, undefined)
  const img = await h.get('/attachments?id=sha256:aaaa', headers)
  assert.equal(img.status, 200)
  assert.equal(img.headers.get('content-type'), 'image/png')
  assert.equal(new Uint8Array(await img.arrayBuffer()).length, PNG.length)
  const file = await h.get('/attachments?id=sha256:ffff', headers)
  assert.equal(new Uint8Array(await file.arrayBuffer()).length, 4)
})
