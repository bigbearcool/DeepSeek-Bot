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
import { Avatar, MdText, colorOf } from '../view/Bits';

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
  @StorageLink('foreground') foreground: boolean = true;
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
    return false;
  }

  private ctx(): common.UIAbilityContext {
    return this.getUIContext().getHostContext() as common.UIAbilityContext;
  }

  private async start(): Promise<void> {
    this.store = await SessionStore.open(this.ctx());
    this.auth = new Auth(this.store);
    const s = this.store.current();
    this.pairAddr = s.baseUrl.replace('/api/mobile-bridge/v1', '');
    const r = LaunchRouter.route(true, s.paired, s.lastChat);
    if (r === StartRoute.Pair) {
      this.screen = 'pair';
      return;
    }
    this.goList();
    if (r === StartRoute.Chat) {
      const k = s.lastChat;
      const kind = k.startsWith('room:') ? 'room' : 'bot';
      const id = k.substring(k.indexOf(':') + 1);
      this.openChat(kind, id, '');
    }
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
        if (this.foreground && (this.screen === 'list' || this.screen === 'chat')) {
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
    this.notice = '';
    this.screen = 'chat';
    this.chats = ChatListLogic.clearOpen(this.chats, kind, id);
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
      const url = kind === 'room' ? api.streamUrl('', id, -1) : api.streamUrl(id, '', -1);
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

  private async send(): Promise<void> {
    const text = this.input.trim();
    if (text.length === 0 || this.auth === null || this.sending) {
      return;
    }
    const cid = util.generateRandomUUID(false);
    const mine = newItem('user', text, Date.now());
    mine.local = true;
    mine.clientMessageId = cid;
    this.messages = this.messages.concat([mine]);
    this.input = '';
    this.sending = true;
    this.wait = WaitMachine.reduce(this.wait, new WaitEvent(WaitKind.Submitted, cid));
    this.scrollEnd();
    const kind = this.openKind;
    const id = this.openId;
    try {
      const r = await this.auth.with((t: string, api: BridgeApi) =>
        kind === 'room' ? api.roomSend(t, id, text, cid, []) : api.send(t, id, text, cid, []));
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
  ListView() {
    Column() {
      Row() {
        Text('消息').fontSize(26).fontWeight(FontWeight.Bold).fontColor($r('app.color.text')).layoutWeight(1)
      }
      .padding({ left: 16, right: 16, top: 12, bottom: 8 })
      if (this.offline) {
        Text('暂时连不上电脑，正在重试').fontSize(13).fontColor($r('app.color.sub')).padding(8)
      }
      if (this.approvals.length > 0) {
        Text(this.approvals.length + ' 个操作在等你确认').fontSize(14).fontColor('#B25E00')
          .backgroundColor('#FFF4E5').borderRadius(10).padding(10).margin({ left: 16, right: 16, bottom: 6 })
          .width('92%')
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
  Bubble(m: ChatItem) {
    if (m.kind === 'user') {
      Row() {
        Blank()
        Column() {
          Text(m.text ?? '').fontSize(15).fontColor($r('app.color.text')).copyOption(CopyOptions.LocalDevice)
        }
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
        Column() {
          MdText({ text: m.text ?? '' })
        }
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
      Row({ space: 8 }) {
        TextArea({ placeholder: '发消息', text: this.input })
          .onChange((v: string) => this.input = v)
          .constraintSize({ minHeight: 42, maxHeight: 140 })
          .layoutWeight(1)
        if (this.isRunning()) {
          Button('停止').height(40).backgroundColor('#E5484D').onClick(() => this.stop())
        } else {
          Button('发送').height(40).backgroundColor('#000A25')
            .enabled(this.input.trim().length > 0 && !this.sending).onClick(() => this.send())
        }
      }
      .padding(10)
      .width('100%')
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
      } else {
        this.ListView()
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.bg'))
  }
}
