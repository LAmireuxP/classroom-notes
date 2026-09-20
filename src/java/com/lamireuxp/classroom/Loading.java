package com.lamireuxp.classroom;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 加载态 —— 微光扫过条 + 说明文字，常驻到调用方 stop()。
 *
 * 起因：AI 总结和云转写原来只用一个 Tip 闪一下「正在请求…」，Tip 到点自动消失，
 * 之后界面就完全静止了。而 Net.postJson 的 readTimeout 是 120 秒、transcribe 是
 * 180 秒——用户盯着一动不动的屏幕，根本分不清是在跑还是卡死了。
 *
 * 手法参考 uiverse-io/galaxy 的 shimmer：一条浅色高光匀速横扫。用 ObjectAnimator
 * 驱动 translationX，属于渲染期变换、不触发布局，也不依赖任何第三方库。
 */
public final class Loading {

    private Loading() {}

    /**
     * 关闭句柄。
     * 必须 stop()：无限动画在视图被移除后仍会持有引用继续跑，既漏内存又白耗帧。
     */
    public static final class Handle {
        public final View view;
        private final ObjectAnimator anim;

        Handle(View view, ObjectAnimator anim) {
            this.view = view;
            this.anim = anim;
        }

        public void stop() {
            try { anim.cancel(); } catch (Throwable ignored) {}
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) parent.removeView(view);
        }
    }

    public static Handle show(Context c, String text) {
        LinearLayout card = Ui.card(c);

        // ---- 微光轨道 ----
        FrameLayout track = new FrameLayout(c);
        track.setBackground(Ui.round(c, Ui.surfaceHigh(c), Color.TRANSPARENT, Ui.R_FULL, 0));
        track.setClipToOutline(true);        // 让扫过的高光被圆角裁掉

        View shine = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(Ui.isDark(c) ? 0x1AFFFFFF : 0x40FFFFFF);
        shine.setBackground(g);
        final int shineW = Ui.dp(c, 44);
        shine.setLayoutParams(new FrameLayout.LayoutParams(
                shineW, ViewGroup.LayoutParams.MATCH_PARENT));
        track.addView(shine);

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4));
        tlp.bottomMargin = Ui.dp(c, 10);
        track.setLayoutParams(tlp);
        card.addView(track);

        TextView tv = Ui.text(c, text, Ui.T_LABEL, Ui.onSurfaceVariant(c), false);
        card.addView(tv);

        // ---- 横扫 ----
        ObjectAnimator anim = ObjectAnimator.ofFloat(shine, "translationX",
                -shineW, Ui.dp(c, 240));
        anim.setDuration(1100);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.start();

        return new Handle(card, anim);
    }
}