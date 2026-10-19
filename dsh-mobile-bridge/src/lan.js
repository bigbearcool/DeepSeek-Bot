import { networkInterfaces } from 'node:os'

const VIRTUAL = /^(lo|docker|br-|veth|virbr|vmnet|vboxnet|utun|tun|tap|zt|tailscale|wg|llw|awdl|bridge|cni|flannel|kube|podman|lxc|lxd|anpi|gif|stf|ap\d)/i

function rank(ip) {
  if (/^192\.168\./.test(ip)) return 0
  if (/^10\./.test(ip)) return 1
  if (/^172\.(1[6-9]|2\d|3[01])\./.test(ip)) return 2
  return 9 // non-private (public / CGNAT / link-local excluded below)
}

/** IPv4 LAN addresses, private ranges first; skips loopback, link-local, docker/virtual NICs. */
export function lanAddresses(ifaces = networkInterfaces()) {
  const out = []
  for (const [name, list] of Object.entries(ifaces ?? {})) {
    if (VIRTUAL.test(name)) continue
    for (const a of list ?? []) {
      const fam = a.family === 4 ? 'IPv4' : a.family
      if (fam !== 'IPv4' || a.internal) continue
      if (/^(127\.|169\.254\.)/.test(a.address)) continue
      out.push({ name, address: a.address, rank: rank(a.address) })
    }
  }
  out.sort((x, y) => x.rank - y.rank)
  return out.map(x => x.address)
}

/** `http://<lan-ip>:<port>` or '' when listening on loopback only / nothing found. */
export function lanUrl({ host, port, ifaces } = {}) {
  if (!port) return ''
  if (host && host !== '0.0.0.0' && host !== '::') {
    if (/^(127\.|::1$|localhost$)/.test(host)) return ''
    return `http://${host}:${port}`
  }
  const [ip] = lanAddresses(ifaces)
  return ip ? `http://${ip}:${port}` : ''
}
