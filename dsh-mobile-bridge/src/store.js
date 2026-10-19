import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { randomBytes, createHash } from 'node:crypto'
import { ACCESS_TTL_MS, PAIR_TTL_MS, REFRESH_TTL_MS, STORE_FILE } from './constants.js'

const empty = () => ({ version: 1, devices: {}, pairings: {} })

function token() {
  return randomBytes(24).toString('base64url')
}

function hash(value) {
  return createHash('sha256').update(String(value)).digest('hex')
}

export function createStore({ home, now = Date.now } = {}) {
  const path = join(home, STORE_FILE)
  let data = empty()
  let loaded = false

  async function load() {
    if (loaded) return data
    try {
      data = { ...empty(), ...JSON.parse(await readFile(path, 'utf8')) }
      data.devices ??= {}
      data.pairings ??= {}
    } catch (error) {
      if (error?.code !== 'ENOENT') throw error
      data = empty()
    }
    loaded = true
    return data
  }

  async function save() {
    await mkdir(home, { recursive: true })
    await writeFile(path, JSON.stringify(data, null, 2))
  }

  async function beginPair() {
    await load()
    // One active code at a time (UI regenerate invalidates the previous unused code).
    data.pairings = {}
    const code = randomBytes(3).toString('hex') // 6 hex chars
    const expiresAt = now() + PAIR_TTL_MS
    data.pairings[code] = { expiresAt }
    await save()
    return { code, expiresAt, expiresIn: Math.floor(PAIR_TTL_MS / 1000) }
  }

  async function activePair() {
    await load()
    const entries = Object.entries(data.pairings)
      .filter(([, p]) => (p.expiresAt ?? 0) >= now())
      .sort((a, b) => b[1].expiresAt - a[1].expiresAt)
    if (!entries.length) return null
    const [code, p] = entries[0]
    return {
      code,
      expiresAt: p.expiresAt,
      expiresIn: Math.max(0, Math.floor((p.expiresAt - now()) / 1000)),
    }
  }

  async function completePair({ code, deviceId, label = '' }) {
    await load()
    const id = String(deviceId ?? '').trim().slice(0, 80)
    const pair = data.pairings[String(code ?? '').trim()]
    if (!id) throw Object.assign(new Error('deviceId required'), { status: 400 })
    if (!pair || pair.expiresAt < now()) throw Object.assign(new Error('pairing code invalid or expired'), { status: 400 })
    delete data.pairings[String(code).trim()]
    const refreshToken = token()
    data.devices[id] = {
      id,
      label: String(label ?? '').slice(0, 80),
      refreshHash: hash(refreshToken),
      createdAt: now(),
      revoked: false,
    }
    await save()
    return { deviceId: id, refreshToken, refreshExpiresAt: now() + REFRESH_TTL_MS }
  }

  async function issueAccess({ deviceId, refreshToken }) {
    await load()
    const device = data.devices[String(deviceId ?? '')]
    if (!device || device.revoked) throw Object.assign(new Error('unknown device'), { status: 401 })
    if (device.refreshHash !== hash(refreshToken)) throw Object.assign(new Error('invalid refresh token'), { status: 401 })
    const accessToken = token()
    const expiresAt = now() + ACCESS_TTL_MS
    device.accessHash = hash(accessToken)
    device.accessExpiresAt = expiresAt
    await save()
    return { accessToken, expiresAt, expiresIn: Math.floor(ACCESS_TTL_MS / 1000) }
  }

  async function requireAccess(accessToken) {
    await load()
    const hashed = hash(accessToken)
    const device = Object.values(data.devices).find(d => !d.revoked && d.accessHash === hashed)
    if (!device || (device.accessExpiresAt ?? 0) < now()) {
      throw Object.assign(new Error('access token invalid or expired'), { status: 401 })
    }
    return device
  }

  async function revoke(deviceId) {
    await load()
    const device = data.devices[String(deviceId ?? '')]
    if (device) {
      device.revoked = true
      device.accessHash = null
      await save()
    }
  }

  async function listDevices() {
    await load()
    return Object.values(data.devices).map(({ id, label, createdAt, revoked }) => ({ id, label, createdAt, revoked: !!revoked }))
  }

  return { path, beginPair, activePair, completePair, issueAccess, requireAccess, revoke, listDevices, load }
}
