package com.lamireuxp.classroom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.animation.LayoutTransition;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * 长按拖动排序 —— 触摸流自拦截的实现，零第三方依赖。
 *
 * 为什么不用 androidx 的 ItemTouchHelper：它属于 recyclerview，而本项目零依赖、
 * 直接对着 android.jar 编译。也不用框架的 startDragAndDrop + OnDragListener
 * （上一版的路线，三个坑都是真机踩出来的）：
 *  1. 拖动阴影的尺寸必须显式提供，默认 DragShadowBuilder 给不出尺寸，
 *     startDragAndDrop 直接抛「Drag shadow dimensions must be positive」，起拖即崩；
 *  2. 拖动事件按「指针落在哪个视图上」派发：原行隐藏后列表会缩短，把行拖到列表
 *     末尾再松手，指针已在容器之外，ACTION_DROP 派发不到，走的是「取消复原」——
 *     「拖到底再松手」这个最自然的动作反而永远落不了位；
 *  3. 影子画在系统层、事件派发与取消语义都不受控，出问题只能在外围打补丁。
 *
 * 这版让容器自己接管触摸流（onInterceptTouchEvent），全程可控：
 *
 *  · **长按起拖**：按下后起一个系统长按时限（约 500ms）的定时器，期间手指滑出
 *    触摸回弹阈值就当作滚动、撤掉定时器——行上的点击 / 涟漪 / 按压缩放照常工作，
 *    长按住的一瞬给一次触感反馈。起拖后后续事件由容器 onTouchEvent 接管，
 *    手指移到哪儿都算数——触摸流被握着，**没有「拖出容器」这回事**。
 *  · **浮动卡片**：起拖瞬间把行截成位图，挂到窗口层（android.R.id.content），
 *    垫卡片底、加海拔阴影，跟着手指走（渲染期变换，不触发布局）。
 *  · **占位空档**：原行就地转 INVISIBLE——**不是 GONE**。INVISIBLE 保留原尺寸，
 *    列表总高不变，「这一行会落到哪」始终是一个看得见的空档；空档挪到哪，
 *    其余行就靠 LayoutTransition 滑开让位（160ms）。列表不缩短，
 *    上一版「拖到底松手落不了位」的坑由此根除。
 *  · **槽位判定**：指尖压在哪 个槽位上，空档就挪到哪。槽位按行序编号（空档也算
 *    一行），压着空档自己 = 原地不动；压着同组的行 = 挪过去；指尖悬在同组范围
 *    之外时吸附到组的首/尾两槽——「拖到最顶/最底松手」因此可达。压着别的组的
 *    行不算数：同组之外不换位，空档钉在组边界上。
 *  · **单一真相**：落库顺序不在拖动中记账，而是**松手那一刻从容器子 View 序列
 *    现读**——ids 与视觉从构造上不可能脱节。
 *  · **边缘自动滚动**：拖到可视区上下边缘 56dp 内按帧步进滚动；滚动本身不产生
 *    MOVE 事件，滚动循环里要拿最后的指尖位置重判落点，否则长列表拖不到远处。
 *  · **松手落位**：浮动卡片吸附回空档（150ms），随后摘影子、原行就地恢复可见、
 *    落库——**不重绘**，没有跳变。拖动没有「取消」路径：在哪儿松手都按落位处理，
 *    系统回收触摸流（ACTION_CANCEL）也一样。
 */
public final class DragSort {

    /**
     * 松手落位（或系统回收触摸流）时回调：newOrder 是完整的新顺序。
     * 列表的视觉顺序此时已是最终顺序，调用方落库即可，**不需要**重绘。
     */
    public interface Callback {
        void onDrop(List<String> newOrder);
    }

    private DragSort() {}

    /**
     * 让一组行可以被拖动排序。
     *
     * @param container 行的父容器（必须是 DragSort.Layout，靠它拦截触摸流）；
     *                  容器里可以有别的子 View（标题、计数等），落点判定会跳过它们
     * @param scroller  外层滚动容器，用于边缘自动滚动；不需要可传 null
     * @param rows      可拖动的行，顺序与 ids / sections 一一对应
     * @param ids       各行对应的数据 id（行 → id 的固定映射，与顺序无关——
     *                  拖动中的顺序以容器子 View 的实际排列为准）
     * @param sections  各行所属分组（同组之间才允许换位）
     * @param callback  落位回调
     */
    public static void enable(Layout container, ScrollView scroller, List<View> rows,
                              List<String> ids, List<String> sections, Callback callback) {
        if (rows.size() < 2) return;      // 一行不值得拖
        container.configure(scroller, rows, ids, sections, callback);
    }

    /**
     * 可拖动排序的列表容器。
     * 就是一个纵向 LinearLayout，多了「长按子行 → 接管触摸流 → 拖动排序」的能力；
     * 没有调 DragSort.enable()（或行数不足）时就是个普通容器，触摸全部照旧。
     */
    public static final class Layout extends LinearLayout {

        private static final long SLIDE_MS = 160;   // 其余行让位的过渡
        private static final long SNAP_MS = 150;    // 浮动卡片吸附回空档
        private static final int SCROLL_EDGE_DP = 56;
        private static final int SCROLL_STEP_DP = 8;

        private ScrollView scroller;
        private Callback callback;
        /**
         * 可拖行的集合，只用于成员判断。落点判定/空档挪位每次都要问
         * 「这个子 View 是不是可拖行」，容器子 View 一多，List.contains 的
         * 线性扫就是 O(n²)；Set 把它降到 O(n)。行→id/分组的映射在 configure
         * 时一次性建好，拖动中不变。
         */
        private final HashSet<View> rowSet = new HashSet<View>();
        private final HashMap<View, String> idOfRow = new HashMap<View, String>();
        private final HashMap<View, String> sectionOfRow = new HashMap<View, String>();

        // —— 一次拖动里的状态 ——
        private boolean dragging;
        private View gapRow;         // 空档（原行，INVISIBLE 占位）
        private String gapSection;
        private List<String> initialOrder;
        private ImageView ghost;     // 浮动卡片
        private Bitmap snap;
        private float grabOffsetY;   // 指尖到行顶的偏移（屏幕坐标）
        private float lastRawY;      // 最后一次指尖的屏幕 Y（自动滚动时重判落点用）
        private int touchPointer;
        private float downX, downY;
        private View touchChild;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private int scrollStep;
        private final int touchSlop;
        private final LayoutTransition transition = new LayoutTransition();

        /** 长按定时器：到点还没滑走，就把按着的那一行拿起来。 */
        private final Runnable pendingLongPress = new Runnable() {
            @Override public void run() {
                if (dragging || touchChild == null || rowSet.isEmpty()) return;
                beginDrag(touchChild, downY);
            }
        };

        /** 边缘自动滚动。滚动不产生 MOVE 事件，每帧要拿最后的指尖位置重判落点。 */
        private final Runnable scrollTick = new Runnable() {
            @Override public void run() {
                if (scrollStep == 0 || scroller == null || !dragging) return;
                scroller.scrollBy(0, scrollStep);
                reevaluateTarget();
                handler.postDelayed(this, 16);
            }
        };

        public Layout(Context c) {
            super(c);
            setOrientation(VERTICAL);
            touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();

            // 空档的 remove/add 会牵动其余行的位置：用 CHANGE_APPEARING /
            // CHANGE_DISAPPEARING 动画「因增删而挪位的兄弟行」（CHANGING 管的是
            // 纯重排布局，不是这一类）。空档自身的出现/消失动画关掉——它是
            // INVISIBLE 的，淡入淡出没有意义，DISAPPEARING 还会把它留在屏上拖时间。
            transition.setDuration(SLIDE_MS);
            transition.disableTransitionType(LayoutTransition.APPEARING);
            transition.disableTransitionType(LayoutTransition.DISAPPEARING);
            transition.enableTransitionType(LayoutTransition.CHANGE_APPEARING);
            transition.enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            transition.enableTransitionType(LayoutTransition.CHANGING);
            // 外面就是 ScrollView，父层级别跟着一起动，整页会抖
            transition.setAnimateParentHierarchy(false);
        }

        void configure(ScrollView scroller, List<View> rows, List<String> ids,
                       List<String> sections, Callback callback) {
            this.scroller = scroller;
            this.callback = callback;
            rowSet.clear();
            idOfRow.clear();
            sectionOfRow.clear();
            for (int i = 0; i < rows.size(); i++) {
                rowSet.add(rows.get(i));
                idOfRow.put(rows.get(i), ids.get(i));
                sectionOfRow.put(rows.get(i), sections.get(i));
            }
        }

        // ================= 触摸拦截 =================

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handler.removeCallbacks(pendingLongPress);
                    downX = e.getX();
                    downY = e.getY();
                    touchPointer = e.getPointerId(0);
                    touchChild = rowUnder(downY);
                    if (touchChild != null) {
                        handler.postDelayed(pendingLongPress,
                                ViewConfiguration.getLongPressTimeout());
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (!dragging && touchChild != null) {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (dx * dx + dy * dy > (float) touchSlop * touchSlop) {
                            handler.removeCallbacks(pendingLongPress);   // 在滚动，不是长按
                        }
                    }
                    break;

                default:
                    handler.removeCallbacks(pendingLongPress);
                    break;
            }
            // 拖动中返回 true：后续事件从子行转到自己的 onTouchEvent
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!dragging) return super.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    trackPointer(e);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    endDrag();
                    break;
            }
            return true;
        }

        // ================= 拖动 =================

        private void beginDrag(View row, float containerDownY) {
            dragging = true;
            gapRow = row;
            gapSection = sectionOfRow.get(row);
            initialOrder = currentOrder();
            // 指尖此刻的屏幕 Y。downY 可能落后真实位置最多一个 touchSlop，可忽略
            lastRawY = containerScreenY() + containerDownY;

            int w = Math.max(1, row.getWidth()), h = Math.max(1, row.getHeight());
            snap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            row.draw(new Canvas(snap));

            // 空档：INVISIBLE 而不是 GONE——保留尺寸，列表总高不变（见类注释）
            row.setVisibility(View.INVISIBLE);

            // 浮动卡片：窗口层的一张位图 + 卡片底 + 海拔阴影
            ghost = new ImageView(getContext());
            ghost.setImageBitmap(snap);
            ghost.setScaleType(ImageView.ScaleType.FIT_XY);
            ghost.setBackground(Ui.round(getContext(), Ui.surfaceHigh(getContext()),
                    Color.TRANSPARENT, Ui.R_M, 0));
            ghost.setElevation(Ui.dp(getContext(), 8));
            int[] rloc = new int[2];
            row.getLocationOnScreen(rloc);
            ghost.setTranslationX(rloc[0]);
            ghost.setTranslationY(rloc[1] - overlayScreenY());
            overlay().addView(ghost, new ViewGroup.LayoutParams(w, h));

            // 手指捏在行里的什么位置，影子就按这个偏移跟手
            grabOffsetY = Math.max(0, Math.min(h, lastRawY - rloc[1]));

            row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            setLayoutTransition(transition);   // 只在拖动期间挂，落位就摘
        }

        private void trackPointer(MotionEvent e) {
            int idx = e.findPointerIndex(touchPointer);
            if (idx < 0 || ghost == null) return;
            // 屏幕坐标 = 容器屏幕原点 + 事件在容器里的坐标。滚动会同时改变两者，
            // 相加恒等于真实屏幕位置，所以不依赖 getRawY（它拿不到任意触点）。
            lastRawY = containerScreenY() + e.getY(idx);
            ghost.setTranslationY(lastRawY - grabOffsetY - overlayScreenY());
            reevaluateTarget();
            autoScroll();
        }

        /**
         * 重判空档落点：指尖压在哪 个槽位上，空档就挪到哪（详见类注释「槽位判定」）。
         */
        private void reevaluateTarget() {
            if (gapRow == null) return;
            float fingerY = lastRawY - containerScreenY();
            int idx = 0;
            int gapSlot = -1;
            int target = -1;
            int g0 = -1, g1 = -1;          // 同组槽位范围（空档自己也算组内一行）
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (!rowSet.contains(c)) continue;
                boolean isGap = c == gapRow;
                if (isGap || gapSection.equals(sectionOfRow.get(c))) {
                    if (g0 < 0) g0 = idx;
                    g1 = idx;
                    if (target < 0 && fingerY >= c.getTop() && fingerY < c.getBottom()) {
                        target = idx;
                    }
                }
                if (isGap) gapSlot = idx;
                idx++;
            }
            if (gapSlot < 0 || g0 < 0) return;
            if (target < 0) {
                // 指尖不压在任何同组行上：按它相对同组范围的位置吸附到组首/组尾。
                // 组范围的顶/底取「那一槽的行」的边界——空档自己是 INVISIBLE 的，
                // 边界照样有效（它保留着尺寸）。
                View topRow = rowAtSlot(g0);
                View bottomRow = rowAtSlot(g1);
                if (topRow != null && fingerY < topRow.getTop()) target = g0;
                else if (bottomRow != null && fingerY >= bottomRow.getBottom()) target = g1;
            }
            if (target < 0 || target == gapSlot) return;
            moveGap(target);
        }

        /** 第 slot 个行子 View（跳过非行元素后数）。 */
        private View rowAtSlot(int slot) {
            int counted = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (!rowSet.contains(c)) continue;
                if (counted == slot) return c;
                counted++;
            }
            return null;
        }

        /**
         * 把空档挪到第 target 槽：先摘下来，再插到「剩余行里第 target 行」前面。
         * 容器里混着非行元素，不能拿 target 直接当子 View 下标——现场数。
         */
        private void moveGap(int target) {
            if (gapRow == null) return;
            removeView(gapRow);
            int insertAt = getChildCount();
            int counted = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (!rowSet.contains(c)) continue;
                if (counted == target) { insertAt = i; break; }
                counted++;
            }
            addView(gapRow, insertAt);
        }

        /** 当前行序（容器子 View 的实际顺序——单一真相，落库就用它）。 */
        private List<String> currentOrder() {
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < getChildCount(); i++) {
                String id = idOfRow.get(getChildAt(i));
                if (id != null) out.add(id);
            }
            return out;
        }

        // ================= 松手 =================

        /** 松手：浮动卡片吸附回空档，随后与真行交接（摘影子、亮行、落库）。 */
        private void endDrag() {
            if (!dragging) return;
            dragging = false;
            stopAutoScroll();

            final ImageView g = ghost;
            final View row = gapRow;
            ghost = null;
            gapRow = null;
            if (g != null && row != null) {
                int[] rloc = new int[2];
                row.getLocationOnScreen(rloc);
                g.animate().translationY(rloc[1] - overlayScreenY())
                        .setDuration(SNAP_MS)
                        .setInterpolator(Ui.EASE_STANDARD)
                        .withEndAction(new Runnable() {
                            @Override public void run() { finishDrag(g, row); }
                        }).start();
            } else {
                finishDrag(null, row);
            }
        }

        private void finishDrag(ImageView g, View row) {
            setLayoutTransition(null);
            if (g != null) {
                ViewGroup overlay = overlay();
                if (g.getParent() == overlay) overlay.removeView(g);
                if (snap != null) { snap.recycle(); snap = null; }
            }
            if (row != null) row.setVisibility(View.VISIBLE);
            if (callback != null) {
                List<String> finalOrder = currentOrder();
                if (!finalOrder.equals(initialOrder)) {
                    callback.onDrop(finalOrder);
                }
            }
            initialOrder = null;
        }

        // ================= 命中 / 滚动 =================

        /** 容器坐标 y 压在哪一行上（跳过 INVISIBLE 空档与非行子 View）——起拖选行用。 */
        private View rowUnder(float containerY) {
            if (rowSet.isEmpty()) return null;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() != View.VISIBLE) continue;
                if (rowSet.contains(c) && containerY >= c.getTop() && containerY < c.getBottom()) {
                    return c;
                }
            }
            return null;
        }

        /** 拖到可视区上下边缘内时按帧步进滚动。 */
        private void autoScroll() {
            if (scroller == null) return;
            int[] sl = new int[2];
            scroller.getLocationOnScreen(sl);
            int edge = Ui.dp(getContext(), SCROLL_EDGE_DP);
            int top = sl[1] + edge;
            int bottom = sl[1] + scroller.getHeight() - edge;
            int step = lastRawY < top ? -Ui.dp(getContext(), SCROLL_STEP_DP)
                    : (lastRawY > bottom ? Ui.dp(getContext(), SCROLL_STEP_DP) : 0);
            if (step == scrollStep) return;
            stopAutoScroll();
            scrollStep = step;
            if (step != 0) handler.post(scrollTick);
        }

        private void stopAutoScroll() {
            scrollStep = 0;
            handler.removeCallbacks(scrollTick);
        }

        // ================= 坐标 =================

        private ViewGroup overlay() {
            return (ViewGroup) getRootView().findViewById(android.R.id.content);
        }

        private int overlayScreenY() {
            int[] o = new int[2];
            overlay().getLocationOnScreen(o);
            return o[1];
        }

        private int containerScreenY() {
            int[] c = new int[2];
            getLocationOnScreen(c);
            return c[1];
        }
    }
}
