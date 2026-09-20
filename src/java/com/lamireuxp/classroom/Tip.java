package com.lamireuxp.classroom;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 提示 —— 对齐 MD3 Snackbar。
 *
 * MD3 规范：
 *  - Snackbar 用于短暂反馈，从底部升起，inverseSurface 底 + inverseOnSurface 文字
 *  - 停留 4s 左右，可手动关闭
 *  - 错误用 errorContainer 表达，而非红色 toast
 */
public final class Tip {

    private Tip() {}

    private static final long DURATION = 2600;
    /** 带操作按钮时停留更久——用户得先读完再决定点不点。 */
    private static final long DURATION_ACTION = 6000;

    public static void show(Activity a, String msg) {
        showInternal(a, msg, Ui.inverseSurface(a), Ui.inverseOnSurface(a), null, null);
    }

    public static void error(Activity a, String msg) {
        showInternal(a, msg, Ui.tone(a, "error_container"), Ui.onErrorContainer(a), null, null);
    }

    public static void success(Activity a, String msg) {
        showInternal(a, msg, Ui.tone(a, "primary_container"), Ui.onPrimaryContainer(a), null, null);
    }

    /**
     * 带操作按钮的错误提示（MD3 Snackbar 的 action）。
     *
     * 用在「有明确出路」的失败上——比如设备没有系统语音识别服务，
     * 用户该做的是去开云转写，那就直接把入口摆在提示条上，
     * 而不是让他照着文案自己去翻菜单。
     */
    public static void errorAction(Activity a, String msg, String actionLabel,
                                   final Runnable action) {
        showInternal(a, msg, Ui.tone(a, "error_container"), Ui.onErrorContainer(a),
                actionLabel, action);
    }

    private static void showInternal(Activity a, String msg, int bg, int fg,
                                     String actionLabel, final Runnable action) {
        if (a == null || a.isFinishing()) return;
        if (msg == null || msg.length() == 0) return;

        View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        final ViewGroup root = (ViewGroup) decor;

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.round(a, bg, Color.TRANSPARENT, Ui.R_S, 0));
        Ui.elevation(bar, 6);
        int padH = Ui.dp(a, 16), padV = Ui.dp(a, 14);
        bar.setPadding(padH, padV, padH, padV);

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
            Ui.pressScale(act);
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

        // 关闭按钮
        LinearLayout close = Icons.iconButton(a, R.drawable.ic_close, 32, fg);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss(root, bar);
            }
        });
        bar.addView(close);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.leftMargin = Ui.dp(a, 12);
        lp.rightMargin = Ui.dp(a, 12);
        lp.bottomMargin = Ui.dp(a, 20);
        bar.setLayoutParams(lp);

        bar.setAlpha(0f);
        bar.setTranslationY(Ui.dp(a, 24));
        root.addView(bar);
        bar.animate().alpha(1f).translationY(0f)
                .setDuration(Ui.DUR_BASE).setInterpolator(Ui.EASE_STANDARD).start();

        bar.postDelayed(new Runnable() {
            @Override public void run() { dismiss(root, bar); }
        }, actionLabel != null ? DURATION_ACTION : DURATION);
    }

    private static void dismiss(final ViewGroup root, final View bar) {
        if (bar.getParent() == null) return;
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