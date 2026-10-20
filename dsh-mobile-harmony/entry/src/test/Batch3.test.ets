import { describe, it, expect } from '@ohos/hypium';
import { AttachLogic, ChatPrefs, NotifyLogic, ProfileLogic } from '../main/ets/common/Extra';
import { ChatListResponse, Attachment, BotInfo, ChatEntry, SearchHit, parseBotOptions, parseChatItem, botDraftBody, NewBotDraft } from '../main/ets/common/Models';
import { BotRules } from '../main/ets/common/Group';
import { J } from '../main/ets/common/Json';

function att(type: string): Attachment {
  const a: Attachment = { type: type, id: 'x', name: 'a.pdf', bytes: 1, mediaType: null, width: null, height: null, localUri: null };
  return a;
}

function chat(kind: string, id: string, name: string): ChatEntry {
  const e: ChatEntry = { kind: kind, id: id, name: name, color: null, main: false, preview: '', lastAt: null, unread: 0, running: false, members: [] };
  return e;
}

export default function batch3Test() {
  describe('attach', () => {
    it('size', 0, () => {
      expect(AttachLogic.size(12)).assertEqual('12 B');
      expect(AttachLogic.size(340 * 1024)).assertEqual('340 KB');
      expect(AttachLogic.size(1.2 * 1048576)).assertEqual('1.2 MB');
      expect(AttachLogic.size(null)).assertEqual('');
    });
    it('ext', 0, () => {
      expect(AttachLogic.ext('报告.docx')).assertEqual('DOCX');
      expect(AttachLogic.ext('noext')).assertEqual('文件');
      expect(AttachLogic.isImageName('a.JPG')).assertTrue();
      expect(AttachLogic.isImageName('a.pdf')).assertFalse();
    });
    it('label', 0, () => {
      expect(AttachLogic.label([att('image')])).assertEqual('[图片]');
      expect(AttachLogic.label([att('image'), att('image')])).assertEqual('[2 张图片]');
      expect(AttachLogic.label([att('image'), att('file')])).assertEqual('[2 个文件]');
      expect(AttachLogic.tooBig(26 * 1024 * 1024)).assertTrue();
    });
    it('parseItemAttachments', 0, () => {
      const i = parseChatItem(J.parse('{"kind":"user","attachments":[{"type":"image","id":"u1","width":10},{"type":"other"}]}'));
      expect(i.attachments.length).assertEqual(2);
      expect(i.attachments[0].width).assertEqual(10);
      expect(i.attachments[1].type).assertEqual('file');
    });
  });

  describe('options', () => {
    it('parse', 0, () => {
      const o = parseBotOptions(J.parse('{"models":[{"ref":"m1"},{"ref":"m2","name":"二号"}],"defaultModel":"m2",' +
        '"folders":{"team":"/t","picker":"browse","folders":[{"path":"/a"}]},"limits":{"name":20}}'));
      expect(o.models[0].name).assertEqual('m1');
      expect(BotRules.startModel(o)).assertEqual('m2');
      expect(o.canBrowse).assertTrue();
      expect(o.folders[0].title).assertEqual('/a');
      expect(o.nameMax).assertEqual(20);
      expect(o.roleMax).assertEqual(24);
      expect(o.agentPresets).assertNull();
    });
    it('draftBody', 0, () => {
      const d = new NewBotDraft();
      d.name = ' 小明 ';
      d.shape = 'gumdrop';
      const b = botDraftBody(d);
      expect(J.str(b, 'name')).assertEqual('小明');
      expect(J.has(b, 'role')).assertFalse();
      expect(J.str(J.obj(b, 'avatar') ?? b, 'shape')).assertEqual('gumdrop');
    });
  });

  describe('profile', () => {
    it('network', 0, () => {
      expect(ProfileLogic.network('http://192.168.1.5:3081/api')).assertEqual('局域网');
      expect(ProfileLogic.network('http://box.local')).assertEqual('局域网');
      expect(ProfileLogic.network('https://example.com:8443')).assertEqual('外网');
      expect(ProfileLogic.network('http://172.40.0.1')).assertEqual('外网');
    });
    it('search', 0, () => {
      const chats = [chat('bot', 'a', '研究员'), chat('room', 'r', '研发群'), chat('bot', 'b', '写手')];
      const bots: BotInfo[] = [{ id: 'a', name: '研究员', role: '查资料', sessionId: null, model: null, main: false, color: null, shape: null }];
      const msg: SearchHit = { kind: 'bot', id: 'b', name: '写手', who: '写手', text: '研究一下', message: true };
      const r = ProfileLogic.results(chats, bots, '研', [msg]);
      expect(r.length).assertEqual(3);
      expect(r[0].text).assertEqual('查资料');
      expect(ProfileLogic.tag(r[1])).assertEqual('群聊');
      expect(ProfileLogic.tag(r[2])).assertEqual('消息');
      expect(ProfileLogic.results(chats, bots, '', [msg]).length).assertEqual(3);
      expect(ProfileLogic.addable(bots, ['a'], '').length).assertEqual(0);
    });
  });

  describe('notify', () => {
    it('firstLookSilent', 0, () => {
      const now: ChatListResponse = { available: true, chats: [chat('bot', 'a', '甲')], approvals: [] };
      expect(NotifyLogic.alerts(null, now, new ChatPrefs(), '').length).assertEqual(0);
    });
  });

  describe('prefs', () => {
    it('roundTrip', 0, () => {
      const p = ChatPrefs.decode('').withMuted('bot:a', true).withNotify(false);
      const q = ChatPrefs.decode(p.encode());
      expect(q.notify).assertFalse();
      expect(q.muted.join(',')).assertEqual('bot:a');
      expect(q.withMuted('bot:a', false).muted.length).assertEqual(0);
    });
  });
}
