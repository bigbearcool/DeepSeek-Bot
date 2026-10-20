import { preferences } from '@kit.ArkData';
import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import { common } from '@kit.AbilityKit';

const SECRET_ALIAS = 'dsh_refresh_token';

function toBytes(s: string): Uint8Array {
  return util.TextEncoder.create().encodeInto(s);
}

function fromBytes(b: Uint8Array): string {
  return util.TextDecoder.create('utf-8').decodeToString(b);
}

export class Session {
  baseUrl: string = '';
  deviceId: string = '';
  refreshToken: string = '';
  accessToken: string = '';
  accessExpiresAt: number = 0;
  lastChat: string = '';

  get paired(): boolean {
    return this.deviceId.length > 0 && this.refreshToken.length > 0;
  }

  accessValid(now: number): boolean {
    return this.accessToken.length > 0 && this.accessExpiresAt > now + 60000;
  }
}

/** Saved pairing; the long-lived pass is kept in the system's protected store. */
export class SessionStore {
  private prefs: preferences.Preferences;
  private cache: Session = new Session();

  private constructor(p: preferences.Preferences) {
    this.prefs = p;
  }

  static async open(ctx: common.Context): Promise<SessionStore> {
    const p = await preferences.getPreferences(ctx, 'dsh_mobile_session');
    const s = new SessionStore(p);
    await s.load();
    return s;
  }

  private async load(): Promise<void> {
    const c = new Session();
    c.baseUrl = this.prefs.getSync('base_url', '') as string;
    c.deviceId = this.prefs.getSync('device_id', '') as string;
    c.lastChat = this.prefs.getSync('last_chat', '') as string;
    c.refreshToken = await this.readSecret();
    this.cache = c;
  }

  current(): Session {
    return this.cache;
  }

  private async readSecret(): Promise<string> {
    try {
      const q: asset.AssetMap = new Map();
      q.set(asset.Tag.ALIAS, toBytes(SECRET_ALIAS));
      q.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
      const res = await asset.query(q);
      if (res.length > 0) {
        const v = res[0].get(asset.Tag.SECRET) as Uint8Array;
        return fromBytes(v);
      }
    } catch (e) {
      // nothing saved, or the protected store is not available
    }
    return this.prefs.getSync('refresh_fallback', '') as string;
  }

  private async writeSecret(v: string): Promise<void> {
    try {
      const q: asset.AssetMap = new Map();
      q.set(asset.Tag.ALIAS, toBytes(SECRET_ALIAS));
      await asset.remove(q);
    } catch (e) {
      // none yet
    }
    this.prefs.deleteSync('refresh_fallback');
    if (v.length === 0) {
      return;
    }
    try {
      const a: asset.AssetMap = new Map();
      a.set(asset.Tag.ALIAS, toBytes(SECRET_ALIAS));
      a.set(asset.Tag.SECRET, toBytes(v));
      await asset.add(a);
    } catch (e) {
      this.prefs.putSync('refresh_fallback', v);
    }
  }

  async setBaseUrl(url: string): Promise<void> {
    this.cache.baseUrl = url;
    this.prefs.putSync('base_url', url);
    await this.prefs.flush();
  }

  async ensureDeviceId(): Promise<string> {
    if (this.cache.deviceId.length > 0) {
      return this.cache.deviceId;
    }
    const id = 'harmony-' + util.generateRandomUUID(false).replace(new RegExp('-', 'g'), '').substring(0, 16);
    this.cache.deviceId = id;
    this.prefs.putSync('device_id', id);
    await this.prefs.flush();
    return id;
  }

  async savePairing(deviceId: string, refreshToken: string): Promise<void> {
    this.cache.deviceId = deviceId;
    this.cache.refreshToken = refreshToken;
    this.cache.accessToken = '';
    this.cache.accessExpiresAt = 0;
    this.prefs.putSync('device_id', deviceId);
    await this.writeSecret(refreshToken);
    await this.prefs.flush();
  }

  saveAccess(token: string, expiresAt: number): void {
    // Short-lived; kept in memory only.
    this.cache.accessToken = token;
    this.cache.accessExpiresAt = expiresAt;
  }

  async setLastChat(key: string): Promise<void> {
    this.cache.lastChat = key;
    this.prefs.putSync('last_chat', key);
    await this.prefs.flush();
  }

  getPref(key: string, d: string): string {
    return this.prefs.getSync(key, d) as string;
  }

  async setPref(key: string, v: string): Promise<void> {
    this.prefs.putSync(key, v);
    await this.prefs.flush();
  }

  async clearAuth(): Promise<void> {
    this.cache.refreshToken = '';
    this.cache.accessToken = '';
    this.cache.accessExpiresAt = 0;
    this.cache.lastChat = '';
    this.prefs.deleteSync('last_chat');
    await this.writeSecret('');
    await this.prefs.flush();
  }
}
