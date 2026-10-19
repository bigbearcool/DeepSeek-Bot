import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'

/**
 * Read-only view of the ds-bot team from its state file, plus optional send via
 * the shared DSH sessionController when the host injects it.
 */
export function createDsBotAdapter({ dshHome, botHome, ctx, logger = console } = {}) {
  const statePath = join(botHome ?? join(dshHome, 'bot'), 'state.json')

  async function readState() {
    try {
      return JSON.parse(await readFile(statePath, 'utf8'))
    } catch (error) {
      if (error?.code === 'ENOENT') return null
      throw error
    }
  }

  async function team() {
    const state = await readState()
    if (state === null) {
      return {
        available: false,
        reason: 'ds-bot state.json not found; is ds-bot installed and opened once?',
        bots: [],
        mainBotIds: [],
        revision: null,
      }
    }
    const bots = Object.values(state.bots ?? {}).map(bot => ({
      id: bot.id,
      name: bot.name,
      role: bot.role ?? null,
      sessionId: bot.sessionId ?? bot.id,
      model: bot.model ?? null,
      agentPreset: bot.agentPreset ?? null,
      main: Array.isArray(state.mainBotIds) && state.mainBotIds.includes(bot.id),
    }))
    return {
      available: true,
      bots,
      mainBotIds: state.mainBotIds ?? [],
      revision: state.revision ?? null,
      statePath,
    }
  }

  async function history({ botId, cursor, limit = 50 } = {}) {
    // TODO: wire to ds-bot chatPage / session event log. Skeleton returns an empty page.
    void cursor
    const snapshot = await team()
    const bot = snapshot.bots.find(item => item.id === botId)
    if (!bot) {
      return { available: snapshot.available, botId, items: [], nextCursor: null, stub: true, reason: snapshot.available ? 'unknown bot' : snapshot.reason }
    }
    return {
      available: true,
      botId,
      items: [],
      nextCursor: null,
      stub: true,
      reason: 'history not wired yet; use desktop/web or extend adapter to call ds-bot chatPage',
      limit,
    }
  }

  async function send({ botId, text } = {}) {
    const trimmed = String(text ?? '').trim()
    if (!trimmed) {
      const err = new Error('text required')
      err.status = 400
      throw err
    }
    const snapshot = await team()
    const bot = snapshot.bots.find(item => item.id === botId)
    if (!bot) {
      const err = new Error(snapshot.available ? 'unknown bot' : snapshot.reason)
      err.status = snapshot.available ? 404 : 503
      throw err
    }
    const sessionController = ctx?.get?.('sessionController') ?? ctx?.sessionController
    if (sessionController?.prompt) {
      await sessionController.prompt({
        requestId: randomUUID(),
        sessionId: bot.sessionId,
        mode: 'queue',
        content: [{ type: 'text', text: trimmed }],
      }, AbortSignal.timeout(30_000))
      return { ok: true, botId, sessionId: bot.sessionId, via: 'sessionController.prompt' }
    }
    // Clear stub so callers know how to finish the wiring.
    return {
      ok: false,
      stub: true,
      status: 501,
      botId,
      sessionId: bot.sessionId,
      reason: 'sessionController.prompt not available in this host; TODO: call ds-bot send endpoint or inject sessionController',
    }
  }

  return { statePath, team, history, send }
}
