import { Looks, FALLBACK_SHAPE } from '../common/Look';
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

/** The same character avatar the computer draws, in the Bot's color, eyes cut out. */
@Component
export struct CharAvatar {
  @Prop @Watch('redraw') botId: string = '';
  @Prop @Watch('redraw') shape: string = '';
  @Prop @Watch('redraw') color: string = '';
  @Prop dim: number = 44;
  @Prop @Watch('redraw') dark: boolean = false;
  private ready: boolean = false;
  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private ctx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);

  redraw(_: string): void {
    if (this.ready) {
      this.draw();
    }
  }

  private draw(): void {
    const c = this.ctx;
    const look = Looks.lookOf(this.botId.length > 0 ? this.botId : null, this.shape.length > 0 ? this.shape : null,
      this.color.length > 0 ? this.color : null);
    const ch = Looks.shapeOf(look.shape) ?? Looks.shapeOf(FALLBACK_SHAPE);
    if (ch === null) {
      return;
    }
    const k = c.width / 100;
    c.clearRect(0, 0, c.width, c.height);
    c.save();
    c.scale(k, k);
    if (look.color === 'aurora') {
      const g = c.createLinearGradient(0, 0, 100, 100);
      g.addColorStop(0, '#A4FFB0');
      g.addColorStop(0.42, '#00F0E4');
      g.addColorStop(1, '#2E9EF7');
      c.fillStyle = g;
    } else {
      c.fillStyle = Looks.ink(look.color, this.dark);
    }
    c.fill(new Path2D(ch.body), 'evenodd');
    for (const p of ch.paints) {
      c.globalAlpha = p.opacity;
      c.fillStyle = p.color;
      c.fill(new Path2D(p.d), 'evenodd');
    }
    if (ch.shade.length > 0) {
      c.globalAlpha = 0.14;
      c.fillStyle = '#000000';
      c.fill(new Path2D(ch.shade), 'evenodd');
    }
    if (ch.light.length > 0) {
      c.globalAlpha = 0.16;
      c.fillStyle = '#FFFFFF';
      c.fill(new Path2D(ch.light), 'evenodd');
    }
    c.globalAlpha = 1;
    c.globalCompositeOperation = 'destination-out';
    c.fillStyle = '#000000';
    for (const e of ch.eyes) {
      c.save();
      c.translate(e[0], e[1]);
      c.rotate(e[4] * Math.PI / 180);
      const w = e[2];
      const h = e[3];
      c.beginPath();
      c.moveTo(-w / 2, -h / 2 + w / 2);
      c.arc(0, -h / 2 + w / 2, w / 2, Math.PI, 0);
      c.lineTo(w / 2, h / 2 - w / 2);
      c.arc(0, h / 2 - w / 2, w / 2, 0, Math.PI);
      c.closePath();
      c.fill();
      c.restore();
    }
    c.restore();
    c.globalCompositeOperation = 'source-over';
  }

  build() {
    Canvas(this.ctx)
      .width(this.dim)
      .height(this.dim)
      .onReady(() => {
        this.ready = true;
        this.draw();
      })
  }
}

/** Two members' characters overlapping, for a group chat. */
@Component
export struct RoomAvatar {
  @Prop ids: string[] = [];
  @Prop shapes: string[] = [];
  @Prop colors: string[] = [];
  @Prop dim: number = 44;
  @Prop dark: boolean = false;

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      CharAvatar({
        botId: this.ids.length > 1 ? this.ids[1] : 'group-2', shape: this.shapes.length > 1 ? this.shapes[1] : '',
        color: this.colors.length > 1 ? this.colors[1] : '', dim: this.dim * 0.68, dark: this.dark
      }).position({ x: this.dim * 0.32, y: 0 })
      CharAvatar({
        botId: this.ids.length > 0 ? this.ids[0] : 'group', shape: this.shapes.length > 0 ? this.shapes[0] : '',
        color: this.colors.length > 0 ? this.colors[0] : '', dim: this.dim * 0.68, dark: this.dark
      }).position({ x: 0, y: this.dim * 0.32 })
    }
    .width(this.dim)
    .height(this.dim)
  }
}

/** A picture filling the screen; tap to close. */
@Component
export struct ImageViewer {
  @Prop uri: string = '';
  onClose: () => void = () => {
  };
  onSave: () => void = () => {
  };
  @State scaleV: number = 1;
  @State lastScale: number = 1;

  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      Image(this.uri).objectFit(ImageFit.Contain).width('100%').height('100%')
        .scale({ x: this.scaleV, y: this.scaleV })
        .gesture(PinchGesture().onActionUpdate((e: GestureEvent) => {
          this.scaleV = Math.min(4, Math.max(1, this.lastScale * e.scale));
        }).onActionEnd(() => {
          this.lastScale = this.scaleV;
        }))
      Row({ space: 16 }) {
        Button('保存到手机').backgroundColor('#33FFFFFF').onClick(() => this.onSave())
        Button('关闭').backgroundColor('#33FFFFFF').onClick(() => this.onClose())
      }
      .margin({ bottom: 40 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
    .onClick(() => this.onClose())
  }
}
