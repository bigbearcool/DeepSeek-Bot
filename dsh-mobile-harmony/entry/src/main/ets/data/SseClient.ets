import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { SseEvent, SseParser } from '../common/Sse';

/**
 * One live connection to the computer's `/stream`.
 * Each open() gets its own generation: an old connection that finishes late must not
 * close the new one or report a drop for it.
 */
export class SseClient {
  private req: http.HttpRequest | null = null;
  private gen: number = 0;

  open(url: string, token: string, onEvent: (e: SseEvent) => void, onClose: (code: number) => void): void {
    this.close();
    const my = this.gen;
    const req = http.createHttp();
    this.req = req;
    const parser = new SseParser();
    const decoder = util.TextDecoder.create('utf-8');
    hilog.info(0x0d50, 'dshBridge', 'stream open #%{public}d %{public}s', my, url.split('?')[0]);
    req.on('dataReceive', (data: ArrayBuffer) => {
      if (my !== this.gen) {
        return;
      }
      const text = decoder.decodeToString(new Uint8Array(data), { stream: true });
      for (const ev of parser.feed(text)) {
        if (my !== this.gen) {
          return;
        }
        hilog.info(0x0d50, 'dshBridge', 'stream #%{public}d event %{public}s', my, ev.event);
        onEvent(ev);
      }
    });
    const header: Record<string, string> = {};
    header['Authorization'] = 'Bearer ' + token;
    header['Accept'] = 'text/event-stream';
    req.requestInStream(url, {
      method: http.RequestMethod.GET,
      header: header,
      connectTimeout: 15000,
      readTimeout: 24 * 3600 * 1000
    }).then((code: number) => {
      this.finish(my, code, onClose);
    }).catch(() => {
      this.finish(my, 0, onClose);
    });
  }

  /** True while connection `my` is still the live one. */
  static isCurrent(my: number, gen: number): boolean {
    return my === gen;
  }

  private finish(my: number, code: number, onClose: (code: number) => void): void {
    if (!SseClient.isCurrent(my, this.gen)) {
      return;
    }
    hilog.warn(0x0d50, 'dshBridge', 'stream #%{public}d closed %{public}d', my, code);
    this.close();
    onClose(code);
  }

  close(): void {
    this.gen++;
    const r = this.req;
    this.req = null;
    if (r !== null) {
      try {
        r.off('dataReceive');
        r.destroy();
      } catch (e) {
      }
    }
  }
}
