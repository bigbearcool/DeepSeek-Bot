import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import vm from 'node:vm'
import { pairEntrySpec } from '../src/pair-panel.js'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const CLIENT = join(ROOT, 'client.js')
const PKG = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8'))

test('package is 0.0.9 with web client + sidebar inject', () => {
  assert.equal(PKG.version, '0.0.9')
  assert.equal(PKG.exports['./client'], './client.js')
  assert.equal(PKG.dsh?.client?.platform, 'web')
  assert.ok(PKG.dsh.client.inject.includes('@deepseek-ai/dsh-client-ui-sidebar'))
})

test('client opens in-app panel — no window.open to /ui', () => {
  const source = readFileSync(CLIENT, 'utf8')
  const spec = pairEntrySpec()

  assert.match(source, /sidebar\.footer\.action/)
  assert.match(source, /手机配对/)
  assert.match(source, /id:\s*['"]mobile-pair['"]/)
  assert.match(source, /data-mb-pair-panel/)
  assert.match(source, /createPortal/)
  assert.match(source, /role:\s*['"]dialog['"]/)
  assert.doesNotMatch(source, /dshmb\.bigbear\.cool/)
  assert.match(source, /公网访问地址/)
  assert.match(source, /\/settings/)
  assert.match(source, /auth\/pair\/begin/)
  assert.match(source, /auth\/pair\/qr/)
  assert.match(source, /devices\/revoke/)

  // Must not jump out to the standalone page from the entry.
  assert.doesNotMatch(source, /window\.open\s*\(/)
  assert.doesNotMatch(source, /location\.(href|assign)\s*=\s*['"`][^'"`]*\/api\/mobile-bridge\/v1\/ui/)
  assert.equal(spec.openMode, 'in-app')
})

test('client registers sidebar entry that toggles in-app panel state', () => {
  const source = readFileSync(CLIENT, 'utf8')
  const registered = []
  const portals = []

  const fakeReact = {
    createElement(type, props, ...children) {
      return { type, props: props ?? {}, children }
    },
    Fragment: Symbol('Fragment'),
    useState(init) {
      return [typeof init === 'function' ? init() : init, () => {}]
    },
    useEffect() {},
    useCallback(fn) { return fn },
    useRef(v) { return { current: v } },
  }
  const fakeReactDOM = {
    createPortal(node, container) {
      portals.push({ node, container })
      return { type: 'portal', node, container }
    },
  }
  const moduleApi = {
    exports: null,
    load({ id, factory }) {
      assert.equal(id, 'dsh-mobile-bridge')
      moduleApi.exports = factory((name) => {
        if (name === 'react') return fakeReact
        if (name === 'react-dom') return fakeReactDOM
        throw new Error(`unexpected require: ${name}`)
      })
    },
  }
  const sandbox = {
    window: { __ModuleLoader__: moduleApi },
    document: {
      body: { nodeType: 1 },
      createElement: () => ({ dataset: {}, textContent: '', remove() {} }),
      head: { appendChild() {} },
    },
    navigator: { clipboard: { writeText: async () => {} } },
  }
  vm.runInNewContext(source, sandbox, { filename: 'client.js' })
  const mod = moduleApi.exports
  assert.equal(mod.inject[0], 'slots')

  const ctx = {
    effect(run) { run() },
    slots: {
      inject(name, factory) {
        assert.equal(name, 'sidebar.footer.action')
        factory()
      },
      register(opts, component) {
        registered.push({ opts, component })
        return () => {}
      },
    },
  }
  mod.apply(ctx)
  assert.equal(registered.length, 1)
  assert.equal(registered[0].opts.id, 'mobile-pair')

  const wide = registered[0].component({ wide: true })
  const flat = JSON.stringify(wide)
  assert.match(flat, /手机配对/)
  assert.match(flat, /mb-pair-btn/)
  assert.match(flat, /data-mb-pair-entry/)
  // Entry click handler is openPanel (setState), not a navigation helper.
  assert.doesNotMatch(source, /openPairPage|PAIR_UI/)
})
