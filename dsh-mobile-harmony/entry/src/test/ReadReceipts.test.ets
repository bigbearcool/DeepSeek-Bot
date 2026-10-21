import { describe, expect, it } from '@ohos/hypium';
import { ReadReceipts } from '../main/ets/common/ReadReceipts';
import { ChatEntry, parseChatList } from '../main/ets/common/Models';
import { J } from '../main/ets/common/Json';

function chat(id: string, seq: number): ChatEntry {
  return { kind: 'bot', id: id, name: id, seq: seq, color: null, main: false, preview: '', lastAt: null,
    unread: 1, running: false, members: [] };
}

export default function readReceiptsTest() {
  describe('readReceipts', () => {
    it('listPreservesServerSequence', 0, () => {
      const list = parseChatList(J.parse('{"chats":[{"id":"a","kind":"bot","seq":9,"unread":1}]}'));
      expect(list.chats[0].seq).assertEqual(9);
    });
    it('confirmedReadMasksOnlyOlderSnapshotsOfThatChat', 0, async () => {
      const reads = new ReadReceipts();
      reads.record('bot', 'a', 9);
      await reads.flush(async (kind: string, id: string, seq: number) => {});
      expect(reads.apply([chat('a', 9)])[0].unread).assertEqual(0);
      expect(reads.apply([chat('a', 10)])[0].unread).assertEqual(1);
      expect(reads.apply([chat('b', 9)])[0].unread).assertEqual(1);
      const group = chat('a', 9); group.kind = 'room';
      expect(reads.apply([group])[0].unread).assertEqual(1);
    });
    it('coalescesInFlightReceiptsWithoutDroppingNewerMessages', 0, async () => {
      const reads = new ReadReceipts();
      const writes: number[] = [];
      let release: () => void = () => {};
      const blocked = new Promise<void>((resolve) => { release = resolve; });
      reads.record('bot', 'a', 9);
      const pending = reads.flush(async (kind: string, id: string, seq: number) => {
        writes.push(seq);
        if (seq === 9) { await blocked; }
      });
      reads.record('bot', 'a', 9);
      reads.record('bot', 'a', 10);
      release();
      await pending;
      expect(writes.join(',')).assertEqual('9,10');
      expect(reads.apply([chat('a', 10)])[0].unread).assertEqual(0);
    });
    it('failedReceiptsRemainPendingAndDoNotHideUnread', 0, async () => {
      const reads = new ReadReceipts();
      reads.record('bot', 'a', 9);
      let failed = false;
      try {
        await reads.flush(async (kind: string, id: string, seq: number) => { throw new Error('offline'); });
      } catch (e) { failed = true; }
      expect(failed).assertTrue();
      expect(reads.apply([chat('a', 9)])[0].unread).assertEqual(1);
      let retried = 0;
      await reads.flush(async (kind: string, id: string, seq: number) => { retried = seq; });
      expect(retried).assertEqual(9);
      expect(reads.apply([chat('a', 9)])[0].unread).assertEqual(0);
    });
    it('invalidPositionsAreNotSent', 0, async () => {
      const reads = new ReadReceipts();
      reads.record('bot', 'a', -1);
      reads.record('bot', '', 9);
      reads.record('invalid', 'a', 9);
      let calls = 0;
      await reads.flush(async (kind: string, id: string, seq: number) => { calls++; });
      expect(calls).assertEqual(0);
    });
  });
}
