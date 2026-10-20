import { join } from 'node:path'
import { NAME, VERSION } from './src/constants.js'
import { createStore } from './src/store.js'
import { createDsBotAdapter } from './src/dsbot-adapter.js'
import { createDsBotApi } from './src/dsbot-api.js'
import { createLiveFeed } from './src/live-feed.js'
import { installRoutes } from './src/routes.js'
import { startDeviceServer } from './src/device-server.js'

export const name = NAME
export const inject = ['connection', 'sessionController']

function dshHomeOf(envHome) {
  if (envHome) return envHome
  return join(process.env.HOME ?? process.env.USERPROFILE ?? '.', '.dsh')
}

export function apply(ctx, config = {}) {
  return createRuntime(ctx, config)
}

export function createRuntime(ctx, config = {}) {
  const dshHome = config.dshHome ?? dshHomeOf(process.env.DSH_HOME)
  const home = config.home ?? join(dshHome, 'mobile-bridge')
  const botHome = config.botHome ?? join(dshHome, 'bot')
  const adapter = createDsBotAdapter({ dshHome, botHome, ctx })
  const feed = createLiveFeed({ ctx, adapter })
  const rt = {
    ctx,
    config,
    dshHome,
    home,
    botHome,
    store: createStore({ home }),
    adapter,
    feed,
    dsbot: createDsBotApi({ ctx }),
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
