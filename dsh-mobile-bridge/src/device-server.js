import { createServer } from 'node:http'
import { DEVICE_HOST, DEVICE_HOST_LOCAL, DEVICE_PORT, NAME, RATE_LIMIT, TRUSTED_PROXIES } from './constants.js'
import { createHandler } from './routes.js'

/** Fixed-window limiter keyed by string. */
export function createRateLimiter({ max = RATE_LIMIT.max, windowMs = RATE_LIMIT.windowMs, now = Date.now } = {}) {
  const buckets = new Map()
  return {
    hit(key) {
      const t = now()
      let b = buckets.get(key)
      if (!b || t >= b.resetAt) {
        b = { count: 0, resetAt: t + windowMs }
        buckets.set(key, b)
      }
      if (buckets.size > 10_000) {
        for (const [k, v] of buckets) if (t >= v.resetAt) buckets.delete(k)
      }
      b.count += 1
      if (b.count > max) return { ok: false, retryAfterMs: b.resetAt - t }
      return { ok: true }
    },
  }
}

const norm = ip => String(ip ?? '').trim().replace(/^::ffff:/, '')

/**
 * Client IP: socket peer address, unless the peer is a trusted local proxy (e.g. Caddy on
 * 127.0.0.1). Then X-Forwarded-For is walked right-to-left skipping trusted hops (first
 * untrusted hop wins), falling back to X-Real-IP, then the peer. Headers from untrusted
 * peers are ignored entirely (cannot be spoofed to dodge the limiter).
 */
export function clientIpOf(remoteAddress, headers, trusted = TRUSTED_PROXIES) {
  const trust = new Set(trusted.map(norm))
  const peer = norm(remoteAddress)
  if (!trust.has(peer)) return peer
  const xff = String(headers['x-forwarded-for'] ?? '').split(',').map(norm).filter(Boolean)
  for (let i = xff.length - 1; i >= 0; i--) if (!trust.has(xff[i])) return xff[i]
  const real = norm(headers['x-real-ip'])
  if (real) return real
  return xff[0] ?? peer
}

export function startDeviceServer(rt, config = {}) {
  const { ctx } = rt
  const port = config.devicePort ?? DEVICE_PORT
  // deviceLan: false → loopback only (e.g. behind a same-host reverse proxy). deviceHost overrides.
  const host = config.deviceHost ?? (config.deviceLan === false ? DEVICE_HOST_LOCAL : DEVICE_HOST)
  const trusted = config.trustedProxies ?? TRUSTED_PROXIES
  const limiter = createRateLimiter(config.rateLimit ?? {})
  const handle = createHandler(rt, { surface: 'device', limiter })
  const sockets = new Set()

  const server = createServer(async (req, res) => {
    const ac = new AbortController()
    res.on('close', () => ac.abort())
    try {
      const chunks = []
      for await (const c of req) chunks.push(c)
      const headers = new Headers()
      for (const [k, v] of Object.entries(req.headers)) {
        if (Array.isArray(v)) v.forEach(x => headers.append(k, x)); else if (v != null) headers.set(k, v)
      }
      const method = req.method ?? 'GET'
      const request = new Request(`http://${req.headers.host ?? `${host}:${port}`}${req.url}`, {
        method, headers, signal: ac.signal,
        body: method === 'GET' || method === 'HEAD' || !chunks.length ? undefined : Buffer.concat(chunks),
      })
      const clientIp = clientIpOf(req.socket.remoteAddress, req.headers, trusted)
      const response = await handle(request, { clientIp })
      res.writeHead(response.status, Object.fromEntries(response.headers))
      if (!response.body) return res.end()
      if (typeof res.flushHeaders === 'function') res.flushHeaders()
      const reader = response.body.getReader()
      ac.signal.addEventListener('abort', () => reader.cancel().catch(() => {}))
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        res.write(value)
      }
      res.end()
    } catch (err) {
      if (!res.headersSent) {
        res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: { message: 'internal error' } }))
      } else res.destroy()
      ctx.logger?.warn?.(`${NAME}: device listener request failed: ${err?.message ?? err}`)
    }
  })
  server.on('connection', s => { sockets.add(s); s.on('close', () => sockets.delete(s)) })

  const state = { server, host, port, listening: false, error: null }
  state.ready = new Promise((resolve) => {
    server.once('listening', () => {
      state.listening = true
      state.port = server.address().port
      ctx.logger?.info?.(`${NAME}: device listener on http://${host}:${state.port}`)
      resolve(state)
    })
    server.on('error', (err) => {
      state.error = err
      const msg = err.code === 'EADDRINUSE'
        ? `${NAME}: device listener port ${host}:${port} already in use — device API disabled (set devicePort to another port). dsh keeps running.`
        : `${NAME}: device listener failed on ${host}:${port}: ${err.message}`
      ;(ctx.logger?.error ?? ctx.logger?.warn)?.call(ctx.logger, msg)
      resolve(state)
    })
  })
  server.listen(port, host)

  state.close = () => new Promise((resolve) => {
    state.listening = false
    for (const s of sockets) s.destroy()
    if (!server.listening) return resolve()
    server.close(() => resolve())
  })
  return state
}
