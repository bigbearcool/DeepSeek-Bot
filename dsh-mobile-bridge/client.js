// Browser half: sidebar 「手机配对」 opens an in-app pairing panel (no new tab).
window.__ModuleLoader__.load({
  id: 'dsh-mobile-bridge',
  factory(require) {
    const React = require('react')
    const ReactDOM = require('react-dom')
    const h = React.createElement
    const { useState, useEffect, useCallback, useRef } = React
    const createPortal = ReactDOM.createPortal

    const API = '/api/mobile-bridge/v1'
    const LABEL = '手机配对'
    const DEFAULT_APP_URL = ''
    const TTL_MS = 5 * 60 * 1000

    function formatCode(code) {
      const c = String(code || '')
      return c.length === 6 ? `${c.slice(0, 3)} ${c.slice(3)}` : c
    }

    function PhoneIcon() {
      return h('svg', {
        viewBox: '0 0 24 24', width: 15, height: 15, fill: 'none',
        stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round', strokeLinejoin: 'round',
        'aria-hidden': true,
      },
        h('rect', { x: 7, y: 2.5, width: 10, height: 19, rx: 2.2 }),
        h('path', { d: 'M11 18.5h2' }),
      )
    }

    function CloseIcon() {
      return h('svg', {
        viewBox: '0 0 24 24', width: 16, height: 16, fill: 'none',
        stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', 'aria-hidden': true,
      }, h('path', { d: 'M6 6l12 12M18 6L6 18' }))
    }

    const STYLE = `
.mb-pair-entry{flex:1;min-width:0;display:flex}
.mb-pair-btn{flex:1;height:36px;border-radius:999px;border:1px solid var(--bt-line,rgba(20,20,20,.08));background:var(--bt-main,var(--dsw-alias-button-elevated-fill,#fff));color:var(--bt-ink,var(--dsw-alias-label-primary));font:inherit;font-size:14px;line-height:20px;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:8px;transition:background-color .12s ease}
.mb-pair-btn:hover{background:var(--bt-hover,var(--dsw-alias-interactive-bg-hover,rgba(119,119,119,.09)))}
.mb-pair-btn svg{flex:none}
.mb-pair-btn-rail{flex:none;width:36px;height:36px;padding:0;border-radius:var(--dsw-radius-md,10px);border:0;background:none;color:var(--dsw-alias-label-primary,inherit);cursor:pointer;display:inline-flex;align-items:center;justify-content:center}
.mb-pair-btn-rail:hover{background:var(--dsw-alias-interactive-bg-hover,rgba(127,127,127,.14))}
.mb-pair-layer{position:fixed;inset:var(--dsh-frame-chrome-top,0px) 0 0;z-index:70;display:flex;align-items:center;justify-content:center;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",system-ui,sans-serif}
.mb-pair-scrim{position:absolute;inset:0;background:rgba(0,0,0,.32)}
body[data-ds-dark-theme] .mb-pair-scrim{background:rgba(0,0,0,.5)}
.mb-pair-dialog{position:relative;width:min(560px,calc(100vw - 40px));max-height:min(720px,calc(100vh - 2*max(24px,var(--dsh-frame-overlay-top,24px))));background:var(--bt-main,var(--dsw-alias-bg-primary,#fff));border-radius:16px;border:.5px solid var(--bt-line-2,var(--dsw-alias-stroke-primary,rgba(0,0,0,.08)));box-shadow:0 24px 80px rgba(0,0,0,.24);display:flex;flex-direction:column;overflow:hidden;color:var(--bt-ink,var(--dsw-alias-label-primary,#152033))}
.mb-pair-head{display:flex;align-items:center;gap:10px;padding:16px 18px 8px;flex:none}
.mb-pair-head h2{margin:0;font-size:17px;font-weight:600;flex:1}
.mb-pair-x{width:32px;height:32px;border-radius:50%;border:0;background:var(--bt-hover,rgba(119,119,119,.09));color:var(--bt-ink-2,inherit);display:flex;align-items:center;justify-content:center;cursor:pointer;padding:0}
.mb-pair-x:hover{background:var(--bt-active,rgba(119,119,119,.16))}
.mb-pair-body{flex:1;overflow-y:auto;padding:8px 18px 20px;display:flex;flex-direction:column;gap:14px}
.mb-pair-lead{margin:0;font-size:13px;line-height:1.5;color:var(--bt-ink-2,var(--dsw-alias-label-secondary,#5b6b86))}
.mb-pair-steps{margin:0;padding:12px 14px;border-radius:12px;background:var(--bt-hover,rgba(119,119,119,.06));font-size:13px;line-height:1.55;color:var(--bt-ink,inherit)}
.mb-pair-steps strong{display:block;margin-bottom:6px;font-size:13px}
.mb-pair-steps ol{margin:0;padding-left:1.2em}
.mb-pair-steps li{margin:4px 0}
.mb-pair-steps code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;padding:1px 5px;border-radius:5px;background:var(--bt-main,rgba(0,0,0,.04))}
.mb-pair-card{border:1px solid var(--bt-line,rgba(20,20,20,.08));border-radius:14px;padding:14px}
.mb-pair-card h3{margin:0 0 12px;font-size:14px;font-weight:600}
.mb-pair-row{display:flex;gap:16px;align-items:flex-start;flex-wrap:wrap}
.mb-pair-code-wrap{flex:1;min-width:180px}
.mb-pair-code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:36px;font-weight:700;letter-spacing:.08em;line-height:1.2;font-variant-numeric:tabular-nums}
.mb-pair-meta{margin-top:8px;font-size:13px;color:var(--bt-ink-2,var(--dsw-alias-label-secondary,#5b6b86))}
.mb-pair-bar{height:6px;background:var(--bt-line,rgba(20,20,20,.1));border-radius:99px;overflow:hidden;margin-top:10px}
.mb-pair-bar>i{display:block;height:100%;width:100%;background:#3ecf8e;transform-origin:left center;transition:transform .2s linear}
.mb-pair-bar.expiring>i{background:#f5a524}
.mb-pair-bar.expired>i{background:#ff6b7a;transform:scaleX(0)}
.mb-pair-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}
.mb-pair-actions button,.mb-pair-copy-url{height:32px;padding:0 12px;border-radius:8px;border:1px solid var(--bt-line,rgba(20,20,20,.12));background:var(--bt-main,#fff);color:inherit;font:inherit;font-size:13px;cursor:pointer}
.mb-pair-actions button.primary{background:var(--dsw-alias-button-primary-fill,#3b6ef5);border-color:transparent;color:#fff;font-weight:600}
.mb-pair-actions button:disabled{opacity:.5;cursor:not-allowed}
.mb-pair-actions button.danger{color:#d64545;border-color:color-mix(in srgb,#d64545 40%,transparent)}
.mb-pair-qr{flex:none;width:160px;height:160px;border-radius:12px;background:#fff;display:flex;align-items:center;justify-content:center;overflow:hidden;border:1px solid var(--bt-line,rgba(20,20,20,.08))}
.mb-pair-qr svg{width:148px;height:148px}
.mb-pair-qr .empty{font-size:12px;color:var(--bt-ink-2,#5b6b86);padding:8px;text-align:center}
.mb-pair-dev-table{width:100%;border-collapse:collapse;font-size:13px}
.mb-pair-dev-table th,.mb-pair-dev-table td{text-align:left;padding:8px 4px;border-bottom:1px solid var(--bt-line,rgba(20,20,20,.08))}
.mb-pair-dev-table th{color:var(--bt-ink-2,#5b6b86);font-weight:600}
.mb-pair-empty{margin:0;font-size:13px;color:var(--bt-ink-2,#5b6b86)}
.mb-pair-toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);z-index:80;background:var(--bt-main,#fff);border:1px solid var(--bt-line,rgba(20,20,20,.12));padding:10px 14px;border-radius:10px;box-shadow:0 8px 30px rgba(0,0,0,.2);font-size:13px;pointer-events:none}
.mb-pair-err{margin:0;font-size:13px;color:#d64545}
@media (max-width:520px){.mb-pair-dialog{width:100vw;max-height:100%;border-radius:0;border:0}.mb-pair-qr{margin:0 auto}}
`

    function usePairCountdown(expiresAt) {
      const [tick, setTick] = useState(0)
      useEffect(() => {
        if (!expiresAt) return undefined
        const id = setInterval(() => setTick(t => t + 1), 250)
        return () => clearInterval(id)
      }, [expiresAt])
      void tick
      const now = Date.now()
      if (!expiresAt) {
        return { text: '请点击「重新生成」获取配对码', expired: true, expiring: false, ratio: 0 }
      }
      const leftMs = Math.max(0, expiresAt - now)
      if (leftMs <= 0) {
        return { text: '已过期 — 请重新生成', expired: true, expiring: false, ratio: 0 }
      }
      const sec = Math.ceil(leftMs / 1000)
      const m = Math.floor(sec / 60)
      const s = sec % 60
      return {
        text: `剩余 ${m > 0 ? `${m} 分 ` : ''}${s} 秒`,
        expired: false,
        expiring: leftMs < 60_000,
        ratio: Math.max(0, Math.min(1, leftMs / TTL_MS)),
      }
    }

    function PairPanel({ onClose }) {
      const [pair, setPair] = useState({ code: null, expiresAt: null, payload: '', svg: '' })
      const [devices, setDevices] = useState([])
      const [busy, setBusy] = useState(false)
      const [error, setError] = useState('')
      const [toast, setToast] = useState('')
      const toastTimer = useRef(null)
      const [urlDraft, setUrlDraft] = useState(null)
      const dialogRef = useRef(null)

      const showToast = useCallback((msg) => {
        setToast(msg)
        clearTimeout(toastTimer.current)
        toastTimer.current = setTimeout(() => setToast(''), 1600)
      }, [])

      const loadDevices = useCallback(async () => {
        try {
          const res = await fetch(`${API}/devices`)
          const body = await res.json()
          if (body.ok) setDevices((body.devices || []).filter(d => !d.revoked))
        } catch { /* ignore */ }
      }, [])

      const regen = useCallback(async () => {
        setBusy(true)
        setError('')
        try {
          const res = await fetch(`${API}/auth/pair/begin`, { method: 'POST' })
          const body = await res.json()
          if (!res.ok || !body.ok) throw new Error(body?.error?.message || '生成失败')
          const qrBody = await fetch(`${API}/auth/pair/qr`).then(r => r.json())
          setPair({
            code: body.code,
            expiresAt: body.expiresAt,
            payload: qrBody.payload || `dshmb:${body.code}`,
            appUrl: qrBody.appUrl || DEFAULT_APP_URL,
            publicUrl: qrBody.publicUrl || '',
            lanUrl: qrBody.lanUrl || '',
            svg: qrBody.svg || '',
          })
          showToast('已生成新配对码')
          await loadDevices()
        } catch (err) {
          setError(String(err.message || err))
        } finally {
          setBusy(false)
        }
      }, [loadDevices, showToast])

      useEffect(() => {
        let cancelled = false
        ;(async () => {
          try {
            const active = await fetch(`${API}/auth/pair/active`).then(r => r.json())
            if (cancelled) return
            if (active.ok && active.code) {
              const qrBody = await fetch(`${API}/auth/pair/qr`).then(r => r.json())
              if (cancelled) return
              setPair({
                code: active.code,
                expiresAt: active.expiresAt,
                payload: qrBody.payload || `dshmb:${active.code}`,
                appUrl: qrBody.appUrl || DEFAULT_APP_URL,
                publicUrl: qrBody.publicUrl || '',
                lanUrl: qrBody.lanUrl || '',
            publicUrl: qrBody.publicUrl || '',
            lanUrl: qrBody.lanUrl || '',
                svg: qrBody.svg || '',
              })
              await loadDevices()
            } else {
              await regen()
            }
          } catch {
            if (!cancelled) await regen()
          }
        })()
        const poll = setInterval(() => { void loadDevices() }, 8000)
        return () => {
          cancelled = true
          clearInterval(poll)
          clearTimeout(toastTimer.current)
        }
      }, [loadDevices, regen])

      useEffect(() => {
        const onKey = (e) => { if (e.key === 'Escape') onClose() }
        window.addEventListener('keydown', onKey)
        return () => window.removeEventListener('keydown', onKey)
      }, [onClose])

      useEffect(() => {
        dialogRef.current?.querySelector('button, [href], input')?.focus?.()
      }, [])

      const cd = usePairCountdown(pair.expiresAt)

      const copyCode = async () => {
        if (!pair.code) return
        try {
          await navigator.clipboard.writeText(pair.code)
          showToast('已复制配对码')
        } catch {
          showToast('复制失败')
        }
      }

      const copyUrl = async () => {
        try {
          await navigator.clipboard.writeText(pair.appUrl || DEFAULT_APP_URL)
          showToast('已复制服务器地址')
        } catch {
          showToast('复制失败')
        }
      }

      const savePublicUrl = async () => {
        try {
          const res = await fetch(`${API}/settings`, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ publicUrl: urlDraft ?? pair.publicUrl ?? '' }),
          })
          const body = await res.json()
          if (!res.ok || !body.ok) throw new Error(body?.error?.message || '保存失败')
          const qrBody = await fetch(`${API}/auth/pair/qr`).then(r => r.json()).catch(() => ({}))
          setPair(p => ({
            ...p,
            appUrl: body.appUrl || '', publicUrl: body.publicUrl || '', lanUrl: body.lanUrl || '',
            payload: qrBody.payload || p.payload, svg: qrBody.svg || p.svg,
          }))
          setUrlDraft(null)
          showToast('已保存')
        } catch (err) {
          showToast(String(err.message || err))
        }
      }

      const revoke = async (id) => {
        if (!window.confirm(`取消配对「${id}」？该手机会马上无法访问。`)) return
        try {
          const res = await fetch(`${API}/devices/revoke`, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ deviceId: id }),
          })
          const body = await res.json()
          if (!res.ok || !body.ok) throw new Error(body?.error?.message || '操作失败')
          showToast('已取消配对')
          await loadDevices()
        } catch (err) {
          showToast(String(err.message || err))
        }
      }

      const barClass = `mb-pair-bar${cd.expired ? ' expired' : cd.expiring ? ' expiring' : ''}`

      return h('div', { className: 'mb-pair-layer', 'data-mb-pair-panel': '1' },
        h('div', { className: 'mb-pair-scrim', onMouseDown: onClose }),
        h('div', {
          ref: dialogRef,
          className: 'mb-pair-dialog',
          role: 'dialog',
          'aria-modal': 'true',
          'aria-label': LABEL,
        },
          h('div', { className: 'mb-pair-head' },
            h('h2', null, LABEL),
            h('button', {
              type: 'button',
              className: 'mb-pair-x',
              'aria-label': '关闭',
              onClick: onClose,
            }, h(CloseIcon)),
          ),
          h('div', { className: 'mb-pair-body' },
            h('p', { className: 'mb-pair-lead' },
              '在电脑上生成一次性配对码，用手机输入或扫码完成连接。配对码约 5 分钟内有效。'),
            h('section', { className: 'mb-pair-steps', 'aria-label': '使用说明' },
              h('strong', null, '怎么配对'),
              h('ol', null,
                h('li', null, '打开手机上的 DS Bot App。'),
                h('li', null,
                  '在 App 里填服务器地址：',
                  h('code', null, pair.appUrl || '（未找到可用地址，请在下方填写公网访问地址）'),
                  ' ',
                  h('button', {
                    type: 'button',
                    className: 'mb-pair-copy-url',
                    onClick: copyUrl,
                  }, '复制地址'),
                ),
                h('li', null, '输入下面的 6 位配对码，或扫描二维码。'),
              ),
            ),
            h('section', { className: 'mb-pair-steps', 'aria-label': '公网访问地址' },
              h('strong', null, '公网访问地址'),
              h('p', null, pair.lanUrl
                ? `同一 Wi-Fi 下手机可直接用：${pair.lanUrl}。想在外面也能用，请填写公网访问地址（例如 https://你的域名:端口），不填就用局域网地址。`
                : '想在外面也能用，请填写公网访问地址（例如 https://你的域名:端口）。'),
              h('div', { className: 'mb-pair-row' },
                h('input', {
                  type: 'url',
                  'data-mb-public-url': '1',
                  placeholder: 'https://',
                  value: urlDraft ?? pair.publicUrl ?? '',
                  onChange: e => setUrlDraft(e.target.value),
                  style: { flex: 1, minWidth: 0 },
                }),
                h('button', { type: 'button', onClick: () => { void savePublicUrl() } }, '保存'),
              ),
            ),
            h('section', { className: 'mb-pair-card' },
              h('h3', null, '配对码'),
              h('div', { className: 'mb-pair-row' },
                h('div', { className: 'mb-pair-code-wrap' },
                  h('div', {
                    className: 'mb-pair-code',
                    'aria-live': 'polite',
                    'data-mb-pair-code': pair.code || '',
                  }, pair.code ? formatCode(pair.code) : '······'),
                  h('div', { className: 'mb-pair-meta' }, cd.text),
                  h('div', { className: barClass },
                    h('i', { style: { transform: `scaleX(${cd.ratio})` } })),
                  h('div', { className: 'mb-pair-actions' },
                    h('button', {
                      type: 'button',
                      className: 'primary',
                      disabled: busy,
                      onClick: () => { void regen() },
                    }, '重新生成'),
                    h('button', {
                      type: 'button',
                      disabled: !pair.code,
                      onClick: () => { void copyCode() },
                    }, '复制配对码'),
                  ),
                  error ? h('p', { className: 'mb-pair-err', role: 'alert' }, error) : null,
                ),
                pair.svg
                  ? h('div', {
                      className: 'mb-pair-qr',
                      'aria-hidden': 'false',
                      'data-mb-pair-qr': pair.payload || '',
                      dangerouslySetInnerHTML: { __html: pair.svg },
                    })
                  : h('div', {
                      className: 'mb-pair-qr',
                      'aria-hidden': 'true',
                      'data-mb-pair-qr': '',
                    }, h('span', { className: 'empty' }, '暂无二维码')),
              ),
            ),
            h('section', { className: 'mb-pair-card' },
              h('h3', null, '已配对设备'),
              devices.length === 0
                ? h('p', { className: 'mb-pair-empty' }, '还没有已配对的手机。配对成功后会出现在这里。')
                : h('table', { className: 'mb-pair-dev-table' },
                    h('thead', null,
                      h('tr', null,
                        h('th', null, '设备'),
                        h('th', null, '编号'),
                        h('th', null, ''),
                      ),
                    ),
                    h('tbody', null,
                      devices.map(d => h('tr', { key: d.id },
                        h('td', null, d.label || '未命名手机'),
                        h('td', null, h('code', null, d.id)),
                        h('td', null,
                          h('button', {
                            type: 'button',
                            className: 'danger',
                            onClick: () => { void revoke(d.id) },
                          }, '取消配对'),
                        ),
                      )),
                    ),
                  ),
            ),
          ),
        ),
        toast ? h('div', { className: 'mb-pair-toast', role: 'status' }, toast) : null,
      )
    }

    function MobilePairEntry({ wide }) {
      const [open, setOpen] = useState(false)
      const openPanel = useCallback(() => setOpen(true), [])
      const closePanel = useCallback(() => setOpen(false), [])

      const btn = wide
        ? h('span', { className: 'mb-pair-entry' },
            h('button', {
              type: 'button',
              className: 'mb-pair-btn',
              title: LABEL,
              'aria-label': LABEL,
              'data-mb-pair-entry': '1',
              onClick: openPanel,
            }, h(PhoneIcon), LABEL))
        : h('button', {
            type: 'button',
            className: 'mb-pair-btn-rail',
            title: LABEL,
            'aria-label': LABEL,
            'data-mb-pair-entry': '1',
            onClick: openPanel,
          }, h(PhoneIcon))

      return h(React.Fragment, null,
        btn,
        open && createPortal(
          h(PairPanel, { onClose: closePanel }),
          document.body,
        ),
      )
    }

    return {
      inject: ['slots'],
      apply(ctx) {
        ctx.effect(() => {
          const tag = document.createElement('style')
          tag.dataset.plugin = 'dsh-mobile-bridge'
          tag.textContent = STYLE
          document.head.appendChild(tag)
          return () => tag.remove()
        }, 'dsh-mobile-bridge: pair panel styles')

        ctx.slots.inject('sidebar.footer.action', () => ctx.slots.register({
          name: 'sidebar.footer.action',
          id: 'mobile-pair',
          order: -50,
        }, MobilePairEntry))
      },
    }
  },
})
