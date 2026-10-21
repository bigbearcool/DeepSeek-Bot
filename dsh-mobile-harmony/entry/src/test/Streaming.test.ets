import { describe, expect, it } from '@ohos/hypium';
import { ChatMerge, StreamText, WaitEvent, WaitKind, WaitMachine, WaitPhase, WaitState } from '../main/ets/common/ChatLogic';
import { LiveReply, RoomStreams } from '../main/ets/common/Group';
import { newItem } from '../main/ets/common/Models';

export default function streamingTest() {
  describe('streamSettlement', () => {
    it('screenshotDeltaKeepsRepeatedCharacter', 0, () => {
      let text = '';
      for (const delta of ['收到，大熊。小秘在，消息', '收', '发正常。']) {
        text = StreamText.merge(text, delta);
      }
      expect(text).assertEqual('收到，大熊。小秘在，消息收发正常。');
      expect(StreamText.merge('哈', '哈')).assertEqual('哈哈');
    });
    it('finalMessageClearsStreamBeforeEnd', 0, () => {
      let s = WaitMachine.reduce(new WaitState(), new WaitEvent(WaitKind.StreamStart, '', 'a'));
      s = WaitMachine.reduce(s, new WaitEvent(WaitKind.Delta, '答案', 'a'));
      s = WaitMachine.reduce(s, new WaitEvent(WaitKind.BotMessage));
      expect(s.phase).assertEqual(WaitPhase.Idle);
      expect(s.text).assertEqual('');
      s = WaitMachine.reduce(s, new WaitEvent(WaitKind.Delta, '迟到', 'a'));
      expect(s.text).assertEqual('');
      expect(WaitMachine.reduce(s, new WaitEvent(WaitKind.StreamEnd, '', 'a')).phase).assertEqual(WaitPhase.Idle);
    });
    it('endWaitsForDurableMessageWithoutCommittingCopy', 0, () => {
      let s = WaitMachine.reduce(new WaitState(), new WaitEvent(WaitKind.StreamStart, '', 'a'));
      s = WaitMachine.reduce(s, new WaitEvent(WaitKind.Delta, '答案', 'a'));
      s = WaitMachine.reduce(s, new WaitEvent(WaitKind.StreamEnd, '', 'a'));
      expect(s.phase).assertEqual(WaitPhase.Streaming);
      expect(s.text).assertEqual('答案');
      expect(s.endedAt > 0).assertTrue();
      expect(WaitMachine.reduce(s, new WaitEvent(WaitKind.BotMessage)).visible).assertFalse();
    });
    it('oldAttemptCannotCloseNewAttempt', 0, () => {
      let s = WaitMachine.reduce(new WaitState(), new WaitEvent(WaitKind.StreamStart, '', 'old'));
      s = WaitMachine.reduce(s, new WaitEvent(WaitKind.StreamStart, '', 'new'));
      s = WaitMachine.reduce(s, new WaitEvent(WaitKind.Delta, '新答案', 'new'));
      s = WaitMachine.reduce(s, new WaitEvent(WaitKind.Delta, '旧字', 'old'));
      s = WaitMachine.reduce(s, new WaitEvent(WaitKind.StreamEnd, '', 'old'));
      expect(s.text).assertEqual('新答案');
      expect(s.endedAt).assertEqual(0);
    });
    it('duplicateStartDoesNotEraseText', 0, () => {
      let s = WaitMachine.reduce(new WaitState(), new WaitEvent(WaitKind.StreamStart, '', 'a'));
      s = WaitMachine.reduce(s, new WaitEvent(WaitKind.Delta, '答案', 'a'));
      s = WaitMachine.reduce(s, new WaitEvent(WaitKind.StreamStart, '', 'a'));
      expect(s.text).assertEqual('答案');
    });
    it('historyCannotEraseNewerLiveMessageOrOlderPages', 0, () => {
      const old = newItem('bot', '旧页', 1); old.seq = 1;
      const newer = newItem('bot', '实时回复', 3); newer.seq = 3;
      const middle = newItem('user', '问题', 2); middle.seq = 2;
      const out = ChatMerge.mergeHistory([old, newer], [middle]);
      expect(out.map((x) => x.text).join(',')).assertEqual('旧页,问题,实时回复');
      expect(ChatMerge.mergeHistory(out, []).length).assertEqual(3);
    });
    it('groupFinalSettlesActiveStreamWithNamePrefix', 0, () => {
      let live = RoomStreams.start([], 'a', 'writer', '甲');
      live = RoomStreams.delta(live, 'a', '甲：答案', 'writer', '甲');
      const item = newItem('member', '答案', 1); item.botId = 'writer';
      live = RoomStreams.onMessage(live, item);
      live = RoomStreams.delta(live, 'a', '迟到', 'writer', '甲');
      live = RoomStreams.end(live, 'a', 2);
      expect(live.length).assertEqual(0);
    });
    it('groupSettlementDoesNotRemoveOtherMember', 0, () => {
      let live: LiveReply[] = RoomStreams.start([], 'a', 'writer', '甲');
      live = RoomStreams.start(live, 'b', 'reader', '乙');
      const item = newItem('member', '答案', 1); item.botId = 'writer';
      live = RoomStreams.onMessage(live, item);
      expect(live.length).assertEqual(1);
      expect(live[0].attemptId).assertEqual('b');
    });
  });
}
