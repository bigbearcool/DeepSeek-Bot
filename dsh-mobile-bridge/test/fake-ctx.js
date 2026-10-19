export function fakeContext({ services = {} } = {}) {
  const calls = []
  const routes = []
  const disposers = []
  return {
    calls,
    routes,
    logger: { info() {}, warn() {} },
    get: name => services[name],
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
