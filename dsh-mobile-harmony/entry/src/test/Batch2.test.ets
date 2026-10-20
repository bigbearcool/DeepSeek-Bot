import { describe, it, expect } from '@ohos/hypium';
import { StreamText } from '../main/ets/common/ChatLogic';
import { BotRules, LiveReply, Mentions, MentionRow, RoomRound, RoomRules, RoomStreams } from '../main/ets/common/Group';
import { BotInfo, NewRoomDraft, newItem } from '../main/ets/common/Models';

function bot(id: string, name: string): BotInfo {
  const b: BotInfo = { id: id, name: name, role: null, sessionId: null, model: null, main: false, color: null, shape: null };
  return b;
}

const team: BotInfo[] = [bot('a', '新 Bot'), bot('b', '新 Bot 2'), bot('c', '研究员')];

export default function batch2Test() {
  describe('mentions', () => {
    it('activeAt', 0, () => {
      expect(Mentions.activeAt('你好 @研')?.query).assertEqual('研');
      expect(Mentions.activeAt('你好 ＠研')?.atIndex).assertEqual(3);
      expect(Mentions.activeAt('mail a@b')).assertNull();
      expect(Mentions.activeAt('@a\nb')).assertNull();
      expect(Mentions.activeAt('没有')).assertNull();
    });
    it('rowsLongestFirstWithEveryone', 0, () => {
      expect(Mentions.rows(team, '新').map((x: MentionRow) => x.id).join(',')).assertEqual('b,a');
      const r = Mentions.rows(team, '');
      expect(r.map((x: MentionRow) => x.id).join(',')).assertEqual('b,a,c,');
      expect(r[3].everyone).assertTrue();
      expect(Mentions.rows(team, '所').length).assertEqual(1);
      expect(Mentions.rows([bot('a', 'x')], '').length).assertEqual(1);
    });
    it('apply', 0, () => {
      const t = '你好 @研';
      const a = Mentions.activeAt(t);
      expect(a === null ? '' : Mentions.apply(t, a, '@研究员 ')).assertEqual('你好 @研究员 ');
    });
    it('calledLongestName', 0, () => {
      const c = Mentions.calledIds('@新 Bot 2 看看', team);
      expect(c.ids.join(',')).assertEqual('b');
      expect(Mentions.calledIds('@新 Bot 看看', team).ids.join(',')).assertEqual('a');
    });
    it('fullwidthAndEveryone', 0, () => {
      expect(Mentions.calledIds('＠研究员 你来', team).ids.join(',')).assertEqual('c');
      expect(Mentions.calledIds('@所有人 早', team).all).assertTrue();
      expect(Mentions.calledIds('mail x@研究员', team).ids.length).assertEqual(0);
    });
    it('whoAnswers', 0, () => {
      expect(Mentions.whoAnswers('大家好', team, 'everyone', 'c').join(',')).assertEqual('c,a,b');
      expect(Mentions.whoAnswers('大家好', team, 'admin', 'c').join(',')).assertEqual('c');
      expect(Mentions.whoAnswers('@新 Bot 你说', team, 'everyone', 'c').join(',')).assertEqual('a');
      expect(Mentions.whoAnswers('@所有人 说', team, 'admin', 'c').length).assertEqual(3);
    });
  });

  describe('roomStreams', () => {
    it('passHidden', 0, () => {
      expect(RoomStreams.shown(new LiveReply('1', 'a', '甲', '[PASS]'))).assertNull();
      expect(RoomStreams.shown(new LiveReply('1', 'a', '甲', '[PA'))).assertNull();
      expect(RoomStreams.shown(new LiveReply('1', 'a', '甲', '甲：你好'))).assertEqual('你好');
    });
    it('startDeltaEnd', 0, () => {
      let l = RoomStreams.start([], 'x', 'a', '甲');
      l = RoomStreams.delta(l, 'x', '你', null, null);
      l = RoomStreams.delta(l, 'x', '好', null, null);
      expect(l[0].text).assertEqual('你好');
      l = RoomStreams.end(l, 'x', 100);
      expect(l[0].endedAt).assertEqual(100);
      expect(RoomStreams.prune(l, 100 + 9000).length).assertEqual(0);
    });
    it('passEndDropped', 0, () => {
      const l = RoomStreams.delta([], 'y', 'PASS', 'a', '甲');
      expect(RoomStreams.end(l, 'y', 1).length).assertEqual(0);
    });
    it('loggedLineReplaces', 0, () => {
      const l = RoomStreams.end(RoomStreams.delta([], 'x', '答案', 'a', '甲'), 'x', 5);
      const m = newItem('member', '答案', 6);
      m.botId = 'a';
      expect(RoomStreams.onMessage(l, m).length).assertEqual(0);
    });
  });

  describe('roomRound', () => {
    it('endsWait', 0, () => {
      const e = newItem('event', null, 1);
      e.role = 'failed';
      expect(RoomRound.endsWait(e)).assertTrue();
      expect(RoomRound.endsWait(newItem('member', 'x', 1))).assertTrue();
      expect(RoomRound.endsWait(newItem('user', 'x', 1))).assertFalse();
    });
    it('quietNote', 0, () => {
      expect(RoomRound.onEnd([], 0, 0, 1).length).assertEqual(1);
      expect(RoomRound.onEnd([], 1, 0, 1).length).assertEqual(0);
    });
    it('since', 0, () => {
      const a = newItem('member', 'x', 1);
      a.seq = 7;
      const b = newItem('user', 'y', 2);
      b.seq = 9;
      b.local = true;
      expect(RoomRound.since([a, b])).assertEqual(7);
      expect(RoomRound.since([])).assertEqual(-1);
    });
  });

  describe('rules', () => {
    it('freshName', 0, () => {
      expect(BotRules.freshName([])).assertEqual('新助手');
      expect(BotRules.freshName(['新助手', '新助手 2'])).assertEqual('新助手 3');
    });
    it('problem', 0, () => {
      expect(BotRules.problem(' ', [])).assertEqual('助手需要一个名字');
      expect(BotRules.problem('a@b', [])).assertEqual('助手名字里不能有 @、` 或换行');
      expect(BotRules.problem('所有人', []) !== null).assertTrue();
      expect(BotRules.problem('研究员', ['研究员']) !== null).assertTrue();
      expect(BotRules.problem('小明', ['研究员'])).assertNull();
    });
    it('room', 0, () => {
      let d = new NewRoomDraft();
      expect(RoomRules.canCreate(d)).assertFalse();
      d = RoomRules.pickAdmin(RoomRules.toggle(d, 'a'), 'a');
      expect(d.members.length).assertEqual(0);
      expect(RoomRules.canCreate(d)).assertFalse();
      d = RoomRules.toggle(d, 'b');
      expect(RoomRules.canCreate(d)).assertTrue();
      expect(RoomRules.toggle(d, 'a').members.join(',')).assertEqual('b');
      expect(RoomRules.canCreate(d, 1)).assertFalse();
    });
  });
}
