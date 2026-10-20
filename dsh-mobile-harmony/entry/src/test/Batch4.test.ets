import { describe, it, expect } from '@ohos/hypium';
import { Alert, ChatPrefs, NotifyLogic, Seen } from '../main/ets/common/Extra';
import { ApprovalInfo, ChatEntry, ChatListResponse } from '../main/ets/common/Models';

function chat(kind: string, id: string, unread: number, preview: string): ChatEntry {
  const e: ChatEntry = { kind: kind, id: id, name: id + '名', color: null, main: false, preview: preview, lastAt: 1, unread: unread, running: false, members: [] };
  return e;
}

function ap(id: string, roomId: string | null): ApprovalInfo {
  const a: ApprovalInfo = { id: id, botId: 'b', botName: '甲', roomId: roomId, roomName: roomId === null ? null : '组', action: '删除文件', reason: '', detail: '', answerable: true };
  return a;
}

function resp(chats: ChatEntry[], aps: ApprovalInfo[]): ChatListResponse {
  const r: ChatListResponse = { available: true, chats: chats, approvals: aps };
  return r;
}

export default function batch4Test() {
  describe('notifyLogic', () => {
    it('newMessages', 0, () => {
      const prev: Seen = NotifyLogic.seen(resp([chat('bot', 'a', 1, ''), chat('bot', 'b', 0, '')], []));
      const out = NotifyLogic.alerts(prev, resp([chat('bot', 'a', 3, '**你好**'), chat('bot', 'b', 0, '')], []), new ChatPrefs(), '');
      expect(out.length).assertEqual(1);
      expect(out[0].text).assertEqual('你好');
      expect(out[0].count).assertEqual(3);
      expect(NotifyLogic.countLine(out[0].count)).assertEqual('3 条新消息');
      expect(out[0].tag).assertEqual('bot:a');
    });
    it('respectsMuteOpenAndSwitch', 0, () => {
      const prev = NotifyLogic.seen(resp([chat('bot', 'a', 0, '')], []));
      const now = resp([chat('bot', 'a', 1, '')], []);
      expect(NotifyLogic.alerts(prev, now, new ChatPrefs().withMuted('bot:a', true), '').length).assertEqual(0);
      expect(NotifyLogic.alerts(prev, now, new ChatPrefs(), 'bot:a').length).assertEqual(0);
      expect(NotifyLogic.alerts(prev, now, new ChatPrefs().withNotify(false), '').length).assertEqual(0);
      expect(NotifyLogic.alerts(prev, now, new ChatPrefs(), '')[0].text).assertEqual('有新消息');
    });
    it('approvalsOnce', 0, () => {
      const prev = NotifyLogic.seen(resp([], [ap('x', null)]));
      const out: Alert[] = NotifyLogic.alerts(prev, resp([], [ap('x', null), ap('y', 'r1')]), new ChatPrefs(), '');
      expect(out.length).assertEqual(1);
      expect(out[0].kind).assertEqual('room');
      expect(out[0].name).assertEqual('组');
      expect(out[0].text).assertEqual('甲 在等你确认：删除文件');
      expect(out[0].tag).assertEqual('ap:y');
    });
  });
}
