import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { picker, fileIo as fs, fileUri } from '@kit.CoreFileKit';
import { common, Want, wantConstant } from '@kit.AbilityKit';

export class PickedFile {
  uri: string = '';
  name: string = '';
  bytes: number = 0;
  data: ArrayBuffer = new ArrayBuffer(0);
}

export class Files {
  static async pickImages(max: number): Promise<string[]> {
    const p = new photoAccessHelper.PhotoViewPicker();
    const opt = new photoAccessHelper.PhotoSelectOptions();
    opt.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
    opt.maxSelectNumber = max;
    const r = await p.select(opt);
    return r.photoUris;
  }

  static async pickFiles(ctx: common.Context, max: number): Promise<string[]> {
    const p = new picker.DocumentViewPicker(ctx);
    const opt = new picker.DocumentSelectOptions();
    opt.maxSelectNumber = max;
    return await p.select(opt);
  }

  static nameOf(uri: string): string {
    const last = uri.split('/').pop() ?? 'file';
    try {
      return decodeURIComponent(last);
    } catch (e) {
      return last;
    }
  }

  static read(uri: string): PickedFile {
    const f = fs.openSync(uri, fs.OpenMode.READ_ONLY);
    try {
      const st = fs.statSync(f.fd);
      const buf = new ArrayBuffer(st.size);
      fs.readSync(f.fd, buf);
      const p = new PickedFile();
      p.uri = uri;
      p.name = Files.nameOf(uri);
      p.bytes = st.size;
      p.data = buf;
      return p;
    } finally {
      fs.closeSync(f);
    }
  }

  static sizeOf(uri: string): number {
    const f = fs.openSync(uri, fs.OpenMode.READ_ONLY);
    try {
      return fs.statSync(f.fd).size;
    } finally {
      fs.closeSync(f);
    }
  }

  /** Keeps a copy of a downloaded picture or file; returns a uri the app can show. */
  static cached(dir: string, id: string): string {
    const path = dir + '/att_' + id.replace(new RegExp('[^A-Za-z0-9_.-]', 'g'), '_');
    return fs.accessSync(path) ? fileUri.getUriFromPath(path) : '';
  }

  static save(dir: string, id: string, data: ArrayBuffer): string {
    const path = dir + '/att_' + id.replace(new RegExp('[^A-Za-z0-9_.-]', 'g'), '_');
    const f = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
    try {
      fs.writeSync(f.fd, data);
    } finally {
      fs.closeSync(f);
    }
    return fileUri.getUriFromPath(path);
  }

  private static copyTo(srcUri: string, destUri: string): void {
    const src = fs.openSync(srcUri, fs.OpenMode.READ_ONLY);
    const dst = fs.openSync(destUri, fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
    try {
      const buf = new ArrayBuffer(64 * 1024);
      let n = fs.readSync(src.fd, buf);
      while (n > 0) {
        fs.writeSync(dst.fd, buf.slice(0, n));
        n = fs.readSync(src.fd, buf);
      }
    } finally {
      fs.closeSync(src);
      fs.closeSync(dst);
    }
  }

  /** Lets the person choose where to keep the file; false when they backed out. */
  static async saveAs(ctx: common.Context, srcUri: string, name: string): Promise<boolean> {
    const p = new picker.DocumentViewPicker(ctx);
    const opt = new picker.DocumentSaveOptions();
    opt.newFileNames = [name];
    const out = await p.save(opt);
    if (out.length === 0) {
      return false;
    }
    Files.copyTo(srcUri, out[0]);
    return true;
  }

  /** Puts a picture into the phone's gallery after the system asks the person. */
  static async saveToGallery(ctx: common.Context, srcUri: string, name: string): Promise<boolean> {
    const helper = photoAccessHelper.getPhotoAccessHelper(ctx);
    const dot = name.lastIndexOf('.');
    const ext = dot > 0 ? name.substring(dot + 1).toLowerCase() : 'jpg';
    const cfg: photoAccessHelper.PhotoCreationConfig = {
      title: dot > 0 ? name.substring(0, dot) : name,
      fileNameExtension: ext,
      photoType: photoAccessHelper.PhotoType.IMAGE
    };
    const uris = await helper.showAssetsCreationDialog([srcUri], [cfg]);
    if (uris.length === 0) {
      return false;
    }
    Files.copyTo(srcUri, uris[0]);
    return true;
  }

  /** Opens the file in another app on the phone, with its real name so the right app is offered. */
  static async open(ctx: common.UIAbilityContext, srcUri: string, name: string): Promise<void> {
    const dir = ctx.cacheDir + '/open';
    if (!fs.accessSync(dir)) {
      fs.mkdirSync(dir);
    }
    const safe = name.replace(new RegExp('[/\\\\:*?"<>|]', 'g'), '_');
    const path = dir + '/' + (safe.length > 0 ? safe : 'file');
    const f = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
    fs.closeSync(f);
    const uri = fileUri.getUriFromPath(path);
    Files.copyTo(srcUri, uri);
    const want: Want = {
      action: 'ohos.want.action.viewData',
      uri: uri,
      flags: wantConstant.Flags.FLAG_AUTH_READ_URI_PERMISSION
    };
    await ctx.startAbility(want);
  }
}
