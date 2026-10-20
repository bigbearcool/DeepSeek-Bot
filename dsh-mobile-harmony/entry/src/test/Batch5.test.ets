import { describe, it, expect } from '@ohos/hypium';
import { Exchanges, ListPrefs, Looks, roomTitle } from '../main/ets/common/Look';
import { CHARACTERS } from '../main/ets/common/Characters';
import { ChatEntry, ChatItem, newItem } from '../main/ets/common/Models';
import { LaunchRouter, LiveTimers, StartRoute } from '../main/ets/common/ChatLogic';
import { AuthRevokedError, BridgeError, friendly } from '../main/ets/common/Errors';

function c(id: string, lastAt: number): ChatEntry {
  const e: ChatEntry = { kind: 'bot', id: id, name: id, color: null, main: false, preview: '', lastAt: lastAt, unread: 0, running: false, members: [] };
  return e;
}

function ev(role: string, bot: string): ChatItem {
  const i = newItem('event', null, null);
  i.role = role;
  i.botId = bot;
  i.name = bot;
  return i;
}

export default function batch5Test() {
  describe('looks', () => {
    it('defaultLookMatchesComputer', 0, () => {
      const expected: string[][] = [['chief', 'star', 'blue'], ['writer', 'star', 'red'], ['bot-1', 'gumdrop', 'blue'],
        ['abc', 'gumdrop', 'cyan'], ['招聘', 'kitty', 'red'], ['session-a2d5e4ab', 'kitty', 'cyan'], ['x', 'moon', 'green']];
      for (const e of expected) {
        expect(Looks.defaultShape(e[0])).assertEqual(e[1]);
        expect(Looks.defaultColor(e[0])).assertEqual(e[2]);
      }
    });
    it('lookOf', 0, () => {
      expect(Looks.lookOf('chief', 'whale', 'violet').shape).assertEqual('whale');
      expect(Looks.lookOf('chief', 'nope', 'not-a-color').color).assertEqual('blue');
      expect(Looks.lookOf('chief', null, '#123abc').color).assertEqual('#123abc');
      expect(Looks.lookOf(null, null, null).shape).assertEqual('gumdrop');
      expect(Looks.lookOf(null, null, null).color).assertEqual('black');
      expect(Looks.ink('blue', false)).assertEqual('#2A92FE');
      expect(Looks.ink('kimi', false)).assertEqual('#141416');
      expect(CHARACTERS.length).assertEqual(20);
    });
    it('quote', 0, () => {
      const sent = Looks.quoted('小秘', '本周待办（示例） 重点事项', '第二条改到周四');
      expect(sent).assertEqual('> 小秘：本周待办（示例） 重点事项\n\n第二条改到周四');
      const q = Looks.splitQuote(sent);
      expect(q[0]).assertEqual('小秘：本周待办（示例） 重点事项');
      expect(q[1]).assertEqual('第二条改到周四');
      expect(Looks.splitQuote('普通消息')[0]).assertEqual('');
      expect(Looks.splitQuote('> 没有空行')[0]).assertEqual('');
      expect(Looks.snippet('## 一二三四五', 3)).assertEqual('一二三…');
    });
    it('preview', 0, () => {
      expect(Looks.plainPreview('详情见 [日程](https://example.com) 好')).assertEqual('详情见 日程 好');
      expect(Looks.plainPreview('详情见 [日程](https://exam…')).assertEqual('详情见 日程');
    });
    it('working', 0, () => {
      expect(Looks.workingLabel(['小秘'], false)).assertEqual('小秘 正在工作');
      expect(Looks.workingLabel(['工匠', '园长', '工匠'], true)).assertEqual('工匠、园长 正在输入…');
      expect(Looks.workingLabel([], false)).assertEqual('助手 正在工作');
      expect(Looks.inputHint('', true)).assertEqual('在群里发消息');
      expect(Looks.inputHint('小秘', false)).assertEqual('向 小秘 提问');
    });
    it('dividers', 0, () => {
      const now = new Date(2026, 9, 20, 15, 0).getTime();
      const t0 = new Date(2026, 9, 20, 8, 0).getTime();
      const d = Looks.dividers([null, t0, t0 + 60000, t0 + 20 * 60000], now);
      expect(d.size).assertEqual(2);
      expect(d.get(1)).assertEqual('今天 上午8:00');
      expect(d.has(3)).assertTrue();
    });
  });

  describe('exchanges', () => {
    it('runs', 0, () => {
      const items: ChatItem[] = [newItem('user', 'a', null), ev('request', 'b1'), ev('reply', 'b2'), ev('report', 'b1'),
        newItem('bot', 'ok', null), ev('done', 'b1'), ev('reply', 'b3')];
      const runs = Exchanges.runs(items);
      expect(runs.length).assertEqual(2);
      expect(runs[0].start).assertEqual(1);
      expect(runs[0].count).assertEqual(3);
      expect(runs[0].bots).assertEqual(2);
      expect(Exchanges.words(runs[0])).assertEqual('3 条消息，与 2 个助手');
      expect(runs[1].start).assertEqual(6);
      expect(Exchanges.isExchange(items[5])).assertFalse();
    });
  });

  describe('listPrefs', () => {
    const a = c('a', 100);
    const b = c('b', 200);
    const r = c('r', 300);
    it('pin', 0, () => {
      const p = new ListPrefs().togglePin(r);
      expect(p.apply([a, b, r]).map((x: ChatEntry) => x.id).join(',')).assertEqual('r,a,b');
      expect(p.isPinned(r)).assertTrue();
      expect(p.togglePin(r).isPinned(r)).assertFalse();
    });
    it('hide', 0, () => {
      const p = new ListPrefs().togglePin(b).hide(b, 250);
      expect(p.apply([a, b, r]).map((x: ChatEntry) => x.id).join(',')).assertEqual('a,r');
      expect(p.isPinned(b)).assertFalse();
      expect(p.hiddenCount([a, b, r])).assertEqual(1);
      expect(p.apply([a, c('b', 400), r]).length).assertEqual(3);
      expect(p.unhideAll().apply([a, b, r]).length).assertEqual(3);
    });
    it('unreadAndRoundTrip', 0, () => {
      const p = new ListPrefs().toggleUnread(a).hide(b, 250).withTheme('dark');
      expect(p.markedUnread(a)).assertTrue();
      expect(p.opened('bot', 'a').markedUnread(a)).assertFalse();
      const q = ListPrefs.decode(p.encode());
      expect(q.markedUnread(a)).assertTrue();
      expect(q.hidden.get('bot:b')).assertEqual(250);
      expect(q.theme).assertEqual('dark');
      expect(ListPrefs.decode('').theme).assertEqual('system');
      expect(ListPrefs.normTheme('blue')).assertEqual('system');
    });
  });

  describe('roomTitle', () => {
    it('names', 0, () => {
      expect(roomTitle('周会', ['You', '园长'])).assertEqual('周会');
      expect(roomTitle('You, 园长, 小秘…', [])).assertEqual('我、园长、小秘…');
      expect(roomTitle('You, 园长, 工匠', [])).assertEqual('我、园长、工匠');
      expect(roomTitle('', ['You', '园长', '小秘'])).assertEqual('我、园长、小秘');
      expect(roomTitle('', [])).assertEqual('群聊');
      expect(roomTitle('猫, 狗', [])).assertEqual('猫, 狗');
    });
  });

  describe('liveTimers', () => {
    it('backoffGrows', 0, () => {
      expect(LiveTimers.backoff(0)).assertEqual(1000);
      expect(LiveTimers.backoff(1)).assertEqual(2000);
      expect(LiveTimers.backoff(3)).assertEqual(8000);
      expect(LiveTimers.backoff(9)).assertEqual(30000);
    });
    it('stallPollsHistory', 0, () => {
      expect(LiveTimers.shouldPoll(true, 0, 0, 10000)).assertTrue();
      expect(LiveTimers.shouldPoll(true, 5000, 0, 10000)).assertFalse();
      expect(LiveTimers.shouldPoll(false, 0, 0, 60000)).assertFalse();
      expect(LiveTimers.shouldPoll(true, 0, 9000, 12000)).assertFalse();
    });
    it('repliedEndsWait', 0, () => {
      expect(LiveTimers.replied([newItem('user', '测试', 1)])).assertFalse();
      expect(LiveTimers.replied([newItem('user', '测试', 1), newItem('bot', '1', 2)])).assertTrue();
      expect(LiveTimers.replied([newItem('bot', '旧', 1), newItem('user', '测试', 2), newItem('event', 'x', 3)])).assertFalse();
    });
    it('dotsCycle', 0, () => {
      expect(LiveTimers.dotOn(0, 0)).assertTrue();
      expect(LiveTimers.dotOn(1, 0)).assertFalse();
      expect(LiveTimers.dotOn(4, 1)).assertTrue();
    });
  });

  describe('launchResume', () => {
    it('notPairedGoesToPairing', 0, () => {
      expect(LaunchRouter.route(true, false, 'bot:chief')).assertEqual(StartRoute.Pair);
      expect(LaunchRouter.requiresRepair(new AuthRevokedError('x'))).assertTrue();
    });
    it('resumeOnlyExistingChat', 0, () => {
      const bots = ['chief', 'writer'];
      const rooms = ['r1'];
      expect(LaunchRouter.resumeKey('bot:writer', bots, rooms)).assertEqual('bot:writer');
      expect(LaunchRouter.resumeKey('room:r1', bots, rooms)).assertEqual('room:r1');
      expect(LaunchRouter.resumeKey('bot:gone', bots, rooms)).assertEqual('');
      expect(LaunchRouter.resumeKey('room:gone', bots, rooms)).assertEqual('');
      expect(LaunchRouter.resumeKey('bot:', bots, rooms)).assertEqual('');
      expect(LaunchRouter.resumeKey('chief', bots, rooms)).assertEqual('');
      expect(LaunchRouter.resumeKey('', bots, rooms)).assertEqual('');
      expect(LaunchRouter.resumeKey('team:chief', bots, rooms)).assertEqual('');
    });
    it('goneChatBackToList', 0, () => {
      expect(LaunchRouter.chatGone(new BridgeError('unknown group chat', 404))).assertTrue();
      expect(LaunchRouter.chatGone(new BridgeError('botId required', 400))).assertTrue();
      expect(LaunchRouter.chatGone(new BridgeError('boom', 500))).assertFalse();
      expect(LaunchRouter.chatGone(new BridgeError('network', 0))).assertFalse();
      expect(LaunchRouter.chatGone(new AuthRevokedError('x'))).assertFalse();
      expect(friendly(new BridgeError('x', 403))).assertEqual('电脑没有接受这个操作');
    });
  });
}
