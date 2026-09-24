#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从设计稿 SVG 渲染图标图形，只做「提升小尺寸可辨识度」的最小改动，形状本身不重画。

背景——为什么需要这一步：
设计稿（luyin-icon.svg）是 512 尺寸下画的，细描边很雅。但启动器实际显示的是 48dp：
  · 最外那对声波描边 11px / 512 ≈ 2%，缩到 48px 是 0.5px——不是「变细」而是**消失**；
  · 鹿角 18px 缩完约 1.3px，边缘全是锯齿；
  · 话筒是白填充描边，放在白底自适应图标上，话筒身和背景融成一片，「麦克风」读不出来。
手绘重画这条路试过了（三版），结论是重画出来的鹿角会读成「粗指头」，不如原稿。所以改成
**解析原 SVG 的路径、原样使用**，只调两类参数：

  1. 描边整体加粗（STROKE_SCALE）；
  2. 话筒胶囊由描边改为实心填充——外轮廓取原描边的**外边界**，剪影和原稿完全一致；
  3. 丢掉设计稿自己标了 opacity 的那对装饰声波（设计上它本就是弱化的，也正好是小尺寸下先消失的那批）。

形状坐标全部来自 SVG，一个数都不手改。改设计稿后重跑本脚本即可。

用法：
    python tools/icon-art.py                 # → tools/icon-source/luyin-icon-v2-512.png
    python tools/icon-art.py --scale 1.5     # 试别的加粗倍数
    python tools/icon-art.py --keep-faint    # 保留那对装饰声波（对照用）
    python tools/icon-art.py --no-grille     # 不给话筒开格栅缝
"""

import argparse
import math
import os
import re
import sys

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("需要 Pillow：pip install Pillow")

import numpy as np

SS = 4                  # 超采样倍数（Pillow 画线没有抗锯齿，靠超采样再缩拿到干净边缘）
OUT = 512               # 输出边长
W = OUT * SS

STOPS = [(0.00, (0x4F, 0x7D, 0xF7)),
         (0.52, (0x9A, 0x6B, 0xEE)),
         (1.00, (0xFF, 0x6F, 0xA8))]
G_AXIS = ((110, 70), (400, 460))
WHITE = 255


# ---------------- SVG 解析（只覆盖设计稿实际用到的命令）----------------

def _nums(s):
    return [float(x) for x in re.findall(r'-?\d*\.?\d+', s)]


def sample_path(d, n=48):
    """把 path 的 d 采样成折线。支持 M / L / C / Q，坐标为绝对值（设计稿全是绝对坐标）。"""
    toks = re.findall(r'([MLCQZmlcqz])([^MLCQZmlcqz]*)', d)
    pts, cur = [], (0.0, 0.0)
    for cmd, arg in toks:
        v = _nums(arg)
        if cmd in 'Mm':
            cur = (v[0], v[1])
            pts.append(cur)
        elif cmd in 'Ll':
            for i in range(0, len(v), 2):
                cur = (v[i], v[i + 1])
                pts.append(cur)
        elif cmd in 'Cc':
            for i in range(0, len(v), 6):
                p1, p2, p3 = (v[i], v[i + 1]), (v[i + 2], v[i + 3]), (v[i + 4], v[i + 5])
                for k in range(1, n + 1):
                    t = k / n
                    u = 1 - t
                    pts.append((u**3 * cur[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
                                u**3 * cur[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]))
                cur = p3
        elif cmd in 'Qq':
            for i in range(0, len(v), 4):
                p1, p2 = (v[i], v[i + 1]), (v[i + 2], v[i + 3])
                for k in range(1, n + 1):
                    t = k / n
                    u = 1 - t
                    pts.append((u * u * cur[0] + 2 * u * t * p1[0] + t * t * p2[0],
                                u * u * cur[1] + 2 * u * t * p1[1] + t * t * p2[1]))
                cur = p2
    return pts


def parse_svg(path):
    """抽出需要绘制的元素。背景整块白 rect 丢弃。"""
    text = open(path, encoding='utf-8').read()
    body = text[text.index('>', text.index('<svg')) + 1:]
    items = []
    for tag, attrs in re.findall(r'<(path|rect|line)\b([^>]*)/?>', body):
        def attr(name, default=None):
            m = re.search(name + r'="([^"]*)"', attrs)
            return m.group(1) if m else default
        if tag == 'rect' and attr('width') == '512':
            continue                                   # 设计稿的白底
        items.append({
            'tag': tag,
            'attrs': attrs,
            'd': attr('d'),
            'stroke': float(attr('stroke-width', '0')),
            'opacity': float(attr('opacity', '1')),
            'fill': attr('fill', 'none'),
        })
    return items


# ---------------- 绘制 ----------------

def stroke(draw, pts, width, color=WHITE):
    """圆头折线：线段本体 + 两端补圆（Pillow 的端点只有平头）。"""
    p = [(x * SS, y * SS) for x, y in pts]
    w = max(1, int(round(width * SS)))
    if len(p) >= 2:
        draw.line(p, fill=color, width=w, joint='curve')
    r = w / 2.0
    for x, y in (p[0], p[-1]):
        draw.ellipse((x - r, y - r, x + r, y + r), fill=color)


def fill_round_rect(draw, x1, y1, x2, y2, radius, color=WHITE):
    draw.rounded_rectangle((x1 * SS, y1 * SS, x2 * SS, y2 * SS),
                           radius=radius * SS, fill=color)


def build_mask(items, scale, keep_faint, grille, min_stroke):
    m = Image.new('L', (W, W), 0)
    d = ImageDraw.Draw(m)

    for it in items:
        # 设计稿用 opacity 标出的那对声波是**弱化装饰**，也正是小尺寸下最先消失的那批。
        # 按 opacity 过滤而不是按坐标硬编码：规则来自设计稿自身，改稿也不会失效。
        if it['opacity'] < 1 and not keep_faint:
            continue

        if it['tag'] == 'rect':
            x, y = float(re.search(r'x="([^"]*)"', it['attrs']).group(1)), \
                   float(re.search(r'y="([^"]*)"', it['attrs']).group(1))
            w = float(re.search(r'width="([^"]*)"', it['attrs']).group(1))
            h = float(re.search(r'height="([^"]*)"', it['attrs']).group(1))
            rx = float(re.search(r'rx="([^"]*)"', it['attrs']).group(1))
            o = it['stroke'] / 2.0            # 描边中心到外边界
            # 把描边胶囊改成实心填充：外轮廓取原描边的外边界，
            # 所以**剪影与设计稿完全一致**，只是不再空心。
            fill_round_rect(d, x - o, y - o, x + w + o, y + h + o, rx + o)
            if grille:
                # 格栅缝：大尺寸下增加「话筒」的细节。缩到 48px 时会糊掉，
                # 所以开得很细，只在大尺寸贡献细节、不破坏小尺寸的剪影。
                for gy in (y + h * 0.30, y + h * 0.50, y + h * 0.70):
                    stroke(d, [(x + w * 0.30, gy), (x + w * 0.70, gy)], 7, color=0)
        elif it['tag'] == 'line':
            a = lambda n: float(re.search(n + r'="([^"]*)"', it['attrs']).group(1))
            stroke(d, [(a('x1'), a('y1')), (a('x2'), a('y2'))],
                   max(it['stroke'] * scale, min_stroke))
        elif it['d']:
            stroke(d, sample_path(it['d']), max(it['stroke'] * scale, min_stroke))

    return m


def widths_of(items, scale, keep_faint, min_stroke):
    """实际用到的描边宽度（供报告）。"""
    out = []
    for it in items:
        if it['opacity'] < 1 and not keep_faint:
            continue
        for w in [it['stroke']] if it['tag'] != 'rect' else [it['stroke']]:
            if w > 0:
                out.append(max(w * scale, min_stroke))
    return sorted(set(round(w) for w in out))


def gradient_image():
    """按品牌三色造对角渐变（在 512 坐标系算，再套到蒙版上）。"""
    (x1, y1), (x2, y2) = G_AXIS
    dx, dy = x2 - x1, y2 - y1
    L2 = dx * dx + dy * dy
    yy, xx = np.mgrid[0:OUT, 0:OUT].astype(float)
    t = np.clip(((xx - x1) * dx + (yy - y1) * dy) / L2, 0.0, 1.0)
    rgb = np.zeros((OUT, OUT, 3), float)
    for i in range(len(STOPS) - 1):
        t0, c0 = STOPS[i]
        t1, c1 = STOPS[i + 1]
        k = np.clip((t - t0) / (t1 - t0), 0.0, 1.0)
        seg = (t >= t0) & (t <= t1)
        for ch in range(3):
            rgb[:, :, ch] = np.where(seg, c0[ch] + (c1[ch] - c0[ch]) * k, rgb[:, :, ch])
    img = np.zeros((OUT, OUT, 4), np.uint8)
    img[:, :, :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    img[:, :, 3] = 255
    return Image.fromarray(img, 'RGBA')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scale', type=float, default=1.0,
                    help='描边整体加粗倍数（默认 1.0 即不动，靠 --min-stroke 兜底细线）')
    ap.add_argument('--min-stroke', type=float, default=20.0, dest='min_stroke',
                    help='最小描边宽度（512 坐标系，默认 20）。低于它的描边会被顶到 20——'
                         '粗的那几根不动，优雅度不受影响，只救会消失的细线。')
    ap.add_argument('--keep-faint', action='store_true', help='保留设计稿标了 opacity 的装饰声波')
    ap.add_argument('--no-grille', action='store_true', help='话筒不开格栅缝')
    ap.add_argument('--svg', default=None)
    ap.add_argument('-o', '--out', default=None)
    args = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    src = args.svg or os.path.join(here, 'icon-source', 'luyin-icon.svg')
    out = args.out or os.path.join(here, 'icon-source', 'luyin-icon-v2-512.png')

    items = parse_svg(src)
    n_stroke = sum(1 for i in items if i['tag'] != 'rect')
    n_faint = sum(1 for i in items if i['opacity'] < 1)
    print(f"解析 {src}")
    print(f"  元素 {len(items)} 个（描边 {n_stroke}，其中弱化装饰 {n_faint} 个"
          f"{'（保留）' if args.keep_faint else '（丢弃）'}）")
    print(f"  描边加粗 ×{args.scale}，最小描边 {args.min_stroke:.0f}px 兜底")

    mask = build_mask(items, args.scale, args.keep_faint, not args.no_grille,
                      args.min_stroke)
    mask_small = mask.resize((OUT, OUT), Image.LANCZOS)

    art = gradient_image()
    art.putalpha(mask_small)
    art.save(out)

    bb = mask_small.getbbox()
    a = np.array(mask_small)
    ws = widths_of(items, args.scale, args.keep_faint, args.min_stroke)
    print(f"\n已生成 {out}")
    print(f"  图形范围 {bb} → {bb[2]-bb[0]}×{bb[3]-bb[1]}  "
          f"宽高比 {(bb[2]-bb[0])/(bb[3]-bb[1]):.3f}  不透明占比 {(a>8).mean():.1%}")
    print(f"  实际描边宽度 {ws}  (512 坐标系)")
    for w in ws:
        px = w / 512 * 48 * 0.65
        flag = '✅' if px >= 1.0 else '⚠️ 小尺寸下会断'
        print(f"    {w:>3}px → 48dp 图标上约 {px:.2f}px  {flag}")


if __name__ == '__main__':
    main()