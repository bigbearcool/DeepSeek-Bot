// Browser half: sidebar 「手机配对」 entry. Served as classic script via dsh.client.
window.__ModuleLoader__.load({
  id: 'dsh-mobile-bridge',
  factory(require) {
    const React = require('react')
    const h = React.createElement
    const PAIR_UI = '/api/mobile-bridge/v1/ui'
    const LABEL = '手机配对'

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

    const STYLE = `
.mb-pair-entry{flex:1;min-width:0;display:flex}
.mb-pair-btn{flex:1;height:36px;border-radius:999px;border:1px solid var(--bt-line,rgba(20,20,20,.08));background:var(--bt-main,var(--dsw-alias-button-elevated-fill,#fff));color:var(--bt-ink,var(--dsw-alias-label-primary));font:inherit;font-size:14px;line-height:20px;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:8px;transition:background-color .12s ease}
.mb-pair-btn:hover{background:var(--bt-hover,var(--dsw-alias-interactive-bg-hover,rgba(119,119,119,.09)))}
.mb-pair-btn svg{flex:none}
.mb-pair-btn-rail{flex:none;width:36px;height:36px;padding:0;border-radius:var(--dsw-radius-md,10px);border:0;background:none;color:var(--dsw-alias-label-primary,inherit);cursor:pointer;display:inline-flex;align-items:center;justify-content:center}
.mb-pair-btn-rail:hover{background:var(--dsw-alias-interactive-bg-hover,rgba(127,127,127,.14))}
`

    function openPairPage() {
      // Same origin keeps dsh login; new tab so the chat stays put.
      window.open(PAIR_UI, '_blank', 'noopener,noreferrer')
    }

    function MobilePairEntry({ wide }) {
      if (wide) {
        return h('span', { className: 'mb-pair-entry' },
          h('button', {
            type: 'button',
            className: 'mb-pair-btn',
            title: LABEL,
            'aria-label': LABEL,
            onClick: openPairPage,
          }, h(PhoneIcon), LABEL))
      }
      return h('button', {
        type: 'button',
        className: 'mb-pair-btn-rail',
        title: LABEL,
        'aria-label': LABEL,
        onClick: openPairPage,
      }, h(PhoneIcon))
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
        }, 'dsh-mobile-bridge: pair entry styles')

        ctx.slots.inject('sidebar.footer.action', () => ctx.slots.register({
          name: 'sidebar.footer.action',
          id: 'mobile-pair',
          // After Connect plugins (-100); before default 0 rows.
          order: -50,
        }, MobilePairEntry))
      },
    }
  },
})
