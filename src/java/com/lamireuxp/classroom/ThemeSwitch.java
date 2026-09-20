package com.lamireuxp.classroom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * 主题切换开关 —— 设计手法参考 uiverse-io/galaxy 的 Toggle-switches。
 *
 * 原设计要点：
 *  - 胶囊轨道 + 圆形滑块滑动
 *  - 月亮 / 太阳图标交叉淡入淡出
 *  - 300~500ms 缓动，带轻微发光
 *  - 滑块 hover 时微移（触感反馈）
 *
 * 用原生 View + ValueAnimator 实现同样的观感。
 */
public final class ThemeSwitch {

    private ThemeSwitch() {}

    private static final int TRACK_W = 52;   // 轨道宽 dp
    private static final int TRACK_H = 30;   // 轨道高 dp
    private static final int KNOB = 24;      // 滑块直径 dp
    private static final long DUR = 280;     // 动画时长

    /**
     * 构建主题开关。返回的视图已绑定点击事件（切换并重建 Activity）。
     */
    public static View create(final android.app.Activity a) {
        final Context c = a;
        final boolean dark = Ui.isDark(c);

        // 轨道
        final LinearLayout track = new LinearLayout(c);
        track.setOrientation(LinearLayout.HORIZONTAL);
        int tw = Ui.dp(c, TRACK_W), th = Ui.dp(c, TRACK_H);
        // 用 setMinimumWidth 而非 LayoutParams，避免被外部 WRAP_CONTENT 覆盖
        track.setMinimumWidth(tw);
        track.setMinimumHeight(th);
        track.setGravity(dark ? Gravity.END : Gravity.START);
        track.setLayoutParams(new LinearLayout.LayoutParams(tw, th));
        track.setBackground(Ui.round(c, dark ? Ui.primaryContainer(c) : Ui.surfaceHighest(c),
                Color.TRANSPARENT, Ui.R_FULL, 0));
        track.setClickable(true);
        track.setFocusable(true);
        track.setContentDescription(dark ? "切换到浅色主题" : "切换到深色主题");

        // 内边距让滑块不贴边（用实际像素，避免 dp 取整误差）
        int pad = (th - Ui.dp(c, KNOB)) / 2;
        if (pad < 0) pad = 0;
        track.setPadding(pad, pad, pad, pad);

        // 滑块（内含图标）
        final FrameLayout knob = new FrameLayout(c);
        int k = Ui.dp(c, KNOB);
        knob.setLayoutParams(new LinearLayout.LayoutParams(k, k));
        GradientDrawable knobBg = Ui.circle(dark ? Ui.primary(c) : Ui.surface(c), k);
        knob.setBackground(knobBg);
        Ui.elevation(knob, 2);

        // 月亮 / 太阳图标（叠放，交叉淡入）
        ImageView moon = Icons.icon(c, R.drawable.ic_moon,
                dark ? Ui.onPrimary(c) : Ui.onSurfaceVariant(c), 14);
        moon.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        knob.addView(moon);

        ImageView sun = Icons.icon(c, R.drawable.ic_sun,
                dark ? Ui.onPrimary(c) : Ui.onSurfaceVariant(c), 14);
        sun.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        sun.setAlpha(0f);
        knob.addView(sun);

        // 初始状态：深色显示月亮，浅色显示太阳
        moon.setAlpha(dark ? 1f : 0f);
        sun.setAlpha(dark ? 0f : 1f);

        track.addView(knob);

        // 点击：播放滑动动画，然后切换主题
        track.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                animateThenToggle(a, track, moon, sun, dark);
            }
        });

        return track;
    }

    /** 播放"滑块移动 + 图标交叉淡入"动画，结束后重建 Activity 应用主题。 */
    private static void animateThenToggle(final android.app.Activity a,
                                          final LinearLayout track,
                                          final ImageView moon, final ImageView sun,
                                          final boolean wasDark) {
        // 轨道重力反向（滑块滑到另一端）
        track.setGravity(wasDark ? Gravity.START : Gravity.END);
        track.setBackground(Ui.round(a, wasDark ? Ui.surfaceHighest(a) : Ui.primaryContainer(a),
                Color.TRANSPARENT, Ui.R_FULL, 0));

        // 图标交叉淡入
        final float from = wasDark ? 1f : 0f;   // 月亮 alpha 起点
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(DUR);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator anim) {
                float p = (Float) anim.getAnimatedValue();
                moon.setAlpha(from * (1f - p));
                sun.setAlpha((1f - from) * (1f - p) + p);
            }
        });
        va.start();

        // 动画结束后重建
        track.postDelayed(new Runnable() {
            @Override public void run() {
                Prefs.setDark(track.getContext(), !wasDark);
                a.recreate();
            }
        }, DUR - 40);
    }
}