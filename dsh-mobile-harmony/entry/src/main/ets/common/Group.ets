import { BotInfo, BotOptions, ChatItem, NewRoomDraft, newItem } from './Models';

export class MentionActive {
  atIndex: number;
  query: string;

  constructor(atIndex: number, query: string) {
    this.atIndex = atIndex;
    this.query = query;
  }
}

export class MentionRow {
  id: string;
  name: string;
  insert: string;
  everyone: boolean;

  constructor(id: string, name: string, insert: string, everyone: boolean = false) {
    this.id = id;
    this.name = name;
    this.insert = insert;
    this.everyone = everyone;
  }
}

export class Called {
  all: boolean;
  ids: string[];

  constructor(all: boolean, ids: string[]) {
    this.all = all;
    this.ids = ids;
  }
}

function isAsciiWord(ch: string): boolean {
  return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch === '_';
}

/** Same as the computer: ASCII letters/digits and _ . / before @ make it an address. */
function isAddressBefore(ch: string): boolean {
  return isAsciiWord(ch) || ch === '.' || ch === '/';
}

function fitsAt(rest: string, word: string): boolean {
  if (rest.length < word.length) {
    return false;
  }
  if (rest.substring(0, word.length).toLowerCase() !== word.toLowerCase()) {
    return false;
  }
  if (rest.length === word.length) {
    return true;
  }
  const next = rest.charAt(word.length);
  if (isAsciiWord(next) || next === '-' || next === '/' || next === '\\') {
    return false;
  }
  if (next === '.' && rest.length > word.length + 1) {
    const n2 = rest.charAt(word.length + 1);
    if (isAsciiWord(n2) || n2 === '-') {
      return false;
    }
  }
  return true;
}

/** Group "@" rules, matching the desktop: no @ → by group mode; @name → only that member. */
export class Mentions {
  static readonly ALL_WORDS: string[] = ['all', 'everyone', '所有人', '全体'];

  static activeAt(text: string): MentionActive | null {
    const at = Math.max(text.lastIndexOf('@'), text.lastIndexOf('＠'));
    if (at < 0) {
      return null;
    }
    if (at > 0 && isAddressBefore(text.charAt(at - 1))) {
      return null;
    }
    const q = text.substring(at + 1);
    if (q.indexOf('\n') >= 0 || q.indexOf('\r') >= 0) {
      return null;
    }
    return new MentionActive(at, q);
  }

  static rows(members: BotInfo[], query: string, includeEveryone: boolean = true): MentionRow[] {
    const needle = query.trim().toLowerCase();
    const bots = members
      .filter((b: BotInfo) => b.name.trim().length > 0 && (needle.length === 0 || b.name.toLowerCase().indexOf(needle) >= 0))
      .sort((a: BotInfo, b: BotInfo) => b.name.length - a.name.length)
      .map((b: BotInfo) => new MentionRow(b.id, b.name, '@' + b.name + ' '));
    const wantsAll = includeEveryone && members.length > 1 && Mentions.ALL_WORDS.some((w: string) =>
      needle.length === 0 || w.startsWith(needle) || '所有人'.indexOf(needle) >= 0);
    if (wantsAll) {
      bots.push(new MentionRow('', '所有人', '@所有人 ', true));
    }
    return bots;
  }

  static apply(text: string, active: MentionActive, insert: string): string {
    const end = Math.min(text.length, active.atIndex + 1 + active.query.length);
    return text.substring(0, active.atIndex) + insert + text.substring(end);
  }

  static calledIds(text: string, members: BotInfo[]): Called {
    const value = text.replace(new RegExp('＠', 'g'), '@');
    const byLength = members.filter((b: BotInfo) => b.name.trim().length > 0)
      .sort((a: BotInfo, b: BotInfo) => b.name.length - a.name.length);
    const found: string[] = [];
    let all = false;
    let i = 0;
    while (true) {
      const at = value.indexOf('@', i);
      if (at < 0) {
        break;
      }
      if (at > 0 && isAddressBefore(value.charAt(at - 1))) {
        i = at + 1;
        continue;
      }
      const rest = value.substring(at + 1);
      const bot = byLength.find((b: BotInfo) => fitsAt(rest, b.name));
      if (bot !== undefined) {
        if (found.indexOf(bot.id) < 0) {
          found.push(bot.id);
        }
        i = at + 1 + bot.name.length;
        continue;
      }
      const word = Mentions.ALL_WORDS.find((w: string) => fitsAt(rest, w));
      if (word !== undefined) {
        all = true;
        i = at + 1 + word.length;
        continue;
      }
      i = at + 1;
    }
    return new Called(all, found);
  }

  /** Who answers a message, mirroring the computer. */
  static whoAnswers(text: string, members: BotInfo[], mode: string, adminId: string | null): string[] {
    const called = Mentions.calledIds(text, members);
    if (called.all) {
      return members.map((b: BotInfo) => b.id);
    }
    if (called.ids.length > 0) {
      return called.ids;
    }
    const has = (id: string) => members.some((b: BotInfo) => b.id === id);
    if (mode === 'admin' && adminId !== null && has(adminId)) {
      return [adminId];
    }
    const ids = members.map((b: BotInfo) => b.id);
    if (adminId !== null && has(adminId)) {
      return [adminId].concat(ids.filter((x: string) => x !== adminId));
    }
    return ids;
  }
}

/** A member's reply being written right now in a group. */
export class LiveReply {
  attemptId: string;
  botId: string | null;
  name: string | null;
  text: string;
  endedAt: number;

  constructor(attemptId: string, botId: string | null, name: string | null, text: string = '', endedAt: number = 0) {
    this.attemptId = attemptId;
    this.botId = botId;
    this.name = name;
    this.text = text;
    this.endedAt = endedAt;
  }
}

const PASS = new RegExp('^\\s*[\\[【(（]\\s*pass\\s*[\\]】)）]|^\\s*pass\\s*[.。!！]?\\s*$', 'i');

function escapeRe(s: string): string {
  return s.replace(new RegExp('[.*+?^${}()|[\\]\\\\]', 'g'), '\\$&');
}

export class RoomStreams {
  static readonly KEEP_ENDED_MS: number = 8000;

  static isPass(text: string): boolean {
    return text.trim().length === 0 || PASS.test(text);
  }

  static shown(r: LiveReply): string | null {
    let t = r.text;
    if (r.name !== null && r.name.trim().length > 0) {
      t = t.replace(new RegExp('^\\s*\\**' + escapeRe(r.name) + '\\**\\s*[:：]\\s*'), '');
    }
    const tt = t.trim();
    if (tt.length === 0 || PASS.test(tt)) {
      return null;
    }
    const up = tt.toUpperCase();
    if (['[PASS]', '【PASS】', '(PASS)', '（PASS）'].some((p: string) => p.startsWith(up))) {
      return null;
    }
    return t;
  }

  static start(list: LiveReply[], attemptId: string, botId: string | null, name: string | null): LiveReply[] {
    return list.filter((x: LiveReply) => x.attemptId !== attemptId).concat([new LiveReply(attemptId, botId, name)]);
  }

  static delta(list: LiveReply[], attemptId: string, text: string, botId: string | null, name: string | null): LiveReply[] {
    if (!list.some((x: LiveReply) => x.attemptId === attemptId)) {
      return list.concat([new LiveReply(attemptId, botId, name, text)]);
    }
    return list.map((x: LiveReply) => x.attemptId === attemptId ?
      new LiveReply(x.attemptId, x.botId, x.name, x.text + text, x.endedAt) : x);
  }

  static end(list: LiveReply[], attemptId: string, now: number): LiveReply[] {
    const out: LiveReply[] = [];
    for (const x of list) {
      if (x.attemptId !== attemptId) {
        out.push(x);
      } else if (!RoomStreams.isPass(x.text)) {
        out.push(new LiveReply(x.attemptId, x.botId, x.name, x.text, now));
      }
    }
    return out;
  }

  static onMessage(list: LiveReply[], item: ChatItem): LiveReply[] {
    if (item.kind !== 'member' || item.botId === null) {
      return list;
    }
    const i = list.findIndex((x: LiveReply) => x.botId === item.botId && x.endedAt > 0);
    if (i >= 0) {
      const out = list.slice();
      out.splice(i, 1);
      return out;
    }
    const body = item.text ?? '';
    return list.filter((x: LiveReply) => !(x.botId === item.botId && x.text.trim().length > 0 &&
      body.indexOf(x.text.trim().substring(0, 40)) >= 0));
  }

  static prune(list: LiveReply[], now: number): LiveReply[] {
    return list.filter((x: LiveReply) => x.endedAt === 0 || now - x.endedAt < RoomStreams.KEEP_ENDED_MS);
  }
}

export class RoomRound {
  static endsWait(item: ChatItem): boolean {
    const ends = ['failed', 'approval', 'working', 'missed'];
    return item.kind === 'member' || (item.kind === 'event' && item.role !== null && ends.indexOf(item.role) >= 0);
  }

  static onEnd(messages: ChatItem[], replied: number, failed: number, now: number): ChatItem[] {
    if (replied === 0 && failed === 0) {
      const q = newItem('event', '这一轮没有成员发言', now);
      q.role = 'quiet';
      return messages.concat([q]);
    }
    return messages;
  }

  static dropUnshown(live: LiveReply[]): LiveReply[] {
    return live.filter((x: LiveReply) => RoomStreams.shown(x) !== null);
  }

  static since(messages: ChatItem[]): number {
    let max = -1;
    for (const m of messages) {
      if (!m.local && m.seq !== null && m.seq > max) {
        max = m.seq;
      }
    }
    return max;
  }
}

export class BotRules {
  static readonly DEFAULT_NAME: string = '新助手';

  static freshName(taken: string[], base: string = BotRules.DEFAULT_NAME): string {
    const set = taken.map((t: string) => t.toLowerCase());
    if (set.indexOf(base.toLowerCase()) < 0) {
      return base;
    }
    let i = 2;
    while (set.indexOf(base.toLowerCase() + ' ' + i) >= 0) {
      i++;
    }
    return base + ' ' + i;
  }

  static problem(name: string, taken: string[]): string | null {
    const t = name.trim();
    if (t.length === 0) {
      return '助手需要一个名字';
    }
    if (new RegExp('[@`\\n\\r]').test(t)) {
      return '助手名字里不能有 @、` 或换行';
    }
    if (Mentions.ALL_WORDS.indexOf(t.toLowerCase()) >= 0) {
      return '「' + t + '」在群里表示所有人，不能用作助手名字';
    }
    if (['user', 'you'].indexOf(t.toLowerCase()) >= 0) {
      return '「' + t + '」在对话里表示你本人，不能用作助手名字';
    }
    if (taken.some((x: string) => x.toLowerCase() === t.toLowerCase())) {
      return '已经有叫 ' + t + ' 的助手了';
    }
    return null;
  }

  static startModel(o: BotOptions): string {
    const d = o.models.find((m) => m.ref === o.defaultModel);
    if (d !== undefined) {
      return d.ref;
    }
    return o.models.length > 0 ? o.models[0].ref : '';
  }
}

export class RoomRules {
  static canCreate(d: NewRoomDraft, max: number = 12): boolean {
    if (d.admin === null) {
      return false;
    }
    const others = d.members.filter((m: string) => m !== d.admin);
    return others.length > 0 && 1 + others.length <= max;
  }

  static pickAdmin(d: NewRoomDraft, id: string): NewRoomDraft {
    const n = new NewRoomDraft();
    n.admin = id;
    n.members = d.members.filter((m: string) => m !== id);
    n.name = d.name;
    return n;
  }

  static toggle(d: NewRoomDraft, id: string): NewRoomDraft {
    if (id === d.admin) {
      return d;
    }
    const n = new NewRoomDraft();
    n.admin = d.admin;
    n.name = d.name;
    n.members = d.members.indexOf(id) >= 0 ? d.members.filter((m: string) => m !== id) : d.members.concat([id]);
    return n;
  }

  /** Members of a group as Bots, in the group's order. */
  static memberBots(memberIds: string[], bots: BotInfo[]): BotInfo[] {
    const out: BotInfo[] = [];
    for (const id of memberIds) {
      const b = bots.find((x: BotInfo) => x.id === id);
      if (b !== undefined) {
        out.push(b);
      }
    }
    return out;
  }
}
