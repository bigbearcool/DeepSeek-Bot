import { ApprovalInfo, Attachment, BotInfo, ChatEntry, ChatListResponse, SearchHit } from './Models';
import { Look } from './ChatLogic';

export class AttachLogic {
  static readonly MAX_BYTES: number = 25 * 1024 * 1024;
  static readonly MAX_PER_MESSAGE: number = 9;

  static size(bytes: number | null): string {
    if (bytes === null || bytes < 0) {
      return '';
    }
    if (bytes < 1024) {
      return bytes + ' B';
    }
    if (bytes < 1024 * 1024) {
      return Math.round(bytes / 1024) + ' KB';
    }
    return (bytes / 1048576).toFixed(1) + ' MB';
  }

  static ext(name: string | null): string {
    const n = name ?? '';
    const i = n.lastIndexOf('.');
    if (i < 0 || i === n.length - 1) {
      return '文件';
    }
    return n.substring(i + 1).toUpperCase().substring(0, 5);
  }

  static isImageName(name: string): boolean {
    return new RegExp('\\.(png|jpe?g|gif|webp|heic|heif|bmp)$', 'i').test(name);
  }

  /** Text shown for a message that only carries pictures or files. */
  static label(a: Attachment[]): string {
    if (a.length === 0) {
      return '';
    }
    const imgs = a.filter((x: Attachment) => x.type === 'image').length;
    if (imgs === a.length) {
      return imgs === 1 ? '[图片]' : '[' + imgs + ' 张图片]';
    }
    return '[' + a.length + ' 个文件]';
  }

  static tooBig(bytes: number): boolean {
    return bytes > AttachLogic.MAX_BYTES;
  }
}

export class ChatPrefs {
  notify: boolean = true;
  muted: string[] = [];

  static decode(s: string): ChatPrefs {
    const p = new ChatPrefs();
    if (s.length === 0) {
      return p;
    }
    const parts = s.split('|');
    p.notify = parts[0] !== '0';
    p.muted = parts.length > 1 && parts[1].length > 0 ? parts[1].split(',') : [];
    return p;
  }

  encode(): string {
    return (this.notify ? '1' : '0') + '|' + this.muted.join(',');
  }

  withMuted(key: string, on: boolean): ChatPrefs {
    const p = new ChatPrefs();
    p.notify = this.notify;
    p.muted = this.muted.filter((k: string) => k !== key);
    if (on) {
      p.muted.push(key);
    }
    return p;
  }

  withNotify(on: boolean): ChatPrefs {
    const p = new ChatPrefs();
    p.notify = on;
    p.muted = this.muted.slice();
    return p;
  }
}

export class Alert {
  kind: string;
  id: string;
  name: string;
  text: string;
  count: number;
  approvalId: string;

  constructor(kind: string, id: string, name: string, text: string, count: number, approvalId: string = '') {
    this.kind = kind;
    this.id = id;
    this.name = name;
    this.text = text;
    this.count = count;
    this.approvalId = approvalId;
  }

  get tag(): string {
    return this.approvalId.length > 0 ? 'ap:' + this.approvalId : this.kind + ':' + this.id;
  }
}

export class Seen {
  unread: Map<string, number> = new Map();
  approvals: string[] = [];
}

export class NotifyLogic {
  static key(kind: string, id: string): string {
    return kind + ':' + id;
  }

  static seen(now: ChatListResponse): Seen {
    const s = new Seen();
    for (const c of now.chats) {
      s.unread.set(NotifyLogic.key(c.kind, c.id), c.unread);
    }
    s.approvals = now.approvals.map((a: ApprovalInfo) => a.id);
    return s;
  }

  /** New messages and approvals since the last look; the first look only remembers. */
  static alerts(prev: Seen | null, now: ChatListResponse, prefs: ChatPrefs, openKey: string): Alert[] {
    if (prev === null || !prefs.notify) {
      return [];
    }
    const out: Alert[] = [];
    for (const c of now.chats) {
      const k = NotifyLogic.key(c.kind, c.id);
      const before = prev.unread.get(k) ?? 0;
      if (c.unread <= before || prefs.muted.indexOf(k) >= 0 || k === openKey) {
        continue;
      }
      const t = Look.plainPreview(c.preview);
      out.push(new Alert(c.kind, c.id, c.name, t.length > 0 ? t : '有新消息', c.unread));
    }
    for (const a of now.approvals) {
      if (prev.approvals.indexOf(a.id) >= 0) {
        continue;
      }
      const kind = a.roomId !== null ? 'room' : 'bot';
      const id = a.roomId ?? a.botId;
      if (id === null || prefs.muted.indexOf(NotifyLogic.key(kind, id)) >= 0) {
        continue;
      }
      const who = a.botName ?? '助手';
      out.push(new Alert(kind, id, a.roomName ?? who, who + ' 在等你确认：' + a.action, 1, a.id));
    }
    return out;
  }

  static countLine(count: number): string {
    return count > 1 ? count + ' 条新消息' : '';
  }
}

export class ProfileLogic {
  static addable(bots: BotInfo[], members: string[], query: string): BotInfo[] {
    const q = query.trim().toLowerCase();
    return bots.filter((b: BotInfo) => members.indexOf(b.id) < 0 && (q.length === 0 || b.name.toLowerCase().indexOf(q) >= 0));
  }

  static results(chats: ChatEntry[], bots: BotInfo[], query: string, messages: SearchHit[]): SearchHit[] {
    const q = query.trim().toLowerCase();
    const role = (c: ChatEntry): string => {
      if (c.kind === 'room') {
        return '';
      }
      const b = bots.find((x: BotInfo) => x.id === c.id);
      return b === undefined ? '' : (b.role ?? '');
    };
    const named = chats.filter((c: ChatEntry) => q.length === 0 || c.name.toLowerCase().indexOf(q) >= 0)
      .map((c: ChatEntry) => {
        const h: SearchHit = { kind: c.kind, id: c.id, name: c.name, who: null, text: role(c), message: false };
        return h;
      });
    return q.length === 0 ? named : named.concat(messages);
  }

  static tag(h: SearchHit): string {
    if (h.message) {
      return '消息';
    }
    return h.kind === 'room' ? '群聊' : '助手';
  }

  /** 局域网 when the computer is on this network, else 外网. */
  static network(baseUrl: string): string {
    let host = baseUrl;
    const s = host.indexOf('://');
    if (s >= 0) {
      host = host.substring(s + 3);
    }
    host = host.split('/')[0].split(':')[0].toLowerCase();
    const p = host.split('.').map((x: string) => Number(x));
    const ip = p.length === 4 && p.every((n: number) => !isNaN(n));
    const priv = host === 'localhost' || host.endsWith('.local') || host.endsWith('.lan') ||
      (ip && (p[0] === 10 || p[0] === 127 || (p[0] === 192 && p[1] === 168) ||
        (p[0] === 172 && p[1] >= 16 && p[1] <= 31) || (p[0] === 169 && p[1] === 254)));
    return priv ? '局域网' : '外网';
  }
}
