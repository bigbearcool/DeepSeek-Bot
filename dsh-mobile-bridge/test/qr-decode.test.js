import assert from 'node:assert/strict'
import { test } from 'node:test'
import jsQR from 'jsqr'
import { buildMatrix, pairPayload, qrSvg } from '../src/qr.js'

/** Rasterise module matrix (scale px/module, 4-module quiet zone) and decode with jsQR. */
function decode(matrix, scale = 6, quiet = 4) {
  const n = matrix.length
  const dim = (n + quiet * 2) * scale
  const data = new Uint8ClampedArray(dim * dim * 4).fill(255)
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    if (!matrix[y][x]) continue
    for (let dy = 0; dy < scale; dy++) for (let dx = 0; dx < scale; dx++) {
      const i = (((y + quiet) * scale + dy) * dim + (x + quiet) * scale + dx) * 4
      data[i] = data[i + 1] = data[i + 2] = 0
    }
  }
  return jsQR(data, dim, dim)?.data ?? null
}

/** Parse our own SVG rects back into a matrix, so the shipped SVG itself is verified. */
function matrixFromSvg(svg, n, size, margin = 2) {
  const cell = size / (n + margin * 2)
  const m = Array.from({ length: n }, () => Array(n).fill(false))
  for (const r of svg.matchAll(/<rect x="([\d.]+)" y="([\d.]+)" width="[\d.]+" height="[\d.]+" fill="#111827"/g)) {
    const x = Math.round(Number(r[1]) / cell) - margin
    const y = Math.round(Number(r[2]) / cell) - margin
    if (x >= 0 && y >= 0 && x < n && y < n) m[y][x] = true
  }
  return m
}

const cases = [
  pairPayload('ab12cd', { appUrl: 'https://example.com:8443' }),
  pairPayload('0f9e8d', { appUrl: 'http://192.168.100.200:3081' }),
  pairPayload('ffffff', { appUrl: 'https://my-very-long-subdomain.example.com:8443' }),
  pairPayload('123abc'),
]

for (const text of cases) {
  test(`QR decodes back to ${text}`, () => {
    const { matrix, payload } = buildMatrix(text)
    assert.equal(payload, text)
    assert.equal(decode(matrix), text)
    const svg = qrSvg(text, { size: 200 })
    assert.equal(decode(matrixFromSvg(svg, matrix.length, 200)), text)
  })
}

test('too-long URL falls back to legacy code-only payload', () => {
  const p = pairPayload('ab12cd', { appUrl: 'https://' + 'x'.repeat(80) + '.example.com' })
  assert.equal(p, 'dshmb:ab12cd')
  assert.equal(decode(buildMatrix(p).matrix), p)
})
