package com.lamireuxp.classroom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 拼豆画布 —— 像素网格的缩放 / 平移 / 逐格编辑。
 *
 * 手势约定（与主流像素编辑器一致）：
 *   · 单指 = 当前工具（画笔 / 橡皮 / 吸色 / 填充）；平移工具下单指拖 = 移动画布；
 *   · 双指 = 缩放（绕双指中点）+ 平移（中点位移）——画到一半想挪画布，不用切工具；
 *   · 缩放范围 4 ~ 120px/格：下限保证 256 格宽也能一眼看全，上限保证单格够点。
 *
 * 渲染：只画可见行列（屏幕外的格子不算），256×256 下滚动全程满帧。
 * 格内色号文字在单格 ≥ 30px 时出现——这个阈值以下文字会小到没法读，白耗绘制。
 */
public final class BeadCanvasView extends View {

    /** 工具编号，编辑器底栏与这里的语义一一对应。 */
    public static final int TOOL_BRUSH = 0, TOOL_ERASER = 1, TOOL_PICK = 2,
            TOOL_FILL = 3, TOOL_HAND = 4;

    /** 空格的像素值：byte 存不下 255 的正数，读回来要 & 0xFF。 */
    public static final int EMPTY = 255;

    public interface Listener {
        /** 一笔（或一次批量操作）开始前回调：编辑器在这里压撤销快照。 */
        void onStrokeBegin();
        /** 画布内容变化（每像素都会回调，编辑器里只置脏标记，别做重活）。 */
        void onPatternChanged();
        /** 吸色结果：colorIndex 为 -1 表示吸到了空格。 */
        void onPick(int colorIndex);
    }

    private Db.BeadPattern pat;
    private int page;
    private final Listener listener;

    private int tool = TOOL_BRUSH;
    /** 当前色 = 配色表下标；编辑器负责把 brand:code 落进配色表后传下标进来。 */
    private int colorIndex = 0;
    /** 配色表的实际 RGB（编辑器同步进来，避免每帧查表）。 */
    private int[] colorRgbs = new int[0];
    /** 色号短文本（画布内标注用，如 "A1"）。 */
    private String[] colorCodes = new String[0];

    // 视口：cell = 每格像素数；ox/oy = 网格左上角在视图里的位置
    private float cell = 12f, ox = 0, oy = 0;
    private boolean laidOut;

    // 参考图叠加（对比模式）
    private android.graphics.Bitmap overlay;
    private float overlayAlpha = 0f;

    private final Paint pFill = new Paint();
    private final Paint pLine = new Paint();
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pOverlay = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    private final ScaleGestureDetector scaleDet;
    private final GestureDetector tapDet;

    // 多指状态
    private float lastX, lastY;
    private int lastPointerCount;
    private boolean stroking;
    private int lastCellX = -1, lastCellY = -1;

    public BeadCanvasView(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        pLine.setStyle(Paint.Style.STROKE);
        pText.setTextAlign(Paint.Align.CENTER);
        scaleDet = new ScaleGestureDetector(c, new ScaleListener());
        tapDet = new GestureDetector(c, new TapListener());
        setFocusable(true);
        setClickable(true);
    }

    public void setPattern(Db.BeadPattern p, int page) {
        this.pat = p;
        this.page = page;
        if (!laidOut) return;
        fit();
        invalidate();
    }

    public void setTool(int t) { tool = t; }

    public void setColor(int idx, int[] rgbs, String[] codes) {
        colorIndex = idx;
        colorRgbs = rgbs;
        colorCodes = codes;
        invalidate();
    }

    public void setOverlay(android.graphics.Bitmap b, float alpha) {
        overlay = b;
        overlayAlpha = alpha;
        invalidate();
    }

    public float getCell() { return cell; }

    /** 视图布局完成后把画布居中铺满（只做一次）。 */
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (!laidOut && pat != null && r > l) {
            laidOut = true;
            fit();
        }
    }

    /** 缩放到恰好铺满视图并居中。 */
    public void fit() {
        if (pat == null || getWidth() == 0) return;
        float s = Math.min(getWidth() * 0.92f / pat.w, getHeight() * 0.92f / pat.h);
        cell = Math.max(4f, Math.min(120f, s));
        ox = (getWidth() - pat.w * cell) / 2f;
        oy = (getHeight() - pat.h * cell) / 2f;
        invalidate();
    }

    // ---------------- 手势 ----------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDet.onTouchEvent(e);
        tapDet.onTouchEvent(e);
        int n = e.getPointerCount();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                lastPointerCount = 1;
                // 吸色是「看一眼」语义，不落笔；平移工具同理
                if (tool != TOOL_HAND && tool != TOOL_PICK && pat != null) {
                    // 落笔前压快照；一笔内连续拖动不再重复压
                    if (listener != null) listener.onStrokeBegin();
                    stroking = true;
                    applyAt(e.getX(), e.getY(), true);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二根手指落下 → 本笔结束，转入视口手势（拖到画布外不误画）
                stroking = false;
                lastCellX = lastCellY = -1;
                lastX = (e.getX(0) + e.getX(1)) / 2f;
                lastY = (e.getY(0) + e.getY(1)) / 2f;
                lastPointerCount = n;
                break;
            case MotionEvent.ACTION_MOVE:
                if (n >= 2) {
                    float mx = (e.getX(0) + e.getX(1)) / 2f;
                    float my = (e.getY(0) + e.getY(1)) / 2f;
                    ox += mx - lastX;
                    oy += my - lastY;
                    lastX = mx;
                    lastY = my;
                    clampOrigin();
                    invalidate();
                } else if (stroking && tool != TOOL_HAND) {
                    applyAt(e.getX(), e.getY(), false);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stroking = false;
                lastCellX = lastCellY = -1;
                lastPointerCount = 1;
                break;
        }
        return true;
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScale(ScaleGestureDetector d) {
            float ns = cell * d.getScaleFactor();
            ns = Math.max(4f, Math.min(120f, ns));
            // 绕双指焦点缩放：焦点在网格上的位置缩放前后不动
            float fx = d.getFocusX(), fy = d.getFocusY();
            ox = fx - (fx - ox) * (ns / cell);
            oy = fy - (fy - oy) * (ns / cell);
            cell = ns;
            clampOrigin();
            invalidate();
            return true;
        }
    }

    private final class TapListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
            // 平移工具/双指状态下不落笔；单指点按已在 ACTION_DOWN 画过，
            // 这里只处理吸色（吸色是「看一眼」语义，不该在拖动中触发）
            if (tool == TOOL_PICK && pat != null && listener != null) {
                int[] ij = cellAt(e.getX(), e.getY());
                if (ij == null) return true;
                byte v = pat.pixels[page * pat.w * pat.h + ij[0] + ij[1] * pat.w];
                listener.onPick(v == EMPTY ? -1 : (v & 0xFF));
            }
            return true;
        }
    }

    /** 落笔/拖动画点。drag 时做线性插值补格，快滑不断线。 */
    private void applyAt(float x, float y, boolean down) {
        if (pat == null) return;
        int[] ij = cellAt(x, y);
        int cx = ij == null ? -1 : ij[0], cy = ij == null ? -1 : ij[1];
        if (down) {
            if (ij != null) paintCell(cx, cy);
            lastCellX = cx;
            lastCellY = cy;
            return;
        }
        if (lastCellX < 0) {            // 拖出画布后回来：从当前点重新起线
            if (ij != null) paintCell(cx, cy);
            lastCellX = cx;
            lastCellY = cy;
            return;
        }
        if (cx < 0) return;             // 拖出画布：只更新不画，回来时重新起线
        // Bresenham 连线：快速拖动时事件间隔跨好几格，直接只画当前格会断
        int dx = Math.abs(cx - lastCellX), dy = Math.abs(cy - lastCellY);
        int sx = cx >= lastCellX ? 1 : -1, sy = cy >= lastCellY ? 1 : -1;
        int err = dx - dy, ex = lastCellX, ey = lastCellY;
        while (true) {
            paintCell(ex, ey);
            if (ex == cx && ey == cy) break;
            int e2 = err * 2;
            if (e2 > -dy) { err -= dy; ex += sx; }
            if (e2 < dx) { err += dx; ey += sy; }
        }
        lastCellX = cx;
        lastCellY = cy;
    }

    private void paintCell(int x, int y) {
        int w = pat.w, h = pat.h;
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int off = page * w * h + y * w + x;
        byte nv = tool == TOOL_ERASER ? (byte) EMPTY : (byte) colorIndex;
        if (tool == TOOL_FILL) {
            floodFill(x, y);
            stroking = false;           // 填充是一锤子买卖，拖着走不再连续填
        } else if (pat.pixels[off] != nv) {
            pat.pixels[off] = nv;
        } else {
            return;
        }
        if (listener != null) listener.onPatternChanged();
        invalidate();
    }

    /** 油漆桶：四邻域 BFS，只替换与起点同值的连续区域。 */
    private void floodFill(int x, int y) {
        int w = pat.w, h = pat.h, base = page * w * h;
        byte from = pat.pixels[base + y * w + x];
        byte to = tool == TOOL_ERASER ? (byte) EMPTY : (byte) colorIndex;
        if (from == to) return;
        int[] stack = new int[w * h];
        int sp = 0;
        stack[sp++] = y * w + x;
        pat.pixels[base + y * w + x] = to;
        while (sp > 0) {
            int cur = stack[--sp];
            int cy = cur / w, cxx = cur % w;
            if (cxx > 0 && pat.pixels[base + cur - 1] == from) {
                pat.pixels[base + cur - 1] = to;
                stack[sp++] = cur - 1;
            }
            if (cxx < w - 1 && pat.pixels[base + cur + 1] == from) {
                pat.pixels[base + cur + 1] = to;
                stack[sp++] = cur + 1;
            }
            if (cy > 0 && pat.pixels[base + cur - w] == from) {
                pat.pixels[base + cur - w] = to;
                stack[sp++] = cur - w;
            }
            if (cy < h - 1 && pat.pixels[base + cur + w] == from) {
                pat.pixels[base + cur + w] = to;
                stack[sp++] = cur + w;
            }
        }
    }

    private int[] cellAt(float x, float y) {
        if (pat == null) return null;
        int cx = (int) Math.floor((x - ox) / cell);
        int cy = (int) Math.floor((y - oy) / cell);
        if (cx < 0 || cy < 0 || cx >= pat.w || cy >= pat.h) return null;
        return new int[]{cx, cy};
    }

    /** 画布不许整体拖丢：至少留一格在屏内。 */
    private void clampOrigin() {
        if (pat == null) return;
        float gw = pat.w * cell, gh = pat.h * cell;
        ox = Math.min(Math.max(ox, cell - gw), getWidth() - cell);
        oy = Math.min(Math.max(oy, cell - gh), getHeight() - cell);
    }

    // ---------------- 绘制 ----------------

    @Override protected void onDraw(Canvas cv) {
        cv.drawColor(Ui.surfaceLowest(getContext()));
        if (pat == null) return;

        float gw = pat.w * cell, gh = pat.h * cell;
        // 网格底色：比页面底暗一档，空格与页面区分开
        pFill.setColor(Ui.surfaceContainer(getContext()));
        cv.drawRect(ox, oy, ox + gw, oy + gh, pFill);

        // 可见行列范围（裁剪到网格内）
        int i0 = Math.max(0, (int) Math.floor(-ox / cell));
        int j0 = Math.max(0, (int) Math.floor(-oy / cell));
        int i1 = Math.min(pat.w, (int) Math.ceil((getWidth() - ox) / cell));
        int j1 = Math.min(pat.h, (int) Math.ceil((getHeight() - oy) / cell));
        int base = page * pat.w * pat.h;

        boolean showText = cell >= 30f && colorCodes.length > 0;
        if (showText) {
            pText.setTextSize(Math.max(9f, Math.min(18f, cell * 0.3f)));
        }

        for (int j = j0; j < j1; j++) {
            float top = oy + j * cell;
            for (int i = i0; i < i1; i++) {
                byte v = pat.pixels[base + j * pat.w + i];
                if (v == EMPTY) continue;
                int idx = v & 0xFF;
                if (idx >= colorRgbs.length) continue;
                pFill.setColor(colorRgbs[idx]);
                float left = ox + i * cell;
                cv.drawRect(left, top, left + cell, top + cell, pFill);
                if (showText) {
                    String code = colorCodes[Math.min(idx, colorCodes.length - 1)];
                    if (code != null && code.length() > 0) {
                        pText.setColor(luma(colorRgbs[idx]) > 140 ? 0xFF000000 : 0xFFFFFFFF);
                        // drawText 的 y 是基线：基线 ≈ 中心 + 字高的 0.35，视觉上才算居中
                        cv.drawText(code, left + cell / 2f,
                                top + cell / 2f + pText.getTextSize() * 0.35f, pText);
                    }
                }
            }
        }

        // 参考图叠加（对比模式）：画在格子上面，透明度由滑条控制
        if (overlay != null && overlayAlpha > 0f) {
            pOverlay.setAlpha((int) (overlayAlpha * 255));
            cv.drawBitmap(overlay, null, new RectF(ox, oy, ox + gw, oy + gh), pOverlay);
        }

        // 网格线：小格不画（糊成一片），>=7px 起画
        if (cell >= 7f) {
            pLine.setColor(Ui.outlineVariant(getContext()));
            pLine.setStrokeWidth(1f);
            pLine.setAlpha(140);
            for (int i = i0; i <= i1; i++) {
                float x = ox + i * cell;
                cv.drawLine(x, Math.max(oy, oy + j0 * cell), x,
                        Math.min(oy + gh, oy + j1 * cell), pLine);
            }
            for (int j = j0; j <= j1; j++) {
                float y = oy + j * cell;
                cv.drawLine(Math.max(ox, ox + i0 * cell), y,
                        Math.min(ox + gw, ox + i1 * cell), y, pLine);
            }
            pLine.setAlpha(255);
        }

        // 外框
        pLine.setColor(Ui.outlineVariant(getContext()));
        pLine.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 1)));
        pLine.setAlpha(255);
        cv.drawRect(ox, oy, ox + gw, oy + gh, pLine);
    }

    private static int luma(int rgb) {
        return (int) (0.299 * ((rgb >> 16) & 0xFF)
                + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
    }

    /** 供编辑器把某个格子的值直接写回（批量操作复用画布外逻辑时不需要它，留作调试钩子）。 */
    public Rect gridRect() {
        return pat == null ? new Rect() : new Rect((int) ox, (int) oy,
                (int) (ox + pat.w * cell), (int) (oy + pat.h * cell));
    }
}
