import assert from 'node:assert/strict'
import { test } from 'node:test'
import { pairPayload, qrSvg } from '../src/qr.js'

test('pairPayload is short scannable token', () => {
  assert.equal(pairPayload('ab12cd'), 'dshmb:ab12cd')
})

test('qrSvg returns SVG with modules', () => {
  const svg = qrSvg(pairPayload('ab12cd'), { size: 120 })
  assert.match(svg, /^<svg /)
  assert.match(svg, /role="img"/)
  assert.match(svg, /<rect /)
  assert.ok(svg.length > 500)
})
