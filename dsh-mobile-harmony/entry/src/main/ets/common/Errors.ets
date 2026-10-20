export class BridgeError extends Error {
  status: number;
  zh: string;

  constructor(message: string, status: number = 0, zh: string = '') {
    super(message);
    this.name = 'BridgeError';
    this.status = status;
    this.zh = zh;
  }
}

/** The phone can no longer renew its pass: pairing again is needed. */
export class AuthRevokedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AuthRevokedError';
  }
}

/** Words for the user; never shows codes or English errors. */
export function friendly(e: Object): string {
  if (e instanceof BridgeError) {
    const b = e as BridgeError;
    if (b.zh.length > 0) {
      return b.zh;
    }
    if (b.status === 429) {
      return '操作太频繁，请稍后再试';
    }
    if (b.status >= 500) {
      return '电脑端暂时出错，请稍后再试';
    }
    if (b.status === 404) {
      return '电脑上找不到这个内容';
    }
    if (b.status === 0) {
      return '连不上电脑，请检查网络';
    }
    return '电脑没有接受这个操作';
  }
  if (e instanceof AuthRevokedError) {
    return '需要重新配对';
  }
  return '连不上电脑，请检查网络';
}
