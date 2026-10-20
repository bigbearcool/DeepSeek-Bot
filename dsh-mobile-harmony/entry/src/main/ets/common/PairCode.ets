/** Pairing input: raw 6-hex code, `dshmb:<code>`, or `dshmb:<code>@<http(s) url>`. */
export interface ParsedPair {
  code: string;
  baseUrl: string | null;
}

const CODE = new RegExp('^[0-9a-fA-F]{6}$');

function isHttpUrl(u: string): boolean {
  const lower = u.toLowerCase();
  let host = '';
  if (lower.startsWith('https://')) {
    host = u.substring(8);
  } else if (lower.startsWith('http://')) {
    host = u.substring(7);
  } else {
    return false;
  }
  return host.trim().length > 0 && !host.startsWith('/') && !(new RegExp('\\s')).test(host);
}

export class PairCode {
  static parse(raw: string): ParsedPair | null {
    const t = raw.trim();
    if (t.length === 0) {
      return null;
    }
    if (!t.toLowerCase().startsWith('dshmb:')) {
      if (CODE.test(t)) {
        const p: ParsedPair = { code: t.toLowerCase(), baseUrl: null };
        return p;
      }
      return null;
    }
    let rest = t.substring(6).trim();
    if (rest.startsWith('//')) {
      rest = rest.substring(2);
    }
    let url: string | null = null;
    const at = rest.indexOf('@');
    if (at >= 0) {
      const u = rest.substring(at + 1).trim();
      rest = rest.substring(0, at);
      if (!isHttpUrl(u)) {
        return null;
      }
      url = u;
    } else {
      rest = rest.split('/')[0].split('?')[0].split('#')[0];
    }
    const code = rest.trim();
    if (!CODE.test(code)) {
      return null;
    }
    const p: ParsedPair = { code: code.toLowerCase(), baseUrl: url };
    return p;
  }

  static normalize(raw: string): string | null {
    const p = PairCode.parse(raw);
    return p === null ? null : p.code;
  }
}

export const API_PREFIX = '/api/mobile-bridge/v1';

/** Trim, drop trailing '/', append the bridge path unless present. */
export function normalizeBase(url: string): string {
  let t = url.trim();
  while (t.endsWith('/')) {
    t = t.substring(0, t.length - 1);
  }
  if (t.length === 0) {
    return t;
  }
  return t.toLowerCase().endsWith(API_PREFIX.toLowerCase()) ? t : t + API_PREFIX;
}
