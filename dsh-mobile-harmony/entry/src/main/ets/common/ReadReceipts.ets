import { ChatEntry } from './Models';

interface ReadMark {
  kind: string;
  id: string;
  seq: number;
}

type ReadWriter = (kind: string, id: string, seq: number) => Promise<void>;

/** Only confirmed, displayed positions may suppress a stale list's unread badge. */
export class ReadReceipts {
  private pending: Map<string, ReadMark> = new Map();
  private confirmed: Map<string, number> = new Map();
  private task: Promise<void> | null = null;
  private active: ReadMark | null = null;

  record(kind: string, id: string, seq: number): void {
    if ((kind !== 'bot' && kind !== 'room') || id.length === 0 || !Number.isFinite(seq) || seq < 0) {
      return;
    }
    const key = kind + ':' + id;
    const active = this.active;
    if (active !== null && active.kind === kind && active.id === id && seq <= active.seq) {
      return;
    }
    if (seq <= (this.confirmed.get(key) ?? -1) || seq <= (this.pending.get(key)?.seq ?? -1)) {
      return;
    }
    this.pending.set(key, { kind: kind, id: id, seq: seq });
  }

  flush(write: ReadWriter): Promise<void> {
    if (this.task !== null) {
      return this.task;
    }
    if (this.pending.size === 0) {
      return Promise.resolve();
    }
    this.task = this.drain(write).finally(() => {
      this.task = null;
    });
    return this.task;
  }

  private async drain(write: ReadWriter): Promise<void> {
    while (this.pending.size > 0) {
      let next: ReadMark | null = null;
      for (const mark of this.pending.values()) {
        next = mark;
        break;
      }
      if (next === null) {
        return;
      }
      const mark = next;
      const key = mark.kind + ':' + mark.id;
      this.pending.delete(key);
      this.active = mark;
      try {
        await write(mark.kind, mark.id, mark.seq);
        this.confirmed.set(key, Math.max(mark.seq, this.confirmed.get(key) ?? -1));
      } catch (e) {
        this.active = null;
        this.record(mark.kind, mark.id, mark.seq);
        throw e as Error;
      } finally {
        this.active = null;
      }
    }
  }

  apply(chats: ChatEntry[]): ChatEntry[] {
    return chats.map((c: ChatEntry) => {
      if (c.seq === undefined || c.seq === null || c.seq > (this.confirmed.get(c.kind + ':' + c.id) ?? -1)) {
        return c;
      }
      const n: ChatEntry = {
        kind: c.kind, id: c.id, name: c.name, color: c.color, main: c.main, preview: c.preview,
        lastAt: c.lastAt, unread: 0, running: c.running, members: c.members, seq: c.seq
      };
      return n;
    });
  }
}
