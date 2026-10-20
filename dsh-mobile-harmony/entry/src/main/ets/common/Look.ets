import { CHARACTERS, CharShape, INKS } from './Characters';
import { ChatEntry, ChatItem } from './Models';
import { Look } from './ChatLogic';

export const DEFAULT_SHAPES: string[] = ['star', 'moon', 'gumdrop', 'peanut', 'bunny', 'kitty', 'acorn'];
export const FALLBACK_SHAPE = 'gumdrop';
export const PICKER_COLORS: string[] = ['black', 'brown', 'red', 'orange', 'yellow', 'green', 'cyan', 'blue', 'violet',
  'magenta', 'gray', 'aurora'];
export const DEFAULT_COLORS: string[] = ['red', 'orange', 'yellow', 'green', 'cyan', 'blue', 'violet', 'magenta'];
const LEGACY: Record<string, string> = {
  'kimi': 'charcoal', 'zai': 'graphite', 'minimax': 'rose', 'stepfun': 'sky', 'mimo': 'tangerine', 'qwen': 'cobalt'
};
export const SHAPE_LABELS: Record<string, string> = {
  'star': '星星', 'moon': '月亮', 'gumdrop': '软糖', 'peanut': '花生', 'bunny': '兔子', 'kitty': '小猫', 'acorn': '橡果',
  'whale': '鲸鱼', 'wave': '浪花', 'squircle': '圆方', 'zed': '小牛', 'hex': '花朵', 'saucer': '飞碟', 'beacon': '灯塔',
  'plus': '十字', 'magnet': '磁铁', 'pebbles': '石子', 'cushion': '靠垫', 'ring': '圆环', 'gem': '宝石'
};
export const COLOR_LABELS: Record<string, string> = {
  'black': '黑', 'brown': '棕', 'red': '红', 'orange': '橙', 'yellow': '黄', 'green': '绿', 'cyan': '青', 'blue': '蓝',
  'violet': '紫', 'magenta': '品红', 'gray': '灰', 'aurora': '极光'
};
const HEX = new RegExp('^#[0-9a-fA-F]{6}$');

export class LookPair {
  shape: string;
  color: string;

  constructor(shape: string, color: string) {
    this.shape = shape;
    this.color = color;
  }
}

/** Same avatar rules as the computer, so a Bot looks the same everywhere. */
export class Looks {
  static fnv1a(text: string): number {
    let v = -2128831035;
    for (let i = 0; i < text.length; i++) {
      v = Math.imul(v ^ text.charCodeAt(i), 16777619);
    }
    return v;
  }

  static defaultShape(id: string): string {
    let v = Looks.fnv1a(id);
    v = Math.imul(v ^ (v >>> 16), 73244475);
    v = Math.imul(v ^ (v >>> 13), -1028477387);
    const u = (v ^ (v >>> 16)) >>> 0;
    return DEFAULT_SHAPES[u % DEFAULT_SHAPES.length];
  }

  static defaultColor(id: string): string {
    const t = (Looks.fnv1a(id) + 1831565813) | 0;
    let n = Math.imul(t ^ (t >>> 15), 1 | t);
    n = ((n + Math.imul(n ^ (n >>> 7), 61 | n)) | 0) ^ n;
    const u = (n ^ (n >>> 14)) >>> 0;
    return DEFAULT_COLORS[Math.floor((u / 4294967296) * DEFAULT_COLORS.length)];
  }

  static shapeOf(id: string): CharShape | null {
    const c = CHARACTERS.find((x: CharShape) => x.id === id);
    return c === undefined ? null : c;
  }

  static isColor(c: string | null): boolean {
    return c !== null && (HEX.test(c) || INKS[c] !== undefined || LEGACY[c] !== undefined);
  }

  static lookOf(id: string | null, shape: string | null, color: string | null): LookPair {
    const s = shape !== null && Looks.shapeOf(shape) !== null ? shape :
      (id !== null && id.length > 0 ? Looks.defaultShape(id) : FALLBACK_SHAPE);
    const c = Looks.isColor(color) ? (color as string) : (id !== null && id.length > 0 ? Looks.defaultColor(id) : 'black');
    return new LookPair(s, c);
  }

  /** A color id or #rrggbb as a hex color for the light or dark look. */
  static ink(color: string, dark: boolean): string {
    if (HEX.test(color)) {
      return color;
    }
    const pair = INKS[color] ?? INKS[LEGACY[color] ?? ''];
    if (pair === undefined) {
      return dark ? '#FFFFFF' : '#000000';
    }
    return dark ? pair[1] : pair[0];
  }

  static readonly DIVIDER_GAP_MS: number = 15 * 60000;

  /** Index -> label 「今天 上午8:00」 before the first message and after a 15-minute gap. */
  static dividers(times: (number | null)[], now: number): Map<number, string> {
    const out = new Map<number, string>();
    let prev = -1;
    for (let i = 0; i < times.length; i++) {
      const t = times[i];
      if (t === null || t <= 0) {
        continue;
      }
      if (prev < 0 || t - prev >= Looks.DIVIDER_GAP_MS) {
        out.set(i, Look.day(t, now) + ' ' + Look.clock(t));
      }
      prev = t;
    }
    return out;
  }

  static plainPreview(text: string): string {
    return text
      .replace(new RegExp('!?\\[([^\\]]*)]\\([^)]*\\)?', 'g'), '$1')
      .replace(new RegExp('```[a-zA-Z0-9]*', 'g'), ' ')
      .replace(new RegExp('#{1,6}\\s+', 'g'), '')
      .replace(new RegExp('(^|\\s)([-*+]|\\d+\\.)\\s+', 'g'), '$1')
      .replace(new RegExp('(^|\\s)>\\s?', 'g'), '$1')
      .replace(new RegExp('\\*\\*|__|`|~~', 'g'), '')
      .replace(new RegExp('\\s*\\|\\s*', 'g'), ' ')
      .replace(new RegExp('\\s+', 'g'), ' ')
      .trim();
  }

  static snippet(text: string, max: number = 40): string {
    const p = Looks.plainPreview(text);
    return p.length > max ? p.substring(0, max) + '…' : p;
  }

  static quoted(name: string | null, snippet: string, reply: string): string {
    const who = name !== null && name.trim().length > 0 ? name + '：' : '';
    return '> ' + who + snippet + '\n\n' + reply;
  }

  /** [quote, rest]; quote is '' when there is none. */
  static splitQuote(text: string): string[] {
    if (!text.startsWith('> ')) {
      return ['', text];
    }
    const end = text.indexOf('\n\n');
    if (end < 0) {
      return ['', text];
    }
    return [text.substring(2, end).trim(), text.substring(end + 2)];
  }

  static workingLabel(names: string[], inRoom: boolean): string {
    const uniq: string[] = [];
    for (const n of names) {
      if (n.trim().length > 0 && uniq.indexOf(n) < 0) {
        uniq.push(n);
      }
    }
    const who = uniq.length > 0 ? uniq.join('、') : '助手';
    return inRoom ? who + ' 正在输入…' : who + ' 正在工作';
  }

  static inputHint(name: string, inRoom: boolean): string {
    if (inRoom) {
      return name.trim().length === 0 ? '在群里发消息' : '给 ' + name + ' 发消息';
    }
    return name.trim().length === 0 ? '向助手提问' : '向 ' + name + ' 提问';
  }
}

export class ExRun {
  start: number;
  count: number;
  bots: number;

  constructor(start: number, count: number, bots: number) {
    this.start = start;
    this.count = count;
    this.bots = bots;
  }
}

/** Messages the Bots sent each other, folded into one line in a chat. */
export class Exchanges {
  static isExchange(i: ChatItem): boolean {
    return i.kind === 'event' && i.role !== null && ['request', 'reply', 'report'].indexOf(i.role) >= 0;
  }

  static runs(items: ChatItem[]): ExRun[] {
    const out: ExRun[] = [];
    let i = 0;
    while (i < items.length) {
      if (!Exchanges.isExchange(items[i])) {
        i++;
        continue;
      }
      const start = i;
      const who: string[] = [];
      while (i < items.length && Exchanges.isExchange(items[i])) {
        const k = items[i].botId ?? items[i].name ?? '';
        if (who.indexOf(k) < 0) {
          who.push(k);
        }
        i++;
      }
      out.push(new ExRun(start, i - start, who.length));
    }
    return out;
  }

  static words(r: ExRun): string {
    return r.count + ' 条消息，与 ' + r.bots + ' 个助手';
  }
}

/** What this phone remembers about the list on its own. */
export class ListPrefs {
  pinned: string[] = [];
  hidden: Map<string, number> = new Map();
  unread: string[] = [];
  theme: string = 'system';

  static key(c: ChatEntry): string {
    return c.kind + ':' + c.id;
  }

  copy(): ListPrefs {
    const p = new ListPrefs();
    p.pinned = this.pinned.slice();
    p.hidden = new Map(this.hidden);
    p.unread = this.unread.slice();
    p.theme = this.theme;
    return p;
  }

  private visible(c: ChatEntry): boolean {
    const at = this.hidden.get(ListPrefs.key(c));
    return at === undefined || (c.lastAt ?? 0) > at;
  }

  apply(chats: ChatEntry[]): ChatEntry[] {
    const vis = chats.filter((c: ChatEntry) => this.visible(c));
    const pin = vis.filter((c: ChatEntry) => this.pinned.indexOf(ListPrefs.key(c)) >= 0);
    const rest = vis.filter((c: ChatEntry) => this.pinned.indexOf(ListPrefs.key(c)) < 0);
    return pin.concat(rest);
  }

  hiddenCount(chats: ChatEntry[]): number {
    return chats.filter((c: ChatEntry) => !this.visible(c)).length;
  }

  isPinned(c: ChatEntry): boolean {
    return this.pinned.indexOf(ListPrefs.key(c)) >= 0;
  }

  markedUnread(c: ChatEntry): boolean {
    return this.unread.indexOf(ListPrefs.key(c)) >= 0;
  }

  togglePin(c: ChatEntry): ListPrefs {
    const p = this.copy();
    const k = ListPrefs.key(c);
    p.pinned = p.pinned.indexOf(k) >= 0 ? p.pinned.filter((x: string) => x !== k) : p.pinned.concat([k]);
    return p;
  }

  hide(c: ChatEntry, now: number): ListPrefs {
    const p = this.copy();
    const k = ListPrefs.key(c);
    p.hidden.set(k, Math.max(now, c.lastAt ?? 0));
    p.pinned = p.pinned.filter((x: string) => x !== k);
    return p;
  }

  unhideAll(): ListPrefs {
    const p = this.copy();
    p.hidden = new Map();
    return p;
  }

  toggleUnread(c: ChatEntry): ListPrefs {
    const p = this.copy();
    const k = ListPrefs.key(c);
    p.unread = p.unread.indexOf(k) >= 0 ? p.unread.filter((x: string) => x !== k) : p.unread.concat([k]);
    return p;
  }

  opened(kind: string, id: string): ListPrefs {
    const p = this.copy();
    p.unread = p.unread.filter((x: string) => x !== kind + ':' + id);
    return p;
  }

  withTheme(t: string): ListPrefs {
    const p = this.copy();
    p.theme = ListPrefs.normTheme(t);
    return p;
  }

  static normTheme(t: string): string {
    return t === 'light' || t === 'dark' ? t : 'system';
  }

  encode(): string {
    const h: string[] = [];
    this.hidden.forEach((v: number, k: string) => {
      h.push(v + '|' + k);
    });
    return [this.pinned.join(','), h.join(','), this.unread.join(','), this.theme].join('\n');
  }

  static decode(s: string): ListPrefs {
    const p = new ListPrefs();
    const parts = s.split('\n');
    const list = (i: number): string[] => i < parts.length && parts[i].length > 0 ? parts[i].split(',') : [];
    p.pinned = list(0);
    for (const e of list(1)) {
      const i = e.indexOf('|');
      const at = i > 0 ? Number(e.substring(0, i)) : NaN;
      if (!isNaN(at)) {
        p.hidden.set(e.substring(i + 1), at);
      }
    }
    p.unread = list(2);
    p.theme = ListPrefs.normTheme(parts.length > 3 ? parts[3] : '');
    return p;
  }
}

/** What a group chat is called on the phone: its own name, or its members with "You" shown as 我. */
export function roomTitle(name: string, memberNames: string[]): string {
  const fix = (parts: string[]): string => parts.map((x: string) => x.trim())
    .filter((x: string) => x.length > 0)
    .map((x: string) => x === 'You' || x === 'you' || x === 'User' ? '我' : x)
    .join('、');
  const n = name.trim();
  if (n.length === 0) {
    const t = fix(memberNames);
    return t.length > 0 ? t : '群聊';
  }
  const parts = n.split(new RegExp('\\s*[,，、]\\s*'));
  if (parts.length > 1 && parts.some((x: string) => x.trim() === 'You' || x.trim() === 'you')) {
    return fix(parts);
  }
  return n;
}
