export function fakeContext({ services = {} } = {}) {
  const calls = []
  const routes = []
  const disposers = []
  /** @type {Map<string, Function[]>} */
  const listeners = new Map()
  return {
    calls,
    routes,
    logger: { info() {}, warn() {} },
    get: name => services[name],
    on(event, handler) {
      const list = listeners.get(event) ?? []
      list.push(handler)
      listeners.set(event, list)
      return () => {
        const next = (listeners.get(event) ?? []).filter(h => h !== handler)
        listeners.set(event, next)
      }
    },
    emit(event, ...args) {
      for (const handler of listeners.get(event) ?? []) {
        try { void handler(...args) } catch { /* test harness */ }
      }
    },
    effect(execute, label) {
      calls.push(`effect ${label}`)
      const dispose = execute()
      if (typeof dispose === 'function') disposers.push(dispose)
    },
    dispose() {
      return Promise.allSettled(disposers.splice(0).reverse().map(d => d()))
    },
    connection: {
      fetch: {
        register(route) {
          calls.push(`route ${route.methods.join(',')} ${route.path}`)
          routes.push(route)
          return () => {}
        },
      },
    },
  }
}

export async function dispatch(routes, method, pathWithQuery, { body, headers = {}, signal } = {}) {
  const path = pathWithQuery.split('?', 1)[0]
  const route = routes.find(r => r.path === path && r.methods.includes(method))
  if (!route) throw new Error(`no route ${method} ${pathWithQuery}`)
  const init = { method, headers: { ...headers } }
  if (body !== undefined) {
    init.body = JSON.stringify(body)
    init.headers['content-type'] = 'application/json'
  }
  const request = new Request(`http://localhost${pathWithQuery}`, init)
  if (signal) Object.defineProperty(request, 'signal', { value: signal })
  return route.fetch(request)
}
