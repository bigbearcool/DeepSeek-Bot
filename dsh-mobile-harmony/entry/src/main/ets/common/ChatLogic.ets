import { ChatItem, ChatEntry, ApprovalInfo, copyItem, newItem } from './Models';
import { AuthRevokedError, BridgeError } from './Errors';

function norm(t: string | null): string {
  return (t ?? '').trim();
}

/** The phone shows the user's bubble at once; the echo from the computer replaces it. */
export class ChatMerge {
  static readonly WINDOW_MS: number = 120000;

  private static sameServerItem(a: ChatItem, b: ChatItem): boolean {
    return !a.local && a.seq !== null && a.seq === b.seq && a.kind === b.kind;
  }

  private static matchesLocal(local: ChatItem, incoming: ChatItem): boolean {
    if (!local.local || local.kind !== incoming.kind) {
      return false;
    }
    if (local.clientMessageId !== null && incoming.clientMessageId !== null) {
      return local.clientMessageId === incoming.clientMessageId;
    }
    if (norm(local.text) !== norm(incoming.text)) {
      return false;
    }
    if (local.time === null || incoming.time === null) {
      return true;
    }
    return Math.abs(local.time - incoming.time) <= ChatMerge.WINDOW_MS;
  }

  static mergeIncoming(list: ChatItem[], incoming: ChatItem): ChatItem[] {
    if (list.some((x: ChatItem) => ChatMerge.sameServerItem(x, incoming))) {
      return list;
    }
    const i = list.findIndex((x: ChatItem) => ChatMerge.matchesLocal(x, incoming));
    if (i >= 0) {
      const out = list.slice();
      const c = copyItem(incoming);
      c.clientMessageId = incoming.clientMessageId ?? list[i].clientMessageId;
      c.local = false;
      out[i] = c;
      return out;
    }
    return list.concat([incoming]);
  }

  static mergeHistory(current: ChatItem[], page: ChatItem[]): ChatItem[] {
    let out = page.slice();
    for (const item of current) {
      if (!item.local) {
        continue;
      }
      const matched = page.some((p: ChatItem) => ChatMerge.matchesLocal(item, p)) ||
        (item.kind === 'bot' && page.some((p: ChatItem) => p.kind === 'bot' &&
          (p.time ?? 0) >= (item.time ?? 0) - ChatMerge.WINDOW_MS));
      if (!matched) {
        out.push(item);
      }
    }
    return out;
  }

  static commitStreamed(list: ChatItem[], text: string, now: number): ChatItem[] {
    if (text.trim().length === 0) {
      return list;
    }
    const tail = list.slice(Math.max(0, list.length - 6));
    const dup = tail.some((x: ChatItem) => !x.local && x.kind === 'bot' && norm(x.text) === norm(text));
    if (dup) {
      return list;
    }
    const i = newItem('bot', text, now);
    i.local = true;
    return list.concat([i]);
  }
}

export enum WaitPhase { Idle, Waiting, Streaming, Failed }

export class WaitState {
  phase: WaitPhase = WaitPhase.Idle;
  text: string = '';
  error: string = '';
  clientMessageId: string = '';

  static of(phase: WaitPhase, text: string = '', error: string = '', id: string = ''): WaitState {
    const s = new WaitState();
    s.phase = phase;
    s.text = text;
    s.error = error;
    s.clientMessageId = id;
    return s;
  }

  get visible(): boolean {
    return this.phase !== WaitPhase.Idle;
  }
}

/** Merge one stream update: append a delta, or replace when the computer sends the full text so far. */
export class StreamText {
  static merge(prev: string, incoming: string): string {
    if (incoming.length === 0) {
      return prev;
    }
    if (prev.length === 0) {
      return incoming;
    }
    // Full text so far (snapshot): replace, do not append on top of itself.
    if (incoming === prev || incoming.startsWith(prev)) {
      return incoming;
    }
    // Older snapshot arriving late.
    if (prev.startsWith(incoming)) {
      return prev;
    }
    return prev + incoming;
  }
}

export enum WaitKind { Submitted, SendFailed, StreamStart, Delta, StreamEnd, BotMessage, Dismiss }

export class WaitEvent {
  kind: WaitKind;
  value: string;

  constructor(kind: WaitKind, value: string = '') {
    this.kind = kind;
    this.value = value;
  }
}

export class WaitMachine {
  static reduce(s: WaitState, e: WaitEvent): WaitState {
    switch (e.kind) {
      case WaitKind.Submitted:
        return WaitState.of(WaitPhase.Waiting, '', '', e.value);
      case WaitKind.SendFailed:
        return s.phase === WaitPhase.Waiting ? WaitState.of(WaitPhase.Failed, s.text, e.value, s.clientMessageId) : s;
      case WaitKind.StreamStart:
        return WaitState.of(WaitPhase.Streaming, '', '', s.clientMessageId);
      case WaitKind.Delta:
        if (s.phase === WaitPhase.Streaming) {
          return WaitState.of(WaitPhase.Streaming, StreamText.merge(s.text, e.value), '', s.clientMessageId);
        }
        return WaitState.of(WaitPhase.Streaming, e.value, '', s.clientMessageId);
      case WaitKind.StreamEnd:
        return new WaitState();
      case WaitKind.BotMessage:
        return s.phase === WaitPhase.Streaming ? s : new WaitState();
      default:
        return new WaitState();
    }
  }
}

export enum StartRoute { Loading, Pair, Team, Chat }

export class LaunchRouter {
  static route(loaded: boolean, paired: boolean, lastId: string): StartRoute {
    if (!loaded) {
      return StartRoute.Loading;
    }
    if (!paired) {
      return StartRoute.Pair;
    }
    return lastId.length > 0 ? StartRoute.Chat : StartRoute.Team;
  }

  /** The last chat to reopen, or '' when it is empty, malformed or no longer on the computer. */
  static resumeKey(lastChat: string, botIds: string[], roomIds: string[]): string {
    const i = lastChat.indexOf(':');
    if (i <= 0) {
      return '';
    }
    const kind = lastChat.substring(0, i);
    const id = lastChat.substring(i + 1).trim();
    if (id.length === 0) {
      return '';
    }
    if (kind === 'bot') {
      return botIds.indexOf(id) >= 0 ? 'bot:' + id : '';
    }
    if (kind === 'room') {
      return roomIds.indexOf(id) >= 0 ? 'room:' + id : '';
    }
    return '';
  }

  /** The chat being opened is gone from the computer (or was never valid): go back to the list. */
  static chatGone(e: Object): boolean {
    return e instanceof BridgeError && ((e as BridgeError).status === 404 || (e as BridgeError).status === 400);
  }

  /** Only a revoked pass sends the user back to pairing; being offline never does. */
  static requiresRepair(e: Object): boolean {
    return e instanceof AuthRevokedError;
  }
}

const WEEK: string[] = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];

function dayStart(at: number): number {
  const d = new Date(at);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

export class Look {
  static clock(at: number): string {
    const d = new Date(at);
    const h = d.getHours();
    const h12 = h % 12 === 0 ? 12 : h % 12;
    const m = d.getMinutes();
    return (h < 12 ? '上午' : '下午') + h12 + ':' + (m < 10 ? '0' + m : '' + m);
  }

  static daysBetween(at: number, now: number): number {
    return Math.round((dayStart(now) - dayStart(at)) / 86400000);
  }

  static day(at: number, now: number): string {
    const a = new Date(at);
    const n = new Date(now);
    const d = Look.daysBetween(at, now);
    if (d === 0) {
      return '今天';
    }
    if (d === 1) {
      return '昨天';
    }
    if (d >= 2 && d <= 6) {
      return WEEK[a.getDay()];
    }
    if (a.getFullYear() === n.getFullYear()) {
      return (a.getMonth() + 1) + '月' + a.getDate() + '日';
    }
    return a.getFullYear() + '年' + (a.getMonth() + 1) + '月' + a.getDate() + '日';
  }

  /** One-line preview without formatting marks. */
  static plainPreview(t: string): string {
    return t.replace(new RegExp('[*_`#>]+', 'g'), '').replace(new RegExp('\\s+', 'g'), ' ').trim();
  }
}

export class ChatListLogic {
  static sorted(chats: ChatEntry[]): ChatEntry[] {
    const idx = chats.map((c: ChatEntry, i: number) => i);
    idx.sort((a: number, b: number) => {
      const la = chats[a].lastAt ?? -1;
      const lb = chats[b].lastAt ?? -1;
      if (la !== lb) {
        return lb - la;
      }
      return a - b;
    });
    return idx.map((i: number) => chats[i]);
  }

  static badge(unread: number): string {
    if (unread <= 0) {
      return '';
    }
    return unread > 99 ? '99+' : '' + unread;
  }

  static clearOpen(chats: ChatEntry[], kind: string, id: string): ChatEntry[] {
    return chats.map((c: ChatEntry) => {
      if (c.kind === kind && c.id === id && c.unread !== 0) {
        const n: ChatEntry = {
          kind: c.kind, id: c.id, name: c.name, color: c.color, main: c.main, preview: c.preview,
          lastAt: c.lastAt, unread: 0, running: c.running, members: c.members
        };
        return n;
      }
      return c;
    });
  }

  static approvalsFor(all: ApprovalInfo[], kind: string, id: string): ApprovalInfo[] {
    if (kind === 'room') {
      return all.filter((a: ApprovalInfo) => a.roomId === id);
    }
    if (kind === 'bot') {
      return all.filter((a: ApprovalInfo) => a.roomId === null && a.botId === id);
    }
    return [];
  }

  static whereText(a: ApprovalInfo): string {
    const who = a.botName ?? '助手';
    return a.roomName !== null ? who + '（' + a.roomName + '）' : who;
  }

  static timeLabel(at: number | null, now: number): string {
    if (at === null || at <= 0) {
      return '';
    }
    if (now - at >= 0 && now - at < 60000) {
      return '刚刚';
    }
    const d = Look.daysBetween(at, now);
    if (d <= 0) {
      return Look.clock(at);
    }
    if (d <= 6) {
      return Look.day(at, now);
    }
    const a = new Date(at);
    const n = new Date(now);
    if (a.getFullYear() === n.getFullYear()) {
      return (a.getMonth() + 1) + '月' + a.getDate() + '日';
    }
    return a.getFullYear() + '/' + (a.getMonth() + 1) + '/' + a.getDate();
  }
}

/** Timing rules for the live connection and the waiting line (same numbers as Android). */
export class LiveTimers {
  static readonly STALL_MS: number = 10000;
  static readonly DOT_MS: number = 400;

  /** 1s, 2s, 4s … capped at 30s. */
  static backoff(attempt: number): number {
    return Math.min(30000, 1000 * Math.pow(2, Math.min(Math.max(attempt, 0), 5)));
  }

  /** Waiting with no live event for a while: fetch the history once more. */
  static shouldPoll(waiting: boolean, lastEventAt: number, lastPollAt: number, now: number): boolean {
    return waiting && now - lastEventAt >= LiveTimers.STALL_MS && now - lastPollAt >= LiveTimers.STALL_MS;
  }

  /** The reply already arrived after the user's last message, so stop waiting. */
  static replied(items: ChatItem[]): boolean {
    for (let i = items.length - 1; i >= 0; i--) {
      const k = items[i].kind;
      if (k === 'bot' || k === 'member') {
        return true;
      }
      if (k === 'user') {
        return false;
      }
    }
    return false;
  }

  /** Which of the three dots is lit at this step. */
  static dotOn(step: number, i: number): boolean {
    return step % 3 === i;
  }
}
