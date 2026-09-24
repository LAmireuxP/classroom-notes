#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
合成品牌图形：侧视鹿 + 声波（「鹿音」的鹿与音都要在图里）。

为什么要这一步：应用叫「鹿音」。只用鹿就丢了一半——「音」在图里看不见；但也不能退回
多元素堆叠（那正是原图标在 48dp 下糊成一团的原因）。所以取折中：
  · 主体仍是**一个实心剪影**（鹿），48dp 下先认得出形状；
  · 「音」用**两道粗声波**，借鹿抬头鸣叫的姿态贴在嘴前——不额外画一支话筒，
    多一圈实心色块就等于把 48dp 的问题搬回来；
  · 声波只两道且都够粗（≥18px/512，缩到 48dp 约 1.1px 以上），不会断成灰雾。

鹿的吻尖位置是**从素材里量出来的**（逐行扫 alpha 最右边界），不是手填的坐标——
换素材后这套位置会自动跟着变。

共享逻辑（渐变、剥底色、超采样描边）都在 iconlib.py，本文件不重复实现。
用法：python tools/icon-deer-sound.py [--wave-gap 8] [--gap-scale ...]
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import iconlib as il

from PIL import Image, ImageDraw
import numpy as np

# 声波：(半径, 线宽, 起始角, 结束角)。角度 0° 指右、屏幕坐标顺时针为正。
WAVES = [(46, 24, -56, 56),
         (78, 19, -48, 48)]

# 最内那道声波的**内缘**距吻尖留多少。按半径直接摆会出现「声波悬在半空」的感觉——
# 半径 50 时内缘离嘴 48px，看着跟鹿没关系了。所以按内缘定位反推圆心。
EDGE_GAP = 16
# 声波中心比吻尖略低：吻尖量到的是鼻梁最右点，声音是从嘴出来的。
MOUTH_DROP = 10


def snout_tip(mask):
    """鹿吻尖 = 图形上**最靠右**的那个像素。

    这里原来限定「只看上面 45% 的高度带」，理由是怕后腿比吻部更靠右——实测不会
    （吻尖 x=345，后腿最右 325），而那个带在把鹿裁成头颈之后就害事了：裁完吻尖落到
    高度的 60% 处，被带排除在外，量到的成了鹿角（踩过一次）。
    鹿朝右、抬头，全局最右点就是吻尖，不需要任何带。
    """
    a = np.array(mask) > 8
    xs = np.where(a)[1]
    if not len(xs):
        raise ValueError("蒙版里找不到不透明像素")
    x = int(xs.max())
    y = int(np.where(a[:, x])[0][0])          # 该 x 上最靠上的那一行
    return x, y


def body_onset(mask, body_frac=0.50, skip_top=0.40):
    """找「身体开始」的那一行，即只留头颈时该裁到哪。

    第一版用的是「中部高度带里最窄的一行」，结果量到了**下巴**——下颌下方那一行
    只有 48px 宽，比脖子（88px）还窄，于是切在了下巴上，脖子几乎没留住（踩过）。
    可靠得多的判据是**宽度突增**：头颈细、身体粗，所以从头部往下找第一行
    宽度超过「全图最宽行 × body_frac」的，那就是身体起点。
    skip_top 用来跳过顶部的鹿角——鹿角本来就铺得很宽，不跳过会误判。
    """
    a = np.array(mask) > 8
    ys, _ = np.where(a)
    y0, y1 = ys.min(), ys.max()
    h = y1 - y0
    widths = {}
    for y in range(y0, y1 + 1):
        row = np.where(a[y])[0]
        if len(row):
            widths[y] = row.max() - row.min() + 1
    if not widths:
        raise ValueError("蒙版里找不到不透明像素")
    max_w = max(widths.values())
    start = int(y0 + h * skip_top)
    for y in sorted(widths):
        if y < start:
            continue
        if widths[y] >= max_w * body_frac:
            return y, widths[y], max_w
    return y1 + 1, widths.get(y1, 0), max_w


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--svg', default=None, help='鹿的 SVG（默认 tools/icon-source/luyin-deer.svg）')
    ap.add_argument('--edge-gap', type=float, default=EDGE_GAP, dest='edge_gap',
                    help=f'最内声波内缘距吻尖的距离（默认 {EDGE_GAP}）')
    ap.add_argument('--scale', type=float, default=1.0, help='声波整体缩放')
    ap.add_argument('--full', action='store_true',
                    help='保留整只鹿（默认只留头颈：腿、蹄、尾在 48dp 下都是噪声）')
    ap.add_argument('--body-frac', type=float, default=0.50, dest='body_frac',
                    help='宽度达到「全图最宽行」的多少比例就算进入身体（默认 0.50）')
    ap.add_argument('--below', type=float, default=80,
                    help='在身体起点之下再多保留多少像素（默认 80）。'
                         '正好切在身体起点上，头会像悬空吊着；带一点肩胸才像个胸像，'
                         '构图也更接近方形（宽高比 1.70 → 1.39），在方形图标里更立得住。')
    ap.add_argument('-o', '--out', default=None)
    args = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    svg = args.svg or os.path.join(here, 'icon-source', 'luyin-deer.svg')
    out = args.out or os.path.join(here, 'icon-source', 'luyin-brand-512.png')

    deer, stripped = il.svg_alpha(svg, il.OUT)
    if stripped:
        print("  已剥离满画布底色")
    bb = deer.getbbox()
    deer = deer.crop(bb)                       # 裁到鹿的实际范围，后面按它的坐标放声波
    sx, sy = snout_tip(deer)

    # 只留头颈（默认）。--full 保留整只鹿（腿部元素多，48dp 下是噪声）。
    if not args.full:
        onset, w, maxw = body_onset(deer, body_frac=args.body_frac)
        cut = min(deer.size[1], int(onset + args.below))
        print(f"整只鹿 {deer.size[0]}×{deer.size[1]}，身体起于 y={onset}"
              f"（该行宽 {w}，全图最宽 {maxw}），"
              f"再多留 {args.below:.0f}px 肩胸 → 裁到 y={cut}")
        deer = deer.crop((0, 0, deer.size[0], cut))
        deer = deer.crop(deer.getbbox())
        sx, sy = snout_tip(deer)               # 裁剪后重新量吻尖
    print(f"图形 {deer.size[0]}×{deer.size[1]}，吻尖在 ({sx}, {sy})")

    # 最终画布：鹿贴左上，右侧留给声波。
    # 声波圆心由**最内一道的内缘**反推：内缘在吻尖外 edge_gap 处。
    r1, w1 = WAVES[0][0] * args.scale, WAVES[0][1] * args.scale
    cx = sx + args.edge_gap + w1 / 2 - r1
    cy = sy + MOUTH_DROP
    wave_reach = cx + (WAVES[-1][0] + WAVES[-1][1] / 2) * args.scale
    canvas_px = int(round(max(deer.size[0], wave_reach, deer.size[1])))
    print(f"声波圆心 ({cx:.0f}, {cy:.0f})，最外缘 x={wave_reach:.0f}，画布 {canvas_px}")

    mask = Image.new('L', (canvas_px * il.SS, canvas_px * il.SS), 0)
    d = ImageDraw.Draw(mask)

    # 鹿按 **1:1** 画进画布，只乘超采样倍数。
    # 这里原来乘的是 canvas_px*SS/OUT（画布相对 512 的比例），等于把鹿又缩了一次——
    # 声波是按 1:1 坐标摆的，结果鹿被缩小、位置关系全错，而且**看着还挺像样**，
    # 所以一直到换成只留头颈、比例变化放大之后才暴露。坐标空间必须只有一个。
    deer_big = deer.resize((max(1, int(deer.size[0] * il.SS)),
                            max(1, int(deer.size[1] * il.SS))), Image.LANCZOS)
    mask.paste(deer_big, (0, 0), deer_big)

    for r, wid, a0, a1 in WAVES:
        if wid < il.MIN_LEGIBLE_STROKE:
            sys.exit(f"声波线宽 {wid}px 低于可辨识下限 {il.MIN_LEGIBLE_STROKE}px，"
                     f"48dp 上会断成灰雾")
        il.stroke(d, il.arc(cx, cy, r * args.scale, a0, a1), wid * args.scale)

    small = mask.resize((canvas_px, canvas_px), Image.LANCZOS)
    art = il.apply_gradient(np.array(small))
    art = art.crop(art.split()[3].getbbox())
    art.save(out)

    a = np.array(art.split()[3])
    print(f"\n已生成 {out}")
    print(f"  合成后图形 {art.size[0]}×{art.size[1]}  宽高比 {art.size[0]/art.size[1]:.3f}  "
          f"不透明占比 {(a > 8).mean():.1%}")
    for r, wid, a0, a1 in WAVES:
        px = wid * args.scale / 512 * 48 * 0.65
        print(f"  声波 r={r} 线宽={wid}px → 48dp 上约 {px:.2f}px  {'✅' if px >= 1 else '⚠️ 会断'}")
    print(f"\n下一步：python tools/make-icons.py --source {os.path.basename(out)}")


if __name__ == '__main__':
    main()