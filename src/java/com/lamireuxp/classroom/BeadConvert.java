package com.lamireuxp.classroom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片 → 拼豆图纸转换。
 *
 * 两段式（这是和「每像素直接匹配全品牌色库」拉开的关键）：
 *   1. 中位数切分（median cut）把原图压到用户指定的 N 个代表色；
 *   2. 每个代表色匹配一次品牌色号（CIEDE2000），去重后得到图纸配色表；
 *   3. 像素按配色表内最近色落位（表内 ≤ 64 色，这一步很快），
 *      开抖动时按 Floyd–Steinberg 在配色表内扩散误差。
 *
 * 复杂度：O(像素 × 表内色数 + 代表色 × 品牌色数)。256×256 全流程 < 100ms（中端机），
 * 在后台线程跑，不卡界面。
 */
public final class BeadConvert {

    private BeadConvert() {}

    /** 一张图纸的尺寸上限：颂拼豆口径 16×16 ~ 256×256。 */
    public static final int MIN_SIZE = 16;
    public static final int MAX_SIZE = 256;
    /** 单张图纸配色表上限。255 号字节留给「空格」，色号下标最多 254。 */
    public static final int MAX_COLORS = 254;

    /** 转换参数，全部外置——调用方（导入页）从控件收集。 */
    public static final class Params {
        public int w = 48, h = 48;
        public int maxColors = 24;
        public String brand = "mard";
        public boolean dither = false;
    }

    /**
     * 按目标尺寸解码 Uri。inSampleSize 只能 2 的幂，先粗降到 ≤4×目标，
     * 再交给 createScaledBitmap 精确到目标——直接解原图再缩会白吃几十 MB 内存。
     */
    public static Bitmap decodeScaled(Context c, Uri uri, int targetW, int targetH) throws Exception {
        BitmapFactory.Options bo = new BitmapFactory.Options();
        bo.inJustDecodeBounds = true;
        try (java.io.InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bo);
        }
        if (bo.outWidth <= 0 || bo.outHeight <= 0) throw new Exception("无法读取这张图片");
        int maxSide = Math.max(targetW, targetH) * 4;
        int sample = 1;
        while (bo.outWidth / (sample * 2) >= maxSide && bo.outHeight / (sample * 2) >= maxSide) {
            sample *= 2;
        }
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap src;
        try (java.io.InputStream in = c.getContentResolver().openInputStream(uri)) {
            src = BitmapFactory.decodeStream(in, null, o2);
        }
        if (src == null) throw new Exception("图片解码失败");
        return src;
    }

    /** 空白图纸（全部空格，配色表给一个白色占位，画第一笔时就会被真实色号替换）。 */
    public static Db.BeadPattern blank(String title, int w, int h) {
        Db.BeadPattern p = new Db.BeadPattern();
        p.id = Id.gen();
        p.title = title;
        p.w = clampSize(w);
        p.h = clampSize(h);
        p.pages = 1;
        p.pixels = new byte[p.w * p.h];
        java.util.Arrays.fill(p.pixels, (byte) 255);
        p.colors = new ArrayList<String>();
        p.album = "";
        p.fav = false;
        p.created = p.updated = System.currentTimeMillis();
        return p;
    }

    /** 主转换入口。耗时操作，调用方必须放在后台线程。 */
    public static Db.BeadPattern generate(String title, Bitmap src, Params pa) {
        int w = clampSize(pa.w), h = clampSize(pa.h);
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != src && small != null) small.recycle();

        // ---- 1. 收集不透明像素（半透明按背景白合成后参与，全透明当空格）----
        List<int[]> opaque = new ArrayList<int[]>();
        boolean[] empty = new boolean[w * h];
        for (int i = 0; i < px.length; i++) {
            int a = (px[i] >>> 24) & 0xFF;
            if (a < 128) {
                empty[i] = true;
            } else if (a < 255) {
                // 半透明压到白底合成：拼豆没有透明豆，图纸上的正确语义就是衬白
                int r = (int) ((((px[i] >> 16) & 0xFF) * a + 255 * (255 - a)) / 255f);
                int g = (int) ((((px[i] >> 8) & 0xFF) * a + 255 * (255 - a)) / 255f);
                int b = (int) (((px[i] & 0xFF) * a + 255 * (255 - a)) / 255f);
                opaque.add(new int[]{r, g, b});
                px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            } else {
                opaque.add(new int[]{(px[i] >> 16) & 0xFF, (px[i] >> 8) & 0xFF, px[i] & 0xFF});
            }
        }

        // ---- 2. 中位数切分出代表色，再逐个匹配品牌色号、去重成配色表 ----
        int want = Math.max(1, Math.min(pa.maxColors, MAX_COLORS));
        int[][] reps = medianCut(opaque, want);
        Map<String, Integer> order = new LinkedHashMap<String, Integer>();
        List<String> colors = new ArrayList<String>();
        int[][] palRgb = new int[reps.length][];
        for (int i = 0; i < reps.length; i++) {
            BeadPalettes.Color bc = BeadPalettes.nearest(pa.brand,
                    (reps[i][0] << 16) | (reps[i][1] << 8) | reps[i][2]);
            if (bc == null) continue;
            Integer idx = order.get(bc.brand + ":" + bc.code);
            if (idx == null) {
                idx = colors.size();
                order.put(bc.brand + ":" + bc.code, idx);
                colors.add(bc.brand + ":" + bc.code);
            }
            palRgb[i] = new int[]{(bc.rgb >> 16) & 0xFF, (bc.rgb >> 8) & 0xFF, bc.rgb & 0xFF};
        }
        if (colors.isEmpty()) {
            // 全透明图或品牌色库为空：兜一个白色，让图纸可保存可编辑
            BeadPalettes.Color white = BeadPalettes.nearest(pa.brand, 0xFFFFFF);
            String code = white == null ? "universal:W-1" : white.brand + ":" + white.code;
            colors.add(code);
            palRgb = new int[][]{{255, 255, 255}};
        }

        // ---- 3. 像素落位（可抖动）----
        byte[] out = new byte[w * h];
        float[][] err = pa.dither ? new float[w + 2][3] : null;
        for (int y = 0, i = 0; y < h; y++) {
            float[][] next = pa.dither ? new float[w + 2][3] : null;
            for (int x = 0; x < w; x++, i++) {
                if (empty[i]) {
                    out[i] = (byte) 255;
                    continue;
                }
                int r = (px[i] >> 16) & 0xFF, g = (px[i] >> 8) & 0xFF, b = px[i] & 0xFF;
                if (pa.dither) {
                    // FS 误差是「原图值 − 落位色值」的累计；夹到合法区间防止溢出震荡
                    r = clamp255(Math.round(r + err[x + 1][0]));
                    g = clamp255(Math.round(g + err[x + 1][1]));
                    b = clamp255(Math.round(b + err[x + 1][2]));
                }
                int best = 0, bestD = Integer.MAX_VALUE;
                for (int k = 0; k < colors.size(); k++) {
                    int dr = r - palRgb[k][0], dg = g - palRgb[k][1], db = b - palRgb[k][2];
                    int d = dr * dr + dg * dg + db * db;
                    if (d < bestD) {
                        bestD = d;
                        best = k;
                    }
                }
                out[i] = (byte) best;
                if (pa.dither) {
                    int er = r - palRgb[best][0], eg = g - palRgb[best][1], eb = b - palRgb[best][2];
                    // FS 核：右 7/16、左下 3/16、下 5/16、右下 1/16。err[j][0] 是 x-1 列，
                    // err[j][1] 当前列、err[j][2] 下一列；next 行随后合并。
                    err[x + 2][0] += er * 7 / 16f;
                    err[x + 2][1] += eg * 7 / 16f;
                    err[x + 2][2] += eb * 7 / 16f;
                    next[x][0] += er * 3 / 16f;
                    next[x][1] += eg * 3 / 16f;
                    next[x][2] += eb * 3 / 16f;
                    next[x + 1][0] += er * 5 / 16f;
                    next[x + 1][1] += eg * 5 / 16f;
                    next[x + 1][2] += eb * 5 / 16f;
                    next[x + 2][0] += er / 16f;
                    next[x + 2][1] += eg / 16f;
                    next[x + 2][2] += eb / 16f;
                }
            }
            if (pa.dither) err = next;
        }

        Db.BeadPattern p = new Db.BeadPattern();
        p.id = Id.gen();
        p.title = title;
        p.w = w;
        p.h = h;
        p.pages = 1;
        p.pixels = out;
        p.colors = colors;
        p.album = "";
        p.fav = false;
        p.created = p.updated = System.currentTimeMillis();
        return p;
    }

    // ---------------- 中位数切分 ----------------

    /**
     * 中位数切分。每步把「像素最多且最宽」的盒子沿最宽通道按中位数劈开，
     * 直到凑够 want 个盒子或没有可劈的盒子。代表色取盒子平均。
     * 采样：像素超过 2 万时按步长抽样，量化质量肉眼无损，速度先保住。
     */
    private static int[][] medianCut(List<int[]> pixels, int want) {
        if (pixels.isEmpty()) return new int[0][];
        int stride = Math.max(1, pixels.size() / 20000);
        List<int[]> pts = new ArrayList<int[]>();
        for (int i = 0; i < pixels.size(); i += stride) pts.add(pixels.get(i));

        List<List<int[]>> boxes = new ArrayList<List<int[]>>();
        boxes.add(pts);
        while (boxes.size() < want) {
            int bi = -1;
            long bestScore = -1;
            int bestCh = 0;
            for (int i = 0; i < boxes.size(); i++) {
                List<int[]> box = boxes.get(i);
                if (box.size() < 2) continue;
                int[] min = {255, 255, 255}, max = {0, 0, 0};
                for (int[] p : box) {
                    for (int ch = 0; ch < 3; ch++) {
                        if (p[ch] < min[ch]) min[ch] = p[ch];
                        if (p[ch] > max[ch]) max[ch] = p[ch];
                    }
                }
                int wide = 0, wideCh = 0;
                for (int ch = 0; ch < 3; ch++) {
                    int span = max[ch] - min[ch];
                    if (span > wide) {
                        wide = span;
                        wideCh = ch;
                    }
                }
                long score = (long) box.size() * Math.max(1, wide);
                if (score > bestScore) {
                    bestScore = score;
                    bi = i;
                    bestCh = wideCh;
                }
            }
            if (bi < 0) break;      // 全是不可再分的单色盒
            List<int[]> box = boxes.get(bi);
            final int splitCh = bestCh;
            // Collections.sort 而不是 List.sort：后者是 Java 8 默认方法，
            // API 22 的运行时没有它，d8 对平台接口做不了脱糖。
            java.util.Collections.sort(box, new java.util.Comparator<int[]>() {
                @Override public int compare(int[] a, int[] b) {
                    return Integer.compare(a[splitCh], b[splitCh]);
                }
            });
            int mid = box.size() / 2;
            boxes.set(bi, new ArrayList<int[]>(box.subList(0, mid)));
            boxes.add(new ArrayList<int[]>(box.subList(mid, box.size())));
        }

        int[][] out = new int[boxes.size()][];
        for (int i = 0; i < boxes.size(); i++) {
            long r = 0, g = 0, b = 0;
            List<int[]> box = boxes.get(i);
            for (int[] p : box) {
                r += p[0];
                g += p[1];
                b += p[2];
            }
            out[i] = new int[]{(int) (r / box.size()), (int) (g / box.size()), (int) (b / box.size())};
        }
        return out;
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    private static int clampSize(int v) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, v));
    }

    /** 预览位图：目标尺寸的整图缩放（转换结果的「原图」参照）。 */
    public static Bitmap preview(Bitmap src, int w, int h) {
        return Bitmap.createScaledBitmap(src, Math.max(1, w), Math.max(1, h), true);
    }

    /** 给画布对比模式准备参考图：按图纸宽高比拉伸到位图（上限 512px，省内存）。 */
    public static Bitmap overlayBitmap(Bitmap src, int w, int h) {
        float scale = Math.min(512f / Math.max(1, w), 512f / Math.max(1, h));
        int tw = Math.max(1, Math.round(w * scale));
        int th = Math.max(1, Math.round(h * scale));
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint pa = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(src, null, new android.graphics.RectF(0, 0, tw, th), pa);
        return out;
    }
}
