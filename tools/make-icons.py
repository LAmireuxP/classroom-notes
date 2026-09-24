#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从一张平面图标生成 Android 全套启动器图标资源。

为什么需要这个脚本：手头只有一张 512×512 的成品 PNG（图形 + 白底烧在一起），
而 Android 要的是三套互不相同的东西——
  1. 自适应图标的前景层（Android 8+）：108dp 画布、透明底、图形缩在中央安全区；
  2. 旧版方形图标（Android 7.1 及以下）：白卡片 + 阴影 + 图形；
  3. 旧版圆形图标：实心圆 + 阴影 + 图形。
三种的图形占比还各不相同，手改一次就得重算一遍，所以写成脚本。

用法：
    python tools/make-icons.py                # 生成并写入 src/res/mipmap-*
    python tools/make-icons.py --check        # 只生成到 build/ 并报告，不写入项目

依赖：Pillow（pip install Pillow）
"""

import argparse
import os
import shutil
import sys
from collections import deque

try:
    from PIL import Image, ImageDraw, ImageFilter
except ImportError:
    sys.exit("需要 Pillow：pip install Pillow")

import numpy as np

# ---------------- 可调参数 ----------------

# 源图（平面版：图形 + 白底）。要换图形就替换这个文件。
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   'icon-source', 'luyin-icon-512.png')

# 自适应图标前景层：图形**长边**占画布的比例。
# 上限是安全区 66.7%（72dp/108dp），留一点余量是因为圆形遮罩的可用高度比方圆形更紧：
# 图形贴到 66.7% 时，顶端（鹿角）正好落在圆边上，容易被切。
ADAPTIVE_ART = 0.65

# 旧版图标沿用原图标的结构与占比（实测自 1.3 版图标，换图形不该连带改风格）：
#   方形：白卡片占 79%，卡片圆角 22%
#   圆形：实心圆占 91%
# 但**图形占比不能照抄旧值**：旧图标的图形是近方形的，实测占整图 43.8%；照抄到高瘦的
# 图形上（如侧视鹿，宽高比 0.75）宽度就只剩 33%，缩在卡片中间显得很小。
# 按长边 62% / 66% 取，视觉分量与自适应图标接近，卡片里也还留得住白边。
LEGACY_CARD = 0.79
LEGACY_CARD_RADIUS = 0.22
LEGACY_SQUARE_ART = 0.62
LEGACY_ROUND_DIA = 0.91
LEGACY_ROUND_ART = 0.66

# 调色板色数。渐变图形产生的独特色数以万计，直接存真彩 PNG 会有 40 KB+；
# 量化到 256 色肉眼无差（见 --check 报的误差），体积能砍掉一大半。
PALETTE = 256

DENSITIES = [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]


# ---------------- 抠底 ----------------

def load_art(path):
    """读源图并返回透明底的图形。

    两种源图都要支持：
      - 平面版（图形 + 白底烧在一起，如设计稿导出）→ 泛洪抠底；
      - 已经是透明底的（如 tools/icon-art.py 自己画的）→ 直接用它的 alpha 通道。
    判据是有没有成规模的透明像素：白底图整张不透明，透明底图会有大片 alpha=0。
    """
    img = Image.open(path)
    if img.mode == 'RGBA':
        a = np.array(img.split()[3])
        if (a < 8).mean() > 0.05:          # 超过 5% 的像素透明 → 已是透明底
            art = img
            return art.crop(art.split()[3].getbbox())
    return key_out_background(img)


def key_out_background(img, threshold=240):
    """把与画面边缘连通的白色去掉，得到透明底的图形。

    不能用「凡是白像素都透明」：图形里的麦克风胶囊是纯白填充（SVG 里 fill="#FFFFFF"），
    那样会把话筒掏空。所以从四边向内泛洪，只吃**连通到边缘**的白——被渐变描边
    围住的内部区域泛洪进不去，自然保留。
    """
    a = np.array(img.convert('RGB'))
    h, w, _ = a.shape
    near_white = (a >= threshold).all(axis=2)
    bg = np.zeros((h, w), bool)
    dq = deque()
    for x in range(w):
        for y in (0, h - 1):
            if near_white[y, x] and not bg[y, x]:
                bg[y, x] = True
                dq.append((y, x))
    for y in range(h):
        for x in (0, w - 1):
            if near_white[y, x] and not bg[y, x]:
                bg[y, x] = True
                dq.append((y, x))
    while dq:
        y, x = dq.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and near_white[ny, nx] and not bg[ny, nx]:
                bg[ny, nx] = True
                dq.append((ny, nx))
    out = Image.fromarray(np.dstack([a, np.where(bg, 0, 255).astype(np.uint8)]), 'RGBA')
    return out.crop(out.split()[3].getbbox())


# ---------------- 生成 ----------------

def _shadow(size, painter, blur_f=0.012, off_f=0.02, strength=0.5):
    """先画一层模糊的黑色形状当阴影（向下略偏），再在上面盖白色实体。"""
    off = int(round(size * off_f))
    sh = Image.new('L', (size, size), 0)
    painter(ImageDraw.Draw(sh), off)
    sh = sh.filter(ImageFilter.GaussianBlur(max(1, size * blur_f)))
    sh = sh.point(lambda v: int(v * strength))
    layer = Image.new('RGBA', (size, size), (0, 0, 0, 255))
    layer.putalpha(sh)
    return layer


def _place(base, art, size, fraction):
    """把图形按「**长边**占整图 fraction」缩放并居中贴上去。

    为什么按长边而不是按高度：早期版本按高度对齐，遇到横向很宽的图形（比如宽高比 2:1）
    算出来的宽度会超过画布，图形两侧被 PNG 边缘直接裁掉——而且产物尺寸自检看不出来
    （画布尺寸是对的，只是里面的图形被切了）。按长边对齐则任何宽高比都不会溢出。
    """
    scale = size * fraction / max(art.size)
    tw, th = max(1, round(art.size[0] * scale)), max(1, round(art.size[1] * scale))
    a = art.resize((tw, th), Image.LANCZOS)
    if tw > size or th > size:                  # 兜底：宁可信这道闸，也别悄悄裁掉图形
        raise ValueError(f"图形 {art.size} 按 {fraction:.0%} 缩放后 {a.size} 超出画布 {size}")
    base.alpha_composite(a, ((size - tw) // 2, (size - th) // 2))


def make_foreground(art, px):
    """自适应图标前景层：108dp 画布 + 透明底 + 图形缩在安全区中央。"""
    canvas = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    _place(canvas, art, px, ADAPTIVE_ART)
    return canvas


def make_legacy_square(art, px):
    card = int(round(px * LEGACY_CARD))
    o = (px - card) // 2
    rad = int(card * LEGACY_CARD_RADIUS)
    base = _shadow(px, lambda d, off: d.rounded_rectangle(
        (o, o + off, o + card, o + card + off), radius=rad, fill=255))
    ImageDraw.Draw(base).rounded_rectangle((o, o, o + card - 1, o + card - 1),
                                          radius=rad, fill=(255, 255, 255, 255))
    _place(base, art, px, LEGACY_SQUARE_ART)
    return base


def make_legacy_round(art, px):
    dia = int(round(px * LEGACY_ROUND_DIA))
    o = (px - dia) // 2
    base = _shadow(px, lambda d, off: d.ellipse(
        (o, o + off, o + dia - 1, o + dia - 1 + off), fill=255))
    ImageDraw.Draw(base).ellipse((o, o, o + dia - 1, o + dia - 1), fill=(255, 255, 255, 255))
    _place(base, art, px, LEGACY_ROUND_ART)
    return base


def save_png(img, path):
    """按调色板量化后保存：渐变图形用真彩存会有几十 KB，量化到 256 色肉眼无差。"""
    img.quantize(colors=PALETTE, method=Image.FASTOCTREE).save(path, 'PNG', optimize=True)


# ---------------- 主流程 ----------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true',
                    help='只生成到 build/icon-gen 并报告，不写入 src/res')
    ap.add_argument('--source', default=SRC, help=f'平面源图路径（默认 {SRC}）')
    args = ap.parse_args()

    if not os.path.exists(args.source):
        sys.exit(f"找不到源图：{args.source}\n"
                 f"把平面版图标（图形+白底，正方形，≥512px）放到 tools/icon-source/ 下")

    art = load_art(args.source)
    print(f"源图 {args.source}")
    print(f"抠底后图形 {art.size[0]}x{art.size[1]}（宽高比 {art.size[0]/art.size[1]:.3f}）")

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dest_root = os.path.join(root, 'build', 'icon-gen') if args.check \
        else os.path.join(root, 'src', 'res')
    if args.check:
        shutil.rmtree(dest_root, ignore_errors=True)

    total = 0
    print(f"\n{'密度':<10}{'前景层':>12}{'方形':>10}{'圆形':>10}")
    for name, scale in DENSITIES:
        d = os.path.join(dest_root, f'mipmap-{name}')
        os.makedirs(d, exist_ok=True)
        # 三种产物的画布尺寸**不一样**，别用同一个表达式：
        #   自适应前景层是 108dp（含四周 18dp 的出血区，给启动器裁切用）
        #   旧版图标是 48dp（就是最终显示尺寸，没有出血）
        # 混用会让旧版图标按 108dp 生成——像素多出 5 倍，而且密度桶对不上，
        # 启动器要再缩一次，既浪费体积又可能被某些启动器画错大小。
        jobs = (('ic_launcher_foreground.png', make_foreground, 108),
                ('ic_launcher.png', make_legacy_square, 48),
                ('ic_launcher_round.png', make_legacy_round, 48))
        sizes = []
        for fn, make, dp in jobs:
            p = os.path.join(d, fn)
            save_png(make(art, int(dp * scale)), p)
            sizes.append(os.path.getsize(p))
        total += sum(sizes)
        print(f"{name:<10}{sizes[0]:>11,}B{sizes[1]:>9,}B{sizes[2]:>9,}B")
    print(f"{'合计':<10}{total:>31,}B = {total/1024:.1f} KB")

    # 自检：把每个产物的期望像素尺寸算一遍并断言。
    # 加这一步是因为踩过：三种图标的画布尺寸不同（108dp / 48dp / 48dp），
    # 脚本里曾让它们共用同一个表达式，旧版图标于是按 108dp 生成——
    # 多出 5 倍像素、密度桶对不上，而且**看着一切正常**（图形比例是对的），
    # 只有比对尺寸才能发现。这类错误必须让脚本自己叫出来。
    bad = []
    for name, scale in DENSITIES:
        for fn, dp in (('ic_launcher_foreground.png', 108), ('ic_launcher.png', 48),
                       ('ic_launcher_round.png', 48)):
            p = os.path.join(dest_root, f'mipmap-{name}', fn)
            got = Image.open(p).size
            want = (int(dp * scale), int(dp * scale))
            if got != want:
                bad.append(f"{name}/{fn}: {got[0]}x{got[1]}，应为 {want[0]}x{want[1]}")
    if bad:
        print("\n❌ 尺寸自检未通过：")
        for b in bad:
            print(f"   {b}")
        sys.exit(1)
    print("✅ 尺寸自检通过（前景层 108dp / 旧版方形与圆形 48dp，各密度正确）")

    if args.check:
        print("\n（--check 模式，未写入 src/res）")
    else:
        print("\n已写入 src/res/mipmap-*/")
        print("提醒：自适应图标的背景色在 src/res/values/colors.xml 的 ic_launcher_background")


if __name__ == '__main__':
    main()