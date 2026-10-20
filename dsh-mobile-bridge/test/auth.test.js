import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { createStore } from '../src/store.js'

test('pairing issues refresh then access tokens', async (t) => {
  const home = mkdtempSync(join(tmpdir(), 'mb-auth-'))
  t.after(() => rmSync(home, { recursive: true, force: true }))
  let now = 1_000_000
  const store = createStore({ home, now: () => now })

  const { code } = await store.beginPair()
  assert.match(code, /^[0-9a-f]{6}$/)

  const paired = await store.completePair({ code, deviceId: 'phone-1', label: 'Pixel' })
  assert.equal(paired.deviceId, 'phone-1')
  assert.ok(paired.refreshToken)

  const access = await store.issueAccess({ deviceId: 'phone-1', refreshToken: paired.refreshToken })
  assert.ok(access.accessToken)
  assert.equal(access.expiresIn, 1800)

  const device = await store.requireAccess(access.accessToken)
  assert.equal(device.id, 'phone-1')

  now += 31 * 60 * 1000
  await assert.rejects(() => store.requireAccess(access.accessToken), /expired|invalid/)
})

test('bad pairing code is rejected', async (t) => {
  const home = mkdtempSync(join(tmpdir(), 'mb-auth-bad-'))
  t.after(() => rmSync(home, { recursive: true, force: true }))
  const store = createStore({ home })
  await assert.rejects(
    () => store.completePair({ code: 'nope', deviceId: 'x' }),
    /invalid or expired/,
  )
})
