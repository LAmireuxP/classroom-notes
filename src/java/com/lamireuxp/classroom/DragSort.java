package com.lamireuxp.classroom;

import android.app.Activity;
import android.content.ClipData;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.animation.LayoutTransition;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * 长按拖动排序 —— 零第三方依赖的实现。
 *
 * 为什么不用 ItemTouchHelper：它属于 androidx.recyclerview，而本项目是零依赖、直接对着
 * android.jar 编译的（构建脚本里没有 Gradle 拉依赖那一步）。所以用框架自带的
 * startDragAndDrop + OnDragListener。
 *
 * 第一版的做法是「被拖的行压暗待在原地、落点行描个高亮」——能用，但一点也不丝滑：
 * 手指带着的是一整行的系统影子，列表纹丝不动，松手还要整页重绘一遍，三件事叠起来
 * 就是「拖着一个色块，松手咣当一下」。
 * 这一版改成丝滑的三件事：
 *
 *  · 起拖瞬间把这一行**截成位图**交给拖动阴影跟着手指走，原行就地隐藏（GONE）——
 *    不会出现「影子 + 暗行」的重影。必须在隐藏**之前**截图：GONE 的视图 draw 不出东西。
 *  · 列表**实时让位**：手指扫过哪一行，隐藏的那一行就在容器里挪到那个位置，
 *    其余行靠 LayoutTransition 滑开收拢。这是「拖着看得到空档在动」的关键。
 *    LayoutTransition 只在拖动期间挂上——它会让容器里所有子 View 的增删都带动画，
 *    一直挂着的话正常重绘也会慢半拍。
 *  · 松手时列表的视觉顺序已经是最终顺序（隐藏的那行就是空档），直接按当前顺序落库，
 *    **不再重绘**——没有跳变。只有取消拖动（拖到容器外松手）才需要重绘复原。
 *
 * 其余约定：
 *  · **长按起拖**，不画拖动把手——笔记行左边有置顶图标/状态点、待办行左边是勾选框，
 *    再加把手会把行挤满。长按在 Android 上就是「拾起并移动」的通用手势。
 *  · **只在同组内可拖**：笔记的置顶/未置顶、待办的进行中/已完成各自成组。列表排序是
 *    「分组字段 DESC, sort ASC」，跨组改 sort 不会改变实际位置，用户只会看到那一行
 *    「弹回去」，比直接不许拖更让人困惑。
 *  · 拖到可视区上下边缘时自动滚动（框架拖动不带自动滚动），否则长列表里拖不到远处。
 */
public final class DragSort {

    /** 拖完回调：newOrder 是完整的新顺序（含全部行，不只是可见的）。 */
    public interface Callback {
        /** 有效放下时回调（列表的视觉顺序已是最终顺序）。 */
        void onDrop(List<String> newOrder);

        /** 拖动结束（无论是否有效放下）。dropped=false 表示被取消，调用方需要复原列表。 */
        void onDragEnded(boolean dropped);
    }

    private DragSort() {}

    /** 拖动中携带的状态。用 localState 传，省得去解析 ClipData 里的字符串。 */
    private static final class Dragged {
        final String id;
        final String section;
        Dragged(String id, String section) { this.id = id; this.section = section; }
    }

    /**
     * 让一组行可以被拖动排序。
     *
     * @param activity  取主题色用
     * @param container 行的父容器（拖动监听挂在它上面）；行必须是它的直接子 View，
     *                  落点判定用的是行相对容器的 getTop()/getBottom()
     * @param scroller  外层滚动容器，用于边缘自动滚动；不需要可传 null
     * @param rows      可拖动的行，顺序与 ids / sections 一一对应
     * @param ids       各行对应的数据 id
     * @param sections  各行所属分组（同组之间才允许放下）
     * @param callback  拖完的回调
     */
    public static void enable(final Activity activity, final ViewGroup container,
                              final ScrollView scroller, final List<View> rows,
                              final List<String> ids, final List<String> sections,
                              final Callback callback) {
        if (rows.size() < 2) return;      // 一行不值得拖

        for (int i = 0; i < rows.size(); i++) {
            final View row = rows.get(i);
            final String id = ids.get(i);
            final String section = sections.get(i);
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    // 先截图再隐藏：GONE 的视图 draw 出来是空白，顺序反了影子就是空的
                    final Bitmap snap = snapshot(v);
                    final int w = v.getWidth(), h = v.getHeight();
                    v.setVisibility(View.GONE);
                    v.startDragAndDrop(ClipData.newPlainText("id", id),
                            new View.DragShadowBuilder() {
                                @Override public void onDrawShadow(Canvas canvas) {
                                    // 给影子垫一层卡片底，不然透明背景的行拖起来只剩文字悬在半空
                                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                                    p.setColor(Ui.surfaceHigh(activity));
                                    p.setShadowLayer(Ui.dp(activity, 6), 0, Ui.dp(activity, 2),
                                            0x33000000);
                                    float r = Ui.dp(activity, Ui.R_M);
                                    canvas.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
                                    canvas.drawBitmap(snap, 0, 0, null);
                                }
                            }, new Dragged(id, section), 0);
                    return true;
                }
            });
        }

        container.setOnDragListener(new View.OnDragListener() {
            /** 被拖的那一行（隐藏中）。它的位置就是当前空档。 */
            private View gapView;
            private int gapIndex;
            private boolean dropped;

            /** 拖动期间才挂 LayoutTransition，拖完摘掉——见类注释里「为什么只在拖动期间」。 */
            private final LayoutTransition transition = new LayoutTransition();

            private final Handler handler = new Handler(Looper.getMainLooper());
            private int scrollStep;
            private final Runnable tick = new Runnable() {
                @Override public void run() {
                    if (scrollStep == 0 || scroller == null) return;
                    scroller.scrollBy(0, scrollStep);
                    handler.postDelayed(this, 16);
                }
            };

            @Override public boolean onDrag(View v, DragEvent e) {
                switch (e.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED: {
                        transition.setDuration(160);
                        transition.enableTransitionType(LayoutTransition.CHANGING);
                        container.setLayoutTransition(transition);
                        return true;
                    }

                    case DragEvent.ACTION_DRAG_LOCATION: {
                        Dragged d = (Dragged) e.getLocalState();
                        int idx = rowIndexOf(e.getY());
                        boolean sameGroup = idx >= 0 && d != null
                                && sections.get(idx).equals(d.section);
                        if (sameGroup && idx != gapIndex) {
                            // 隐藏的那一行挪到新位置：其余行靠 LayoutTransition 滑开
                            ids.remove(d.id);
                            ids.add(idx, d.id);
                            container.removeView(gapView);
                            container.addView(gapView, idx);
                            gapIndex = idx;
                        }
                        autoScroll(e.getY(), v);
                        return true;
                    }

                    case DragEvent.ACTION_DROP: {
                        stopAutoScroll();
                        dropped = true;
                        finishDrag(activity, container);
                        callback.onDrop(new ArrayList<String>(ids));
                        return true;
                    }

                    case DragEvent.ACTION_DRAG_ENDED: {
                        stopAutoScroll();
                        container.setLayoutTransition(null);
                        boolean wasDropped = dropped;
                        dropped = false;
                        if (!wasDropped) {
                            // 拖到容器外松手：数据没动，但视图顺序可能已经被挪过 → 复原
                            callback.onDragEnded(false);
                        }
                        return true;
                    }
                }
                return false;
            }

            /**
             * 收尾：把隐藏的行恢复可见、摘掉 LayoutTransition。
             * visibleIndex 指定恢复到容器里的第几位（拖动中它被挪过）。
             */
            private void finishDrag(Activity act, ViewGroup cont) {
                if (gapView != null) {
                    int at = Math.max(0, Math.min(gapIndex, cont.getChildCount()));
                    if (gapView.getParent() == cont) cont.removeView(gapView);
                    gapView.setVisibility(View.VISIBLE);
                    cont.addView(gapView, at);
                    gapView = null;
                }
                cont.setLayoutTransition(null);
            }

            /** 落点 y（容器坐标系）对应的行下标；跳过隐藏中的那行与非行元素。 */
            private int rowIndexOf(float y) {
                for (int i = 0; i < container.getChildCount(); i++) {
                    View c = container.getChildAt(i);
                    if (c.getVisibility() != View.VISIBLE) continue;
                    if (rows.contains(c) && y >= c.getTop() && y < c.getBottom()) return i;
                }
                return -1;
            }

            /** 拖到可视区上下边缘时自动滚动（阈值 56dp）。 */
            private void autoScroll(float containerY, View container) {
                if (scroller == null) { stopAutoScroll(); return; }
                int[] cl = new int[2];
                container.getLocationOnScreen(cl);
                int screenY = cl[1] + (int) containerY;
                int[] sl = new int[2];
                scroller.getLocationOnScreen(sl);
                int edge = Ui.dp(activity, 56);
                int top = sl[1] + edge, bottom = sl[1] + scroller.getHeight() - edge;
                int step = screenY < top ? -Ui.dp(activity, 8)
                        : (screenY > bottom ? Ui.dp(activity, 8) : 0);
                if (step == scrollStep) return;
                stopAutoScroll();
                scrollStep = step;
                if (step != 0) handler.post(tick);
            }

            private void stopAutoScroll() {
                scrollStep = 0;
                handler.removeCallbacks(tick);
            }
        });
    }

    /** 把一个 View 画成位图（拖动影子用）。 */
    private static Bitmap snapshot(View v) {
        int w = Math.max(1, v.getWidth()), h = Math.max(1, v.getHeight());
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        return b;
    }
}