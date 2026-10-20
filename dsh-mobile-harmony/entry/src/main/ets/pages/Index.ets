import { util } from '@kit.ArkTS';
import { scanBarcode, scanCore } from '@kit.ScanKit';
import { common } from '@kit.AbilityKit';
import { SessionStore } from '../data/SessionStore';
import { Auth } from '../data/Auth';
import { BridgeApi } from '../data/BridgeApi';
import { SseClient } from '../data/SseClient';
import { SseEvent } from '../common/Sse';
import { J } from '../common/Json';
import { PairCode } from '../common/PairCode';
import { friendly } from '../common/Errors';
import {
  ApprovalInfo, BotInfo, ChatEntry, ChatItem, RoomInfo, newItem, parseChatItem
} from '../common/Models';
import {
  ChatListLogic, ChatMerge, LaunchRouter, Look, StartRoute, WaitEvent, WaitKind, WaitMachine, WaitPhase, WaitState
} from '../common/ChatLogic';
import { AttImage, Avatar, MdText, colorOf } from '../view/Bits';
import { Files, PickedFile } from '../data/Files';
import { AttachLogic, ChatPrefs, NotifyLogic, ProfileLogic, Seen } from '../common/Extra';
import { Notifier } from '../data/Notifier';
import { BotRules } from '../common/Group';
import { Attachment, BotOptions, BotProfile, EffortOption, ModelOption, NewBotDraft, SearchHit } from '../common/Models';
import { LiveReply, MentionActive, MentionRow, Mentions, RoomRound, RoomRules, RoomStreams } from '../common/Group';
import { NewRoomDraft } from '../common/Models';
import { JObj } from '../common/Json';

class Pending {
  localId: string = '';
  uri: string = '';
  name: string = '';
  isImage: boolean = false;
  bytes: number = 0;
  uploadId: string = '';
  failed: string = '';
}

@Entry
@Component
struct Index {
  @State screen: string = 'loading';
  @State notice: string = '';
  // pairing
  @State pairCode: string = '';
  @State pairAddr: string = '';
  @State busy: boolean = false;
  // list
  @State chats: ChatEntry[] = [];
  @State approvals: ApprovalInfo[] = [];
  @State bots: BotInfo[] = [];
  @State rooms: RoomInfo[] = [];
  @State offline: boolean = false;
  // open chat
  @State openKind: string = '';
  @State openId: string = '';
  @State openName: string = '';
  @State messages: ChatItem[] = [];
  @State nextCursor: string = '';
  @State wait: WaitState = new WaitState();
  @State input: string = '';
  @State sending: boolean = false;
  @State live: LiveReply[] = [];
  @State roomDraft: NewRoomDraft = new NewRoomDraft();
  @State roomName: string = '';
  @State menuOpen: boolean = false;
  @State pending: Pending[] = [];
  @State attachOpen: boolean = false;
  @State profile: BotProfile | null = null;
  @State options: BotOptions | null = null;
  @State efforts: EffortOption[] = [];
  @State botDraft: NewBotDraft = new NewBotDraft();
  @State profileBack: string = 'chat';
  @State searchQ: string = '';
  @State searchHits: SearchHit[] = [];
  @State formError: string = '';
  @StorageLink('foreground') foreground: boolean = true;
  @StorageLink('openChat') @Watch('onOpenChat') openChatReq: string = '';
  @State prefs: ChatPrefs = new ChatPrefs();
  @State confirmRepair: boolean = false;
  private seen: Seen | null = null;
  private store: SessionStore | null = null;
  private auth: Auth | null = null;
  private sse: SseClient = new SseClient();
  private sseAttempt: number = 0;
  private listTimer: number = -1;
  private scroller: Scroller = new Scroller();

  aboutToAppear(): void {
    this.start();
  }

  aboutToDisappear(): void {
    this.sse.close();
    if (this.listTimer >= 0) {
      clearInterval(this.listTimer);
    }
  }

  onBackPress(): boolean {
    if (this.screen === 'chat') {
      this.closeChat();
      return true;
    }
    if (this.screen === 'roominfo') {
      this.screen = 'chat';
      return true;
    }
    if (this.screen === 'profile') {
      this.screen = this.profileBack;
      return true;
    }
    if (this.screen !== 'list' && this.screen !== 'pair' && this.screen !== 'loading') {
      this.screen = 'list';
      return true;
    }
    return false;
  }

  private ctx(): common.UIAbilityContext {
    return this.getUIContext().getHostContext() as common.UIAbilityContext;
  }

  private async start(): Promise<void> {
    this.store = await SessionStore.open(this.ctx());
    this.auth = new Auth(this.store);
    const s = this.store.current();
    this.prefs = ChatPrefs.decode(this.store.getPref('chat_prefs', ''));
    this.pairAddr = s.baseUrl.replace('/api/mobile-bridge/v1', '');
    const r = LaunchRouter.route(true, s.paired, s.lastChat);
    if (r === StartRoute.Pair) {
      this.screen = 'pair';
      return;
    }
    this.goList();
    Notifier.ask(this.ctx());
    if (this.openChatReq.length > 0) {
      this.onOpenChat();
      return;
    }
    if (r === StartRoute.Chat) {
      const k = s.lastChat;
      const kind = k.startsWith('room:') ? 'room' : 'bot';
      const id = k.substring(k.indexOf(':') + 1);
      this.openChat(kind, id, '');
    }
  }

  onOpenChat(): void {
    const k = this.openChatReq;
    if (k.length === 0 || this.auth === null || !this.store?.current().paired) {
      return;
    }
    this.openChatReq = '';
    const kind = k.startsWith('room:') ? 'room' : 'bot';
    this.openChat(kind, k.substring(k.indexOf(':') + 1), '');
  }

  private async savePrefs(p: ChatPrefs): Promise<void> {
    this.prefs = p;
    if (this.store !== null) {
      await this.store.setPref('chat_prefs', p.encode());
    }
  }

  private isMuted(kind: string, id: string): boolean {
    return this.prefs.muted.indexOf(NotifyLogic.key(kind, id)) >= 0;
  }

  private toggleMute(kind: string, id: string): void {
    this.savePrefs(this.prefs.withMuted(NotifyLogic.key(kind, id), !this.isMuted(kind, id)));
  }

  private async repair(): Promise<void> {
    this.sse.close();
    if (this.auth !== null) {
      await this.auth.logout();
    }
    Notifier.clearAll();
    this.chats = [];
    this.approvals = [];
    this.seen = null;
    this.notice = '';
    this.screen = 'pair';
  }

  // ---------- errors ----------
  private fail(e: Object): void {
    if (LaunchRouter.requiresRepair(e)) {
      this.sse.close();
      this.screen = 'pair';
      this.notice = '这台手机需要重新配对';
      if (this.auth !== null) {
        this.auth.logout();
      }
      return;
    }
    this.notice = friendly(e);
  }

  // ---------- pairing ----------
  private async scan(): Promise<void> {
    try {
      const opts: scanBarcode.ScanOptions = { scanTypes: [scanCore.ScanType.QR_CODE], enableMultiMode: false, enableAlbum: true };
      const r = await scanBarcode.startScanForResult(this.ctx(), opts);
      const p = PairCode.parse(r.originalValue);
      if (p === null) {
        this.notice = '这不是电脑上的配对二维码';
        return;
      }
      if (p.baseUrl !== null) {
        this.pairAddr = p.baseUrl;
      }
      this.pairCode = p.code;
      await this.pair();
    } catch (e) {
      // user closed the scanner
    }
  }

  private async pair(): Promise<void> {
    if (this.auth === null) {
      return;
    }
    const p = PairCode.parse(this.pairCode);
    if (p === null) {
      this.notice = '配对码是 6 位，请对照电脑上显示的填写';
      return;
    }
    if (p.baseUrl !== null) {
      this.pairAddr = p.baseUrl;
    }
    if (this.pairAddr.trim().length === 0) {
      this.notice = '请填写电脑地址，或直接扫码';
      return;
    }
    this.busy = true;
    this.notice = '';
    try {
      await this.auth.saveBaseUrl(this.pairAddr);
      await this.auth.pair(p.code);
      this.pairCode = '';
      this.goList();
    } catch (e) {
      this.notice = friendly(e as Object);
    } finally {
      this.busy = false;
    }
  }

  // ---------- list ----------
  private goList(): void {
    this.screen = 'list';
    this.refreshList();
    if (this.listTimer < 0) {
      this.listTimer = setInterval(() => {
        if (this.screen !== 'pair' && this.screen !== 'loading') {
          this.refreshList();
        }
      }, 15000);
    }
  }

  async refreshList(): Promise<void> {
    if (this.auth === null) {
      return;
    }
    try {
      const list = await this.auth.with((t: string, api: BridgeApi) => api.chatList(t));
      const team = await this.auth.with((t: string, api: BridgeApi) => api.team(t));
      this.bots = team.bots;
      this.rooms = team.rooms;
      let chats = ChatListLogic.sorted(list.chats);
      if (this.screen === 'chat') {
        chats = ChatListLogic.clearOpen(chats, this.openKind, this.openId);
      }
      this.chats = chats;
      this.approvals = list.approvals;
      const openKey = this.screen === 'chat' && this.foreground ? NotifyLogic.key(this.openKind, this.openId) : '';
      const bundle = this.ctx().abilityInfo.bundleName;
      for (const a of NotifyLogic.alerts(this.seen, list, this.prefs, openKey)) {
        Notifier.show(bundle, a);
      }
      this.seen = NotifyLogic.seen(list);
      this.offline = false;
    } catch (e) {
      if (LaunchRouter.requiresRepair(e as Object)) {
        this.fail(e as Object);
      } else {
        this.offline = true;
      }
    }
  }

  // ---------- chat ----------
  async openChat(kind: string, id: string, name: string): Promise<void> {
    this.sse.close();
    this.openKind = kind;
    this.openId = id;
    this.openName = name.length > 0 ? name : this.nameOf(kind, id);
    this.messages = [];
    this.nextCursor = '';
    this.wait = new WaitState();
    this.live = [];
    this.pending = [];
    this.notice = '';
    this.screen = 'chat';
    this.chats = ChatListLogic.clearOpen(this.chats, kind, id);
    Notifier.clear(kind, id);
    if (this.store !== null) {
      await this.store.setLastChat(kind + ':' + id);
    }
    await this.loadHistory();
    this.sseAttempt = 0;
    this.openStream();
    this.markRead();
  }

  private nameOf(kind: string, id: string): string {
    const c = this.chats.find((x: ChatEntry) => x.kind === kind && x.id === id);
    if (c !== undefined) {
      return c.name;
    }
    if (kind === 'room') {
      const r = this.rooms.find((x: RoomInfo) => x.id === id);
      return r === undefined ? '群聊' : r.name;
    }
    const b = this.bots.find((x: BotInfo) => x.id === id);
    return b === undefined ? '助手' : b.name;
  }

  closeChat(): void {
    this.sse.close();
    this.screen = 'list';
    this.openKind = '';
    this.openId = '';
    if (this.store !== null) {
      this.store.setLastChat('');
    }
    this.refreshList();
  }

  private async markRead(): Promise<void> {
    if (this.auth === null) {
      return;
    }
    const kind = this.openKind;
    const id = this.openId;
    try {
      await this.auth.with((t: string, api: BridgeApi) => api.markRead(t, kind, id));
    } catch (e) {
      // not important
    }
  }

  private async loadHistory(): Promise<void> {
    if (this.auth === null) {
      return;
    }
    const kind = this.openKind;
    const id = this.openId;
    try {
      if (kind === 'room') {
        const items = await this.auth.with((t: string, api: BridgeApi) => api.roomHistory(t, id));
        if (this.openId === id) {
          this.messages = ChatMerge.mergeHistory(this.messages, items);
        }
      } else {
        const h = await this.auth.with((t: string, api: BridgeApi) => api.history(t, id, null));
        if (this.openId === id) {
          this.messages = ChatMerge.mergeHistory(this.messages, h.items);
          this.nextCursor = h.nextCursor ?? '';
        }
      }
      this.scrollEnd();
    } catch (e) {
      this.fail(e as Object);
    }
  }

  private async loadOlder(): Promise<void> {
    if (this.auth === null || this.nextCursor.length === 0 || this.openKind !== 'bot') {
      return;
    }
    const id = this.openId;
    const cur = this.nextCursor;
    this.nextCursor = '';
    try {
      const h = await this.auth.with((t: string, api: BridgeApi) => api.history(t, id, cur));
      if (this.openId === id) {
        this.messages = h.items.concat(this.messages);
        this.nextCursor = h.nextCursor ?? '';
      }
    } catch (e) {
      this.nextCursor = cur;
    }
  }

  private scrollEnd(): void {
    setTimeout(() => {
      this.scroller.scrollEdge(Edge.Bottom);
    }, 50);
  }

  private async openStream(): Promise<void> {
    if (this.auth === null || this.screen !== 'chat') {
      return;
    }
    const kind = this.openKind;
    const id = this.openId;
    try {
      const token = await this.auth.refresh(false);
      const api = this.auth.api();
      const url = kind === 'room' ? api.streamUrl('', id, RoomRound.since(this.messages)) : api.streamUrl(id, '', -1);
      this.sse.open(url, token, (ev: SseEvent) => {
        this.sseAttempt = 0;
        if (this.openKind === kind && this.openId === id) {
          this.onSse(ev);
        }
      }, (code: number) => {
        if (this.screen !== 'chat' || this.openId !== id) {
          return;
        }
        if (code === 401 && this.auth !== null) {
          this.auth.refresh(true).catch((e: Object) => this.fail(e));
        }
        const backoff = Math.min(30000, 1000 * Math.pow(2, Math.min(this.sseAttempt, 5)));
        this.sseAttempt++;
        setTimeout(() => {
          if (this.screen === 'chat' && this.openId === id) {
            this.loadHistory();
            this.openStream();
          }
        }, backoff);
      });
    } catch (e) {
      this.fail(e as Object);
    }
  }

  private onSse(ev: SseEvent): void {
    if (this.openKind === 'room') {
      this.onRoomSse(ev);
      return;
    }
    const data = J.parse(ev.data);
    if (ev.event === 'message') {
      const o = J.obj(data, 'item');
      if (o === null) {
        return;
      }
      const item = parseChatItem(o);
      if (item.kind === 'bot' || item.kind === 'member') {
        this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.BotMessage));
      }
      this.messages = ChatMerge.mergeIncoming(this.messages, item);
      this.scrollEnd();
    } else if (ev.event === 'stream' && this.openKind === 'bot') {
      const phase = J.strOr(data, 'phase', '');
      if (phase === 'start') {
        this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.StreamStart));
      } else if (phase === 'end') {
        if (this.wait.phase === WaitPhase.Streaming) {
          this.messages = ChatMerge.commitStreamed(this.messages, this.wait.text, Date.now());
        }
        this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.StreamEnd));
      } else {
        const d = J.strOr(data, 'text', '');
        if (d.length > 0) {
          this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.Delta, d));
          this.scrollEnd();
        }
      }
    }
  }

  private onRoomSse(ev: SseEvent): void {
    const data = J.parse(ev.data);
    const now = Date.now();
    if (ev.event === 'message') {
      const o = J.obj(data, 'item');
      if (o === null) {
        return;
      }
      const item = parseChatItem(o);
      if (RoomRound.endsWait(item)) {
        this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.Dismiss));
      }
      this.messages = ChatMerge.mergeIncoming(this.messages, item);
      this.live = RoomStreams.prune(RoomStreams.onMessage(this.live, item), now);
      this.scrollEnd();
    } else if (ev.event === 'round') {
      if (J.strOr(data, 'phase', '') !== 'end') {
        return;
      }
      const replied = J.numOr(data, 'replied', 0);
      const failed = J.arr(data, 'failed').length;
      if (this.wait.phase === WaitPhase.Waiting) {
        this.messages = RoomRound.onEnd(this.messages, replied, failed, now);
        this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.Dismiss));
      }
      this.live = RoomRound.dropUnshown(this.live);
    } else if (ev.event === 'stream') {
      const attempt = J.strOr(data, 'attemptId', '');
      if (attempt.length === 0) {
        return;
      }
      const botId = J.str(data, 'botId');
      const name = J.str(data, 'name');
      const phase = J.strOr(data, 'phase', '');
      let live = this.live;
      if (phase === 'start') {
        live = RoomStreams.start(live, attempt, botId, name);
      } else if (phase === 'end') {
        live = RoomStreams.end(live, attempt, now);
      } else {
        const d = J.strOr(data, 'text', '');
        if (d.length > 0) {
          live = RoomStreams.delta(live, attempt, d, botId, name);
        }
      }
      if (live.some((x: LiveReply) => RoomStreams.shown(x) !== null) && this.wait.phase === WaitPhase.Waiting) {
        this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.Dismiss));
      }
      this.live = RoomStreams.prune(live, now);
      this.scrollEnd();
    }
  }

  private currentRoom(): RoomInfo | null {
    const r = this.rooms.find((x: RoomInfo) => x.id === this.openId);
    return r === undefined ? null : r;
  }

  private roomMembers(): BotInfo[] {
    const r = this.currentRoom();
    return r === null ? [] : RoomRules.memberBots(r.members, this.bots);
  }

  private mentionRows(): MentionRow[] {
    if (this.openKind !== 'room') {
      return [];
    }
    const a = Mentions.activeAt(this.input);
    return a === null ? [] : Mentions.rows(this.roomMembers(), a.query);
  }

  private pickMention(row: MentionRow): void {
    const a: MentionActive | null = Mentions.activeAt(this.input);
    if (a !== null) {
      this.input = Mentions.apply(this.input, a, row.insert);
    }
  }

  private answerHint(): string {
    const r = this.currentRoom();
    if (r === null || this.input.trim().length === 0) {
      return '';
    }
    const members = this.roomMembers();
    const ids = Mentions.whoAnswers(this.input, members, r.mode ?? 'everyone', r.admin);
    const names = ids.map((id: string) => {
      const b = members.find((x: BotInfo) => x.id === id);
      return b === undefined ? '' : b.name;
    }).filter((n: string) => n.length > 0);
    if (names.length === 0) {
      return '';
    }
    if (names.length === members.length && names.length > 1) {
      return '所有成员都会回复';
    }
    return names.join('、') + ' 会回复';
  }

  // ---------- group create / settings ----------
  private openNewRoom(): void {
    this.menuOpen = false;
    this.roomDraft = new NewRoomDraft();
    this.notice = '';
    this.screen = 'newroom';
  }

  private async createRoom(): Promise<void> {
    if (this.auth === null || !RoomRules.canCreate(this.roomDraft)) {
      return;
    }
    const d = this.roomDraft;
    this.busy = true;
    try {
      const room = await this.auth.with((t: string, api: BridgeApi) => api.createRoom(t, d));
      this.rooms = this.rooms.concat([room]);
      this.busy = false;
      await this.openChat('room', room.id, room.name);
      this.refreshList();
    } catch (e) {
      this.busy = false;
      this.notice = friendly(e as Object);
    }
  }

  private async updateRoom(body: JObj): Promise<void> {
    if (this.auth === null) {
      return;
    }
    body['id'] = this.openId;
    try {
      const room = await this.auth.with((t: string, api: BridgeApi) => api.updateRoom(t, body));
      this.rooms = this.rooms.map((r: RoomInfo) => r.id === room.id ? room : r);
      this.openName = room.name.length > 0 ? room.name : this.openName;
      this.notice = '';
    } catch (e) {
      this.notice = friendly(e as Object);
    }
  }

  private setMode(mode: string): void {
    const b: JObj = {};
    b['mode'] = mode;
    this.updateRoom(b);
  }

  private renameRoom(): void {
    if (this.roomName.trim().length === 0) {
      return;
    }
    const b: JObj = {};
    b['name'] = this.roomName.trim();
    this.updateRoom(b);
  }

  private addMember(id: string): void {
    const b: JObj = {};
    b['add'] = [id];
    this.updateRoom(b);
  }

  private removeMember(id: string): void {
    const b: JObj = {};
    b['remove'] = [id];
    this.updateRoom(b);
  }

  private async send(): Promise<void> {
    const text = this.input.trim();
    const ready = this.pending.filter((p: Pending) => p.uploadId.length > 0);
    if ((text.length === 0 && ready.length === 0) || this.auth === null || this.sending) {
      return;
    }
    if (this.pending.some((p: Pending) => p.uploadId.length === 0 && p.failed.length === 0)) {
      this.notice = '图片或文件还在上传，请稍等';
      return;
    }
    const ids = ready.map((p: Pending) => p.uploadId);
    const cid = util.generateRandomUUID(false);
    const mine = newItem('user', text, Date.now());
    mine.local = true;
    mine.clientMessageId = cid;
    mine.attachments = ready.map((p: Pending) => {
      const a: Attachment = {
        type: p.isImage ? 'image' : 'file', id: null, name: p.name, bytes: p.bytes, mediaType: null,
        width: null, height: null, localUri: p.uri
      };
      return a;
    });
    this.pending = [];
    this.messages = this.messages.concat([mine]);
    this.input = '';
    this.sending = true;
    this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.Submitted, cid));
    this.scrollEnd();
    const kind = this.openKind;
    const id = this.openId;
    try {
      const r = await this.auth.with((t: string, api: BridgeApi) =>
        kind === 'room' ? api.roomSend(t, id, text, cid, ids) : api.send(t, id, text, cid, ids));
      if (!r.ok) {
        this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.SendFailed, '发送失败：' + (r.reason ?? '电脑没有接受这条消息')));
      }
    } catch (e) {
      if (LaunchRouter.requiresRepair(e as Object)) {
        this.fail(e as Object);
      } else {
        this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.SendFailed, '发送失败：' + friendly(e as Object)));
      }
    } finally {
      this.sending = false;
    }
  }

  // ---------- attachments ----------
  private async attach(images: boolean): Promise<void> {
    this.attachOpen = false;
    const room = AttachLogic.MAX_PER_MESSAGE - this.pending.length;
    if (room <= 0) {
      this.notice = '一条消息最多带 ' + AttachLogic.MAX_PER_MESSAGE + ' 个图片或文件';
      return;
    }
    let uris: string[] = [];
    try {
      uris = images ? await Files.pickImages(room) : await Files.pickFiles(this.ctx(), room);
    } catch (e) {
      return;
    }
    for (const uri of uris) {
      const p = new Pending();
      p.localId = util.generateRandomUUID(false);
      p.uri = uri;
      p.name = Files.nameOf(uri);
      p.isImage = images || AttachLogic.isImageName(p.name);
      this.pending = this.pending.concat([p]);
      this.upload(p);
    }
  }

  private updatePending(localId: string, uploadId: string, failed: string, bytes: number): void {
    this.pending = this.pending.map((x: Pending) => {
      if (x.localId !== localId) {
        return x;
      }
      const n = new Pending();
      n.localId = x.localId;
      n.uri = x.uri;
      n.name = x.name;
      n.isImage = x.isImage;
      n.bytes = bytes > 0 ? bytes : x.bytes;
      n.uploadId = uploadId;
      n.failed = failed;
      return n;
    });
  }

  private async upload(p: Pending): Promise<void> {
    if (this.auth === null) {
      return;
    }
    let f: PickedFile;
    try {
      f = Files.read(p.uri);
    } catch (e) {
      this.updatePending(p.localId, '', '读不到这个文件', 0);
      return;
    }
    if (AttachLogic.tooBig(f.bytes)) {
      this.updatePending(p.localId, '', '超过 25 MB，发不了', f.bytes);
      return;
    }
    const kind = this.openKind;
    const id = this.openId;
    try {
      const r = await this.auth.with((t: string, api: BridgeApi) => api.upload(t, kind, id, f.name, f.data));
      this.updatePending(p.localId, r.uploadId, '', f.bytes);
    } catch (e) {
      this.updatePending(p.localId, '', '上传失败：' + friendly(e as Object), f.bytes);
    }
  }

  private removePending(localId: string): void {
    this.pending = this.pending.filter((x: Pending) => x.localId !== localId);
  }

  private async loadAttachment(id: string): Promise<string> {
    const dir = this.ctx().cacheDir;
    const hit = Files.cached(dir, id);
    if (hit.length > 0 || this.auth === null) {
      return hit;
    }
    const data = await this.auth.with((t: string, api: BridgeApi) => api.attachmentBytes(t, id));
    return Files.save(dir, id, data);
  }

  // ---------- approvals ----------
  private async answer(a: ApprovalInfo, choice: string): Promise<void> {
    if (this.auth === null) {
      return;
    }
    try {
      await this.auth.with((t: string, api: BridgeApi) => api.answerApproval(t, a.id, choice));
      this.approvals = this.approvals.filter((x: ApprovalInfo) => x.id !== a.id);
      this.refreshList();
    } catch (e) {
      this.notice = friendly(e as Object);
    }
  }

  private openApproval(): void {
    if (this.approvals.length === 0) {
      return;
    }
    const a = this.approvals[0];
    if (a.roomId !== null) {
      this.openChat('room', a.roomId, a.roomName ?? '');
    } else if (a.botId !== null) {
      this.openChat('bot', a.botId, a.botName ?? '');
    }
  }

  // ---------- Bot profile / model ----------
  private async loadOptions(): Promise<void> {
    if (this.auth === null) {
      return;
    }
    try {
      this.options = await this.auth.with((t: string, api: BridgeApi) => api.botOptions(t));
    } catch (e) {
      this.formError = friendly(e as Object);
    }
  }

  private async openProfile(id: string, back: string): Promise<void> {
    if (this.auth === null) {
      return;
    }
    this.profileBack = back;
    this.profile = null;
    this.formError = '';
    this.screen = 'profile';
    this.loadOptions();
    try {
      this.profile = await this.auth.with((t: string, api: BridgeApi) => api.botInfo(t, id));
    } catch (e) {
      this.formError = friendly(e as Object);
    }
  }

  private async saveProfile(body: JObj): Promise<void> {
    if (this.auth === null || this.profile === null) {
      return;
    }
    body['id'] = this.profile.id;
    try {
      this.profile = await this.auth.with((t: string, api: BridgeApi) => api.updateBot(t, body));
      this.formError = '已保存';
      const p = this.profile;
      if (p !== null) {
        this.bots = this.bots.map((b: BotInfo) => {
          if (b.id !== p.id) {
            return b;
          }
          const n: BotInfo = {
            id: b.id, name: p.name, role: p.role, sessionId: b.sessionId, model: p.model, main: b.main,
            color: p.color, shape: p.shape
          };
          return n;
        });
        if (this.openKind === 'bot' && this.openId === p.id) {
          this.openName = p.name;
        }
      }
    } catch (e) {
      this.formError = friendly(e as Object);
    }
  }

  private setModel(ref: string): void {
    const b: JObj = {};
    b['model'] = ref;
    this.saveProfile(b);
  }

  private modelName(ref: string | null): string {
    if (ref === null || ref.length === 0) {
      return '默认';
    }
    const o = this.options;
    const m = o === null ? undefined : o.models.find((x: ModelOption) => x.ref === ref);
    return m === undefined ? ref : m.name;
  }

  // ---------- new Bot ----------
  private openNewBot(): void {
    this.menuOpen = false;
    const d = new NewBotDraft();
    d.name = BotRules.freshName(this.bots.map((b: BotInfo) => b.name));
    this.botDraft = d;
    this.efforts = [];
    this.formError = '';
    this.screen = 'newbot';
    this.loadOptions().then(() => {
      const o = this.options;
      if (o === null) {
        return;
      }
      const n = this.copyDraft();
      n.model = BotRules.startModel(o);
      const shapes = o.shapes.map((x) => x.id).filter((x: string) => x !== 'whale');
      const colors = o.colors.map((x) => x.id).filter((x: string) =>
        ['black', 'gray', 'brown', 'charcoal', 'graphite'].indexOf(x) < 0);
      n.shape = shapes.length > 0 ? shapes[Math.floor(Math.random() * shapes.length)] : '';
      n.color = colors.length > 0 ? colors[Math.floor(Math.random() * colors.length)] : '';
      n.workDir = o.teamFolder ?? '';
      this.botDraft = n;
      this.loadEfforts(n.model);
    });
  }

  private copyDraft(): NewBotDraft {
    const o = this.botDraft;
    const n = new NewBotDraft();
    n.name = o.name;
    n.role = o.role;
    n.shape = o.shape;
    n.color = o.color;
    n.model = o.model;
    n.effort = o.effort;
    n.agentPreset = o.agentPreset;
    n.workDir = o.workDir;
    n.instructions = o.instructions;
    n.brief = o.brief;
    return n;
  }

  private async loadEfforts(ref: string): Promise<void> {
    this.efforts = [];
    if (this.auth === null || ref.length === 0) {
      return;
    }
    try {
      const list = await this.auth.with((t: string, api: BridgeApi) => api.efforts(t, ref));
      if (this.botDraft.model === ref) {
        this.efforts = list;
      }
    } catch (e) {
      // optional
    }
  }

  private async createBot(): Promise<void> {
    if (this.auth === null) {
      return;
    }
    const problem = BotRules.problem(this.botDraft.name, this.bots.map((b: BotInfo) => b.name));
    if (problem !== null) {
      this.formError = problem;
      return;
    }
    const d = this.botDraft;
    this.busy = true;
    try {
      const bot = await this.auth.with((t: string, api: BridgeApi) => api.createBot(t, d));
      this.bots = this.bots.concat([bot]);
      this.busy = false;
      await this.openChat('bot', bot.id, bot.name);
      this.refreshList();
    } catch (e) {
      this.busy = false;
      this.formError = friendly(e as Object);
    }
  }

  // ---------- search ----------
  private openSearch(): void {
    this.searchQ = '';
    this.searchHits = ProfileLogic.results(this.chats, this.bots, '', []);
    this.screen = 'search';
  }

  private async doSearch(q: string): Promise<void> {
    this.searchQ = q;
    this.searchHits = ProfileLogic.results(this.chats, this.bots, q, []);
    if (this.auth === null || q.trim().length === 0) {
      return;
    }
    try {
      const hits = await this.auth.with((t: string, api: BridgeApi) => api.search(t, q.trim()));
      if (this.searchQ === q) {
        this.searchHits = ProfileLogic.results(this.chats, this.bots, q, hits);
      }
    } catch (e) {
      // keep local matches
    }
  }

  private async stop(): Promise<void> {
    if (this.auth === null) {
      return;
    }
    const kind = this.openKind;
    const id = this.openId;
    try {
      if (kind === 'room') {
        await this.auth.with((t: string, api: BridgeApi) => api.stopRoom(t, id));
      } else {
        await this.auth.with((t: string, api: BridgeApi) => api.stopBot(t, id));
      }
      this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.Dismiss));
    } catch (e) {
      this.fail(e as Object);
    }
  }

  private isRunning(): boolean {
    const c = this.chats.find((x: ChatEntry) => x.kind === this.openKind && x.id === this.openId);
    return (c !== undefined && c.running) || this.wait.phase === WaitPhase.Waiting || this.wait.phase === WaitPhase.Streaming;
  }

  // ---------- views ----------
  @Builder
  PairView() {
    Column({ space: 16 }) {
      Image($r('app.media.startIcon')).width(96).height(96).borderRadius(24).margin({ top: 72 })
      Text('连接你的电脑').fontSize(24).fontWeight(FontWeight.Bold).fontColor($r('app.color.text'))
      Text('在电脑上打开「手机配对」，扫描二维码即可。').fontSize(14).fontColor($r('app.color.sub'))
        .textAlign(TextAlign.Center)
      Button('扫码配对').width('100%').height(48).enabled(!this.busy).onClick(() => this.scan())
      Text('或者手动填写').fontSize(13).fontColor($r('app.color.sub')).margin({ top: 8 })
      TextInput({ placeholder: '电脑地址', text: this.pairAddr })
        .onChange((v: string) => this.pairAddr = v).height(46)
      TextInput({ placeholder: '6 位配对码', text: this.pairCode })
        .onChange((v: string) => this.pairCode = v).height(46).maxLength(80)
      Button(this.busy ? '正在连接…' : '配对').width('100%').height(48).type(ButtonType.Capsule)
        .backgroundColor('#000A25').enabled(!this.busy).onClick(() => this.pair())
      if (this.notice.length > 0) {
        Text(this.notice).fontSize(14).fontColor('#E5484D').textAlign(TextAlign.Center)
      }
    }
    .padding({ left: 24, right: 24 })
    .width('100%')
    .height('100%')
  }

  @Builder
  ChatRow(c: ChatEntry) {
    Row({ space: 12 }) {
      Avatar({ name: c.name, color: colorOf(c.color), group: c.kind === 'room' })
      Column({ space: 4 }) {
        Row() {
          Text(c.name).fontSize(16).fontWeight(FontWeight.Medium).fontColor($r('app.color.text'))
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
          Text(ChatListLogic.timeLabel(c.lastAt, Date.now())).fontSize(12).fontColor($r('app.color.sub'))
        }
        Row() {
          Text(c.running ? '正在回复…' : Look.plainPreview(c.preview)).fontSize(14).fontColor($r('app.color.sub'))
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
          if (c.unread > 0) {
            Text(ChatListLogic.badge(c.unread)).fontSize(11).fontColor(Color.White).backgroundColor('#E5484D')
              .borderRadius(9).padding({ left: 6, right: 6 }).height(18)
          }
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
    }
    .padding({ left: 16, right: 16, top: 10, bottom: 10 })
    .width('100%')
    .onClick(() => this.openChat(c.kind, c.id, c.name))
  }

  @Builder
  PlusMenu() {
    Column() {
      Text('新建助手').fontSize(16).padding(14).width(150).onClick(() => this.openNewBot())
      Divider()
      Text('新建群聊').fontSize(16).padding(14).width(150).onClick(() => this.openNewRoom())
    }
  }

  @Builder
  ListView() {
    Column() {
      Row() {
        Text('消息').fontSize(26).fontWeight(FontWeight.Bold).fontColor($r('app.color.text')).layoutWeight(1)
        Text('设置').fontSize(16).fontColor($r('app.color.text')).padding(6).onClick(() => {
          this.screen = 'settings';
        })
        Text('搜索').fontSize(16).fontColor($r('app.color.text')).padding(6).onClick(() => this.openSearch())
        Text('＋').fontSize(26).fontColor($r('app.color.text')).padding(4)
          .bindMenu(this.menuOpen, this.PlusMenu, { onDisappear: () => this.menuOpen = false })
          .onClick(() => this.menuOpen = true)
      }
      .padding({ left: 16, right: 16, top: 12, bottom: 8 })
      if (this.offline) {
        Text('暂时连不上电脑，正在重试').fontSize(13).fontColor($r('app.color.sub')).padding(8)
      }
      if (this.approvals.length > 0) {
        Text(this.approvals.length + ' 个操作在等你确认').fontSize(14).fontColor('#B25E00')
          .backgroundColor('#FFF4E5').borderRadius(10).padding(10).margin({ left: 16, right: 16, bottom: 6 })
          .width('92%')
          .onClick(() => this.openApproval())
      }
      Refresh({ refreshing: false }) {
        List() {
          ForEach(this.chats, (c: ChatEntry) => {
            ListItem() {
              this.ChatRow(c)
            }
          }, (c: ChatEntry) => c.kind + ':' + c.id + ':' + c.unread + ':' + (c.lastAt ?? 0) + ':' + c.running)
        }
        .divider({ strokeWidth: 0.5, color: $r('app.color.line'), startMargin: 72 })
        .width('100%')
        .height('100%')
      }
      .onRefreshing(() => this.refreshList())
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  Atts(list: Attachment[]) {
    if (list.length > 0) {
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(list, (a: Attachment, i: number) => {
          if (a.type === 'image') {
            AttImage({ attId: a.id ?? '', localUri: a.localUri ?? '', loader: (id: string) => this.loadAttachment(id) })
              .margin(2)
          } else {
            Row({ space: 8 }) {
              Text(AttachLogic.ext(a.name)).fontSize(11).fontColor(Color.White).backgroundColor('#5B6B8C')
                .borderRadius(6).padding(6)
              Column() {
                Text(a.name ?? '文件').fontSize(14).fontColor($r('app.color.text')).maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(AttachLogic.size(a.bytes)).fontSize(12).fontColor($r('app.color.sub'))
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)
            }
            .padding(8).borderRadius(10).backgroundColor('#0F000000').width(220).margin(2)
          }
        }, (a: Attachment, i: number) => (a.id ?? a.localUri ?? '') + i)
      }
    }
  }

  @Builder
  ApprovalCard(a: ApprovalInfo) {
    Column({ space: 6 }) {
      Text(ChatListLogic.whereText(a) + ' 想要' + a.action).fontSize(15).fontWeight(FontWeight.Medium)
        .fontColor($r('app.color.text'))
      if (a.reason.length > 0) {
        Text(a.reason).fontSize(13).fontColor($r('app.color.sub'))
      }
      if (a.detail.length > 0) {
        Text(a.detail).fontSize(12).fontColor($r('app.color.sub')).maxLines(6)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      if (a.answerable) {
        Row({ space: 10 }) {
          Button('拒绝').layoutWeight(1).height(36).backgroundColor('#8A8F99').onClick(() => this.answer(a, 'reject'))
          Button('同意').layoutWeight(1).height(36).backgroundColor('#000A25').onClick(() => this.answer(a, 'allow'))
        }
      } else {
        Text('这个操作需要在电脑上处理').fontSize(12).fontColor($r('app.color.sub'))
      }
    }
    .alignItems(HorizontalAlign.Start)
    .padding(12)
    .borderRadius(12)
    .backgroundColor('#FFF4E5')
    .margin({ left: 10, right: 10, bottom: 6 })
  }

  @Builder
  AttachMenu() {
    Column() {
      Text('图片').fontSize(16).padding(14).width(120).onClick(() => this.attach(true))
      Divider()
      Text('文件').fontSize(16).padding(14).width(120).onClick(() => this.attach(false))
    }
  }

  @Builder
  Bubble(m: ChatItem) {
    if (m.kind === 'user') {
      Row() {
        Blank()
        Column({ space: 6 }) {
          this.Atts(m.attachments)
          if ((m.text ?? '').length > 0) {
            Text(m.text ?? '').fontSize(15).fontColor($r('app.color.text')).copyOption(CopyOptions.LocalDevice)
          }
        }
        .alignItems(HorizontalAlign.End)
        .padding(10)
        .borderRadius(14)
        .backgroundColor($r('app.color.mine'))
        .constraintSize({ maxWidth: '82%' })
        .opacity(m.local ? 0.7 : 1)
      }
      .width('100%')
    } else if (m.kind === 'bot' || m.kind === 'member') {
      Column({ space: 4 }) {
        if (m.kind === 'member' && m.name !== null) {
          Text(m.name).fontSize(12).fontColor($r('app.color.sub'))
        }
        Column({ space: 6 }) {
          this.Atts(m.attachments)
          if ((m.text ?? '').length > 0) {
            MdText({ text: m.text ?? '' })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .padding(10)
        .borderRadius(14)
        .backgroundColor($r('app.color.card'))
        .constraintSize({ maxWidth: '92%' })
      }
      .alignItems(HorizontalAlign.Start)
      .width('100%')
    } else if ((m.text ?? '').length > 0) {
      Text(m.text ?? '').fontSize(12).fontColor($r('app.color.sub')).width('100%').textAlign(TextAlign.Center)
    }
  }

  @Builder
  ChatView() {
    Column() {
      Row({ space: 8 }) {
        Text('‹').fontSize(30).fontColor($r('app.color.text')).padding({ right: 8 }).onClick(() => this.closeChat())
        Text(this.openName).fontSize(18).fontWeight(FontWeight.Medium).fontColor($r('app.color.text'))
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
        Text(this.isMuted(this.openKind, this.openId) ? '已静音' : '提醒').fontSize(13)
          .fontColor($r('app.color.sub')).padding(6).onClick(() => this.toggleMute(this.openKind, this.openId))
        if (this.openKind === 'bot') {
          Text('···').fontSize(20).fontColor($r('app.color.text')).padding(6)
            .onClick(() => this.openProfile(this.openId, 'chat'))
        }
        if (this.openKind === 'room') {
          Text('···').fontSize(20).fontColor($r('app.color.text')).padding(6).onClick(() => {
            const r = this.currentRoom();
            this.roomName = r === null ? '' : r.name;
            this.notice = '';
            this.screen = 'roominfo';
          })
        }
      }
      .padding({ left: 12, right: 16, top: 8, bottom: 8 })
      .width('100%')
      List({ space: 12, scroller: this.scroller }) {
        if (this.nextCursor.length > 0) {
          ListItem() {
            Text('查看更早的消息').fontSize(13).fontColor($r('app.color.accent')).width('100%')
              .textAlign(TextAlign.Center).onClick(() => this.loadOlder())
          }
        }
        ForEach(this.messages, (m: ChatItem, i: number) => {
          ListItem() {
            this.Bubble(m)
          }
        }, (m: ChatItem, i: number) => (m.seq ?? -1) + ':' + (m.clientMessageId ?? '') + ':' + i + ':' + m.local)
        ForEach(this.live.filter((x: LiveReply) => RoomStreams.shown(x) !== null), (x: LiveReply) => {
          ListItem() {
            Column({ space: 4 }) {
              Text((x.name ?? '成员') + (x.endedAt > 0 ? '' : ' 正在回复…')).fontSize(12).fontColor($r('app.color.sub'))
              Column() {
                MdText({ text: RoomStreams.shown(x) ?? '' })
              }
              .padding(10).borderRadius(14).backgroundColor($r('app.color.card'))
              .constraintSize({ maxWidth: '92%' })
            }
            .alignItems(HorizontalAlign.Start)
            .width('100%')
          }
        }, (x: LiveReply) => x.attemptId + ':' + x.text.length + ':' + x.endedAt)
        if (this.wait.phase === WaitPhase.Streaming && this.wait.text.length > 0) {
          ListItem() {
            Column() {
              MdText({ text: this.wait.text })
            }
            .padding(10).borderRadius(14).backgroundColor($r('app.color.card'))
            .constraintSize({ maxWidth: '92%' })
          }
        } else if (this.wait.phase === WaitPhase.Waiting || this.wait.phase === WaitPhase.Streaming) {
          ListItem() {
            Row({ space: 8 }) {
              LoadingProgress().width(20).height(20)
              Text('正在想…').fontSize(14).fontColor($r('app.color.sub'))
            }
          }
        } else if (this.wait.phase === WaitPhase.Failed) {
          ListItem() {
            Text(this.wait.error).fontSize(14).fontColor('#E5484D')
              .onClick(() => this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.Dismiss)))
          }
        }
      }
      .padding({ left: 12, right: 12 })
      .layoutWeight(1)
      .width('100%')
      if (this.notice.length > 0) {
        Text(this.notice).fontSize(13).fontColor('#E5484D').padding(6)
          .onClick(() => this.notice = '')
      }
      ForEach(ChatListLogic.approvalsFor(this.approvals, this.openKind, this.openId), (a: ApprovalInfo) => {
        this.ApprovalCard(a)
      }, (a: ApprovalInfo) => a.id)
      if (this.pending.length > 0) {
        Scroll() {
          Row({ space: 8 }) {
            ForEach(this.pending, (p: Pending) => {
              Column({ space: 2 }) {
                Text(p.name).fontSize(12).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).width(96)
                Text(p.failed.length > 0 ? p.failed : (p.uploadId.length > 0 ? AttachLogic.size(p.bytes) : '上传中…'))
                  .fontSize(11).fontColor(p.failed.length > 0 ? '#E5484D' : $r('app.color.sub')).maxLines(2).width(96)
                Text('移除').fontSize(11).fontColor($r('app.color.accent')).onClick(() => this.removePending(p.localId))
              }
              .padding(6).borderRadius(8).backgroundColor($r('app.color.card'))
            }, (p: Pending) => p.localId + p.uploadId + p.failed)
          }
          .padding({ left: 10, right: 10 })
        }
        .scrollable(ScrollDirection.Horizontal)
        .width('100%')
      }
      if (this.mentionRows().length > 0) {
        List() {
          ForEach(this.mentionRows(), (r: MentionRow) => {
            ListItem() {
              Row({ space: 10 }) {
                Avatar({ name: r.name, dim: 28, group: r.everyone })
                Text(r.name).fontSize(15).fontColor($r('app.color.text'))
              }
              .padding({ left: 14, right: 14, top: 8, bottom: 8 })
              .width('100%')
              .onClick(() => this.pickMention(r))
            }
          }, (r: MentionRow) => r.id + r.name)
        }
        .constraintSize({ maxHeight: 220 })
        .backgroundColor($r('app.color.card'))
        .borderRadius(12)
        .margin({ left: 10, right: 10 })
      }
      if (this.openKind === 'room' && this.answerHint().length > 0) {
        Text(this.answerHint()).fontSize(12).fontColor($r('app.color.sub')).padding({ left: 16 }).width('100%')
      }
      Row({ space: 8 }) {
        Text('＋').fontSize(26).fontColor($r('app.color.text')).padding({ left: 4, right: 4 })
          .bindMenu(this.attachOpen, this.AttachMenu, { onDisappear: () => this.attachOpen = false })
          .onClick(() => this.attachOpen = true)
        TextArea({ placeholder: this.openKind === 'room' ? '发消息，输入 @ 可以指定谁回复' : '发消息', text: this.input })
          .onChange((v: string) => this.input = v)
          .constraintSize({ minHeight: 42, maxHeight: 140 })
          .layoutWeight(1)
        if (this.isRunning()) {
          Button('停止').height(40).backgroundColor('#E5484D').onClick(() => this.stop())
        } else {
          Button('发送').height(40).backgroundColor('#000A25')
            .enabled((this.input.trim().length > 0 || this.pending.some((p: Pending) => p.uploadId.length > 0)) && !this.sending)
            .onClick(() => this.send())
        }
      }
      .padding(10)
      .width('100%')
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  TopBar(title: string, back: () => void) {
    Row({ space: 8 }) {
      Text('‹').fontSize(30).fontColor($r('app.color.text')).padding({ right: 8 }).onClick(back)
      Text(title).fontSize(18).fontWeight(FontWeight.Medium).fontColor($r('app.color.text')).layoutWeight(1)
    }
    .padding({ left: 12, right: 16, top: 8, bottom: 8 })
    .width('100%')
  }

  @Builder
  PickRow(b: BotInfo, label: string, on: boolean, tap: () => void) {
    Row({ space: 12 }) {
      Avatar({ name: b.name, color: colorOf(b.color), dim: 36 })
      Column() {
        Text(b.name).fontSize(16).fontColor($r('app.color.text'))
        if ((b.role ?? '').length > 0) {
          Text(b.role ?? '').fontSize(12).fontColor($r('app.color.sub'))
        }
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      Text(label).fontSize(14).fontColor(on ? $r('app.color.accent') : $r('app.color.sub'))
    }
    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
    .width('100%')
    .onClick(tap)
  }

  @Builder
  NewRoomView() {
    Column() {
      this.TopBar('新建群聊', () => {
        this.screen = 'list';
      })
      Text(this.roomDraft.admin === null ? '第一步：选一位群主，他会先接话' : '再选至少一位成员')
        .fontSize(14).fontColor($r('app.color.sub')).padding({ left: 16, bottom: 6 }).width('100%')
      List() {
        ForEach(this.bots, (b: BotInfo) => {
          ListItem() {
            if (this.roomDraft.admin === null) {
              this.PickRow(b, '设为群主', false, () => {
                this.roomDraft = RoomRules.pickAdmin(this.roomDraft, b.id);
              })
            } else {
              this.PickRow(b, b.id === this.roomDraft.admin ? '群主' : (this.roomDraft.members.indexOf(b.id) >= 0 ? '已选' : '选择'),
                b.id === this.roomDraft.admin || this.roomDraft.members.indexOf(b.id) >= 0, () => {
                  this.roomDraft = RoomRules.toggle(this.roomDraft, b.id);
                })
            }
          }
        }, (b: BotInfo) => b.id)
      }
      .layoutWeight(1)
      if (this.roomDraft.admin !== null) {
        TextInput({ placeholder: '群名（可不填）', text: this.roomDraft.name }).margin(12).onChange((v: string) => {
          const d = new NewRoomDraft();
          d.admin = this.roomDraft.admin;
          d.members = this.roomDraft.members;
          d.name = v;
          this.roomDraft = d;
        })
      }
      if (this.notice.length > 0) {
        Text(this.notice).fontSize(13).fontColor('#E5484D')
      }
      Button(this.busy ? '正在创建…' : '创建').width('90%').height(46).margin({ bottom: 16 }).backgroundColor('#000A25')
        .enabled(RoomRules.canCreate(this.roomDraft) && !this.busy).onClick(() => this.createRoom())
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  RoomInfoView() {
    Column() {
      this.TopBar('群设置', () => {
        this.screen = 'chat';
      })
      Scroll() {
        Column({ space: 12 }) {
          Text('群名').fontSize(13).fontColor($r('app.color.sub')).width('100%')
          Row({ space: 8 }) {
            TextInput({ text: this.roomName }).onChange((v: string) => this.roomName = v).layoutWeight(1)
            Button('保存').onClick(() => this.renameRoom())
          }
          Text('不 @ 任何人时').fontSize(13).fontColor($r('app.color.sub')).width('100%')
          Row({ space: 8 }) {
            Button('所有成员回复').layoutWeight(1)
              .backgroundColor((this.currentRoom()?.mode ?? 'everyone') !== 'admin' ? '#000A25' : '#8A8F99')
              .onClick(() => this.setMode('everyone'))
            Button('群主先回复').layoutWeight(1)
              .backgroundColor(this.currentRoom()?.mode === 'admin' ? '#000A25' : '#8A8F99')
              .onClick(() => this.setMode('admin'))
          }
          Text('成员').fontSize(13).fontColor($r('app.color.sub')).width('100%')
          ForEach(this.roomMembers(), (b: BotInfo) => {
            Row() {
              Row() {
                this.PickRow(b, '资料与模型', true, () => this.openProfile(b.id, 'roominfo'))
              }
              .layoutWeight(1)
              if (b.id !== this.currentRoom()?.admin) {
                Text('移出').fontSize(14).fontColor('#E5484D').padding(8).onClick(() => this.removeMember(b.id))
              } else {
                Text('群主').fontSize(14).fontColor($r('app.color.sub')).padding(8)
              }
            }
          }, (b: BotInfo) => 'm' + b.id)
          Text('可以加入的助手').fontSize(13).fontColor($r('app.color.sub')).width('100%')
          ForEach(this.bots.filter((b: BotInfo) => (this.currentRoom()?.members ?? []).indexOf(b.id) < 0), (b: BotInfo) => {
            this.PickRow(b, '加入', true, () => this.addMember(b.id))
          }, (b: BotInfo) => 'a' + b.id)
          if (this.notice.length > 0) {
            Text(this.notice).fontSize(13).fontColor('#E5484D')
          }
        }
        .padding(16)
      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  Field(label: string) {
    Text(label).fontSize(13).fontColor($r('app.color.sub')).width('100%').margin({ top: 6 })
  }

  @Builder
  ProfileView() {
    Column() {
      this.TopBar('助手资料', () => {
        this.screen = this.profileBack;
      })
      if (this.profile === null) {
        if (this.formError.length > 0) {
          Text(this.formError).fontSize(14).fontColor('#E5484D').padding(16)
        } else {
          LoadingProgress().width(32).height(32).margin({ top: 60 })
        }
      } else {
        Scroll() {
          Column({ space: 8 }) {
            Row({ space: 12 }) {
              Avatar({ name: this.profile.name, color: colorOf(this.profile.color), dim: 56 })
              Column() {
                Text(this.profile.name).fontSize(20).fontWeight(FontWeight.Bold).fontColor($r('app.color.text'))
                Text(this.profile.role).fontSize(14).fontColor($r('app.color.sub'))
              }
              .alignItems(HorizontalAlign.Start)
            }
            .width('100%')
            this.Field('使用的模型：' + this.modelName(this.profile.model))
            if (this.options !== null) {
              ForEach(this.options.models, (m: ModelOption) => {
                Row() {
                  Column() {
                    Text(m.name).fontSize(15).fontColor($r('app.color.text'))
                    if (m.providerName !== null) {
                      Text(m.providerName).fontSize(12).fontColor($r('app.color.sub'))
                    }
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)
                  if (this.profile?.model === m.ref) {
                    Text('正在用').fontSize(13).fontColor($r('app.color.accent'))
                  } else if (m.needsKey) {
                    Text('需在电脑上设置').fontSize(12).fontColor($r('app.color.sub'))
                  } else {
                    Text('换成这个').fontSize(13).fontColor($r('app.color.accent'))
                  }
                }
                .padding(10)
                .borderRadius(10)
                .backgroundColor($r('app.color.card'))
                .width('100%')
                .onClick(() => {
                  if (!m.needsKey && this.profile?.model !== m.ref) {
                    this.setModel(m.ref);
                  }
                })
              }, (m: ModelOption) => m.ref)
            }
            this.Field('职责')
            TextInput({ text: this.profile.role }).onChange((v: string) => {
              const p = this.profile;
              if (p !== null) {
                p.role = v;
              }
            })
            this.Field('工作说明')
            TextArea({ text: this.profile.instructions }).height(120).onChange((v: string) => {
              const p = this.profile;
              if (p !== null) {
                p.instructions = v;
              }
            })
            Button('保存').width('100%').backgroundColor('#000A25').onClick(() => {
              const p = this.profile;
              if (p !== null) {
                const b: JObj = {};
                b['role'] = p.role;
                b['instructions'] = p.instructions;
                this.saveProfile(b);
              }
            })
            if (this.formError.length > 0) {
              Text(this.formError).fontSize(13).fontColor(this.formError === '已保存' ? $r('app.color.sub') : '#E5484D')
            }
          }
          .padding(16)
        }
        .layoutWeight(1)
      }
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  NewBotView() {
    Column() {
      this.TopBar('新建助手', () => {
        this.screen = 'list';
      })
      Scroll() {
        Column({ space: 8 }) {
          this.Field('名字')
          TextInput({ text: this.botDraft.name }).maxLength(this.options?.nameMax ?? 40).onChange((v: string) => {
            const n = this.copyDraft();
            n.name = v;
            this.botDraft = n;
          })
          this.Field('职责（可不填）')
          TextInput({ text: this.botDraft.role, placeholder: '比如：查资料' }).maxLength(this.options?.roleMax ?? 24)
            .onChange((v: string) => {
              const n = this.copyDraft();
              n.role = v;
              this.botDraft = n;
            })
          this.Field('模型')
          if (this.options === null) {
            LoadingProgress().width(24).height(24)
          } else {
            ForEach(this.options.models, (m: ModelOption) => {
              Row() {
                Text(m.name).fontSize(15).fontColor($r('app.color.text')).layoutWeight(1)
                Text(this.botDraft.model === m.ref ? '已选' : (m.needsKey ? '需在电脑上设置' : ''))
                  .fontSize(13).fontColor($r('app.color.accent'))
              }
              .padding(10).borderRadius(10).width('100%')
              .backgroundColor(this.botDraft.model === m.ref ? $r('app.color.mine') : $r('app.color.card'))
              .onClick(() => {
                if (m.needsKey) {
                  return;
                }
                const n = this.copyDraft();
                n.model = m.ref;
                n.effort = '';
                this.botDraft = n;
                this.loadEfforts(m.ref);
              })
            }, (m: ModelOption) => m.ref)
          }
          if (this.efforts.length > 0) {
            this.Field('思考深度')
            Flex({ wrap: FlexWrap.Wrap }) {
              ForEach(this.efforts, (e: EffortOption) => {
                Text(e.name).fontSize(14).padding({ left: 12, right: 12, top: 6, bottom: 6 }).margin(4)
                  .borderRadius(14)
                  .backgroundColor(this.botDraft.effort === e.id ? '#000A25' : $r('app.color.card'))
                  .fontColor(this.botDraft.effort === e.id ? Color.White : $r('app.color.text'))
                  .onClick(() => {
                    const n = this.copyDraft();
                    n.effort = e.id;
                    this.botDraft = n;
                  })
              }, (e: EffortOption) => e.id)
            }
          }
          this.Field('工作说明（可不填）')
          TextArea({ text: this.botDraft.instructions, placeholder: '告诉它该怎么做事' }).height(100)
            .onChange((v: string) => {
              const n = this.copyDraft();
              n.instructions = v;
              this.botDraft = n;
            })
          if (this.formError.length > 0) {
            Text(this.formError).fontSize(13).fontColor('#E5484D')
          }
          Button(this.busy ? '正在创建…' : '创建').width('100%').height(46).backgroundColor('#000A25')
            .enabled(!this.busy && this.options !== null).onClick(() => this.createBot())
        }
        .padding(16)
      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  SearchView() {
    Column() {
      Row({ space: 8 }) {
        Text('‹').fontSize(30).fontColor($r('app.color.text')).onClick(() => {
          this.screen = 'list';
        })
        Search({ value: this.searchQ, placeholder: '搜索助手、群聊和消息' }).layoutWeight(1)
          .onChange((v: string) => this.doSearch(v))
      }
      .padding({ left: 12, right: 12, top: 8, bottom: 8 })
      List() {
        ForEach(this.searchHits, (h: SearchHit, i: number) => {
          ListItem() {
            Row({ space: 10 }) {
              Column({ space: 2 }) {
                Text(h.name).fontSize(15).fontColor($r('app.color.text'))
                if (h.text.length > 0) {
                  Text((h.who !== null && h.message ? h.who + '：' : '') + h.text).fontSize(13)
                    .fontColor($r('app.color.sub')).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                }
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)
              Text(ProfileLogic.tag(h)).fontSize(12).fontColor($r('app.color.sub'))
            }
            .padding({ left: 16, right: 16, top: 10, bottom: 10 })
            .onClick(() => this.openChat(h.kind === 'room' ? 'room' : 'bot', h.id, h.name))
          }
        }, (h: SearchHit, i: number) => h.kind + h.id + i + h.text.length)
      }
      .divider({ strokeWidth: 0.5, color: $r('app.color.line') })
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  SettingsView() {
    Column() {
      this.TopBar('设置', () => {
        this.screen = 'list';
      })
      Scroll() {
        Column({ space: 10 }) {
          Row() {
            Text('连接方式').fontSize(15).fontColor($r('app.color.text')).layoutWeight(1)
            Text(ProfileLogic.network(this.store?.current().baseUrl ?? '')).fontSize(14).fontColor($r('app.color.sub'))
          }
          .padding(12).borderRadius(12).backgroundColor($r('app.color.card')).width('100%')
          Row() {
            Text('新消息提醒').fontSize(15).fontColor($r('app.color.text')).layoutWeight(1)
            Toggle({ type: ToggleType.Switch, isOn: this.prefs.notify }).onChange((on: boolean) => {
              this.savePrefs(this.prefs.withNotify(on));
              if (on) {
                Notifier.ask(this.ctx());
              }
            })
          }
          .padding(12).borderRadius(12).backgroundColor($r('app.color.card')).width('100%')
          Text('应用在后台久了会被系统暂停，这时收不到提醒，打开应用后会补上。').fontSize(12)
            .fontColor($r('app.color.sub')).width('100%')
          if (this.prefs.muted.length > 0) {
            Text('已静音的会话').fontSize(13).fontColor($r('app.color.sub')).width('100%')
            ForEach(this.prefs.muted, (k: string) => {
              Row() {
                Text(this.nameOf(k.startsWith('room:') ? 'room' : 'bot', k.substring(k.indexOf(':') + 1)))
                  .fontSize(15).fontColor($r('app.color.text')).layoutWeight(1)
                Text('取消静音').fontSize(13).fontColor($r('app.color.accent')).onClick(() => {
                  this.savePrefs(this.prefs.withMuted(k, false));
                })
              }
              .padding(12).borderRadius(12).backgroundColor($r('app.color.card')).width('100%')
            }, (k: string) => k)
          }
          Button('重新配对').width('100%').height(44).backgroundColor('#E5484D').margin({ top: 20 })
            .onClick(() => {
              this.confirmRepair = true;
            })
          if (this.confirmRepair) {
            Text('这台手机会断开，需要在电脑上重新扫码。').fontSize(13).fontColor($r('app.color.sub'))
            Row({ space: 10 }) {
              Button('取消').layoutWeight(1).backgroundColor('#8A8F99').onClick(() => {
                this.confirmRepair = false;
              })
              Button('确定断开').layoutWeight(1).backgroundColor('#E5484D').onClick(() => {
                this.confirmRepair = false;
                this.repair();
              })
            }
          }
          Text('版本 0.1.0').fontSize(12).fontColor($r('app.color.sub')).margin({ top: 20 })
        }
        .padding(16)
      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
  }

  build() {
    Column() {
      if (this.screen === 'loading') {
        LoadingProgress().width(40).height(40).margin({ top: 200 })
      } else if (this.screen === 'pair') {
        this.PairView()
      } else if (this.screen === 'chat') {
        this.ChatView()
      } else if (this.screen === 'settings') {
        this.SettingsView()
      } else if (this.screen === 'profile') {
        this.ProfileView()
      } else if (this.screen === 'newbot') {
        this.NewBotView()
      } else if (this.screen === 'search') {
        this.SearchView()
      } else if (this.screen === 'newroom') {
        this.NewRoomView()
      } else if (this.screen === 'roominfo') {
        this.RoomInfoView()
      } else {
        this.ListView()
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.bg'))
  }
}
