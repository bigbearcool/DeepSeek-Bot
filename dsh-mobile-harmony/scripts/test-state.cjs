// Exercise the page's actual controller methods without an ArkUI runtime.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const { test } = require('node:test');
const esbuild = require('../../ds-bot/node_modules/esbuild');
const root = path.resolve(__dirname, '../entry/src/main/ets');
const page = fs.readFileSync(path.join(root, 'pages/Index.ets'), 'utf8');
function method(start, end) {
  const a = page.indexOf(start);
  const b = page.indexOf(end, a);
  assert.ok(a >= 0 && b > a, 'page controller method must exist: ' + start);
  return page.slice(a, b);
}
const source = `
import { ChatMerge, WaitMachine, WaitState, WaitPhase, WaitKind, WaitEvent, LiveTimers, LaunchRouter, ChatListLogic } from './common/ChatLogic';
import { parseChatItem, parseProfile } from './common/Models';
import { J } from './common/Json';
import { ReadReceipts } from './common/ReadReceipts';
import { NotifyLogic, ChatPrefs } from './common/Extra';
import { roomTitle } from './common/Look';
const friendly = e => e.message;
const Notifier = { show() {}, clear() {} };
import { RoomStreams, RoomRound } from './common/Group';
export { WaitPhase };
export class Page {
  profile = null; profileGeneration = 0; profileEffortRequest = 0; profileSaving = false; profileEfforts = []; profileDefaultEffort = null;
  profileEffortsLoading = false; profileEffortsError = ''; profileBack = ''; options = null; formError = '';
  openKind = 'bot'; openId = 'a'; screen = 'chat'; messages = []; live = []; foreground = true;
  chats = []; notice = ''; readReceipts = new ReadReceipts();
  store = null; bots = []; rooms = []; approvals = []; seen = null; prefs = new ChatPrefs(); offline = false; listGeneration = 0;
  ctx() { return { abilityInfo: { bundleName: 'test.app' } }; }
  wait = new WaitState(); nextCursor = ''; historyLoaded = false; auth = null;
  chatGeneration = 0; streamGeneration = 0; reconnectTimer = -1;
  sseAttempt = 0; lastEventAt = 0;
  sse = { url: '', close() { this.url = ''; }, open(url, token, onEvent, onClose) { this.url = url; } };
  startStallCheck() {} scrollEnd() {} toast(text) {} fail(e) { throw e; }
  ${method('  private async loadOptions()', '  // ---------- new Bot ----------')}
  ${method('  private invalidateChat()', '  async openChat(')}
  ${method('  async refreshList()', '  // ---------- chat ----------')}
  ${method('  closeChat()', '  private async markRead()')}
  ${method('  private async markRead()', '  private async loadHistory()')}
  ${method('  private async loadHistory()', '  private async loadOlder()')}
  ${method('  private async openStream()', '  private onSse(')}
  ${method('  private onSse(', '  private currentRoom()')}
}
`;
const built = esbuild.buildSync({ stdin: { contents: source, resolveDir: root, loader: 'ts' },
  bundle: true, platform: 'node', format: 'cjs', loader: { '.ets': 'ts' },
  resolveExtensions: ['.ets', '.ts', '.js'], write: false });
const compiled = new Module(__filename + '.compiled', module);
compiled._compile(built.outputFiles[0].text, __filename + '.compiled');
const { Page, WaitPhase } = compiled.exports;
const send = (p, event, data) => p.onSse({ event, data: JSON.stringify(data) });
const count = p => p.messages.filter(m => m.kind === 'bot').length +
  Number(p.wait.phase === WaitPhase.Streaming && p.wait.text.length > 0);
const final = (text, seq = 9) => ({ kind: 'bot', text, seq, time: 1000 + seq });

for (const finalFirst of [true, false]) {
  test('screenshot reply has one bubble, finalFirst=' + finalFirst, () => {
    const p = new Page();
    const text = '收到，大熊。小秘在，消息收发正常。';
    send(p, 'stream', { phase: 'start', attemptId: 'a' });
    for (const delta of ['收到，大熊。小秘在，消息', '收', '发正常。']) {
      send(p, 'stream', { attemptId: 'a', text: delta });
    }
    assert.equal(p.wait.text, text);
    const durable = () => send(p, 'message', { item: final(text) });
    const end = () => send(p, 'stream', { phase: 'end', attemptId: 'a' });
    (finalFirst ? durable : end)();
    assert.equal(count(p), 1);
    (finalFirst ? end : durable)();
    assert.equal(count(p), 1);
    assert.equal(p.messages.length, 1);
    assert.equal(p.messages[0].text, text);
  });
}
test('late history cannot erase an SSE reply', async () => {
  const p = new Page();
  let resolve;
  p.auth = { with: block => block('mock', { history: () => new Promise(r => { resolve = r; }), markRead: async () => {} }) };
  const history = p.loadHistory();
  send(p, 'message', { item: final('new') });
  resolve({ items: [], nextCursor: null });
  await history;
  assert.equal(p.messages[0].text, 'new');
});
test('group history settles a stream, including name-prefix normalization', async () => {
  const p = new Page(); p.openKind = 'room';
  send(p, 'stream', { phase: 'start', attemptId: 'g', botId: 'writer', name: '甲' });
  send(p, 'stream', { attemptId: 'g', botId: 'writer', name: '甲', text: '甲：答案' });
  p.auth = { with: async () => [{ kind: 'member', text: '答案', botId: 'writer', seq: 8 }] };
  await p.loadHistory();
  assert.equal(p.messages.length, 1);
  assert.equal(p.live.length, 0);
  send(p, 'stream', { attemptId: 'g', text: 'late' });
  assert.equal(p.live.length, 0);
});
test('a delayed refresh cannot replace a newer chat subscription', async () => {
  const p = new Page();
  const resolve = [];
  p.auth = { refresh: () => new Promise(r => resolve.push(r)), api: () => ({ streamUrl: id => id }) };
  const a = p.openStream();
  p.chatGeneration++; p.openId = 'b';
  const b = p.openStream();
  resolve[1]('mock'); await b;
  resolve[0]('mock'); await a;
  assert.equal(p.sse.url, 'b');
});
test('same-chat overlapping opens also keep the latest subscription', async () => {
  const p = new Page();
  const resolve = [];
  let opened = 0;
  p.auth = { refresh: () => new Promise(r => resolve.push(r)), api: () => ({ streamUrl: () => 'a' }) };
  p.sse.open = () => { opened++; };
  const old = p.openStream(); const latest = p.openStream();
  resolve[1]('mock'); await latest;
  resolve[0]('mock'); await old;
  assert.equal(opened, 1);
});
test('history for a closed generation cannot change the page', async () => {
  const p = new Page();
  let resolve;
  p.auth = { with: block => block('mock', { history: () => new Promise(r => { resolve = r; }), markRead: async () => {} }) };
  const history = p.loadHistory();
  p.chatGeneration++;
  resolve({ items: [final('old generation')], nextCursor: 'old' });
  await history;
  assert.equal(p.messages.length, 0);
  assert.equal(p.nextCursor, '');
});

for (const kind of ['bot', 'room']) {
  test('visible ' + kind + ' reply acknowledges the displayed sequence', async () => {
    const p = new Page(); p.openKind = kind;
    const marks = [];
    p.auth = { with: block => block('mock', { markRead: async (t, k, id, seq) => { marks.push({k,id,seq}); } }) };
    send(p, 'message', { item: { kind: kind === 'room' ? 'member' : 'bot', text: '已读测试', seq: 9, botId: 'writer' } });
    await new Promise(r => setImmediate(r));
    assert.deepEqual(marks, [{k:kind,id:'a',seq:9}]);
  });
}
test('background messages are not acknowledged', async () => {
  const p = new Page(); p.foreground = false;
  const marks = [];
  p.auth = { with: block => block('mock', { markRead: async () => { marks.push(1); } }) };
  send(p, 'message', { item: final('background') });
  await new Promise(r => setImmediate(r));
  assert.equal(marks.length, 0);
});

const listItem = (seq = 9) => ({ kind:'bot', id:'a', name:'A', color:null, main:false, preview:'reply', lastAt:1000,
  seq, unread:1, running:false, members:[] });
const listResponse = (seq = 9) => ({ available:true, chats:[listItem(seq)], approvals:[] });
test('return to list waits for read acknowledgement and rejects its stale unread count', async () => {
  const p = new Page(); p.messages = [final('read')];
  const calls = []; let release;
  const api = {
    markRead: () => { calls.push('read'); return new Promise(r => { release = r; }); },
    chatList: async () => { calls.push('list'); return listResponse(); },
    team: async () => ({ bots:[], rooms:[] })
  };
  p.auth = { with: block => block('mock', api) };
  p.closeChat();
  await new Promise(r => setImmediate(r));
  assert.deepEqual(calls, ['read']);
  release(); await new Promise(r => setImmediate(r));
  assert.deepEqual(calls, ['read','list']);
  assert.equal(p.chats[0].unread, 0);
});
test('a list request begun before the read cannot resurrect an old badge', async () => {
  const p = new Page(); let releaseList;
  const api = {
    markRead: async () => {},
    chatList: () => new Promise(r => { releaseList = r; }),
    team: async () => ({ bots:[], rooms:[] })
  };
  p.auth = { with: block => block('mock', api) };
  const list = p.refreshList(); await new Promise(r => setImmediate(r));
  send(p, 'message', { item:final('read') });
  p.screen = 'list'; await new Promise(r => setImmediate(r));
  releaseList(listResponse()); await list;
  assert.equal(p.chats[0].unread, 0);
});
test('reply arriving after leaving remains unread', async () => {
  const p = new Page(); p.messages = [final('read')];
  const api = { markRead: async () => {}, chatList: async () => listResponse(10), team:async () => ({bots:[],rooms:[]}) };
  p.auth = { with: block => block('mock',api) };
  p.closeChat(); await new Promise(r => setImmediate(r));
  assert.equal(p.chats[0].unread, 1);
});
test('a background chat page does not hide its unread badge', async () => {
  const p = new Page(); p.foreground = false;
  const api = { chatList:async () => listResponse(), team:async () => ({bots:[],rooms:[]}) };
  p.auth = { with:block => block('mock',api) };
  await p.refreshList();
  assert.equal(p.chats[0].unread, 1);
});

const profile = (id = 'a', model = 'provider/one', effort = 'high') => ({
  id, name: id, role: '', instructions: '', shape: null, color: null, main: false, model, effort
});
const catalog = { efforts: [{ id: 'low', name: '低' }, { id: 'high', name: '高' }], defaultEffort: 'low' };
const tick = () => new Promise(resolve => setImmediate(resolve));

test('profile loads stored effort and model defaults, then saves and clears an override', async () => {
  const p = new Page(); let stored = profile(); const writes = [];
  const api = { botOptions: async () => ({ models: [] }), botInfo: async () => stored,
    effortCatalog: async () => catalog,
    updateBot: async (t, body) => { writes.push(body); stored = { ...stored, effort: body.effort || null }; return stored; } };
  p.auth = { with: fn => fn('test', api) };
  await p.openProfile('a', 'chat');
  assert.equal(p.profileEffortName(), '高');
  p.setProfileEffort('low'); await tick();
  assert.deepEqual(writes[0], { id: 'a', effort: 'low' });
  assert.equal(p.profile.effort, 'low');
  p.setProfileEffort(''); await tick();
  assert.equal(p.profileEffortName(), '模型默认（低）');
  await p.openProfile('a', 'chat'); assert.equal(p.profile.effort, null);
});

test('model switch resets override and loads only the new models capabilities', async () => {
  const p = new Page(); p.profile = profile(); p.screen = 'profile'; p.profileEfforts = catalog.efforts;
  const writes = [];
  p.auth = { with: fn => fn('test', {
    updateBot: async (t, body) => { writes.push(body); return profile('a', body.model, null); },
    effortCatalog: async () => ({ efforts: [], defaultEffort: null })
  }) };
  p.setModel('provider/plain'); await tick();
  assert.deepEqual(writes[0], { id: 'a', model: 'provider/plain', effort: '' });
  assert.deepEqual(p.profileEfforts, []);
  p.setProfileEffort('high'); await tick(); assert.equal(writes.length, 1);
});

test('late effort request for an old model cannot replace new model options', async () => {
  const p = new Page(); p.profile = profile(); p.screen = 'profile'; let release;
  p.auth = { with: fn => fn('test', { effortCatalog: (t, ref) => ref === 'provider/one'
    ? new Promise(resolve => { release = resolve; }) : Promise.resolve({ efforts: [], defaultEffort: null }) }) };
  const old = p.loadProfileEfforts();
  p.profile = profile('a', 'provider/plain', null); await p.loadProfileEfforts();
  release(catalog); await old;
  assert.deepEqual(p.profileEfforts, []); assert.equal(p.profileEffortsLoading, false);
});

test('load and save failures retain previous selection and allow retry', async () => {
  const p = new Page(); p.profile = profile(); p.screen = 'profile'; let fail = true;
  p.auth = { with: fn => fn('test', {
    effortCatalog: async () => { if (fail) throw new Error('offline'); return catalog; },
    updateBot: async () => { throw new Error('refused'); }
  }) };
  await p.loadProfileEfforts(); assert.equal(p.profileEffortsError, 'offline');
  fail = false; await p.loadProfileEfforts(); assert.equal(p.profileEffortsError, '');
  p.setProfileEffort('low'); await tick();
  assert.equal(p.profile.effort, 'high'); assert.equal(p.formError, 'refused'); assert.equal(p.profileSaving, false);
});

test('old profile saves cannot overwrite another assistants page', async () => {
  const p = new Page(); p.profile = profile(); p.screen = 'profile'; let release;
  p.auth = { with: fn => fn('test', {
    botOptions: async () => ({ models: [] }), botInfo: async (t, id) => profile(id), effortCatalog: async () => catalog,
    updateBot: async () => new Promise(resolve => { release = resolve; })
  }) };
  const saving = p.saveProfile({ effort: 'low' });
  await p.openProfile('b', 'roominfo'); release(profile('a', 'provider/one', 'low')); await saving;
  assert.equal(p.profile.id, 'b'); assert.equal(p.profile.effort, 'high');
});

test('late profile read cannot overwrite a newer opened assistant', async () => {
  const p = new Page(); let release;
  p.auth = { with: fn => fn('test', { botOptions: async () => ({ models: [] }),
    botInfo: (t, id) => id === 'a' ? new Promise(resolve => { release = resolve; }) : Promise.resolve(profile(id)),
    effortCatalog: async () => catalog }) };
  const first = p.openProfile('a', 'chat'); await p.openProfile('b', 'chat');
  release(profile()); await first; assert.equal(p.profile.id, 'b');
});

test('a stale same-model capability failure cannot erase a successful retry', async () => {
  const p = new Page(); p.profile = profile(); p.screen = 'profile'; let reject; let calls = 0;
  p.auth = { with: fn => fn('test', { effortCatalog: () => ++calls === 1
    ? new Promise((resolve, fail) => { reject = fail; }) : Promise.resolve(catalog) }) };
  const first = p.loadProfileEfforts(); await p.loadProfileEfforts(); reject(new Error('old error')); await first;
  assert.equal(p.profileEffortsError, ''); assert.deepEqual(p.profileEfforts, catalog.efforts);
});

test('repeated taps while saving do not send competing effort updates', async () => {
  const p = new Page(); p.profile = profile(); p.screen = 'profile'; p.profileEfforts = catalog.efforts;
  let release; let writes = 0;
  p.auth = { with: fn => fn('test', { updateBot: () => { writes++; return new Promise(resolve => { release = resolve; }); } }) };
  p.setProfileEffort('low'); p.setProfileEffort('');
  assert.equal(writes, 1); release(profile('a', 'provider/one', 'low')); await tick();
  assert.equal(p.profile.effort, 'low'); assert.equal(p.profileSaving, false);
});

test('dropdown indices follow saved model and effort, with default first and unavailable models excluded', () => {
  const p = new Page(); p.profile = profile();
  p.options = { models: [
    { ref: 'provider/locked', name: 'Locked', needsKey: true },
    { ref: 'provider/one', name: 'One', needsKey: false },
    { ref: 'provider/plain', name: 'Plain', needsKey: false }
  ] };
  p.profileEfforts = catalog.efforts;
  assert.equal(p.profileModelIndex(), 0);
  assert.equal(p.profileModels().length, 2);
  assert.deepEqual(p.profileEffortOptions().map(x => x.value), ['模型默认', '低', '高']);
  assert.equal(p.profileEffortIndex(), 2);
  p.profile.effort = null; assert.equal(p.profileEffortIndex(), 0);
  p.profile.effort = 'unknown'; assert.equal(p.profileEffortIndex(), -1);
});
