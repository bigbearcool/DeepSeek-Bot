import { Markdown, MdBlock, MdSpan } from '../common/Markdown';

const NAMED: Record<string, string> = {
  'blue': '#3B6CF6', 'green': '#2FA36B', 'red': '#E5484D', 'orange': '#F07A2A', 'yellow': '#E8B400',
  'purple': '#8B5CF6', 'pink': '#E5539A', 'teal': '#14A3A3', 'cyan': '#1EA7D6', 'indigo': '#5B5BD6',
  'gray': '#8A8F99', 'black': '#333333', 'brown': '#9A6B43'
};

export function colorOf(c: string | null): string {
  if (c === null || c.length === 0) {
    return '#3B6CF6';
  }
  if (c.startsWith('#')) {
    return c;
  }
  return NAMED[c] ?? '#3B6CF6';
}

@Component
export struct Avatar {
  @Prop name: string = '';
  @Prop color: string = '#3B6CF6';
  @Prop dim: number = 44;
  @Prop group: boolean = false;

  build() {
    Stack() {
      Text(this.group ? '群' : (this.name.length > 0 ? this.name.substring(0, 1) : '?'))
        .fontColor(Color.White)
        .fontSize(this.dim * 0.42)
        .fontWeight(FontWeight.Medium)
    }
    .width(this.dim)
    .height(this.dim)
    .borderRadius(this.group ? this.dim * 0.28 : this.dim / 2)
    .backgroundColor(this.group ? '#5B6B8C' : this.color)
  }
}

@Component
export struct MdText {
  @Prop text: string = '';
  @Prop color: ResourceColor = $r('app.color.text');

  build() {
    Column({ space: 6 }) {
      ForEach(Markdown.blocks(this.text), (b: MdBlock) => {
        if (b.type === 'code') {
          Text(b.text)
            .fontFamily('monospace')
            .fontSize(13)
            .padding(8)
            .borderRadius(8)
            .backgroundColor('#14000000')
            .width('100%')
            .copyOption(CopyOptions.LocalDevice)
        } else {
          Row({ space: 6 }) {
            if (b.type === 'li' || b.type === 'ol') {
              Text(b.mark).fontSize(15).fontColor(this.color)
            }
            Text() {
              ForEach(Markdown.spans(b.text), (s: MdSpan) => {
                Span(s.text)
                  .fontWeight(s.bold || b.type === 'h' ? FontWeight.Bold : FontWeight.Normal)
                  .fontFamily(s.code ? 'monospace' : undefined)
                  .fontColor(b.type === 'quote' ? $r('app.color.sub') : this.color)
              })
            }
            .fontSize(b.type === 'h' ? 17 : 15)
            .lineHeight(22)
            .copyOption(CopyOptions.LocalDevice)
            .layoutWeight(1)
          }
          .alignItems(VerticalAlign.Top)
          .width('100%')
        }
      })
    }
    .alignItems(HorizontalAlign.Start)
  }
}

/** A picture in a message: loads from the computer once, then from the phone. */
@Component
export struct AttImage {
  @Prop attId: string = '';
  @Prop localUri: string = '';
  @State uri: string = '';
  @State failed: boolean = false;
  loader: (id: string) => Promise<string> = (id: string) => Promise.resolve('');

  aboutToAppear(): void {
    if (this.localUri.length > 0) {
      this.uri = this.localUri;
      return;
    }
    if (this.attId.length === 0) {
      this.failed = true;
      return;
    }
    this.loader(this.attId).then((u: string) => {
      this.uri = u;
      this.failed = u.length === 0;
    }).catch(() => {
      this.failed = true;
    });
  }

  build() {
    Stack() {
      if (this.uri.length > 0) {
        Image(this.uri).objectFit(ImageFit.Cover).width('100%').height('100%')
      } else if (this.failed) {
        Text('图片打不开').fontSize(12).fontColor($r('app.color.sub'))
      } else {
        LoadingProgress().width(24).height(24)
      }
    }
    .width(140)
    .height(140)
    .borderRadius(10)
    .clip(true)
    .backgroundColor('#14000000')
  }
}
