import { NAME, VERSION, API_PREFIX, PAIR_TTL_MS } from './constants.js'
import { pairPayload, qrSvg } from './qr.js'

/**
 * Host-local pairing UI. Served at GET /api/mobile-bridge/v1/ui
 * Calls the same pair/begin + devices APIs the phone uses.
 */
export function pairPageHtml({ code, expiresAt, expiresIn, devices = [], origin = '' } = {}) {
  const payload = code ? pairPayload(code, { origin, apiPrefix: API_PREFIX }) : ''
  const qr = code ? qrSvg(payload, { size: 200 }) : ''
  const ttlSec = Math.max(1, Math.floor(PAIR_TTL_MS / 1000))
  const safeDevices = JSON.stringify(devices).replace(/</g, '\\u003c')
  const initial = JSON.stringify({
    code: code ?? null,
    expiresAt: expiresAt ?? null,
    expiresIn: expiresIn ?? null,
    payload,
  }).replace(/</g, '\\u003c')

  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>手机配对 · ${NAME}</title>
<style>
  :root { color-scheme: light dark; --bg:#0b1220; --card:#141c2e; --fg:#e8eefc; --muted:#93a0b8; --accent:#5b8cff; --ok:#3ecf8e; --danger:#ff6b7a; --border:#243049; }
  @media (prefers-color-scheme: light) {
    :root { --bg:#f4f6fb; --card:#ffffff; --fg:#152033; --muted:#5b6b86; --accent:#3b6ef5; --ok:#0a9f6a; --danger:#d64545; --border:#d9e0ef; }
  }
  * { box-sizing: border-box; }
  body { margin:0; font: 15px/1.45 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif; background:var(--bg); color:var(--fg); }
  main { max-width: 720px; margin: 0 auto; padding: 28px 18px 48px; }
  h1 { font-size: 1.35rem; margin: 0 0 4px; }
  .sub { color: var(--muted); margin-bottom: 22px; }
  .card { background: var(--card); border: 1px solid var(--border); border-radius: 16px; padding: 22px; margin-bottom: 16px; }
  .row { display:flex; flex-wrap:wrap; gap: 22px; align-items:center; }
  .code-wrap { flex: 1 1 220px; }
  .code { font: 700 2.4rem/1.1 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; letter-spacing: 0.28em; text-indent: 0.28em; user-select: all; }
  .meta { color: var(--muted); margin-top: 10px; font-size: 0.92rem; }
  .qr { width: 200px; height: 200px; background:#fff; border-radius: 12px; padding: 8px; display:flex; align-items:center; justify-content:center; }
  .qr svg { display:block; }
  .actions { display:flex; flex-wrap:wrap; gap: 10px; margin-top: 16px; }
  button, .btn { appearance:none; border:1px solid var(--border); background: transparent; color: var(--fg); border-radius: 10px; padding: 10px 14px; font: inherit; cursor: pointer; }
  button.primary { background: var(--accent); border-color: transparent; color: #fff; font-weight: 600; }
  button.danger { color: var(--danger); border-color: color-mix(in srgb, var(--danger) 40%, var(--border)); }
  button:disabled { opacity: 0.5; cursor: not-allowed; }
  .bar { height: 6px; background: var(--border); border-radius: 99px; overflow: hidden; margin-top: 14px; }
  .bar > i { display:block; height:100%; width:100%; background: var(--ok); transform-origin: left center; transition: transform 0.2s linear; }
  .bar.expiring > i { background: #f5a524; }
  .bar.expired > i { background: var(--danger); transform: scaleX(0); }
  table { width:100%; border-collapse: collapse; }
  th, td { text-align:left; padding: 10px 6px; border-bottom: 1px solid var(--border); font-size: 0.92rem; }
  th { color: var(--muted); font-weight: 600; }
  .empty { color: var(--muted); }
  .toast { position: fixed; left: 50%; bottom: 24px; transform: translateX(-50%); background: var(--card); border: 1px solid var(--border); padding: 10px 14px; border-radius: 10px; box-shadow: 0 8px 30px rgba(0,0,0,.25); opacity: 0; pointer-events: none; transition: opacity .2s; }
  .toast.show { opacity: 1; }
  footer { margin-top: 18px; color: var(--muted); font-size: 0.82rem; }
</style>
</head>
<body>
<main>
  <h1>手机配对</h1>
  <p class="sub">在本机生成一次性配对码，用手机扫码或手动输入完成 pair。码 ${ttlSec} 秒内有效。</p>

  <section class="card" aria-labelledby="pair-title">
    <h2 id="pair-title" style="margin:0 0 14px; font-size:1.05rem;">配对码</h2>
    <div class="row">
      <div class="code-wrap">
        <div class="code" id="code" aria-live="polite">${code ? escapeHtml(formatCode(code)) : '······'}</div>
        <div class="meta" id="meta">加载中…</div>
        <div class="bar" id="bar"><i id="barFill"></i></div>
        <div class="actions">
          <button type="button" class="primary" id="btnRegen">重新生成</button>
          <button type="button" id="btnCopy">复制配对码</button>
          <button type="button" id="btnCopyPayload">复制扫码内容</button>
        </div>
      </div>
      <div class="qr" id="qr" aria-hidden="${code ? 'false' : 'true'}">${qr || '<span class="empty">无二维码</span>'}</div>
    </div>
  </section>

  <section class="card" aria-labelledby="dev-title">
    <h2 id="dev-title" style="margin:0 0 10px; font-size:1.05rem;">已配对设备</h2>
    <div id="devices"></div>
  </section>

  <footer>${NAME} ${VERSION} · 前缀 ${API_PREFIX} · 本页仅供本机 / 局域网打开</footer>
</main>
<div class="toast" id="toast" role="status"></div>
<script>
const API = ${JSON.stringify(API_PREFIX)};
const TTL_MS = ${PAIR_TTL_MS};
let state = ${initial};
let devices = ${safeDevices};
let timer = null;

const el = (id) => document.getElementById(id);
function toast(msg) {
  const t = el('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => t.classList.remove('show'), 1600);
}
function formatCode(code) {
  const c = String(code || '');
  return c.length === 6 ? c.slice(0,3) + ' ' + c.slice(3) : c;
}
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
}
function renderDevices() {
  const root = el('devices');
  const list = (devices || []).filter(d => !d.revoked);
  if (!list.length) {
    root.innerHTML = '<p class="empty">还没有配对设备。</p>';
    return;
  }
  root.innerHTML = '<table><thead><tr><th>设备</th><th>ID</th><th></th></tr></thead><tbody>' +
    list.map(d => '<tr><td>' + escapeHtml(d.label || '（无标签）') + '</td><td><code>' + escapeHtml(d.id) +
    '</code></td><td><button type="button" class="danger" data-revoke="' + escapeHtml(d.id) + '">踢掉</button></td></tr>').join('') +
    '</tbody></table>';
  root.querySelectorAll('[data-revoke]').forEach(btn => {
    btn.addEventListener('click', () => revoke(btn.getAttribute('data-revoke')));
  });
}
function setPair(next, svg) {
  state = next;
  el('code').textContent = next.code ? formatCode(next.code) : '······';
  if (svg) el('qr').innerHTML = svg;
  tick();
}
function tick() {
  clearInterval(timer);
  const meta = el('meta');
  const bar = el('bar');
  const fill = el('barFill');
  function frame() {
    if (!state.expiresAt) {
      meta.textContent = '尚未生成配对码';
      bar.className = 'bar expired';
      fill.style.transform = 'scaleX(0)';
      return;
    }
    const left = Math.max(0, state.expiresAt - Date.now());
    const sec = Math.ceil(left / 1000);
    const ratio = Math.max(0, Math.min(1, left / TTL_MS));
    fill.style.transform = 'scaleX(' + ratio + ')';
    if (left <= 0) {
      meta.textContent = '已过期 — 请重新生成';
      bar.className = 'bar expired';
      return;
    }
    meta.textContent = '剩余 ' + sec + ' 秒 · 扫码内容 ' + (state.payload || '');
    bar.className = 'bar' + (left < 60000 ? ' expiring' : '');
  }
  frame();
  timer = setInterval(frame, 250);
}
async function regen() {
  el('btnRegen').disabled = true;
  try {
    const res = await fetch(API + '/auth/pair/begin', { method: 'POST' });
    const body = await res.json();
    if (!res.ok || !body.ok) throw new Error(body?.error?.message || 'begin failed');
    const qrBody = await fetch(API + '/auth/pair/qr').then(r => r.json());
    setPair({
      code: body.code,
      expiresAt: body.expiresAt,
      expiresIn: Math.floor((body.expiresAt - Date.now()) / 1000),
      payload: qrBody.payload || ('dshmb:' + body.code),
    }, qrBody.svg || '');
    toast('已生成新配对码');
    await loadDevices();
  } catch (err) {
    toast(String(err.message || err));
  } finally {
    el('btnRegen').disabled = false;
  }
}
async function loadDevices() {
  try {
    const body = await fetch(API + '/devices').then(r => r.json());
    if (body.ok) { devices = body.devices || []; renderDevices(); }
  } catch {}
}
async function revoke(id) {
  if (!confirm('踢掉设备 ' + id + '？')) return;
  const res = await fetch(API + '/devices/revoke', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceId: id }),
  });
  const body = await res.json();
  if (!res.ok || !body.ok) { toast(body?.error?.message || 'revoke failed'); return; }
  toast('已踢掉');
  await loadDevices();
}
el('btnRegen').addEventListener('click', regen);
el('btnCopy').addEventListener('click', async () => {
  if (!state.code) return;
  await navigator.clipboard.writeText(state.code);
  toast('已复制配对码');
});
el('btnCopyPayload').addEventListener('click', async () => {
  if (!state.payload) return;
  await navigator.clipboard.writeText(state.payload);
  toast('已复制扫码内容');
});
renderDevices();
tick();
if (!state.code) regen();
else loadDevices();
setInterval(loadDevices, 8000);
</script>
</body>
</html>`
}

function formatCode(code) {
  const c = String(code || '')
  return c.length === 6 ? `${c.slice(0, 3)} ${c.slice(3)}` : c
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, ch => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[ch])
}
