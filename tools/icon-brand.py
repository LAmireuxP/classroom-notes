#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
「反转配色」图标 —— 品牌渐变做满幅底，白色鹿 + 声波做前景。

为什么反转：旧图标是「白底 + 彩色图形」，48dp 下彩色剪影贴在白底上层次偏弱——
彩色图形的对比度靠渐变本身的明度，而蓝紫粉三色都在中间调，剪影边缘发虚。
反转之后：白形对渐变的对比度是恒定的满档（三个品牌色都足够深），小尺寸下
轮廓先立起来；渐变底也让图标在一片白底图标的启动器里更有辨识度。

图形本体（鹿头颈 + 两道声波）与 1.4 版完全同源：裁剪参数、声波角度与线宽
全部复用 icon-deer-sound 的量测结果，只有配色反转。鹿素材的授权不变
（game-icons.net "Deer" by Caro Asercion，CC BY 3.0，见 README）。

产物与 make-icons.py 相同（自适应前景 108dp / 旧版方形与圆形 48dp，五密度），
画布尺寸自检也照搬——这类「看着正常但密度桶错了」的问题必须让脚本自己叫出来。
用法：python tools/icon-brand.py --check   # 先出预览与对比图，不写入
      python tools/icon-brand.py           # 写入 src/res/mipmap-*
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import iconlib as il

# icon-deer-sound 的文件名带连字符，不能常规 import——按路径加载。
# 只取它的量测函数与常量（模块体只有定义，main 有 __main__ 闸，加载无副作用）。
import importlib.util
_spec = importlib.util.spec_from_file_location(
    'icon_deer_sound', os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    'icon-deer-sound.py'))
ids = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_spec and ids)

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

# ---------------- 可调参数 ----------------

# 白色图形占画布长边的比例（make-icons.py 同名参数的白底反转版）：
#   自适应前景层：108dp 画布、安全区 66.7%，留一点余量防圆遮罩切角；
#   旧版沿用 1.3 版实测结构（卡片 79% / 圆 91%）。白形比彩形对比度高，
#   同占比下轮廓更清楚，所以不再放大。
ADAPTIVE_ART = 0.62
LEGACY_CARD = 0.79
LEGACY_CARD_RADIUS = 0.22
LEGACY_SQUARE_ART = 0.62
LEGACY_ROUND_DIA = 0.91
LEGACY_ROUND_ART = 0.66

PALETTE = 256
DENSITIES = [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]

JOBS = (('ic_launcher_foreground.png', 108),
        ('ic_launcher.png', 48),
        ('ic_launcher_round.png', 48))


# ---------------- 图形 ----------------

def glyph_mask():
    """白色图形的 alpha 蒙版：鹿头颈（icon-deer-sound 的裁剪参数）+ 两道声波。

    直接复用 ids 的量测函数（吻尖、身体起点）与 WAVES/EDGE_GAP/MOUTH_DROP
    常量——位置关系是那个文件里量出来并踩过坑的，不在这里重推一遍。
    返回裁到实际范围（bbox）的 L 蒙版。
    """
    here = os.path.dirname(os.path.abspath(__file__))
    svg = os.path.join(here, 'icon-source', 'luyin-deer.svg')
    deer, _ = il.svg_alpha(svg, il.OUT)
    deer = deer.crop(deer.getbbox())
    onset, _, _ = ids.body_onset(deer)
    cut = min(deer.size[1], int(onset + 80))
    deer = deer.crop((0, 0, deer.size[0], cut))
    deer = deer.crop(deer.getbbox())
    sx, sy = ids.snout_tip(deer)

    r1, w1 = ids.WAVES[0][0], ids.WAVES[0][1]
    cx = sx + ids.EDGE_GAP + w1 / 2 - r1
    cy = sy + ids.MOUTH_DROP
    reach = cx + ids.WAVES[-1][0] + ids.WAVES[-1][1] / 2
    canvas = int(round(max(deer.size[0], reach, deer.size[1])))

    m = Image.new('L', (canvas * il.SS, canvas * il.SS), 0)
    d = ImageDraw.Draw(m)
    big = deer.resize((deer.size[0] * il.SS, deer.size[1] * il.SS), Image.LANCZOS)
    m.paste(big, (0, 0), big)
    for r, wid, a0, a1 in ids.WAVES:
        il.stroke(d, il.arc(cx, cy, r, a0, a1), wid)
    g = m.resize((canvas, canvas), Image.LANCZOS)
    return g.crop(g.getbbox())


def gradient_rgba(size):
    """品牌渐变转 RGBA（不透明），用于满幅底 / 渐变卡片。

    il.gradient 的渐变轴是 512 坐标系的（G_AXIS 固定），直接传其它尺寸会让
    轴溢出画布、粉色一端被截掉（48dp 对比图里渐变「只剩蓝」就是这么来的）。
    所以统一在 512 生成整段渐变，再缩放到目标尺寸。
    """
    g = Image.fromarray(il.gradient(il.OUT), 'RGB')
    if size != il.OUT:
        g = g.resize((size, size), Image.LANCZOS)
    return g.convert('RGBA')


def _place(base, glyph, size, fraction):
    """白形按长边占比缩放并居中（逻辑同 make-icons._place，兜底防裁切）。"""
    scale = size * fraction / max(glyph.size)
    tw, th = max(1, round(glyph.size[0] * scale)), max(1, round(glyph.size[1] * scale))
    a = glyph.resize((tw, th), Image.LANCZOS)
    if tw > size or th > size:
        raise ValueError(f"图形 {glyph.size} 按 {fraction:.0%} 缩放后 {a.size} 超出画布 {size}")
    white = Image.new('RGBA', a.size, (255, 255, 255, 255))
    white.putalpha(a)                      # glyph 本身就是 L 蒙版，灰度即 alpha
    base.alpha_composite(white, ((size - tw) // 2, (size - th) // 2))


def _shadow(size, painter):
    """旧版图标保留白底时代的阴影结构（向下略偏的模糊黑影）。"""
    off = int(round(size * 0.02))
    sh = Image.new('L', (size, size), 0)
    painter(ImageDraw.Draw(sh), off)
    sh = sh.filter(ImageFilter.GaussianBlur(max(1, size * 0.012)))
    sh = sh.point(lambda v: int(v * 0.5))
    layer = Image.new('RGBA', (size, size), (0, 0, 0, 255))
    layer.putalpha(sh)
    return layer


def make_foreground(glyph, px):
    """自适应前景层：**满幅渐变**（外圈 18dp 是启动器裁切的出血区）+ 白形居中。"""
    base = gradient_rgba(px)
    _place(base, glyph, px, ADAPTIVE_ART)
    return base


def make_legacy_square(glyph, px):
    card = int(round(px * LEGACY_CARD))
    o = (px - card) // 2
    rad = int(card * LEGACY_CARD_RADIUS)
    base = _shadow(px, lambda d, off: d.rounded_rectangle(
        (o, o + off, o + card, o + card + off), radius=rad, fill=255))
    ImageDraw.Draw(base).rounded_rectangle((o, o, o + card - 1, o + card - 1),
                                           radius=rad, fill=(255, 255, 255, 255))
    base.alpha_composite(gradient_rgba(card).resize((card, card), Image.LANCZOS), (o, o))
    _place(base, glyph, px, LEGACY_SQUARE_ART)
    return base


def make_legacy_round(glyph, px):
    dia = int(round(px * LEGACY_ROUND_DIA))
    o = (px - dia) // 2
    base = _shadow(px, lambda d, off: d.ellipse(
        (o, o + off, o + dia - 1, o + dia - 1 + off), fill=255))
    ImageDraw.Draw(base).ellipse((o, o, o + dia - 1, o + dia - 1), fill=(255, 255, 255, 255))
    base.alpha_composite(gradient_rgba(dia).resize((dia, dia), Image.LANCZOS), (o, o))
    _place(base, glyph, px, LEGACY_ROUND_ART)
    return base


def save_png(img, path):
    img.quantize(colors=PALETTE, method=Image.FASTOCTREE).save(path, 'PNG', optimize=True)


# ---------------- 主流程 ----------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true', help='只生成到 build/icon-gen，不写入 src/res')
    args = ap.parse_args()

    glyph = glyph_mask()
    print(f"白色图形 {glyph.size[0]}×{glyph.size[1]}（宽高比 {glyph.size[0]/glyph.size[1]:.3f}）")

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dest_root = os.path.join(root, 'build', 'icon-gen') if args.check \
        else os.path.join(root, 'src', 'res')

    makers = {'ic_launcher_foreground.png': make_foreground,
              'ic_launcher.png': make_legacy_square,
              'ic_launcher_round.png': make_legacy_round}

    total = 0
    for name, scale in DENSITIES:
        d = os.path.join(dest_root, f'mipmap-{name}')
        os.makedirs(d, exist_ok=True)
        line = f"{name:<10}"
        for fn, dp in JOBS:
            p = os.path.join(d, fn)
            save_png(makers[fn](glyph, int(dp * scale)), p)
            line += f"{fn.split('_')[-1]:>9}"
            total += os.path.getsize(p)
        print(line)

    # 尺寸自检（make-icons.py 踩过的坑：三产物画布尺寸不同，共用表达式会错 5 倍）
    bad = []
    for name, scale in DENSITIES:
        for fn, dp in JOBS:
            got = Image.open(os.path.join(dest_root, f'mipmap-{name}', fn)).size
            want = (int(dp * scale), int(dp * scale))
            if got != want:
                bad.append(f"{name}/{fn}: {got}，应为 {want}")
    if bad:
        print('❌ 尺寸自检未通过：', *bad, sep='\n   ')
        sys.exit(1)
    print(f"✅ 尺寸自检通过，合计 {total/1024:.1f} KB" + ('（--check 未写入）' if args.check else ''))

    # 预览：新图标 512 平面图 + 新旧 48dp 对比（方形 / 圆遮罩）
    prev_dir = os.path.join(root, 'build')
    os.makedirs(prev_dir, exist_ok=True)
    flat = gradient_rgba(512)
    _place(flat, glyph, 512, 0.62)
    flat.save(os.path.join(prev_dir, 'icon-new-512.png'))

    old = Image.open(os.path.join(root, 'src', 'res', 'mipmap-xxxhdpi', 'ic_launcher.png')
                     if not args.check else
                     os.path.join(dest_root, 'mipmap-xxxhdpi', 'ic_launcher.png')).convert('RGBA')
    new = Image.open(os.path.join(dest_root, 'mipmap-xxxhdpi', 'ic_launcher.png')).convert('RGBA')
    Z = 3                                            # 放大 3 倍模拟肉眼近看
    cell = 48 * Z + 24
    canvas = Image.new('RGBA', (cell * 4 + 24, cell + 24), (245, 245, 247, 255))
    for i, (img, mask_circle) in enumerate([(old, False), (old, True),
                                            (new, False), (new, True)]):
        big = img.resize((48 * Z, 48 * Z), Image.LANCZOS)
        if mask_circle:
            m = Image.new('L', big.size, 0)
            ImageDraw.Draw(m).ellipse((0, 0, big.size[0] - 1, big.size[1] - 1), fill=255)
            canvas.paste(big, (24 + i * cell, 12), m)
        else:
            canvas.alpha_composite(big, (24 + i * cell, 12))
    canvas.convert('RGB').save(os.path.join(prev_dir, 'icon-compare.png'))
    print(f"预览：{os.path.join(prev_dir, 'icon-new-512.png')} / icon-compare.png"
          f"（旧方 旧圆 新方 新圆 @48dp×3）")


if __name__ == '__main__':
    main()
