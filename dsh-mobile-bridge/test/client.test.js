import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import vm from 'node:vm'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const CLIENT = join(ROOT, 'client.js')
const PKG = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8'))

test('package declares web client export and sidebar inject', () => {
  assert.equal(PKG.version, '0.0.5')
  assert.equal(PKG.exports['./client'], './client.js')
  assert.equal(PKG.dsh?.client?.platform, 'web')
  assert.ok(PKG.dsh.client.inject.includes('@deepseek-ai/dsh-client-ui-sidebar'))
})

test('client registers sidebar.footer.action 手机配对 entry', () => {
  const source = readFileSync(CLIENT, 'utf8')
  assert.match(source, /sidebar\.footer\.action/)
  assert.match(source, /手机配对/)
  assert.match(source, /\/api\/mobile-bridge\/v1\/ui/)
  assert.match(source, /id:\s*['"]mobile-pair['"]/)

  const registered = []
  const fakeReact = {
    createElement(type, props, ...children) {
      return { type, props: props ?? {}, children }
    },
  }
  const moduleApi = {
    exports: null,
    load({ id, factory }) {
      assert.equal(id, 'dsh-mobile-bridge')
      moduleApi.exports = factory((name) => {
        if (name === 'react') return fakeReact
        throw new Error(`unexpected require: ${name}`)
      })
    },
  }
  const sandbox = {
    window: { __ModuleLoader__: moduleApi },
    document: {
      createElement: () => ({ dataset: {}, textContent: '', remove() {} }),
      head: { appendChild() {} },
    },
  }
  vm.runInNewContext(source, sandbox, { filename: 'client.js' })
  const mod = moduleApi.exports
  assert.equal(mod.inject.length, 1)
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
  assert.equal(registered[0].opts.name, 'sidebar.footer.action')
  assert.equal(registered[0].opts.id, 'mobile-pair')
  assert.equal(typeof registered[0].component, 'function')

  const wide = registered[0].component({ wide: true })
  // wide button tree includes label text somewhere
  const flat = JSON.stringify(wide)
  assert.match(flat, /手机配对/)
  assert.match(flat, /mb-pair-btn/)
})
