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

    public static void show(Activity a, String msg) {
        showInternal(a, msg, Ui.inverseSurface(a), Ui.inverseOnSurface(a));
    }

    public static void error(Activity a, String msg) {
        showInternal(a, msg, Ui.tone(a, "error_container"), Ui.onErrorContainer(a));
    }

    public static void success(Activity a, String msg) {
        showInternal(a, msg, Ui.tone(a, "primary_container"), Ui.onPrimaryContainer(a));
    }

    private static void showInternal(Activity a, String msg, int bg, int fg) {
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
        }, DURATION);
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