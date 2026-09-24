#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把一张现成的 SVG 图标（通常是网上找的开源图标）转成「鹿音笔记」的品牌图标素材。

做三件事：
  1. 用 cairosvg 把 SVG 光栅化成 512px；
  2. 取它的 **alpha 通道**当蒙版，套上本项目的品牌渐变（蓝 #4F7DF7 → 紫 #9A6BEE → 粉 #FF6FA8）；
     源图是单色剪影，直接用它自己的颜色会丢掉品牌色；只取形状、颜色由这里决定。
  3. 裁到图形实际边界后存成 art PNG，交给 make-icons.py 出各密度资源。

为什么要单独一步：make-icons.py 管的是「尺寸与安全区」，颜色是另一件事。
两者分开，换图形和换配色互不影响。

用法：
    python tools/icon-from-svg.py 源图.svg --author "作者" --license "CC BY 3.0" --url "出处"
    python tools/make-icons.py            # 再把 art PNG 铺成各密度

来源与授权的记录要求：CC BY 这类授权**要求署名**，所以 --author/--license/--url 会写进
tools/icon-source/SOURCES.md；用 CC0/MIT 也照样记，方便以后追溯这张图是哪来的。
"""

import argparse
import io
import os
import re
import sys

try:
    import cairosvg
except ImportError:
    sys.exit("需要 cairosvg：pip install cairosvg")
try:
    from PIL import Image
except ImportError:
    sys.exit("需要 Pillow：pip install Pillow")

import numpy as np

OUT = 512
STOPS = [(0.00, (0x4F, 0x7D, 0xF7)),
         (0.52, (0x9A, 0x6B, 0xEE)),
         (1.00, (0xFF, 0x6F, 0xA8))]
G_AXIS = ((110, 70), (400, 460))


def gradient(size=OUT):
    """品牌三色对角渐变。"""
    (x1, y1), (x2, y2) = G_AXIS
    dx, dy = x2 - x1, y2 - y1
    L2 = dx * dx + dy * dy
    yy, xx = np.mgrid[0:size, 0:size].astype(float)
    t = np.clip(((xx - x1) * dx + (yy - y1) * dy) / L2, 0.0, 1.0)
    rgb = np.zeros((size, size, 3), float)
    for i in range(len(STOPS) - 1):
        t0, c0 = STOPS[i]
        t1, c1 = STOPS[i + 1]
        k = np.clip((t - t0) / (t1 - t0), 0.0, 1.0)
        seg = (t >= t0) & (t <= t1)
        for ch in range(3):
            rgb[:, :, ch] = np.where(seg, c0[ch] + (c1[ch] - c0[ch]) * k, rgb[:, :, ch])
    return rgb.astype(np.uint8)


def strip_background(svg_text):
    """去掉满画布的底色矩形。

    game-icons.net 的下载链接是 `/icons/<前景色>/<背景色>/...`——默认给了「黑底白图」，
    SVG 里因此有一块覆盖整个画布的 `<path d="M0 0h512v512H0z" fill="#000"/>`。
    只取 alpha 的话，背景和图形一样是不透明，整张图会变成一块实心方块（踩过，产物宽高比 1.000）。
    这里按**几何**判据剥离：正好等于视口大小的路径或 rect 就是底色。
    """
    m = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', svg_text)
    if not m:
        return svg_text, False
    W, H = float(m.group(1)), float(m.group(2))
    stripped = False

    def full_canvas_path(d):
        # 归一化后形如 M0 0h512v512H0z / M0,0 L512,0 L512,512 L0,512 Z
        s = re.sub(r'[\s,]+', '', d).lower()
        return s in (f'm0 0h{W:g}v{H:g}h0z'.replace(' ', ''),
                     f'm0 0h{W:g}v{H:g}h0z'.replace(' ', '')) or \
               s.startswith(f'm0 0h{W:g}v{H:g}') or \
               s == f'm0,0l{W:g},0l{W:g},{H:g}l0,{H:g}z'.replace(' ', '')

    def repl_path(mm):
        nonlocal stripped
        if full_canvas_path(mm.group(1)):
            stripped = True
            return ''
        return mm.group(0)

    svg_text = re.sub(r'<path[^>]*\bd="([^"]+)"[^>]*/?>', repl_path, svg_text)

    def repl_rect(mm):
        nonlocal stripped
        tag = mm.group(0)
        w = re.search(r'width="([\d.]+)"', tag)
        h = re.search(r'height="([\d.]+)"', tag)
        x = re.search(r'x="([\d.]+)"', tag)
        y = re.search(r'y="([\d.]+)"', tag)
        if w and h and float(w.group(1)) >= W and float(h.group(1)) >= H \
                and (not x or float(x.group(1)) <= 0) and (not y or float(y.group(1)) <= 0):
            stripped = True
            return ''
        return tag

    svg_text = re.sub(r'<(?:rect|image)\b[^>]*/?>', repl_rect, svg_text)
    return svg_text, stripped


def rasterize(svg_path, size=OUT):
    """SVG → RGBA。先剥掉满画布底色，再交给 cairosvg。

    cairosvg 直接给带 alpha 的位图，比 svglib+renderPM 那条链稳（后者要额外的 cairo 后端）。
    """
    text = open(svg_path, encoding='utf-8', errors='replace').read()
    text, did = strip_background(text)
    if did:
        print("  已剥离满画布底色")
    png = cairosvg.svg2png(bytestring=text.encode('utf-8'),
                           output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert('RGBA')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('svg', help='源 SVG 路径')
    ap.add_argument('--author', default='', help='作者（CC BY 要求署名）')
    ap.add_argument('--license', default='', help='授权标识，如 CC BY 3.0 / CC0 / MIT')
    ap.add_argument('--url', default='', help='出处链接')
    ap.add_argument('--name', default='', help='产物名（默认取 SVG 文件名）')
    ap.add_argument('-o', '--out', default=None)
    args = ap.parse_args()

    if not os.path.exists(args.svg):
        sys.exit(f"找不到 SVG：{args.svg}")

    here = os.path.dirname(os.path.abspath(__file__))
    src_dir = os.path.join(here, 'icon-source')
    os.makedirs(src_dir, exist_ok=True)

    rgba = rasterize(args.svg)
    a = np.array(rgba.split()[3])
    cov = (a > 8).mean()
    if cov < 0.005:
        sys.exit("源图几乎没有不透明像素——SVG 可能只有描边没有填充，"
                 "或用了 currentColor 却没指定颜色（改用带颜色的下载链接）")
    # 这道闸是必须的：满画布底色会让 alpha 全 1，产物变成一块实心方块。
    # 剥离规则没命中时（底色写法不常见）就得在这里拦住，而不是默默出一个方块图标。
    if cov > 0.95:
        sys.exit(f"图形几乎占满画布（{cov:.1%}），像是把背景也当成了图形。"
                 f"检查 SVG 是否带满画布底色矩形，或该图标本身就没有留白。")

    art = Image.fromarray(np.dstack([gradient(), a]), 'RGBA')
    art = art.crop(art.split()[3].getbbox())

    name = args.name or os.path.splitext(os.path.basename(args.svg))[0]
    out = args.out or os.path.join(src_dir, f'{name}-512.png')
    art.save(out)

    # 把源 SVG 一并留档：以后要微调（比如换背景色、改渐变方向）得有原始图形
    kept_svg = os.path.join(src_dir, f'{name}.svg')
    if os.path.abspath(args.svg) != os.path.abspath(kept_svg):
        open(kept_svg, 'wb').write(open(args.svg, 'rb').read())

    # 记来源。CC BY 要求署名，这条记录是署名的依据，别省。
    log = os.path.join(src_dir, 'SOURCES.md')
    header = ("# 图标素材来源\n\n"
              "本目录下的图形素材出处与授权。**CC BY 类授权要求署名**，"
              "对应的署名必须同时出现在项目 README 的致谢里。\n\n"
              "| 素材 | 作者 | 授权 | 出处 |\n| --- | --- | --- | --- |\n")
    row = f"| `{name}-512.png` | {args.author or '—'} | {args.license or '—'} | {args.url or '—'} |\n"
    if not os.path.exists(log):
        open(log, 'w', encoding='utf-8').write(header + row)
    else:
        text = open(log, encoding='utf-8').read()
        if name in text:
            lines = [l for l in text.splitlines(True) if not l.startswith(f"| `{name}-512.png`")]
            text = ''.join(lines)
        open(log, 'w', encoding='utf-8').write(text.rstrip('\n') + '\n' + row)

    print(f"已生成 {out}")
    print(f"  图形 {art.size[0]}×{art.size[1]}  宽高比 {art.size[0]/art.size[1]:.3f}")
    print(f"  源 SVG 留档 {kept_svg}")
    print(f"  来源记录 {log}")
    print(f"\n下一步：python tools/make-icons.py")


if __name__ == '__main__':
    main()