import { homedir } from 'node:os'
import { join, resolve } from 'node:path'
import { NAME, VERSION } from './src/constants.js'
import { createStore } from './src/store.js'
import { createDsBotAdapter } from './src/dsbot-adapter.js'
import { createDsBotApi } from './src/dsbot-api.js'
import { createDiag } from './src/diag.js'
import { createLiveFeed } from './src/live-feed.js'
import { installRoutes } from './src/routes.js'
import { startDeviceServer } from './src/device-server.js'
import { createAttachments } from './src/attachments.js'

export const name = NAME
export const inject = ['connection', 'sessionController']

/** Same reading as ds-bot's folders.dshHomeOf, so both open the same state.json. */
export function dshHomeOf(envHome) {
  const value = typeof envHome === 'string' ? envHome.trim() : ''
  const raw = value || join(homedir(), '.dsh')
  const expanded = raw === '~' ? homedir() : raw.startsWith('~/') || raw.startsWith('~\\') ? join(homedir(), raw.slice(2)) : raw
  return resolve(expanded)
}

export function apply(ctx, config = {}) {
  return createRuntime(ctx, config)
}

export function createRuntime(ctx, config = {}) {
  const dshHome = config.dshHome ?? dshHomeOf(process.env.DSH_HOME)
  const home = config.home ?? join(dshHome, 'mobile-bridge')
  const botHome = config.botHome ?? join(dshHome, 'bot')
  const adapter = createDsBotAdapter({ dshHome, botHome, ctx })
  const diag = createDiag({ logger: ctx.logger, name: NAME, ...(config.quietDiag ? { print: null } : {}) })
  const attachments = createAttachments({ ctx })
  const feed = createLiveFeed({ ctx, adapter, diag, files: attachments })
  const rt = {
    ctx,
    config,
    dshHome,
    home,
    botHome,
    store: createStore({ home }),
    adapter,
    diag,
    feed,
    dsbot: createDsBotApi({ ctx }),
    attachments,
  }
  installRoutes(rt)
  // devicePort: false disables the standalone device listener.
  if (config.devicePort !== false) {
    ctx.effect(() => {
      rt.deviceServer = startDeviceServer(rt, config)
      return () => rt.deviceServer.close()
    }, `${NAME}: device listener`)
  }
  ctx.logger?.info?.(`${NAME} ${VERSION} ready at /api/mobile-bridge/v1 (data: ${home})`)
  return rt
}
