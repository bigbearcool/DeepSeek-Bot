import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  formatCode,
  formatCountdown,
  assemblePairState,
  pairHowToSteps,
  pairEntrySpec,
  APP_SERVER_URL,
  PAIR_TTL_MS,
} from '../src/pair-panel.js'

test('formatCode spaces a 6-digit code', () => {
  assert.equal(formatCode('123456'), '123 456')
  assert.equal(formatCode(''), '')
  assert.equal(formatCode('12'), '12')
})

test('formatCountdown reports remaining time and expiry', () => {
  const now = 1_000_000
  const ok = formatCountdown(now + 90_000, now)
  assert.equal(ok.expired, false)
  assert.match(ok.text, /剩余/)
  assert.ok(ok.ratio > 0 && ok.ratio <= 1)

  const expiring = formatCountdown(now + 30_000, now)
  assert.equal(expiring.expiring, true)

  const gone = formatCountdown(now - 1, now)
  assert.equal(gone.expired, true)
  assert.match(gone.text, /已过期/)

  const none = formatCountdown(null, now)
  assert.equal(none.expired, true)
})

test('assemblePairState merges begin + qr', () => {
  const expiresAt = Date.now() + PAIR_TTL_MS
  const state = assemblePairState(
    { code: '654321', expiresAt },
    { payload: 'dshmb:654321', svg: '<svg></svg>' },
  )
  assert.equal(state.code, '654321')
  assert.equal(state.payload, 'dshmb:654321')
  assert.equal(state.svg, '<svg></svg>')
  assert.equal(state.expiresAt, expiresAt)

  const fallback = assemblePairState({ code: '111222', expiresAt: expiresAt }, null)
  assert.equal(fallback.payload, 'dshmb:111222')
  assert.equal(fallback.svg, '')
})

test('pairHowToSteps includes App server URL', () => {
  const steps = pairHowToSteps()
  assert.equal(steps.length, 3)
  assert.equal(APP_SERVER_URL, '')
  assert.ok(pairHowToSteps('http://192.168.1.5:3081').some(s => s.includes('http://192.168.1.5:3081')))
})

test('pairEntrySpec requires in-app open, not window.open to /ui', () => {
  const spec = pairEntrySpec()
  assert.equal(spec.openMode, 'in-app')
  assert.equal(spec.label, '手机配对')
  assert.equal(spec.slot, 'sidebar.footer.action')
  assert.ok(spec.forbidden.includes('window.open'))
  assert.ok(spec.apis.includes('POST /auth/pair/begin'))
  assert.ok(spec.apis.includes('GET /auth/pair/qr'))
})
