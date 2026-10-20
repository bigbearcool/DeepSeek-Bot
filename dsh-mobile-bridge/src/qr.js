/**
 * Compact QR Code (byte mode, Version 1–4, ECC M) → SVG.
 * Original code for short pairing payloads (code or short deep link).
 */

const TOTAL_CW = [0, 26, 44, 70, 100]
// ECC codewords per block, and block count, for level M
const EC_PER_BLOCK_M = [0, 10, 16, 26, 18]
const EC_BLOCKS_M = [0, 1, 1, 1, 2]

const GF_EXP = new Uint8Array(512)
const GF_LOG = new Uint8Array(256)
;(() => {
  let x = 1
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x
    GF_LOG[x] = i
    x = (x << 1) ^ (x & 0x80 ? 0x11d : 0)
  }
  for (let i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255]
})()

function gfMul(a, b) {
  if (!a || !b) return 0
  return GF_EXP[GF_LOG[a] + GF_LOG[b]]
}

function rsEncode(data, ecLen) {
  const gen = [1]
  for (let i = 0; i < ecLen; i++) {
    const next = new Array(gen.length + 1).fill(0)
    for (let j = 0; j < gen.length; j++) {
      next[j] ^= gen[j]
      next[j + 1] ^= gfMul(gen[j], GF_EXP[i])
    }
    gen.length = 0
    gen.push(...next)
  }
  const msg = data.concat(new Array(ecLen).fill(0))
  for (let i = 0; i < data.length; i++) {
    const f = msg[i]
    if (!f) continue
    for (let j = 0; j < gen.length; j++) msg[i + j] ^= gfMul(gen[j], f)
  }
  return msg.slice(data.length)
}

function dataCapacity(version) {
  return TOTAL_CW[version] - EC_PER_BLOCK_M[version] * EC_BLOCKS_M[version]
}

function chooseVersion(byteLen) {
  for (let v = 1; v <= 4; v++) {
    // mode 4 + length 8 + data + terminator 4
    const need = Math.ceil((4 + 8 + byteLen * 8 + 4) / 8)
    if (need <= dataCapacity(v)) return v
  }
  return -1
}

function encodeBits(text, version) {
  const raw = [...new TextEncoder().encode(text)]
  const bits = []
  const push = (val, n) => {
    for (let i = n - 1; i >= 0; i--) bits.push((val >>> i) & 1)
  }
  push(0b0100, 4)
  push(raw.length, 8)
  for (const b of raw) push(b, 8)
  const capacity = dataCapacity(version) * 8
  const term = Math.min(4, capacity - bits.length)
  push(0, term)
  while (bits.length % 8) bits.push(0)
  const bytes = []
  for (let i = 0; i < bits.length; i += 8) {
    let v = 0
    for (let j = 0; j < 8; j++) v = (v << 1) | bits[i + j]
    bytes.push(v)
  }
  const pads = [0xec, 0x11]
  let p = 0
  while (bytes.length < dataCapacity(version)) bytes.push(pads[p++ % 2])
  return bytes
}

function interleave(data, version) {
  const blocks = EC_BLOCKS_M[version]
  const ecLen = EC_PER_BLOCK_M[version]
  const totalData = dataCapacity(version)
  const shortBlocks = blocks - (totalData % blocks)
  const shortLen = Math.floor(totalData / blocks)
  const groups = []
  let off = 0
  for (let b = 0; b < blocks; b++) {
    const len = shortLen + (b < shortBlocks ? 0 : 1)
    const block = data.slice(off, off + len)
    off += len
    groups.push({ data: block, ec: rsEncode(block, ecLen) })
  }
  const out = []
  const maxD = Math.max(...groups.map(g => g.data.length))
  for (let i = 0; i < maxD; i++) for (const g of groups) if (i < g.data.length) out.push(g.data[i])
  for (let i = 0; i < ecLen; i++) for (const g of groups) out.push(g.ec[i])
  return out
}

function sizeOf(version) {
  return version * 4 + 17
}

function paintFinder(m, x, y) {
  for (let dy = -1; dy <= 7; dy++) {
    for (let dx = -1; dx <= 7; dx++) {
      const xx = x + dx
      const yy = y + dy
      if (yy < 0 || xx < 0 || yy >= m.length || xx >= m.length) continue
      let on
      if (dx === -1 || dx === 7 || dy === -1 || dy === 7) on = false
      else if (dx === 0 || dx === 6 || dy === 0 || dy === 6) on = true
      else if (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4) on = true
      else on = false
      m[yy][xx] = on
    }
  }
}

function paintTiming(m) {
  for (let i = 8; i < m.length - 8; i++) {
    if (m[6][i] === null) m[6][i] = i % 2 === 0
    if (m[i][6] === null) m[i][6] = i % 2 === 0
  }
}

function paintAlignment(m, version) {
  if (version < 2) return
  const c = { 2: 18, 3: 22, 4: 26 }[version]
  const y = c
  const x = c
  for (let dy = -2; dy <= 2; dy++) {
    for (let dx = -2; dx <= 2; dx++) {
      const d = Math.max(Math.abs(dx), Math.abs(dy))
      m[y + dy][x + dx] = d === 0 || d === 2
    }
  }
}

function reserveFormat(m) {
  const n = m.length
  for (let i = 0; i < 9; i++) {
    if (i !== 6) {
      if (m[8][i] === null) m[8][i] = false
      if (m[i][8] === null) m[i][8] = false
    }
  }
  for (let i = 0; i < 8; i++) {
    if (m[8][n - 1 - i] === null) m[8][n - 1 - i] = false
    if (m[n - 1 - i][8] === null) m[n - 1 - i][8] = false
  }
  m[n - 8][8] = true
}

function placeData(m, codewords) {
  const n = m.length
  const bits = []
  for (const cw of codewords) for (let i = 7; i >= 0; i--) bits.push((cw >>> i) & 1)
  let bi = 0
  let up = true
  for (let x = n - 1; x > 0; x -= 2) {
    if (x === 6) x--
    for (let i = 0; i < n; i++) {
      const y = up ? n - 1 - i : i
      for (let dx = 0; dx < 2; dx++) {
        const xx = x - dx
        if (m[y][xx] !== null) continue
        m[y][xx] = bits[bi++] === 1
      }
    }
    up = !up
  }
}

function maskAt(id, x, y) {
  switch (id) {
    case 0: return (x + y) % 2 === 0
    case 1: return y % 2 === 0
    case 2: return x % 3 === 0
    case 3: return (x + y) % 3 === 0
    case 4: return (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0
    case 5: return (x * y) % 2 + (x * y) % 3 === 0
    case 6: return ((x * y) % 2 + (x * y) % 3) % 2 === 0
    default: return ((x + y) % 2 + (x * y) % 3) % 2 === 0
  }
}

// Format info for ECC M (01) × mask 0..7, already XOR'd with mask 0x5412
const FORMAT_M = [0x5412, 0x5125, 0x5e7c, 0x5b4b, 0x45f9, 0x40ce, 0x4f97, 0x4aa0]

function drawFormat(m, mask) {
  const bits = FORMAT_M[mask]
  const n = m.length
  const a = [
    [8, 0], [8, 1], [8, 2], [8, 3], [8, 4], [8, 5], [8, 7], [8, 8],
    [7, 8], [5, 8], [4, 8], [3, 8], [2, 8], [1, 8], [0, 8],
  ]
  const b = [
    [n - 1, 8], [n - 2, 8], [n - 3, 8], [n - 4, 8], [n - 5, 8], [n - 6, 8], [n - 7, 8],
    [8, n - 8], [8, n - 7], [8, n - 6], [8, n - 5], [8, n - 4], [8, n - 3], [8, n - 2], [8, n - 1],
  ]
  for (let i = 0; i < 15; i++) {
    const on = ((bits >> (14 - i)) & 1) === 1
    m[a[i][0]][a[i][1]] = on
    m[b[i][0]][b[i][1]] = on
  }
}

function penalty(m) {
  let s = 0
  const n = m.length
  for (let y = 0; y < n; y++) {
    let run = 1
    for (let x = 1; x < n; x++) {
      if (m[y][x] === m[y][x - 1]) { run++; if (run === 5) s += 3; else if (run > 5) s++ }
      else run = 1
    }
  }
  for (let x = 0; x < n; x++) {
    let run = 1
    for (let y = 1; y < n; y++) {
      if (m[y][x] === m[y - 1][x]) { run++; if (run === 5) s += 3; else if (run > 5) s++ }
      else run = 1
    }
  }
  let dark = 0
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (m[y][x]) dark++
  s += Math.floor(Math.abs((dark * 100) / (n * n) - 50) / 5) * 10
  return s
}

export function buildMatrix(text) {
  let payload = String(text)
  let version = chooseVersion([...new TextEncoder().encode(payload)].length)
  if (version < 0) {
    // Fall back to bare code if deep-link is too long for V1–4
    payload = /^dshmb:[^@]+@/i.test(payload) ? payload.split('@')[0] : payload.includes('code=') ? decodeURIComponent(payload.split('code=')[1].split('&')[0]) : payload.slice(0, 17)
    version = chooseVersion([...new TextEncoder().encode(payload)].length)
    if (version < 0) throw new Error('payload too long for QR')
  }
  const data = encodeBits(payload, version)
  const codewords = interleave(data, version)
  const n = sizeOf(version)
  const base = Array.from({ length: n }, () => new Array(n).fill(null))
  paintFinder(base, 0, 0)
  paintFinder(base, n - 7, 0)
  paintFinder(base, 0, n - 7)
  paintTiming(base)
  paintAlignment(base, version)
  reserveFormat(base)
  const isFunc = base.map(row => row.map(v => v !== null))
  placeData(base, codewords)

  let best = null
  let bestScore = Infinity
  for (let mask = 0; mask < 8; mask++) {
    const cand = base.map(row => row.slice())
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        if (isFunc[y][x]) continue
        if (maskAt(mask, x, y)) cand[y][x] = !cand[y][x]
      }
    }
    drawFormat(cand, mask)
    const sc = penalty(cand)
    if (sc < bestScore) {
      bestScore = sc
      best = cand
    }
  }
  return { matrix: best, payload }
}

/** `dshmb:<code>@<appUrl>` (or legacy `dshmb:<code>` when no appUrl). Fits Version 4-M (≤62 bytes) for typical URLs. */
export function fitsQr(text) {
  return chooseVersion([...new TextEncoder().encode(String(text))].length) > 0
}

export function pairPayload(code, { appUrl = '' } = {}) {
  const c = String(code).trim()
  const u = String(appUrl || '').trim()
  const full = u ? `dshmb:${c}@${u}` : `dshmb:${c}`
  // URL too long for Version ≤4: fall back to legacy code-only payload (App keeps typed address).
  return fitsQr(full) ? full : `dshmb:${c}`
}

export function qrSvg(text, { size = 180, margin = 2, dark = '#111827', light = '#ffffff' } = {}) {
  const { matrix } = buildMatrix(String(text))
  const n = matrix.length
  const dim = n + margin * 2
  const cell = size / dim
  let rects = ''
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (!matrix[y][x]) continue
      const px = (x + margin) * cell
      const py = (y + margin) * cell
      rects += `<rect x="${px.toFixed(2)}" y="${py.toFixed(2)}" width="${(cell + 0.04).toFixed(2)}" height="${(cell + 0.04).toFixed(2)}" fill="${dark}"/>`
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" shape-rendering="crispEdges" role="img" aria-label="Pairing QR"><rect width="100%" height="100%" fill="${light}"/>${rects}</svg>`
}
