package com.lamireuxp.classroom;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 轻提示 —— 底部悬浮的状态药丸。
 *
 * 原来是通栏色带（MATCH_PARENT + 8dp 直角 + 48dp 大方块关闭按钮），一句话也要占
 * 一整条近 80dp 高的紫色横幅，又重又丑。改成包裹内容的小药丸：
 *  - 宽度跟内容走（长文案封顶换行），底部居中悬浮，16dp 圆角；
 *  - 成功 = 深色药丸 + 绿色对勾，颜色只花在该强调的地方，不再整条铺色；
 *  - 错误才动用 error_container 整条变色；
 *  - 同屏只留一条，新的来了先把旧的收掉，不再叠罗汉。
 */
public final class Tip {

    /** 工具类，不实例化：提示条挂在当前 Activity 的窗口上，用完即弃。 */
    private Tip() {}

    private static final long DURATION = 2200;
    /** 带操作按钮时停留更久——用户得先读完再决定点不点。 */
    private static final long DURATION_ACTION = 6000;
    /** 长文案封顶，一句话不至于占满全屏。 */
    private static final float MAX_WIDTH_DP = 320;

    /** 当前活跃的提示条。同屏只留一条：新的来了先把旧的收掉。 */
    private static View sActive;

    // ================= 对外入口 =================

    /** 成功：深色药丸 + 对勾。用于「用户发起的写操作已完成」。 */
    public static void success(Activity a, String msg) {
        showInternal(a, msg, Ui.inverseSurface(a), Ui.inverseOnSurface(a),
                R.drawable.ic_check, Ui.tone(a, "success"), null, null);
    }

    /** 错误：error_container 整条变色。 */
    public static void error(Activity a, String msg) {
        showInternal(a, msg, Ui.tone(a, "error_container"), Ui.onErrorContainer(a),
                0, 0, null, null);
    }

    /**
     * 带操作按钮的错误提示。
     *
     * 用在「有明确出路」的失败上——比如设备没有系统语音识别服务，
     * 用户该做的是去开云转写，那就直接把入口摆在提示条上。
     */
    public static void errorAction(Activity a, String msg, String actionLabel,
                                   final Runnable action) {
        showInternal(a, msg, Ui.tone(a, "error_container"), Ui.onErrorContainer(a),
                0, 0, actionLabel, action);
    }

    // ================= 内部 =================

    private static void showInternal(Activity a, String msg, int bg, int fg,
                                     int iconRes, int iconColor,
                                     String actionLabel, final Runnable action) {
        if (a == null || a.isFinishing()) return;
        if (msg == null || msg.length() == 0) return;

        View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        final ViewGroup root = (ViewGroup) decor;

        // 先把上一条收掉——同屏只留一条
        dismissActive();

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.round(a, bg, Color.TRANSPARENT, Ui.R_L, 0));
        Ui.elevation(bar, 6);
        int padH = Ui.dp(a, 14), padV = Ui.v(a, 10);
        bar.setPadding(padH, padV, padH, padV);

        // 前导图标（仅成功带）
        if (iconRes != 0) {
            ImageView icon = new ImageView(a);
            icon.setImageResource(iconRes);
            icon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
            int is = Ui.dp(a, 16);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(is, is);
            ilp.rightMargin = Ui.dp(a, 8);
            icon.setLayoutParams(ilp);
            bar.addView(icon);
        }

        // 文字
        TextView tv = Ui.text(a, msg, Ui.T_BODY, fg, false);
        tv.setLineSpacing(Ui.dp(a, 2), 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(tlp);
        bar.addView(tv);

        // 操作按钮（可空）
        if (actionLabel != null && action != null) {
            TextView act = Ui.text(a, actionLabel, Ui.T_BODY, fg, true);
            int ap = Ui.dp(a, 10);
            act.setPadding(ap, Ui.dp(a, 6), ap, Ui.dp(a, 6));
            act.setBackground(Ui.ripple(a, Color.TRANSPARENT, Ui.R_S));
            act.setClickable(true);
            act.setFocusable(true);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.leftMargin = Ui.dp(a, 8);
            act.setLayoutParams(alp);
            act.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dismiss(root, bar);
                    action.run();
                }
            });
            bar.addView(act);
        }

        // 关闭按钮：16dp 视觉 / 40dp 点按区，不再是原来的 48dp 大方块
        int closeSize = Ui.dp(a, 40);
        ImageView close = new ImageView(a);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(fg, PorterDuff.Mode.SRC_IN);
        close.setBackground(Ui.rippleBorderless(a, Ui.R_FULL));
        close.setClickable(true);
        close.setFocusable(true);
        close.setScaleType(ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(closeSize, closeSize);
        clp.leftMargin = Ui.dp(a, 4);
        clp.rightMargin = -Ui.dp(a, 6);   // 视觉右贴边，点按区不变
        close.setLayoutParams(clp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss(root, bar);
            }
        });
        bar.addView(close);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Ui.v(a, 20);
        bar.setLayoutParams(lp);

        bar.setAlpha(0f);
        bar.setTranslationY(Ui.dp(a, 24));
        root.addView(bar);

        // LinearLayout 没有 setMaxWidth（那是 ProgressBar 的），手动测一次：
        // 自然宽度超过封顶就把它钉死在像素值，避免长文案把药丸撑成通栏。
        int maxWidth = Ui.dp(a, MAX_WIDTH_DP);
        int spec = View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST);
        bar.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (bar.getMeasuredWidth() >= maxWidth) {
            bar.getLayoutParams().width = maxWidth;
        }

        sActive = bar;
        bar.animate().alpha(1f).translationY(0f)
                .setDuration(Ui.DUR_BASE).setInterpolator(Ui.EASE_STANDARD).start();

        bar.postDelayed(new Runnable() {
            @Override public void run() { dismiss(root, bar); }
        }, actionLabel != null ? DURATION_ACTION : DURATION);
    }

    /**
     * 立刻收掉当前这条（新的提示要来了）。
     * 只从界面上摘掉，不走淡出动画——同一时刻出现一进一出两条提示反而更乱。
     */
    private static void dismissActive() {
        if (sActive == null) return;
        final View old = sActive;
        sActive = null;
        old.animate().cancel();
        try {
            ViewGroup parent = (ViewGroup) old.getParent();
            if (parent != null) parent.removeView(old);
        } catch (Throwable ignored) {}
    }

    /**
     * 淡出并移除。withEndAction 里再摘视图：动画期间视图还在，直接移除会看不到淡出。
     * 已经被移除过（动画重复触发）时直接返回，保证幂等。
     */
    private static void dismiss(final ViewGroup root, final View bar) {
        if (bar.getParent() == null) return;
        if (sActive == bar) sActive = null;
        bar.animate().alpha(0f).translationY(Ui.dp(bar.getContext(), 16))
                .setDuration(Ui.DUR_FAST).withEndAction(new Runnable() {
                    @Override public void run() {
                        try {
                            root.removeView(bar);
                        } catch (Throwable ignored) {}
                    }
                }).start();
    }
}
