import { notificationManager } from '@kit.NotificationKit';
import { common, wantAgent, Want } from '@kit.AbilityKit';
import { Alert, NotifyLogic } from '../common/Extra';

function hash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) | 0;
  }
  return Math.abs(h) % 1000000000;
}

/** Phone notifications for new messages and requests waiting for a yes/no. */
export class Notifier {
  static async ask(ctx: common.UIAbilityContext): Promise<void> {
    try {
      const ok = await notificationManager.isNotificationEnabled();
      if (!ok) {
        await notificationManager.requestEnableNotification(ctx);
      }
    } catch (e) {
      // user declined; the app keeps working without notifications
    }
  }

  static async show(bundleName: string, a: Alert): Promise<void> {
    try {
      const params: Record<string, Object> = {};
      params['chat'] = a.kind + ':' + a.id;
      const want: Want = { bundleName: bundleName, abilityName: 'EntryAbility', parameters: params };
      const info: wantAgent.WantAgentInfo = {
        wants: [want],
        actionType: wantAgent.OperationType.START_ABILITY,
        requestCode: hash(a.tag),
        actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG]
      };
      const agent = await wantAgent.getWantAgent(info);
      const line = NotifyLogic.countLine(a.count);
      const req: notificationManager.NotificationRequest = {
        id: hash(a.tag),
        content: {
          notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
          normal: { title: a.name, text: line.length > 0 ? a.text + '（' + line + '）' : a.text }
        },
        wantAgent: agent
      };
      await notificationManager.publish(req);
    } catch (e) {
      // not allowed to notify
    }
  }

  static async clear(kind: string, id: string): Promise<void> {
    try {
      await notificationManager.cancel(hash(NotifyLogic.key(kind, id)));
    } catch (e) {
      // nothing shown
    }
  }

  static async clearAll(): Promise<void> {
    try {
      await notificationManager.cancelAll();
    } catch (e) {
      // nothing shown
    }
  }
}
