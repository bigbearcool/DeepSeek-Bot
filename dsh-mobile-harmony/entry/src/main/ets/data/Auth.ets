import { deviceInfo } from '@kit.BasicServicesKit';
import { BridgeApi } from './BridgeApi';
import { SessionStore } from './SessionStore';
import { normalizeBase } from '../common/PairCode';
import { AuthRevokedError, BridgeError } from '../common/Errors';

/** Pairing and renewing the short pass, like the Android app. */
export class Auth {
  store: SessionStore;

  constructor(store: SessionStore) {
    this.store = store;
  }

  base(): string {
    const b = normalizeBase(this.store.current().baseUrl);
    if (b.length === 0) {
      throw new BridgeError('no address', 0, '请先扫描电脑上「手机配对」的二维码，或填写电脑地址');
    }
    return b;
  }

  api(): BridgeApi {
    return new BridgeApi(this.base());
  }

  async saveBaseUrl(url: string): Promise<void> {
    await this.store.setBaseUrl(normalizeBase(url));
  }

  async pair(code: string): Promise<void> {
    const deviceId = await this.store.ensureDeviceId();
    let model = '';
    try {
      model = deviceInfo.marketName;
    } catch (e) {
      model = '';
    }
    const label = ('鸿蒙 ' + model).trim().substring(0, 80);
    const r = await this.api().pairComplete(code, deviceId, label);
    await this.store.savePairing(r.deviceId, r.refreshToken);
    await this.refresh(true);
  }

  async refresh(force: boolean): Promise<string> {
    const s = this.store.current();
    if (!force && s.accessValid(Date.now())) {
      return s.accessToken;
    }
    if (!s.paired) {
      throw new AuthRevokedError('not paired');
    }
    try {
      const a = await this.api().issueAccess(s.deviceId, s.refreshToken);
      this.store.saveAccess(a.accessToken, a.expiresAt);
      return a.accessToken;
    } catch (e) {
      if (e instanceof BridgeError && ((e as BridgeError).status === 401 || (e as BridgeError).status === 403)) {
        throw new AuthRevokedError('unauthorized');
      }
      throw e as Error;
    }
  }

  /** Run with a valid pass; on 401 renew once and retry. */
  async with<T>(block: (token: string, api: BridgeApi) => Promise<T>): Promise<T> {
    const api = this.api();
    const token = await this.refresh(false);
    try {
      return await block(token, api);
    } catch (e) {
      if (e instanceof BridgeError && (e as BridgeError).status === 401) {
        const again = await this.refresh(true);
        return await block(again, api);
      }
      throw e as Error;
    }
  }

  async logout(): Promise<void> {
    await this.store.clearAuth();
  }
}
