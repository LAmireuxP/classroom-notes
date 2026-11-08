package com.lamireuxp.classroom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 图纸导出：缩略图、PNG 图纸表、PDF（图纸 + 色号用量清单）。
 *
 * 渲染统一走「每格一个矩形 + 色号文字」的位图管线，不经过 View 绘制——
 * 导出要在后台线程跑（256×256 的表有 6.5 万个格子），View 只能在主线程画。
 * 空格画浅灰底：和编辑器画布同一个语义，打印出来也能看出「这里没有豆」。
 */
public final class BeadExport {

    private BeadExport() {}

    /** 一格的像素/磅数下限：低于它就不画色号文字（小到没法读）。 */
    private static final float CODE_MIN_CELL = 16f;

    // ================= 用量统计 =================

    /**
     * 一页（或全页）的各色用量。返回数组下标 = 配色表下标，值 = 该色格子数。
     * 一遍扫像素，O(w*h*pages)，256×256×8 页也就 50 万次读，后台线程无感。
     */
    public static int[] countUsage(Db.BeadPattern p, int page, int colorCount) {
        int[] out = new int[Math.max(1, colorCount)];
        int pw = p.w * p.h;
        int from = page < 0 ? 0 : page * pw;
        int to = page < 0 ? p.pixels.length : Math.min(p.pixels.length, from + pw);
        for (int i = from; i < to; i++) {
            int v = p.pixels[i] & 0xFF;
            if (v != BeadCanvasView.EMPTY && v < out.length) out[v]++;
        }
        return out;
    }

    /** 用色总数（所有页、去空格）：统计抽屉的标题行用。 */
    public static int totalBeads(Db.BeadPattern p) {
        int n = 0;
        for (byte b : p.pixels) if ((b & 0xFF) != BeadCanvasView.EMPTY) n++;
        return n;
    }

    // ================= 位图渲染 =================

    /**
     * 图纸库缩略图：单页按比例缩到 maxPx 内。整张位图一次画完（最多 6.5 万格），
     * 列表滚动时配合内存缓存使用，见 BeadHomeActivity。
     */
    public static Bitmap renderThumb(Db.BeadPattern p, int maxPx) {
        float cell = Math.max(1f, maxPx / (float) Math.max(p.w, p.h));
        int w = Math.max(1, Math.round(p.w * cell));
        int h = Math.max(1, Math.round(p.h * cell));
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint pa = new Paint();
        pa.setColor(0xFFE8E5E0);            // 空格底：固定浅灰，不随主题（缩略图可能被深色列表包着）
        cv.drawRect(0, 0, w, h, pa);
        int base = 0;                        // 缩略图永远画第 1 页：库列表的视觉锚点
        for (int y = 0; y < p.h; y++) {
            for (int x = 0; x < p.w; x++) {
                int v = p.pixels[base + y * p.w + x] & 0xFF;
                if (v == BeadCanvasView.EMPTY || v >= p.colors.size()) continue;
                pa.setColor(BeadPalettes.rgbOf(p.colors.get(v)));
                cv.drawRect(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, pa);
            }
        }
        return bmp;
    }

    /**
     * 完整图纸表（单页）：每格填色 + 色号标注 + 网格线 + 外框。
     * 边长上限约 4096px：256 宽的图纸 cell 还有 16px，文字勉强可读；
     * 更大的表再放大只会白吃内存，打印细节交给 PDF 通道（矢量页码 + 清单）。
     */
    public static Bitmap renderSheet(Db.BeadPattern p, int page) {
        float cell = Math.max(6f, 4096f / Math.max(p.w, p.h));
        int w = Math.round(p.w * cell), h = Math.round(p.h * cell);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        drawGrid(cv, p, page, cell, 0, 0, cell >= CODE_MIN_CELL);
        return bmp;
    }

    /** 网格本体渲染。x0/y0 = 网格左上角在画布上的位置（PDF 里用在页内排版）。 */
    private static void drawGrid(Canvas cv, Db.BeadPattern p, int page, float cell,
                                 float x0, float y0, boolean withCodes) {
        Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint pl = new Paint();
        pl.setStyle(Paint.Style.STROKE);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setTextAlign(Paint.Align.CENTER);
        if (withCodes) pt.setTextSize(cell * 0.34f);

        int w = p.w, h = p.h;
        int base = page * w * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float l = x0 + x * cell, t = y0 + y * cell;
                int v = p.pixels[base + y * w + x] & 0xFF;
                if (v == BeadCanvasView.EMPTY || v >= p.colors.size()) {
                    pa.setColor(0xFFEDEBE7);
                } else {
                    pa.setColor(BeadPalettes.rgbOf(p.colors.get(v)));
                }
                cv.drawRect(l, t, l + cell, t + cell, pa);
                if (withCodes && v != BeadCanvasView.EMPTY && v < p.colors.size()) {
                    String code = codeOf(p.colors.get(v));
                    int rgb = pa.getColor();
                    int luma = (int) (0.299 * ((rgb >> 16) & 0xFF)
                            + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    pt.setColor(luma > 140 ? 0xFF1A1A1A : 0xFFFFFFFF);
                    // y 是基线：中心 + 0.35 字高才是视觉居中（与 BeadCanvasView 同一套）
                    cv.drawText(code, l + cell / 2f, t + cell / 2f + pt.getTextSize() * 0.35f, pt);
                }
            }
        }
        // 网格线：细线压在色块上，扫描/打印时格子边界不糊
        pl.setColor(0x44000000);
        pl.setStrokeWidth(Math.max(0.5f, cell / 40f));
        for (int i = 0; i <= w; i++) {
            cv.drawLine(x0 + i * cell, y0, x0 + i * cell, y0 + h * cell, pl);
        }
        for (int j = 0; j <= h; j++) {
            cv.drawLine(x0, y0 + j * cell, x0 + w * cell, y0 + j * cell, pl);
        }
        // 外框加重：打印后裁剪时以它为准
        pl.setColor(0xFF1A1A1A);
        pl.setStrokeWidth(Math.max(1f, cell / 20f));
        cv.drawRect(x0, y0, x0 + w * cell, y0 + h * cell, pl);
    }

    /** 色号短文本（画在格子里 / 图例）：只取 code 部分，不带品牌与中文名。 */
    private static String codeOf(String brandCode) {
        int i = brandCode.indexOf(':');
        return i >= 0 ? brandCode.substring(i + 1) : brandCode;
    }

    // ================= 文件写出 =================

    /** PNG 落地到 SAF 选定的位置。调用方（编辑器菜单）已经在后台线程。 */
    public static void writePng(Context c, Uri uri, Bitmap bmp) throws Exception {
        OutputStream os = c.getContentResolver().openOutputStream(uri, "wt");
        if (os == null) throw new Exception("无法写入该位置");
        try {
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                throw new Exception("PNG 编码失败");
            }
            os.flush();
        } finally {
            os.close();
        }
    }

    /**
     * PDF 落地：A4 纵向，每张图纸页一页 PDF。页面上是标题行 + 图纸网格 +
     * 色号用量清单（两列）。清单放不下时续页——这是 PdfDocument.PageInfo
     * 的事，与图纸页数无关。
     */
    public static void writePdf(Context c, Uri uri, Db.BeadPattern p) throws Exception {
        // A4 @72dpi：PdfDocument 的 Point 单位是 pt（1/72 英寸）
        final int PW = 595, PH = 842;
        final float M = 36f;                 // 页边距 0.5 英寸
        PdfDocument doc = new PdfDocument();
        try {
            for (int pg = 0; pg < p.pages; pg++) {
                PdfDocument.Page page = doc.startPage(
                        new PdfDocument.PageInfo.Builder(PW, PH, pg + 1).create());
                Canvas cv = page.getCanvas();
                Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
                txt.setColor(0xFF1A1A1A);

                // 标题行：图纸名 + 尺寸 + 页码
                txt.setTextSize(14f);
                txt.setFakeBoldText(true);
                String title = nz(p.title);
                if (title.length() == 0) title = "拼豆图纸";
                String head = title + "  ·  " + p.w + "×" + p.h
                        + (p.pages > 1 ? "  ·  第 " + (pg + 1) + "/" + p.pages + " 层" : "");
                cv.drawText(head, M, M + 10f, txt);
                txt.setFakeBoldText(false);

                // 网格：宽铺满（留边距），高度不够就缩；cell 太小就不标色号
                float availW = PW - M * 2;
                float availH = PH - M * 2 - 24f;
                float cell = Math.min(availW / p.w, availH / p.h);
                float gx = M + (availW - cell * p.w) / 2f;
                float gy = M + 20f;
                drawGrid(cv, p, pg, cell, gx, gy, cell >= CODE_MIN_CELL);

                // 用量清单：只列本页用到的色（0 用量的色不上纸）
                drawLegend(cv, p, pg, M, gy + cell * p.h + 18f, PW - M * 2, PH - M, txt);
                doc.finishPage(page);
            }
            OutputStream os = c.getContentResolver().openOutputStream(uri, "wt");
            if (os == null) throw new Exception("无法写入该位置");
            try {
                doc.writeTo(os);
                os.flush();
            } finally {
                os.close();
            }
        } finally {
            doc.close();
        }
    }

    /**
     * 色号用量清单（两列，swatch + 色号 + 数量）。超出 maxY 的行续到下一页 PDF——
     * 返回 null 表示全画完了，否则返回「剩余待画」的起始行号，由调用方开新页再调。
     */
    private static void drawLegend(Canvas cv, Db.BeadPattern p, int page,
                                   float x, float y, float width, float maxY, Paint txt) {
        int[] use = countUsage(p, page, p.colors.size());
        List<Integer> rows = new ArrayList<Integer>();
        for (int i = 0; i < use.length; i++) if (use[i] > 0) rows.add(i);

        float lh = 16f;
        float colW = width / 2f;
        float cy = y;
        txt.setTextSize(11f);
        Paint sw = new Paint();
        for (int r = 0; r < rows.size() && cy + lh <= maxY; r++) {
            int idx = rows.get(r);
            int col = r % 2;                 // 0 = 左列，1 = 右列
            float cx = x + col * colW;
            int rgb = BeadPalettes.rgbOf(p.colors.get(idx));
            sw.setColor(rgb);
            cv.drawRect(cx, cy, cx + 12f, cy + 12f, sw);
            sw.setColor(0x44000000);
            sw.setStyle(Paint.Style.STROKE);
            cv.drawRect(cx, cy, cx + 12f, cy + 12f, sw);
            sw.setStyle(Paint.Style.FILL);
            txt.setColor(0xFF1A1A1A);
            cv.drawText(BeadPalettes.labelOf(p.colors.get(idx)) + " ×" + use[idx],
                    cx + 18f, cy + 10.5f, txt);
            if (col == 1) cy += lh;          // 两列都画过才换行
        }
        // 剩余行不续页：清单尾部被截断时 PDF 里补一行说明，引导去 App 里看完整统计。
        // 续页排版会让 PdfDocument 的页序和图纸页序交叉，调试成本大于收益。
        int shown = Math.min(rows.size(), (int) ((maxY - y) / lh) * 2);
        if (shown < rows.size()) {
            txt.setTextSize(10f);
            txt.setColor(0xFF888888);
            cv.drawText("（其余 " + (rows.size() - shown) + " 种颜色见 App 内统计）",
                    x, Math.min(cy + lh, maxY - 4f), txt);
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
