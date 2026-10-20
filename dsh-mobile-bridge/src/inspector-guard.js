/**
 * Contain the known dsh-experimental-inspector fatal (upstream #9271 / #10259):
 *   Error: inspector: source state exceeds the source-frame byte limit
 *
 * Cordis tree publish runs in queueMicrotask → setState throws → uncaughtException →
 * installFailLoud → process.exit(1). Group rounds (many in-process sessions) grow
 * the tree into the ~69-byte budget gap and kill the whole dsh web process.
 *
 * 0.0.18's safeOn/readState only covers bridge feed handlers reading state.json;
 * they never touch InspectorSourceBuffer.setState. This guard is separate.
 *
 * Strategy (belt + suspenders):
 *  1. Wrap queueMicrotask so the inspector publish throw is caught and dropped.
 *  2. prependListener uncaughtException: if it still surfaces, block FailLoud's exit(1).
 *  3. Best-effort monkey-patch InspectorSourceBuffer.setState when the module is resolvable.
 */
import { createRequire } from 'node:module'
import { NAME } from './constants.js'

const BUDGET_MARKERS = [
  'source state exceeds the source-frame byte limit',
  'Cordis root exceeds the source-frame byte limit',
]

export function isInspectorBudgetError(err) {
  const msg = String(err?.message ?? err ?? '')
  return BUDGET_MARKERS.some(m => msg.includes(m))
}

function note(diag, logger, err, via) {
  const error = String(err?.message ?? err)
  try { diag?.note?.('inspector.budget', { via, error }, { level: 'warn' }) } catch { /* */ }
  try { logger?.warn?.(`${NAME}: swallowed inspector budget error via ${via} (upstream #10259): ${error}`) } catch { /* */ }
}

/** Soften setState: roll back is already done before throw; we just skip the throw. */
function patchSetStateMethod(proto, logger) {
  if (!proto || typeof proto.setState !== 'function' || proto.setState.__dshMobileBridgeBudgetPatched) return false
  const original = proto.setState
  function setStatePatched(topic, payload, monotonicMs) {
    try {
      return original.call(this, topic, payload, monotonicMs)
    } catch (err) {
      if (!isInspectorBudgetError(err)) throw err
      try { logger?.warn?.(`${NAME}: inspector setState dropped oversize state for topic=${topic}`) } catch { /* */ }
      return undefined
    }
  }
  setStatePatched.__dshMobileBridgeBudgetPatched = true
  proto.setState = setStatePatched
  return true
}

export function tryPatchInspectorSetState(logger) {
  let patched = 0
  try {
    const req = createRequire(import.meta.url)
    let resolved
    try { resolved = req.resolve('@deepseek-ai/dsh-experimental-inspector') } catch { resolved = null }
    if (!resolved) {
      // Walk Node's module map for an already-loaded copy (plugin may load before us).
      const caches = []
      try { caches.push(req.cache) } catch { /* */ }
      for (const cache of caches) {
        if (!cache) continue
        for (const key of Object.keys(cache)) {
          if (!key.includes('dsh-experimental-inspector') || !key.endsWith('index.js')) continue
          resolved = key
          break
        }
        if (resolved) break
      }
    }
    if (!resolved) return 0
    const mod = req(resolved)
    // The published bundle does not export the class; walk the module's internal
    // bindings via the required exports object and any nested .default.
    const seen = new Set()
    const visit = (obj) => {
      if (!obj || typeof obj !== 'object' && typeof obj !== 'function') return
      if (seen.has(obj)) return
      seen.add(obj)
      if (typeof obj.setState === 'function' && obj.stateFits && patchSetStateMethod(obj, logger)) patched++
      if (obj.prototype && typeof obj.prototype.setState === 'function' && patchSetStateMethod(obj.prototype, logger)) patched++
      for (const v of Object.values(obj)) visit(v)
    }
    visit(mod)
  } catch { /* optional */ }
  return patched
}

/**
 * Wrap queueMicrotask so sync throws from inspector's publish path never become
 * uncaughtException. Non-budget errors are rethrown unchanged.
 */
export function installQueueMicrotaskGuard({ diag, logger } = {}) {
  if (globalThis.queueMicrotask?.__dshMobileBridgeBudgetPatched) {
    return () => {}
  }
  const original = globalThis.queueMicrotask.bind(globalThis)
  function guardedQueueMicrotask(cb) {
    return original(() => {
      try {
        return cb()
      } catch (err) {
        if (isInspectorBudgetError(err)) {
          note(diag, logger, err, 'queueMicrotask')
          return undefined
        }
        throw err
      }
    })
  }
  guardedQueueMicrotask.__dshMobileBridgeBudgetPatched = true
  globalThis.queueMicrotask = guardedQueueMicrotask
  return () => {
    if (globalThis.queueMicrotask === guardedQueueMicrotask) globalThis.queueMicrotask = original
  }
}

/**
 * If a budget error still reaches uncaughtException, stop FailLoud's process.exit(1)
 * for this tick only. Other fatals still exit.
 */
export function installUncaughtExceptionGuard({ diag, logger } = {}) {
  const onException = (err) => {
    if (!isInspectorBudgetError(err)) return
    note(diag, logger, err, 'uncaughtException')
    const realExit = process.exit.bind(process)
    let restored = false
    const restore = () => {
      if (restored) return
      restored = true
      try { process.exit = realExit } catch { /* */ }
    }
    process.exit = (code) => {
      if (Number(code) === 1) {
        restore()
        return undefined
      }
      restore()
      return realExit(code)
    }
    // FailLoud may call exit sync in the same listener turn, or after an async release.
    setTimeout(restore, 2_000)
  }
  process.prependListener('uncaughtException', onException)
  return () => {
    try { process.off('uncaughtException', onException) } catch { /* */ }
  }
}

/**
 * Install all guards. Safe to call once per process; returns a disposer for tests.
 * @param {{ diag?: object, logger?: object, patch?: boolean }} [opts]
 */
export function installInspectorBudgetGuard(opts = {}) {
  const { diag, logger, patch = true } = opts
  const disposers = []
  if (patch) {
    try { tryPatchInspectorSetState(logger) } catch { /* */ }
  }
  disposers.push(installQueueMicrotaskGuard({ diag, logger }))
  disposers.push(installUncaughtExceptionGuard({ diag, logger }))
  return () => {
    for (const d of disposers.reverse()) {
      try { d() } catch { /* */ }
    }
  }
}
