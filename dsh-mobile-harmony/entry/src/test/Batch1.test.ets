import { describe, it, expect } from '@ohos/hypium';
import { PairCode, normalizeBase } from '../main/ets/common/PairCode';
import { ChatMerge, ChatListLogic, LaunchRouter, StartRoute, WaitEvent, WaitKind, WaitMachine, WaitPhase, WaitState } from '../main/ets/common/ChatLogic';
import { ChatEntry, ChatItem, newItem, parseChatList, parseHistory } from '../main/ets/common/Models';
import { AuthRevokedError, BridgeError, friendly } from '../main/ets/common/Errors';
import { SseParser } from '../main/ets/common/Sse';
import { Markdown } from '../main/ets/common/Markdown';
import { J } from '../main/ets/common/Json';

function local(text: string, cid: string | null, time: number): ChatItem {
  const i = newItem('user', text, time);
  i.local = true;
  i.clientMessageId = cid;
  return i;
}

function server(kind: string, text: string, seq: number, cid: string | null, time: number): ChatItem {
  const i = newItem(kind, text, time);
  i.seq = seq;
  i.clientMessageId = cid;
  return i;
}

function run(events: WaitEvent[]): WaitState {
  let s = new WaitState();
  for (const e of events) {
    s = WaitMachine.reduce(s, e);
  }
  return s;
}

function entry(id: string, lastAt: number | null): ChatEntry {
  const e: ChatEntry = {
    kind: 'bot', id: id, name: id, color: null, main: false, preview: '', lastAt: lastAt, unread: 3, running: false, members: []
  };
  return e;
}

export default function batch1Test() {
  describe('pairCode', () => {
    it('rawHex', 0, () => {
      expect(PairCode.normalize('A1B2C3')).assertEqual('a1b2c3');
    });
    it('scheme', 0, () => {
      expect(PairCode.normalize('dshmb:dead01')).assertEqual('dead01');
      expect(PairCode.normalize('dshmb:DEAD01')).assertEqual('dead01');
    });
    it('rejectsBad', 0, () => {
      expect(PairCode.normalize('12345')).assertNull();
      expect(PairCode.normalize('dshmb:xyz')).assertNull();
      expect(PairCode.normalize('')).assertNull();
    });
    it('withAddress', 0, () => {
      const p = PairCode.parse('dshmb:abc123@https://example.com:8443');
      expect(p?.code).assertEqual('abc123');
      expect(p?.baseUrl).assertEqual('https://example.com:8443');
    });
    it('oldFormats', 0, () => {
      expect(PairCode.parse('dshmb:abc123')?.baseUrl).assertNull();
      expect(PairCode.parse('abc123')?.baseUrl).assertNull();
    });
    it('rejectsNonHttp', 0, () => {
      expect(PairCode.parse('dshmb:abc123@ftp://x')).assertNull();
      expect(PairCode.parse('dshmb:abc123@javascript:alert(1)')).assertNull();
      expect(PairCode.parse('dshmb:abc123@https://')).assertNull();
      expect(PairCode.parse('dshmb:zzzzzz@https://a.b')).assertNull();
    });
  });

  describe('normalizeBase', () => {
    const full = 'https://example.com:8443/api/mobile-bridge/v1';
    it('appends', 0, () => {
      expect(normalizeBase('https://example.com:8443')).assertEqual(full);
      expect(normalizeBase(' https://example.com:8443/ ')).assertEqual(full);
    });
    it('keeps', 0, () => {
      expect(normalizeBase(full)).assertEqual(full);
      expect(normalizeBase(full + '//')).assertEqual(full);
      expect(normalizeBase(normalizeBase(full))).assertEqual(full);
    });
    it('blank', 0, () => {
      expect(normalizeBase('  ')).assertEqual('');
    });
  });

  describe('chatMerge', () => {
    it('echoReplacesById', 0, () => {
      const out = ChatMerge.mergeIncoming([local('你好', 'c1', 1000)], server('user', '你好', 10, 'c1', 1500));
      expect(out.length).assertEqual(1);
      expect(out[0].local).assertFalse();
      expect(out[0].seq).assertEqual(10);
    });
    it('echoWithoutIdByText', 0, () => {
      const out = ChatMerge.mergeIncoming([local('你好', 'c1', 1000)], server('user', '你好', 10, null, 1500));
      expect(out.length).assertEqual(1);
      expect(out[0].clientMessageId).assertEqual('c1');
    });
    it('outsideWindowIsNew', 0, () => {
      expect(ChatMerge.mergeIncoming([local('你好', null, 0)], server('user', '你好', 10, null, 500000)).length).assertEqual(2);
    });
    it('differentIdNotMerged', 0, () => {
      expect(ChatMerge.mergeIncoming([local('你好', 'c1', 0)], server('user', '你好', 10, 'c2', 10)).length).assertEqual(2);
    });
    it('repeatedSeqIgnored', 0, () => {
      const a = server('bot', 'x', 5, null, 1);
      expect(ChatMerge.mergeIncoming([a], server('bot', 'x', 5, null, 1)).length).assertEqual(1);
    });
    it('historyKeepsUnconfirmed', 0, () => {
      const cur = [local('你好', 'c1', 1000), local('还没到', 'c2', 1100)];
      const out = ChatMerge.mergeHistory(cur, [server('user', '你好', 1, 'c1', 1000)]);
      expect(out.length).assertEqual(2);
      expect(out[0].local).assertFalse();
      expect(out[1].text).assertEqual('还没到');
    });
    it('streamedReplacedByServer', 0, () => {
      const streamed = ChatMerge.commitStreamed([], '**答**', 2000000);
      expect(streamed.length).assertEqual(1);
      expect(streamed[0].local).assertTrue();
      const srv = server('bot', '**答**', 9, null, 2000100);
      expect(ChatMerge.mergeHistory(streamed, [srv]).length).assertEqual(1);
      expect(ChatMerge.commitStreamed([srv], '**答**', 2000200).length).assertEqual(1);
    });
  });

  describe('waitMachine', () => {
    it('submit', 0, () => {
      const s = run([new WaitEvent(WaitKind.Submitted, 'c1')]);
      expect(s.phase).assertEqual(WaitPhase.Waiting);
      expect(s.visible).assertTrue();
    });
    it('stream', 0, () => {
      const s = run([new WaitEvent(WaitKind.Submitted, 'c1'), new WaitEvent(WaitKind.StreamStart),
        new WaitEvent(WaitKind.Delta, 'a'), new WaitEvent(WaitKind.Delta, 'b')]);
      expect(s.phase).assertEqual(WaitPhase.Streaming);
      expect(s.text).assertEqual('ab');
      expect(WaitMachine.reduce(s, new WaitEvent(WaitKind.StreamEnd)).phase).assertEqual(WaitPhase.Idle);
    });
    it('deltaWithoutStart', 0, () => {
      expect(run([new WaitEvent(WaitKind.Submitted, 'c1'), new WaitEvent(WaitKind.Delta, 'x')]).text).assertEqual('x');
    });
    it('failure', 0, () => {
      const s = run([new WaitEvent(WaitKind.Submitted, 'c1'), new WaitEvent(WaitKind.SendFailed, '发送失败：网络不通')]);
      expect(s.phase).assertEqual(WaitPhase.Failed);
      expect(s.error).assertEqual('发送失败：网络不通');
      expect(WaitMachine.reduce(s, new WaitEvent(WaitKind.Dismiss)).phase).assertEqual(WaitPhase.Idle);
    });
    it('lateFailure', 0, () => {
      const s = run([new WaitEvent(WaitKind.Submitted, 'c1'), new WaitEvent(WaitKind.StreamStart),
        new WaitEvent(WaitKind.SendFailed, 'x')]);
      expect(s.phase).assertEqual(WaitPhase.Streaming);
    });
    it('botMessage', 0, () => {
      expect(run([new WaitEvent(WaitKind.Submitted, 'c1'), new WaitEvent(WaitKind.BotMessage)]).phase).assertEqual(WaitPhase.Idle);
      expect(run([new WaitEvent(WaitKind.StreamStart), new WaitEvent(WaitKind.BotMessage)]).phase).assertEqual(WaitPhase.Streaming);
    });
  });

  describe('launch', () => {
    it('routes', 0, () => {
      expect(LaunchRouter.route(false, true, 'a')).assertEqual(StartRoute.Loading);
      expect(LaunchRouter.route(true, false, 'a')).assertEqual(StartRoute.Pair);
      expect(LaunchRouter.route(true, true, '')).assertEqual(StartRoute.Team);
      expect(LaunchRouter.route(true, true, 'bot:chief')).assertEqual(StartRoute.Chat);
    });
    it('onlyRevocationRepairs', 0, () => {
      expect(LaunchRouter.requiresRepair(new AuthRevokedError('x'))).assertTrue();
      expect(LaunchRouter.requiresRepair(new BridgeError('rate', 429))).assertFalse();
      expect(LaunchRouter.requiresRepair(new BridgeError('unauthorized', 401))).assertFalse();
    });
    it('friendlyWords', 0, () => {
      expect(friendly(new BridgeError('x', 503))).assertEqual('电脑端暂时出错，请稍后再试');
      expect(friendly(new BridgeError('x', 400, '名字太长'))).assertEqual('名字太长');
    });
  });

  describe('chatList', () => {
    it('sortNewestFirst', 0, () => {
      const out = ChatListLogic.sorted([entry('a', null), entry('b', 5), entry('c', 9), entry('d', null)]);
      expect(out.map((e: ChatEntry) => e.id).join(',')).assertEqual('c,b,a,d');
    });
    it('badge', 0, () => {
      expect(ChatListLogic.badge(0)).assertEqual('');
      expect(ChatListLogic.badge(7)).assertEqual('7');
      expect(ChatListLogic.badge(120)).assertEqual('99+');
    });
    it('clearOpen', 0, () => {
      const out = ChatListLogic.clearOpen([entry('a', 1), entry('b', 2)], 'bot', 'b');
      expect(out[0].unread).assertEqual(3);
      expect(out[1].unread).assertEqual(0);
    });
    it('timeLabel', 0, () => {
      const now = new Date(2026, 9, 20, 15, 0, 0).getTime();
      expect(ChatListLogic.timeLabel(now - 10000, now)).assertEqual('刚刚');
      expect(ChatListLogic.timeLabel(new Date(2026, 9, 20, 8, 41).getTime(), now)).assertEqual('上午8:41');
      expect(ChatListLogic.timeLabel(new Date(2026, 9, 19, 8, 41).getTime(), now)).assertEqual('昨天');
      expect(ChatListLogic.timeLabel(new Date(2026, 9, 3, 8, 41).getTime(), now)).assertEqual('10月3日');
      expect(ChatListLogic.timeLabel(new Date(2025, 9, 3, 8, 41).getTime(), now)).assertEqual('2025/10/3');
      expect(ChatListLogic.timeLabel(null, now)).assertEqual('');
    });
    it('parse', 0, () => {
      const r = parseChatList(J.parse('{"chats":[{"kind":"room","id":"r1","name":"组","unread":-2,"lastAt":0}],' +
        '"approvals":[{"id":"a1","botName":"甲"}]}'));
      expect(r.chats[0].kind).assertEqual('room');
      expect(r.chats[0].unread).assertEqual(0);
      expect(r.chats[0].lastAt).assertNull();
      expect(r.approvals[0].action).assertEqual('做一个需要你同意的操作');
    });
    it('historyCursor', 0, () => {
      expect(parseHistory(J.parse('{"items":[],"nextCursor":{"a":1}}')).nextCursor).assertEqual('{"a":1}');
      expect(parseHistory(J.parse('{"items":[],"nextCursor":null}')).nextCursor).assertNull();
    });
  });

  describe('sse', () => {
    it('splitsAcrossChunks', 0, () => {
      const p = new SseParser();
      expect(p.feed(': keep\n\nevent: stream\ndata: {"te').length).assertEqual(0);
      const out = p.feed('xt":"a"}\r\n\nevent: message\ndata: x\ndata: y\n\n');
      expect(out.length).assertEqual(2);
      expect(out[0].event).assertEqual('stream');
      expect(out[0].data).assertEqual('{"text":"a"}');
      expect(out[1].data).assertEqual('x\ny');
    });
  });

  describe('markdown', () => {
    it('blocks', 0, () => {
      const b = Markdown.blocks('# 标题\n正文\n\n- 一\n2. 二\n```\ncode\n```');
      expect(b.map((x) => x.type).join(',')).assertEqual('h,p,li,ol,code');
      expect(b[4].text).assertEqual('code');
    });
    it('spans', 0, () => {
      const s = Markdown.spans('a **b** `c`');
      expect(s.length).assertEqual(4);
      expect(s[1].bold).assertTrue();
      expect(s[3].code).assertTrue();
    });
  });
}
