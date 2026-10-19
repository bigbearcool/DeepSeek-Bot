import { API_PREFIX, NAME, VERSION } from './constants.js'
import { bearer, error, json, pathOf, readJson } from './http.js'

const encoder = new TextEncoder()

export function installRoutes(rt) {
  const { ctx, store, adapter } = rt

  const withAuth = async (request, next) => {
    const token = bearer(request)
    if (!token) return error(401, 'missing bearer token')
    try {
      const device = await store.requireAccess(token)
      return next(device)
    } catch (err) {
      return error(err.status ?? 401, err.message)
    }
  }

  const handle = async (request) => {
    const method = request.method.toUpperCase()
    const sub = pathOf(request, API_PREFIX)
    if (sub === null) return error(404, 'not found')

    if (method === 'GET' && sub === '/health') {
      return json({ ok: true, name: NAME, version: VERSION })
    }

    if (method === 'POST' && sub === '/auth/pair/begin') {
      const pairing = await store.beginPair()
      return json({ ok: true, ...pairing })
    }

    if (method === 'POST' && sub === '/auth/pair/complete') {
      const body = await readJson(request)
      try {
        const result = await store.completePair(body)
        return json({ ok: true, ...result })
      } catch (err) {
        return error(err.status ?? 400, err.message)
      }
    }

    if (method === 'POST' && sub === '/auth/token') {
      const body = await readJson(request)
      try {
        const result = await store.issueAccess(body)
        return json({ ok: true, ...result })
      } catch (err) {
        return error(err.status ?? 401, err.message)
      }
    }

    if (method === 'GET' && sub === '/team') {
      return withAuth(request, async () => json({ ok: true, ...(await adapter.team()) }))
    }

    if (method === 'GET' && sub === '/chats/history') {
      return withAuth(request, async () => {
        const url = new URL(request.url)
        const botId = url.searchParams.get('botId') ?? ''
        const cursor = url.searchParams.get('cursor')
        const limit = Number(url.searchParams.get('limit') ?? 50)
        return json({ ok: true, ...(await adapter.history({ botId, cursor, limit })) })
      })
    }

    if (method === 'POST' && sub === '/chats/send') {
      return withAuth(request, async () => {
        const body = await readJson(request)
        try {
          const result = await adapter.send({ botId: body.botId, text: body.text })
          if (result.stub) return json({ ok: false, ...result }, result.status ?? 501)
          return json({ ok: true, ...result })
        } catch (err) {
          return error(err.status ?? 500, err.message)
        }
      })
    }

    if (method === 'GET' && sub === '/stream') {
      return withAuth(request, async (device) => {
        let controller
        let tick
        const body = new ReadableStream({
          start(own) {
            controller = own
            const hello = encoder.encode(`event: hello\ndata: ${JSON.stringify({ deviceId: device.id, name: NAME, version: VERSION })}\n\n`)
            own.enqueue(hello)
            // Test tick so clients can verify SSE without a live Bot turn.
            tick = setInterval(() => {
              try {
                own.enqueue(encoder.encode(`event: test\ndata: ${JSON.stringify({ t: Date.now() })}\n\n`))
              } catch { /* closed */ }
            }, 15_000)
            tick.unref?.()
          },
          cancel() {
            clearInterval(tick)
          },
        })
        request.signal.addEventListener('abort', () => {
          clearInterval(tick)
          try { controller?.close() } catch { /* */ }
        }, { once: true })
        return new Response(body, {
          status: 200,
          headers: {
            'content-type': 'text/event-stream; charset=utf-8',
            'cache-control': 'no-store',
          },
        })
      })
    }

    if (method === 'GET' && sub === '/devices') {
      // Host-facing list for kick-device UI (pairing screen). No bearer yet — TODO: bind to local session.
      return json({ ok: true, devices: await store.listDevices() })
    }

    return error(404, `no route ${method} ${sub}`)
  }

  // One registration per concrete path: DSH fetch routes are exact matches (like ds-bot).
  const paths = [
    ['/health', ['GET']],
    ['/auth/pair/begin', ['POST']],
    ['/auth/pair/complete', ['POST']],
    ['/auth/token', ['POST']],
    ['/team', ['GET']],
    ['/chats/history', ['GET']],
    ['/chats/send', ['POST']],
    ['/stream', ['GET']],
    ['/devices', ['GET']],
  ]

  for (const [suffix, methods] of paths) {
    const path = `${API_PREFIX}${suffix}`
    ctx.effect(() => ctx.connection.fetch.register({
      path,
      methods,
      requestBody: 'buffered',
      fetch: handle,
    }), `${NAME}: ${methods.join(',')} ${path}`)
  }
}
