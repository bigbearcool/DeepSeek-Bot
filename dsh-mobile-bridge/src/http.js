export function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', ...headers },
  })
}

export function html(body, status = 200, headers = {}) {
  return new Response(body, {
    status,
    headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', ...headers },
  })
}

export function error(status, message, extra = {}) {
  return json({ ok: false, error: { message, ...extra } }, status)
}

export function bearer(request) {
  const header = request.headers.get('authorization') ?? request.headers.get('Authorization') ?? ''
  const match = /^Bearer\s+(\S+)/i.exec(header)
  return match?.[1] ?? null
}

export async function readJson(request) {
  try {
    return await request.json()
  } catch {
    return {}
  }
}

export function pathOf(request, prefix) {
  const url = new URL(request.url)
  let path = url.pathname
  if (path.endsWith('/') && path.length > 1) path = path.slice(0, -1)
  if (!path.startsWith(prefix)) return null
  return path.slice(prefix.length) || '/'
}
