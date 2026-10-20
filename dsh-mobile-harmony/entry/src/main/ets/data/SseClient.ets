import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { SseEvent, SseParser } from '../common/Sse';

/** One live connection to the computer's `/stream`. */
export class SseClient {
  private req: http.HttpRequest | null = null;
  private closed: boolean = false;

  open(url: string, token: string, onEvent: (e: SseEvent) => void, onClose: (code: number) => void): void {
    this.closed = false;
    const req = http.createHttp();
    this.req = req;
    const parser = new SseParser();
    const decoder = util.TextDecoder.create('utf-8');
    req.on('dataReceive', (data: ArrayBuffer) => {
      const text = decoder.decodeToString(new Uint8Array(data), { stream: true });
      for (const ev of parser.feed(text)) {
        if (!this.closed) {
          onEvent(ev);
        }
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
      this.finish(code, onClose);
    }).catch(() => {
      this.finish(0, onClose);
    });
  }

  private finish(code: number, onClose: (code: number) => void): void {
    const wasOpen = !this.closed;
    this.close();
    if (wasOpen) {
      onClose(code);
    }
  }

  close(): void {
    this.closed = true;
    if (this.req !== null) {
      try {
        this.req.off('dataReceive');
        this.req.destroy();
      } catch (e) {
        // already closed
      }
      this.req = null;
    }
  }
}
