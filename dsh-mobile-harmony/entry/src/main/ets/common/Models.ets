import { J, JObj } from './Json';

export interface BotInfo {
  id: string;
  name: string;
  role: string | null;
  sessionId: string | null;
  model: string | null;
  main: boolean;
  color: string | null;
  shape: string | null;
}

export interface RoomInfo {
  id: string;
  name: string;
  members: string[];
  memberNames: string[];
  admin: string | null;
  /** "everyone" | "admin" | null */
  mode: string | null;
  preview: string | null;
}

export interface TeamResponse {
  available: boolean;
  bots: BotInfo[];
  rooms: RoomInfo[];
  mainBotIds: string[];
}

export interface Attachment {
  type: string;
  id: string | null;
  name: string | null;
  bytes: number | null;
  mediaType: string | null;
  width: number | null;
  height: number | null;
  /** This phone's own copy before the computer has it. */
  localUri: string | null;
}

export interface ChatItem {
  kind: string;
  text: string | null;
  time: number | null;
  seq: number | null;
  role: string | null;
  botId: string | null;
  name: string | null;
  clientMessageId: string | null;
  local: boolean;
  cut: boolean;
  attachments: Attachment[];
}

export function newItem(kind: string, text: string | null, time: number | null): ChatItem {
  const i: ChatItem = {
    kind: kind, text: text, time: time, seq: null, role: null, botId: null, name: null,
    clientMessageId: null, local: false, cut: false, attachments: []
  };
  return i;
}

export function copyItem(a: ChatItem): ChatItem {
  const i: ChatItem = {
    kind: a.kind, text: a.text, time: a.time, seq: a.seq, role: a.role, botId: a.botId, name: a.name,
    clientMessageId: a.clientMessageId, local: a.local, cut: a.cut, attachments: a.attachments
  };
  return i;
}

export interface HistoryResponse {
  items: ChatItem[];
  nextCursor: string | null;
}

export interface SendResponse {
  ok: boolean;
  reason: string | null;
  duplicate: boolean;
}

export interface ChatEntry {
  seq?: number | null;
  kind: string;
  id: string;
  name: string;
  color: string | null;
  main: boolean;
  preview: string;
  lastAt: number | null;
  unread: number;
  running: boolean;
  members: string[];
}

export interface ApprovalInfo {
  id: string;
  botId: string | null;
  botName: string | null;
  roomId: string | null;
  roomName: string | null;
  action: string;
  reason: string;
  detail: string;
  answerable: boolean;
}

export interface ChatListResponse {
  available: boolean;
  chats: ChatEntry[];
  approvals: ApprovalInfo[];
}

export interface ModelOption {
  ref: string;
  name: string;
  providerName: string | null;
  needsKey: boolean;
}

export interface NamedOption {
  id: string;
  label: string;
}

export interface ColorOption {
  id: string;
  hex: string | null;
}

export interface FolderOption {
  path: string;
  title: string;
}

export interface BotOptions {
  models: ModelOption[];
  defaultModel: string | null;
  agentPresets: NamedOption[] | null;
  colors: ColorOption[];
  shapes: NamedOption[];
  teamFolder: string | null;
  folders: FolderOption[];
  canBrowse: boolean;
  nameMax: number;
  roleMax: number;
  roomNameMax: number;
  roomMaxMembers: number;
}

export interface FolderListing {
  path: string;
  entries: FolderOption[];
}

export interface EffortCatalog {
  efforts: EffortOption[];
  defaultEffort: string | null;
}

export interface BotProfile {
  id: string;
  name: string;
  role: string;
  instructions: string;
  shape: string | null;
  color: string | null;
  main: boolean;
  model: string | null;
  effort: string | null;
}

export interface SearchHit {
  kind: string;
  id: string;
  name: string;
  who: string | null;
  text: string;
  message: boolean;
}

export interface UploadResult {
  uploadId: string;
  type: string;
  name: string | null;
  bytes: number;
}

export interface EffortOption {
  id: string;
  name: string;
}

export class NewBotDraft {
  name: string = '';
  role: string = '';
  shape: string = '';
  color: string = '';
  model: string = '';
  effort: string = '';
  agentPreset: string = '';
  workDir: string = '';
  instructions: string = '';
  brief: string = '';
}

export class NewRoomDraft {
  admin: string | null = null;
  members: string[] = [];
  name: string = '';
}

export function parseBot(o: JObj): BotInfo {
  const b: BotInfo = {
    id: J.strOr(o, 'id', ''),
    name: J.strOr(o, 'name', J.strOr(o, 'id', '')),
    role: J.str(o, 'role'),
    sessionId: J.str(o, 'sessionId'),
    model: J.str(o, 'model'),
    main: J.bool(o, 'main', false),
    color: J.str(o, 'color'),
    shape: J.str(o, 'shape')
  };
  return b;
}

export function parseRoom(o: JObj): RoomInfo {
  const r: RoomInfo = {
    id: J.strOr(o, 'id', ''),
    name: J.strOr(o, 'name', ''),
    members: J.strs(o, 'members'),
    memberNames: J.strs(o, 'memberNames'),
    admin: J.str(o, 'admin'),
    mode: J.str(o, 'mode'),
    preview: J.str(o, 'preview')
  };
  return r;
}

export function parseTeam(o: JObj): TeamResponse {
  const t: TeamResponse = {
    available: J.bool(o, 'available', true),
    bots: J.objs(o, 'bots').map((x: JObj) => parseBot(x)),
    rooms: J.objs(o, 'rooms').map((x: JObj) => parseRoom(x)),
    mainBotIds: J.strs(o, 'mainBotIds')
  };
  return t;
}

export function parseAttachment(o: JObj): Attachment {
  const a: Attachment = {
    type: J.str(o, 'type') === 'image' ? 'image' : 'file',
    id: J.str(o, 'id'),
    name: J.str(o, 'name'),
    bytes: J.num(o, 'bytes'),
    mediaType: J.str(o, 'mediaType'),
    width: J.num(o, 'width'),
    height: J.num(o, 'height'),
    localUri: null
  };
  return a;
}

export function parseChatItem(o: JObj): ChatItem {
  const i: ChatItem = {
    kind: J.strOr(o, 'kind', 'event'),
    text: J.str(o, 'text'),
    time: J.num(o, 'time'),
    seq: J.num(o, 'seq'),
    role: J.str(o, 'role'),
    botId: J.str(o, 'botId'),
    name: J.str(o, 'name'),
    clientMessageId: J.str(o, 'clientMessageId'),
    local: false,
    cut: J.bool(o, 'cut', false),
    attachments: J.objs(o, 'attachments').map((x: JObj) => parseAttachment(x))
  };
  return i;
}

export function parseHistory(o: JObj): HistoryResponse {
  let next: string | null = null;
  const nc = o['nextCursor'];
  if (nc !== undefined && nc !== null) {
    next = typeof nc === 'string' ? (nc as string) : JSON.stringify(nc);
    if (next.length === 0 || next === 'null') {
      next = null;
    }
  }
  const h: HistoryResponse = { items: J.objs(o, 'items').map((x: JObj) => parseChatItem(x)), nextCursor: next };
  return h;
}

export function parseChatEntry(o: JObj): ChatEntry {
  const last = J.num(o, 'lastAt');
  const e: ChatEntry = {
    seq: J.num(o, 'seq'),
    kind: J.str(o, 'kind') === 'room' ? 'room' : 'bot',
    id: J.strOr(o, 'id', ''),
    name: J.strOr(o, 'name', J.strOr(o, 'id', '')),
    color: J.str(o, 'color'),
    main: J.bool(o, 'main', false),
    preview: J.strOr(o, 'preview', ''),
    lastAt: last !== null && last > 0 ? last : null,
    unread: Math.max(0, J.numOr(o, 'unread', 0)),
    running: J.bool(o, 'running', false),
    members: J.strs(o, 'members')
  };
  return e;
}

export function parseApproval(o: JObj): ApprovalInfo {
  const a: ApprovalInfo = {
    id: J.strOr(o, 'id', ''),
    botId: J.str(o, 'botId'),
    botName: J.str(o, 'botName'),
    roomId: J.str(o, 'roomId'),
    roomName: J.str(o, 'roomName'),
    action: J.strOr(o, 'action', '做一个需要你同意的操作'),
    reason: J.strOr(o, 'reason', ''),
    detail: J.strOr(o, 'detail', ''),
    answerable: J.bool(o, 'answerable', false)
  };
  return a;
}

export function parseChatList(o: JObj): ChatListResponse {
  const r: ChatListResponse = {
    available: J.bool(o, 'available', true),
    chats: J.objs(o, 'chats').map((x: JObj) => parseChatEntry(x)),
    approvals: J.objs(o, 'approvals').map((x: JObj) => parseApproval(x))
  };
  return r;
}

export function parseBotOptions(o: JObj): BotOptions {
  const folders = J.obj(o, 'folders');
  const empty: JObj = {};
  const limits = J.obj(o, 'limits') ?? empty;
  const presets: NamedOption[] | null = J.has(o, 'agentPresets') ? J.objs(o, 'agentPresets').map((x: JObj) => {
    const n: NamedOption = { id: J.strOr(x, 'value', ''), label: J.strOr(x, 'name', J.strOr(x, 'value', '')) };
    return n;
  }) : null;
  const b: BotOptions = {
    models: J.objs(o, 'models').map((x: JObj) => {
      const m: ModelOption = {
        ref: J.strOr(x, 'ref', ''), name: J.strOr(x, 'name', J.strOr(x, 'ref', '')),
        providerName: J.str(x, 'providerName'), needsKey: J.bool(x, 'needsKey', false)
      };
      return m;
    }),
    defaultModel: J.str(o, 'defaultModel'),
    agentPresets: presets,
    colors: J.objs(o, 'colors').map((x: JObj) => {
      const c: ColorOption = { id: J.strOr(x, 'id', ''), hex: J.str(x, 'hex') };
      return c;
    }),
    shapes: J.objs(o, 'shapes').map((x: JObj) => {
      const n: NamedOption = { id: J.strOr(x, 'id', ''), label: J.strOr(x, 'label', J.strOr(x, 'id', '')) };
      return n;
    }),
    teamFolder: folders === null ? null : J.str(folders, 'team'),
    folders: folders === null ? [] : J.objs(folders, 'folders').map((x: JObj) => {
      const f: FolderOption = { path: J.strOr(x, 'path', ''), title: J.strOr(x, 'title', J.strOr(x, 'path', '')) };
      return f;
    }),
    canBrowse: folders !== null && J.str(folders, 'picker') === 'browse',
    nameMax: J.numOr(limits, 'name', 40),
    roleMax: J.numOr(limits, 'role', 24),
    roomNameMax: J.numOr(limits, 'roomName', 60),
    roomMaxMembers: J.numOr(limits, 'roomMaxMembers', 12)
  };
  return b;
}

export function parseFolderListing(o: JObj, path: string): FolderListing {
  const entries: FolderOption[] = [];
  for (const e of J.objs(o, 'entries')) {
    const p = J.str(e, 'path');
    if (p !== null) {
      const f: FolderOption = { path: p, title: J.strOr(e, 'name', p) };
      entries.push(f);
    }
  }
  const l: FolderListing = { path: J.strOr(o, 'path', path), entries: entries };
  return l;
}

export function parseProfile(o: JObj): BotProfile {
  const p: BotProfile = {
    id: J.strOr(o, 'id', ''),
    name: J.strOr(o, 'name', ''),
    role: J.strOr(o, 'role', ''),
    instructions: J.strOr(o, 'instructions', ''),
    shape: J.str(o, 'shape'),
    color: J.str(o, 'color'),
    main: J.bool(o, 'main', false),
    model: J.str(o, 'model'),
    effort: J.str(o, 'effort')
  };
  return p;
}

export function parseHits(o: JObj): SearchHit[] {
  return J.objs(o, 'hits').map((x: JObj) => {
    const h: SearchHit = {
      kind: J.strOr(x, 'kind', ''), id: J.strOr(x, 'id', ''), name: J.strOr(x, 'name', ''),
      who: J.str(x, 'who'), text: J.strOr(x, 'text', ''), message: true
    };
    return h;
  });
}

export function parseUpload(o: JObj): UploadResult {
  const u: UploadResult = {
    uploadId: J.strOr(o, 'uploadId', ''), type: J.strOr(o, 'type', 'file'),
    name: J.str(o, 'name'), bytes: J.numOr(o, 'bytes', 0)
  };
  return u;
}

/** Body for creating a Bot: blank fields left out so the computer uses its defaults. */
export function botDraftBody(d: NewBotDraft): JObj {
  const o: JObj = {};
  const put = (k: string, v: string) => {
    if (v.trim().length > 0) {
      o[k] = v.trim();
    }
  };
  put('name', d.name);
  put('role', d.role);
  put('color', d.color);
  put('model', d.model);
  put('effort', d.effort);
  put('agentPreset', d.agentPreset);
  put('workDir', d.workDir);
  put('instructions', d.instructions);
  put('brief', d.brief);
  if (d.shape.length > 0) {
    const a: JObj = {};
    a['shape'] = d.shape;
    o['avatar'] = a;
  }
  return o;
}

export function roomDraftBody(d: NewRoomDraft): JObj {
  const o: JObj = {};
  o['members'] = d.members;
  if (d.admin !== null) {
    o['admin'] = d.admin;
  }
  if (d.name.trim().length > 0) {
    o['name'] = d.name.trim();
  }
  return o;
}
