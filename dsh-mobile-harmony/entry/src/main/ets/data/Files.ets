import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { picker, fileIo as fs, fileUri } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';

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
}
