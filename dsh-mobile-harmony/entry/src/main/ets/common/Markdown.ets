/** Block-level reading of a reply, enough for the chat bubbles. */
export class MdBlock {
  type: string; // 'p' | 'h' | 'li' | 'ol' | 'quote' | 'code'
  text: string;
  mark: string;

  constructor(type: string, text: string, mark: string = '') {
    this.type = type;
    this.text = text;
    this.mark = mark;
  }
}

export class MdSpan {
  text: string;
  bold: boolean;
  code: boolean;

  constructor(text: string, bold: boolean, code: boolean) {
    this.text = text;
    this.bold = bold;
    this.code = code;
  }
}

const OL = new RegExp('^(\\d+)[.)]\\s+(.*)$');
const H = new RegExp('^#{1,6}\\s+(.*)$');

export class Markdown {
  static blocks(src: string): MdBlock[] {
    const out: MdBlock[] = [];
    const lines = src.replace(new RegExp('\\r\\n', 'g'), '\n').split('\n');
    let para: string[] = [];
    const flush = () => {
      if (para.length > 0) {
        out.push(new MdBlock('p', para.join('\n')));
        para = [];
      }
    };
    let i = 0;
    while (i < lines.length) {
      const line = lines[i];
      const t = line.trim();
      if (t.startsWith('```')) {
        flush();
        const code: string[] = [];
        i++;
        while (i < lines.length && !lines[i].trim().startsWith('```')) {
          code.push(lines[i]);
          i++;
        }
        out.push(new MdBlock('code', code.join('\n')));
        i++;
        continue;
      }
      if (t.length === 0) {
        flush();
      } else if (H.test(t)) {
        flush();
        const m = t.match(H);
        out.push(new MdBlock('h', m === null ? t : m[1]));
      } else if (t.startsWith('- ') || t.startsWith('* ') || t.startsWith('• ')) {
        flush();
        out.push(new MdBlock('li', t.substring(2), '•'));
      } else if (OL.test(t)) {
        flush();
        const m = t.match(OL);
        out.push(new MdBlock('ol', m === null ? t : m[2], m === null ? '' : m[1] + '.'));
      } else if (t.startsWith('>')) {
        flush();
        out.push(new MdBlock('quote', t.substring(1).trim()));
      } else {
        para.push(line);
      }
      i++;
    }
    flush();
    return out;
  }

  /** **bold** and `code` inside a line. */
  static spans(text: string): MdSpan[] {
    const out: MdSpan[] = [];
    let i = 0;
    let cur = '';
    while (i < text.length) {
      if (text.startsWith('**', i)) {
        const end = text.indexOf('**', i + 2);
        if (end > i + 2) {
          if (cur.length > 0) {
            out.push(new MdSpan(cur, false, false));
            cur = '';
          }
          out.push(new MdSpan(text.substring(i + 2, end), true, false));
          i = end + 2;
          continue;
        }
      }
      if (text.charAt(i) === '`') {
        const end = text.indexOf('`', i + 1);
        if (end > i + 1) {
          if (cur.length > 0) {
            out.push(new MdSpan(cur, false, false));
            cur = '';
          }
          out.push(new MdSpan(text.substring(i + 1, end), false, true));
          i = end + 1;
          continue;
        }
      }
      cur += text.charAt(i);
      i++;
    }
    if (cur.length > 0) {
      out.push(new MdSpan(cur, false, false));
    }
    return out;
  }
}
