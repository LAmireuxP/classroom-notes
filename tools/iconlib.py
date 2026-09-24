#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
图标工具链的共享部分：品牌渐变、SVG 光栅化、剥满画布底色、圆头描边。

**为什么单独抽一个模块**：剥「满画布底色」这条规则原先在 icon-from-svg.py 里，
icon-deer-sound.py 又照抄了一份，结果抄的时候正则少了一步去空格，背景没剥掉、
产物变成一块实心方块（宽高比 1.000）。同一件事写两遍就一定会漂。
所有脚本都从这里取，规则只维护一份。

被 icon-from-svg.py / icon-deer-sound.py 使用。
"""

import io
import math
import re

import numpy as np
from PIL import Image, ImageDraw

try:
    import cairosvg
except ImportError:  # 允许只 import 常量（比如只想拿渐变）时不立刻炸
    cairosvg = None

# 品牌三色渐变（沿用原设计，未改动）
STOPS = [(0.00, (0x4F, 0x7D, 0xF7)),      # 蓝
         (0.52, (0x9A, 0x6B, 0xEE)),      # 紫
         (1.00, (0xFF, 0x6F, 0xA8))]      # 粉
G_AXIS = ((110, 70), (400, 460))          # 渐变方向：左上 → 右下（原设计同）

OUT = 512                                  # 图形素材的统一边长
SS = 4                                     # 绘制时的超采样倍数

# 48dp 图标上「一条线还看得见」的最小宽度（512 坐标系）：
# 48/512*0.65 ≈ 0.061，所以 1px 对应约 16.4px；取 18 留一点余量。
MIN_LEGIBLE_STROKE = 18


def gradient(size=OUT):
    """品牌三色对角渐变（RGB）。"""
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


def apply_gradient(mask_array):
    """把灰度蒙版套上品牌渐变，返回 RGBA 图（透明底）。mask 的灰度即 alpha。

    蒙版**不要求是正方形**：渐变先按素材尺寸生成再拉伸到蒙版的形状。
    早期版本直接把 512 的渐变和任意尺寸的蒙版叠在一起，裁剪过的素材（比如只取鹿头）
    会在这里报形状不匹配——修掉。
    """
    m = np.array(mask_array)
    h, w = m.shape[:2]
    g = gradient(OUT)
    if (h, w) != (OUT, OUT):
        g = np.array(Image.fromarray(g, 'RGB').resize((w, h), Image.LANCZOS))
    return Image.fromarray(np.dstack([g, m]), 'RGBA')


def strip_background(svg_text):
    """剥掉满画布的底色矩形，返回 (新文本, 是否剥过)。

    game-icons.net 的下载链接是 `/icons/<前景色>/<背景色>/...`，默认给的是「黑底白图」，
    SVG 里因此有一块覆盖整个画布的 `<path d="M0 0h512v512H0z"/>`。只取 alpha 的话，
    背景和图形一样不透明，整张图会变成一块实心方块。按**几何**判据剥离：
    正好等于视口大小的路径/矩形就是底色。
    """
    m = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', svg_text)
    if not m:
        return svg_text, False
    W, H = float(m.group(1)), float(m.group(2))
    stripped = [False]

    def norm(s):
        # 去空格与逗号再比较：`M0 0h512v512H0z` 归一化后是 `m00h512v512h0z`，
        # 以前这里没去空格，判据就永远不成立（踩过）。
        return re.sub(r'[\s,]+', '', s).lower()

    def is_full_canvas(d):
        s = norm(d)
        return (s in (norm(f'M0 0h{W:g}v{H:g}H0z'), norm(f'M0 0v{H:g}h{W:g}v-{H:g}z'))
                or s.startswith(norm(f'M0 0h{W:g}v{H:g}'))
                or s == norm(f'M0,0L{W:g},0L{W:g},{H:g}L0,{H:g}z'))

    def repl_path(mm):
        if is_full_canvas(mm.group(1)):
            stripped[0] = True
            return ''
        return mm.group(0)

    svg_text = re.sub(r'<path[^>]*\bd="([^"]+)"[^>]*/?>', repl_path, svg_text)

    def repl_rect(mm):
        tag = mm.group(0)
        w = re.search(r'width="([\d.]+)"', tag)
        h = re.search(r'height="([\d.]+)"', tag)
        x = re.search(r'x="([\d.]+)"', tag)
        y = re.search(r'y="([\d.]+)"', tag)
        if w and h and float(w.group(1)) >= W and float(h.group(1)) >= H \
                and (not x or float(x.group(1)) <= 0) and (not y or float(y.group(1)) <= 0):
            stripped[0] = True
            return ''
        return tag

    svg_text = re.sub(r'<(?:rect|image)\b[^>]*/?>', repl_rect, svg_text)
    return svg_text, stripped[0]


def svg_alpha(svg_path, size=OUT):
    """SVG → 灰度蒙版（alpha）。自动剥满画布底色。"""
    if cairosvg is None:
        raise RuntimeError("需要 cairosvg：pip install cairosvg")
    text = open(svg_path, encoding='utf-8', errors='replace').read()
    text, did = strip_background(text)
    png = cairosvg.svg2png(bytestring=text.encode('utf-8'),
                           output_width=size, output_height=size)
    a = np.array(Image.open(io.BytesIO(png)).convert('RGBA').split()[3])
    cov = (a > 8).mean()
    if cov < 0.005:
        raise ValueError("源图几乎没有不透明像素——SVG 可能只有描边没有填充，"
                         "或用了 currentColor 却没指定颜色")
    if cov > 0.95:
        # 必须在这里拦住：满画布底色会让 alpha 全 1，产物变成一块实心方块，
        # 而下游的尺寸自检看不出来（画布尺寸是对的）。
        raise ValueError(f"图形几乎占满画布（{cov:.1%}），像是把背景也当成了图形")
    return Image.fromarray(a, 'L'), did


# ---------------- 绘制（超采样 + 圆头）----------------

def pt(x, y):
    return (x * SS, y * SS)


def stroke(draw, pts, width, color=255):
    """圆头折线：线段本体 + 两端补圆（Pillow 的端点只有平头）。"""
    p = [pt(x, y) for x, y in pts]
    w = max(1, int(round(width * SS)))
    if len(p) >= 2:
        draw.line(p, fill=color, width=w, joint='curve')
    r = w / 2.0
    for x, y in (p[0], p[-1]):
        draw.ellipse((x - r, y - r, x + r, y + r), fill=color)


def arc(cx, cy, r, a0, a1, n=64):
    """按角度采样圆弧。0° 指右，屏幕坐标 y 向下，顺时针为正。"""
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def save_png(img, path, palette=256):
    """量化到调色板后保存。

    渐变图形产生的独特色数以万计，直接存真彩 PNG 会有 40 KB+；量化到 256 色肉眼无差
    （逐像素核过：图形内部平均误差约 2%，且相邻像素跳变没有变大 = 没有色带），
    体积能砍掉一大半。整份图标资源从 167 KB 降到 54 KB 靠的就是这一步。
    """
    img.quantize(colors=palette, method=Image.FASTOCTREE).save(path, 'PNG', optimize=True)