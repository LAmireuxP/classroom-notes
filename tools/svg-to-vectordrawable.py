#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SVG → Android VectorDrawable（矢量资源 XML）。

为什么要这个：应用内的标志（首页顶栏那枚）需要**单色**矢量。原设计稿是描边用的
线性渐变（`stroke="url(#g)"`），而 Android 的 VectorDrawable **不支持描边渐变**
（只支持填充渐变），所以不能直接用设计稿。
但应用内的图标本来就是单色的（Icons.icon 用 SRC_IN 把颜色整个替换成主题色），
渐变在这里没有意义——把描边改成纯色就能转成矢量，锐度比位图好，还省体积。

保留的东西：
  · 每条路径的 stroke-width / 圆头 / 圆角接头；
  · `opacity="0.7"` 这类弱化标记 → 转成 `strokeAlpha`（SRC_IN 只换颜色不换 alpha，
    所以「外圈声波更淡」这层关系在图里能保住）；
  · 圆角矩形（<rect rx>）→ 用圆弧指令表达的 path（VectorDrawable 没有 rect 元素）。
丢弃：满画布的底色矩形。

用法：
    python tools/svg-to-vectordrawable.py 源.svg src/res/drawable/ic_brand.xml
    python tools/svg-to-vectordrawable.py 源.svg 输出.xml --size 24 --color "#FF000000"
"""

import argparse
import os
import re
import sys


def _num(attrs, name, default=None):
    m = re.search(name + r'="([-\d.]+)"', attrs)
    return float(m.group(1)) if m else default


def rounded_rect_path(x, y, w, h, rx, ry):
    """圆角矩形 → path（用圆弧指令）。VectorDrawable 没有 <rect> 元素。

    只在**有直边段**时才输出 h/v：rx 等于半宽（胶囊形）或 ry 等于半高时，
    直边长度为 0，写出来就是 `h0` 这种零长度空指令——不影响渲染，但很难读。
    """
    rx = min(rx, w / 2)
    ry = min(ry, h / 2)
    if rx <= 0 or ry <= 0:
        return f"M{x:g},{y:g} h{w:g} v{h:g} h{-w:g} z"
    sw, sh = w - 2 * rx, h - 2 * ry
    d = f"M{x + rx:g},{y:g} "
    if sw > 0:
        d += f"h{sw:g} "
    d += f"a{rx:g},{ry:g} 0 0 1 {rx:g},{ry:g} "
    if sh > 0:
        d += f"v{sh:g} "
    d += f"a{rx:g},{ry:g} 0 0 1 {-rx:g},{ry:g} "
    if sw > 0:
        d += f"h{-sw:g} "
    d += f"a{rx:g},{ry:g} 0 0 1 {-rx:g},{-ry:g} "
    if sh > 0:
        d += f"v{-sh:g} "
    d += f"a{rx:g},{ry:g} 0 0 1 {rx:g},{-ry:g} z"
    return d


def convert(svg_path, size_dp, color, stroke_scale):
    text = open(svg_path, encoding='utf-8', errors='replace').read()
    vb = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', text)
    if not vb:
        sys.exit("SVG 里没有 viewBox，无法确定坐标空间")
    VW, VH = float(vb.group(1)), float(vb.group(2))

    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!--',
           '  由 tools/svg-to-vectordrawable.py 从设计稿 SVG 生成，请勿手改——',
           '  改设计稿后重跑脚本。描边用的是纯色（VectorDrawable 不支持描边渐变，',
           '  且应用内图标本来就用 SRC_IN 整体着色，渐变在这里没有意义）。',
           '-->',
           f'<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           f'    android:width="{size_dp:g}dp"',
           f'    android:height="{size_dp:g}dp"',
           f'    android:viewportWidth="{VW:g}"',
           f'    android:viewportHeight="{VH:g}">']

    body = text[text.index('>', text.index('<svg')) + 1:]
    kept = dropped_bg = 0

    for tag, attrs in re.findall(r'<(path|rect|line)\b([^>]*)/?>', body):
        d = None
        if tag == 'path':
            m = re.search(r'\bd="([^"]+)"', attrs)
            if not m:
                continue
            d = m.group(1)
            normalized = re.sub(r'[\s,]+', '', d).lower()
            if normalized.startswith(f'm0 0h{VW:g}v{VH:g}'):
                dropped_bg += 1
                continue
        elif tag == 'rect':
            w, h = _num(attrs, 'width'), _num(attrs, 'height')
            if w is None or h is None:
                continue
            if w >= VW and h >= VH:
                dropped_bg += 1
                continue
            d = rounded_rect_path(_num(attrs, 'x', 0), _num(attrs, 'y', 0), w, h,
                                  _num(attrs, 'rx', 0), _num(attrs, 'ry', _num(attrs, 'rx', 0)))
        elif tag == 'line':
            d = (f"M{_num(attrs, 'x1', 0):g},{_num(attrs, 'y1', 0):g} "
                 f"L{_num(attrs, 'x2', 0):g},{_num(attrs, 'y2', 0):g}")

        if not d:
            continue
        sw = (_num(attrs, 'stroke-width', 0) or 0) * stroke_scale
        op = _num(attrs, 'opacity', 1.0)
        fill = re.search(r'fill="([^"]*)"', attrs)
        fill = fill.group(1) if fill else 'none'

        parts = ['    <path', f'        android:pathData="{d}"']
        if sw > 0:
            parts += [f'        android:strokeColor="{color}"',
                      f'        android:strokeWidth="{sw:g}"',
                      '        android:strokeLineCap="round"',
                      '        android:strokeLineJoin="round"']
            # 描边路径一律不填充（设计稿里的 fill="#FFFFFF" 是「白底挖空」的效果，
            # 在单色图标里等价于不填充）
            parts.append('        android:fillColor="#00000000"')
        else:
            parts.append(f'        android:fillColor="{color}"')
        if op < 1:
            parts.append(f'        android:strokeAlpha="{op:g}"')
        out.append('\n'.join(parts) + '/>')
        kept += 1

    out.append('</vector>')
    return '\n'.join(out) + '\n', kept, dropped_bg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('svg')
    ap.add_argument('out')
    ap.add_argument('--size', type=float, default=24, help='输出边长（dp），默认 24')
    ap.add_argument('--color', default='#FF000000',
                    help='描边色。应用内会被 SRC_IN 替换成主题色，所以随便给个不透明的即可')
    ap.add_argument('--stroke-scale', type=float, default=1.0, dest='stroke_scale',
                    help='描边缩放。设计稿是按 512 尺寸画的，缩到 24dp 后线会偏细，可据此调粗')
    args = ap.parse_args()

    xml, kept, dropped = convert(args.svg, args.size, args.color, args.stroke_scale)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    open(args.out, 'w', encoding='utf-8').write(xml)
    print(f"已生成 {args.out}")
    print(f"  路径 {kept} 条，丢弃满画布底色 {dropped} 个")
    print(f"  尺寸 {args.size:g}dp  描边缩放 ×{args.stroke_scale}")


if __name__ == '__main__':
    main()