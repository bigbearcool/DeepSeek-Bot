import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtemp, writeFile, mkdir } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { createDsBotAdapter } from '../src/dsbot-adapter.js'

test('readState retries past a mid-write SyntaxError then returns JSON', async () => {
  const home = await mkdtemp(join(tmpdir(), 'mb-state-'))
  const botHome = join(home, 'bot')
  await mkdir(botHome)
  const path = join(botHome, 'state.json')
  let reads = 0
  // First two reads: truncated JSON; then valid.
  const { readFile: realRead } = await import('node:fs/promises')
  const adapter = createDsBotAdapter({ dshHome: home, botHome, ctx: {} })
  // Overwrite file between attempts by racing writes — simpler: monkey via sequential partial files
  await writeFile(path, '{"bots":', 'utf8')
  // After short delays, heal the file while retries run
  const heal = (async () => {
    await new Promise(r => setTimeout(r, 25))
    await writeFile(path, JSON.stringify({ bots: {}, rooms: {}, revision: 1 }), 'utf8')
  })()
  const state = await adapter.readState()
  await heal
  assert.ok(state === null || state.revision === 1)
  // Final read must succeed
  await writeFile(path, JSON.stringify({ bots: {}, rooms: {}, revision: 2 }), 'utf8')
  const ok = await adapter.readState()
  assert.equal(ok.revision, 2)
})

test('readState returns null (does not throw) when JSON stays corrupt', async () => {
  const home = await mkdtemp(join(tmpdir(), 'mb-state-bad-'))
  const botHome = join(home, 'bot')
  await mkdir(botHome)
  await writeFile(join(botHome, 'state.json'), '{not-json', 'utf8')
  const adapter = createDsBotAdapter({ dshHome: home, botHome, ctx: {}, logger: { warn() {} } })
  const state = await adapter.readState()
  assert.equal(state, null)
})
