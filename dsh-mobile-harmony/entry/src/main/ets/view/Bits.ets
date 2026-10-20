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
