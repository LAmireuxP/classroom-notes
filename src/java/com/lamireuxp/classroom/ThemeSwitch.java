package com.lamireuxp.classroom;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * 主题切换开关 —— 设计手法参考 uiverse-io/galaxy 的 Toggle-switches。
 *
 *  - 胶囊轨道 + 圆形滑块
 *  - 月亮 / 太阳图标
 *  - 按下即生效：写偏好 + 就地重绘，不等动画、不重建 Activity
 *
 * 这里原本有一段 280ms 的滑动动画，动画播完才在 onAnimationEnd 里切主题。
 * 结果是开关已经滑到底、主题却还是旧的，中间那 280ms 两者是错位的。
 * 现在改成点击里同步做掉，主题和开关在同一帧内一起变。
 */
public final class ThemeSwitch {

    /** 工具类，不实例化：开关是现场构建的一个视图。 */
    private ThemeSwitch() {}

    private static final int TRACK_W = 52;   // 轨道宽 dp
    private static final int TRACK_H = 30;   // 轨道高 dp
    private static final int KNOB = 24;      // 滑块直径 dp

    /**
     * 构建主题开关。
     *
     * @param applyTheme 切换后由宿主 Activity 就地重绘界面（不重建 Activity）
     */
    public static View create(final android.app.Activity a, final Runnable applyTheme) {
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

        // 点击：按下即生效
        track.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 在「跟随系统」下点它 = 转成手动模式，并取当前显示的相反值；
                // 已经是手动模式时就是普通的浅/深对调。两种情况同一条式子。
                Prefs.setThemeMode(a, dark ? Prefs.THEME_LIGHT : Prefs.THEME_DARK);
                applyTheme.run();
            }
        });

        return track;
    }
}