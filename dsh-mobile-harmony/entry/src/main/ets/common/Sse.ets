export class SseEvent {
  event: string;
  data: string;

  constructor(event: string, data: string) {
    this.event = event;
    this.data = data;
  }
}

/** Splits the computer's live feed into events; text may arrive in any chunk sizes. */
export class SseParser {
  private buf: string = '';
  private event: string = 'message';
  private data: string[] = [];

  feed(chunk: string): SseEvent[] {
    this.buf += chunk;
    const out: SseEvent[] = [];
    let nl = this.buf.indexOf('\n');
    while (nl >= 0) {
      let line = this.buf.substring(0, nl);
      this.buf = this.buf.substring(nl + 1);
      if (line.endsWith('\r')) {
        line = line.substring(0, line.length - 1);
      }
      if (line.length === 0) {
        if (this.data.length > 0) {
          out.push(new SseEvent(this.event, this.data.join('\n')));
        }
        this.event = 'message';
        this.data = [];
      } else if (line.startsWith(':')) {
        // keepalive
      } else if (line.startsWith('event:')) {
        this.event = line.substring(6).trim();
      } else if (line.startsWith('data:')) {
        let d = line.substring(5);
        if (d.startsWith(' ')) {
          d = d.substring(1);
        }
        this.data.push(d);
      }
      nl = this.buf.indexOf('\n');
    }
    return out;
  }
}
