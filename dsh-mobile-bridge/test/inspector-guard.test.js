import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  isInspectorBudgetError,
  installQueueMicrotaskGuard,
  installUncaughtExceptionGuard,
  installInspectorBudgetGuard,
} from '../src/inspector-guard.js'

describe('inspector-guard', () => {
  it('detects budget error messages', () => {
    assert.equal(isInspectorBudgetError(new Error('inspector: source state exceeds the source-frame byte limit')), true)
    assert.equal(isInspectorBudgetError(new Error('inspector: Cordis root exceeds the source-frame byte limit')), true)
    assert.equal(isInspectorBudgetError(new Error('something else')), false)
  })

  it('queueMicrotask guard swallows budget throws', async () => {
    const notes = []
    const undo = installQueueMicrotaskGuard({
      diag: { note: (k, p) => notes.push({ k, p }) },
      logger: { warn() {} },
    })
    try {
      await new Promise((resolve, reject) => {
        queueMicrotask(() => { throw new Error('inspector: source state exceeds the source-frame byte limit') })
        queueMicrotask(() => resolve())
        setTimeout(() => reject(new Error('timeout')), 200)
      })
      assert.ok(notes.some(n => n.k === 'inspector.budget'), 'expected budget note')
    } finally {
      undo()
    }
  })

  it('uncaughtException guard blocks FailLoud-style exit(1) for budget errors', () => {
    const undo = installUncaughtExceptionGuard({ diag: { note() {} }, logger: { warn() {} } })
    const realExit = process.exit
    const codes = []
    try {
      process.exit = (code) => { codes.push(code) }
      const err = new Error('inspector: source state exceeds the source-frame byte limit')
      const ours = process.listeners('uncaughtException')[0]
      assert.equal(typeof ours, 'function')
      ours(err)
      process.exit(1)
      assert.deepEqual(codes, [], 'exit(1) after budget error must be swallowed')
      process.exit(2)
      assert.deepEqual(codes, [2], 'non-1 exit codes still pass through after restore')
    } finally {
      process.exit = realExit
      undo()
    }
  })

  it('installInspectorBudgetGuard returns a disposer', () => {
    const undo = installInspectorBudgetGuard({ diag: { note() {} }, logger: { warn() {} }, patch: false })
    assert.equal(typeof undo, 'function')
    undo()
  })
})
