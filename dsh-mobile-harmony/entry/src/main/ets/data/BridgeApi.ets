import { hilog } from '@kit.PerformanceAnalysisKit';
import { http } from '@kit.NetworkKit';
import { J, JObj } from '../common/Json';
import { BridgeError } from '../common/Errors';
import {
  BotInfo, BotOptions, BotProfile, ChatListResponse, EffortOption, FolderListing, HistoryResponse, NewBotDraft,
  NewRoomDraft, RoomInfo, SearchHit, SendResponse, TeamResponse, UploadResult, botDraftBody, parseBot, parseBotOptions,
  parseChatItem, parseChatList, parseFolderListing, parseHistory, parseHits, parseProfile, parseRoom, parseTeam,
  parseUpload, ChatItem
} from '../common/Models';

function enc(v: string): string {
  return encodeURIComponent(v);
}

interface RawResp {
  code: number;
  text: string;
}

export interface PairResult {
  deviceId: string;
  refreshToken: string;
}

export interface AccessResult {
  accessToken: string;
  expiresAt: number;
}

function errorOf(code: number, text: string): BridgeError {
  const o = J.parse(text);
  const e = J.obj(o, 'error');
  const msg = (e === null ? null : J.str(e, 'message')) ?? J.str(o, 'message') ?? ('HTTP ' + code);
  const zh = e === null ? '' : (J.str(e, 'zh') ?? '');
  return new BridgeError(msg, code, zh);
}

/** Talks to dsh-mobile-bridge `/api/mobile-bridge/v1`. */
export class BridgeApi {
  baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  private async raw(method: http.RequestMethod, path: string, token: string, body: string | ArrayBuffer | null,
    contentType: string): Promise<RawResp> {
    const req = http.createHttp();
    const header: Record<string, string> = {};
    if (token.length > 0) {
      header['Authorization'] = 'Bearer ' + token;
    }
    if (body !== null) {
      header['Content-Type'] = contentType;
    }
    const opts: http.HttpRequestOptions = {
      method: method,
      header: header,
      expectDataType: http.HttpDataType.STRING,
      connectTimeout: 15000,
      readTimeout: 60000
    };
    if (body !== null) {
      opts.extraData = body;
    }
    try {
      const resp = await req.request(this.baseUrl + path, opts);
      const text = typeof resp.result === 'string' ? resp.result as string : '';
      const r: RawResp = { code: resp.responseCode, text: text };
      if (r.code >= 400) {
        // Path without query and the server's reason; never the token.
        hilog.warn(0x0d50, 'dshBridge', '%{public}s %{public}s -> %{public}d %{public}s', method, path.split('?')[0],
          r.code, text.substring(0, 200));
      }
      return r;
    } catch (e) {
      throw new BridgeError('network', 0);
    } finally {
      req.destroy();
    }
  }

  private async getJson(path: string, token: string = ''): Promise<JObj> {
    const r = await this.raw(http.RequestMethod.GET, path, token, null, '');
    if (r.code < 200 || r.code > 299) {
      throw errorOf(r.code, r.text);
    }
    return J.parse(r.text);
  }

  private async postJson(path: string, body: JObj, token: string = ''): Promise<JObj> {
    const r = await this.raw(http.RequestMethod.POST, path, token, JSON.stringify(body), 'application/json; charset=utf-8');
    if (r.code < 200 || r.code > 299) {
      throw errorOf(r.code, r.text);
    }
    return J.parse(r.text);
  }

  async health(): Promise<boolean> {
    return J.bool(await this.getJson('/health'), 'ok', false);
  }

  async pairComplete(code: string, deviceId: string, label: string): Promise<PairResult> {
    const b: JObj = {};
    b['code'] = code;
    b['deviceId'] = deviceId;
    b['label'] = label;
    const o = await this.postJson('/auth/pair/complete', b);
    const rt = J.str(o, 'refreshToken');
    if (rt === null) {
      throw new BridgeError('no token', 500);
    }
    const p: PairResult = { deviceId: J.strOr(o, 'deviceId', deviceId), refreshToken: rt };
    return p;
  }

  async issueAccess(deviceId: string, refreshToken: string): Promise<AccessResult> {
    const b: JObj = {};
    b['deviceId'] = deviceId;
    b['refreshToken'] = refreshToken;
    const o = await this.postJson('/auth/token', b);
    const at = J.str(o, 'accessToken');
    if (at === null) {
      throw new BridgeError('no token', 500);
    }
    const a: AccessResult = { accessToken: at, expiresAt: J.numOr(o, 'expiresAt', 0) };
    return a;
  }

  async team(token: string): Promise<TeamResponse> {
    return parseTeam(await this.getJson('/team', token));
  }

  async chatList(token: string): Promise<ChatListResponse> {
    return parseChatList(await this.getJson('/chats/list', token));
  }

  async history(token: string, botId: string, cursor: string | null): Promise<HistoryResponse> {
    let q = '/chats/history?botId=' + enc(botId);
    if (cursor !== null && cursor.length > 0) {
      q += '&cursor=' + enc(cursor);
    }
    return parseHistory(await this.getJson(q, token));
  }

  async roomHistory(token: string, roomId: string): Promise<ChatItem[]> {
    const o = await this.getJson('/rooms/history?roomId=' + enc(roomId), token);
    return J.objs(o, 'items').map((x: JObj) => parseChatItem(x));
  }

  async send(token: string, botId: string, text: string, clientMessageId: string, attachments: string[]): Promise<SendResponse> {
    const b: JObj = {};
    b['botId'] = botId;
    b['text'] = text;
    b['clientMessageId'] = clientMessageId;
    if (attachments.length > 0) {
      b['attachments'] = attachments;
    }
    const r = await this.raw(http.RequestMethod.POST, '/chats/send', token, JSON.stringify(b), 'application/json; charset=utf-8');
    if (r.code === 401) {
      throw errorOf(r.code, r.text);
    }
    const o = J.parse(r.text);
    const e = J.obj(o, 'error');
    const s: SendResponse = {
      ok: J.bool(o, 'ok', r.code >= 200 && r.code <= 299),
      reason: J.str(o, 'reason') ?? (e === null ? null : (J.str(e, 'zh') ?? J.str(e, 'message'))),
      duplicate: J.bool(o, 'duplicate', false)
    };
    return s;
  }

  async roomSend(token: string, roomId: string, text: string, clientMessageId: string, attachments: string[]): Promise<SendResponse> {
    const b: JObj = {};
    b['roomId'] = roomId;
    b['text'] = text;
    b['clientMessageId'] = clientMessageId;
    if (attachments.length > 0) {
      b['attachments'] = attachments;
    }
    const o = await this.postJson('/rooms/send', b, token);
    const s: SendResponse = { ok: J.bool(o, 'ok', true), reason: null, duplicate: J.bool(o, 'duplicate', false) };
    return s;
  }

  async markRead(token: string, kind: string, id: string, seq: number): Promise<void> {
    const b: JObj = {};
    b['kind'] = kind;
    b['id'] = id;
    b['seq'] = seq;
    await this.postJson('/chats/read', b, token);
  }

  async stopBot(token: string, botId: string): Promise<boolean> {
    const b: JObj = {};
    b['botId'] = botId;
    return J.bool(await this.postJson('/chats/stop', b, token), 'stopped', false);
  }

  async stopRoom(token: string, roomId: string): Promise<number> {
    const b: JObj = {};
    b['roomId'] = roomId;
    return J.numOr(await this.postJson('/rooms/stop', b, token), 'stopped', 0);
  }

  /** choice: "allow" | "reject" */
  async answerApproval(token: string, id: string, choice: string): Promise<void> {
    const b: JObj = {};
    b['id'] = id;
    b['choice'] = choice;
    await this.postJson('/approvals/answer', b, token);
  }

  async botOptions(token: string): Promise<BotOptions> {
    return parseBotOptions(await this.getJson('/bots/options', token));
  }

  async efforts(token: string, ref: string): Promise<EffortOption[]> {
    const o = await this.getJson('/bots/efforts?ref=' + enc(ref), token);
    return J.objs(o, 'efforts').map((x: JObj) => {
      const e: EffortOption = { id: J.strOr(x, 'id', ''), name: J.strOr(x, 'name', J.strOr(x, 'id', '')) };
      return e;
    });
  }

  async listFolder(token: string, path: string): Promise<FolderListing> {
    return parseFolderListing(await this.getJson('/folders?path=' + enc(path), token), path);
  }

  async createBot(token: string, d: NewBotDraft): Promise<BotInfo> {
    const o = await this.postJson('/bots/create', botDraftBody(d), token);
    const b = J.obj(o, 'bot');
    if (b === null) {
      throw new BridgeError('no bot', 500);
    }
    return parseBot(b);
  }

  async createRoom(token: string, d: NewRoomDraft): Promise<RoomInfo> {
    const body: JObj = {};
    body['members'] = d.members;
    if (d.admin !== null) {
      body['admin'] = d.admin;
    }
    if (d.name.trim().length > 0) {
      body['name'] = d.name.trim();
    }
    const o = await this.postJson('/rooms/create', body, token);
    const r = J.obj(o, 'room');
    if (r === null) {
      throw new BridgeError('no room', 500);
    }
    return parseRoom(r);
  }

  async botInfo(token: string, id: string): Promise<BotProfile> {
    const o = await this.getJson('/bots/info?id=' + enc(id), token);
    const b = J.obj(o, 'bot');
    if (b === null) {
      throw new BridgeError('no bot', 404);
    }
    return parseProfile(b);
  }

  async updateBot(token: string, body: JObj): Promise<BotProfile> {
    const o = await this.postJson('/bots/update', body, token);
    const b = J.obj(o, 'bot');
    if (b === null) {
      throw new BridgeError('no bot', 500);
    }
    return parseProfile(b);
  }

  async updateRoom(token: string, body: JObj): Promise<RoomInfo> {
    const o = await this.postJson('/rooms/update', body, token);
    const r = J.obj(o, 'room');
    if (r === null) {
      throw new BridgeError('no room', 500);
    }
    return parseRoom(r);
  }

  async search(token: string, q: string): Promise<SearchHit[]> {
    return parseHits(await this.getJson('/search?q=' + enc(q), token));
  }

  async upload(token: string, kind: string, id: string, name: string, bytes: ArrayBuffer): Promise<UploadResult> {
    const path = '/uploads?kind=' + enc(kind) + '&id=' + enc(id) + '&name=' + enc(name);
    const r = await this.raw(http.RequestMethod.POST, path, token, bytes, 'application/octet-stream');
    if (r.code < 200 || r.code > 299) {
      throw errorOf(r.code, r.text);
    }
    return parseUpload(J.parse(r.text));
  }

  async attachmentBytes(token: string, id: string): Promise<ArrayBuffer> {
    const req = http.createHttp();
    const header: Record<string, string> = {};
    header['Authorization'] = 'Bearer ' + token;
    try {
      const resp = await req.request(this.baseUrl + '/attachments?id=' + enc(id), {
        method: http.RequestMethod.GET,
        header: header,
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        connectTimeout: 15000,
        readTimeout: 60000
      });
      if (resp.responseCode < 200 || resp.responseCode > 299) {
        throw new BridgeError('attachment', resp.responseCode);
      }
      return resp.result as ArrayBuffer;
    } finally {
      req.destroy();
    }
  }

  streamUrl(botId: string, roomId: string, since: number): string {
    const p: string[] = [];
    if (roomId.length > 0) {
      p.push('roomId=' + enc(roomId));
      if (since >= 0) {
        p.push('since=' + since);
      }
    } else if (botId.length > 0) {
      p.push('botId=' + enc(botId));
    }
    return this.baseUrl + '/stream' + (p.length > 0 ? '?' + p.join('&') : '');
  }
}
