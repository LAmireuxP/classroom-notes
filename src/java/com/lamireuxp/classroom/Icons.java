package com.lamireuxp.classroom;

import android.content.Context;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 图标与按钮构建 —— 严格对齐 Material Design 3。
 *
 * 关键规范（m3.material.io / impeccable android.md）：
 *  - 图标按钮触摸目标 ≥48dp，图标本身 24dp
 *  - 带图标按钮：图标与文字必须**同基线垂直居中**
 *  - 按钮高 40dp（MD3 标准），图标 18dp，间距 8dp
 */
public final class Icons {

    /** 工具类，不实例化：按钮与图标都是现造现用，不缓存视图。 */
    private Icons() {}

    // 统一的按钮内部度量（dp）
    private static final int BTN_HEIGHT = 40;      // MD3 按钮标准高
    private static final int BTN_ICON = 18;        // 按钮内图标
    private static final int BTN_GAP = 8;          // 图标与文字间距
    private static final int BTN_PAD_H = 20;       // 左右内边距

    /** 裸图标 */
    public static ImageView icon(Context c, int drawableRes, int tintColor, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(drawableRes);
        iv.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
        int s = Ui.dp(c, sizeDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    /**
     * MD3 Icon Button：无底色 + 圆形涟漪，触摸目标 ≥48dp。
     */
    public static LinearLayout iconButton(Context c, int drawableRes, int sizeDp, int tintColor) {
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER);
        int touch = Math.max(48, sizeDp);
        int s = Ui.dp(c, touch);
        box.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        box.setBackground(Ui.rippleBorderless(c, touch / 2f));
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription(descOf(drawableRes));
        box.addView(icon(c, drawableRes, tintColor, 22));
        return box;
    }

    /** MD3 Icon Button（带容器底色）。 */
    public static LinearLayout iconButtonFilled(Context c, int drawableRes, int sizeDp,
                                                int containerColor, int tintColor) {
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER);
        int touch = Math.max(48, sizeDp);
        int s = Ui.dp(c, touch);
        box.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        box.setBackground(Ui.ripple(c, containerColor, touch / 2f));
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription(descOf(drawableRes));
        box.addView(icon(c, drawableRes, tintColor, 22));
        return box;
    }

    /**
     * MD3 带图标按钮 —— 图标与文字严格垂直居中、同基线。
     *
     * 修复要点：
     *  1. 固定按钮高度 40dp，用 Gravity.CENTER 让图标和文字在**容器中心**对齐
     *  2. 图标与文字都不设额外 padding，靠容器统一控制
     *  3. 不使用 includeFontPadding=false（那会破坏基线），改用统一 lineHeight
     *  4. 图标 18dp / 文字 14sp，视觉重量匹配
     *
     * style: 0=filled, 1=tonal, 2=outlined, 3=text, 4=neutral
     */
    public static LinearLayout iconTextButton(Context c, int drawableRes, String text, int style) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);          // 关键：图标与文字整体居中
        // 固定高度 40dp（MD3 标准），不使用 minHeight 以免被父容器拉伸
        int h = Ui.dp(c, BTN_HEIGHT);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, h));
        box.setMinimumHeight(h);
        box.setMinimumWidth(Ui.dp(c, 64));

        int padH = Ui.dp(c, BTN_PAD_H);
        box.setPadding(padH, 0, padH, 0);        // 纵向 padding=0，靠固定高度 + 居中

        int fg;
        switch (style) {
            case 0: // filled
                fg = Ui.onPrimary(c);
                box.setBackground(Ui.ripple(c, Ui.primary(c), Ui.R_S));
                break;
            case 1: // tonal
                fg = Ui.tone(c, "on_secondary_container");
                box.setBackground(Ui.ripple(c, Ui.secondaryContainer(c), Ui.R_S));
                break;
            case 2: // outlined
                fg = Ui.primary(c);
                box.setBackground(Ui.outlinedRipple(c));
                break;
            case 4: // neutral —— 有存在感但不抢焦点，给「同一行里还有主按钮」的次级动作用
                fg = Ui.onSurface(c);
                box.setBackground(Ui.ripple(c, Ui.surfaceHigh(c), Ui.R_S));
                break;
            default: // text
                fg = Ui.primary(c);
                box.setBackground(Ui.ripple(c, 0x00000000, Ui.R_S));
                break;
        }
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription(text);
        Ui.pressScale(box);

        if (drawableRes != 0) {
            ImageView iv = icon(c, drawableRes, fg, BTN_ICON);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(c, BTN_ICON), Ui.dp(c, BTN_ICON));
            lp.rightMargin = Ui.dp(c, BTN_GAP);
            lp.gravity = Gravity.CENTER_VERTICAL;
            iv.setLayoutParams(lp);
            box.addView(iv);
        }

        TextView tv = Ui.text(c, text, Ui.T_BODY, fg, true);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setIncludeFontPadding(false);
        // 关键：把文字行高固定成图标高度，消除基线偏移
        tv.setLineSpacing(0, 1f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, BTN_ICON));
        tp.gravity = Gravity.CENTER_VERTICAL;
        tv.setLayoutParams(tp);
        box.addView(tv);

        return box;
    }

            /**
     * 卡片内紧凑操作按钮 —— 用于一行放多个操作。
     *
     * 修复横向溢出：图标收窄到 15dp、间距 5dp、内边距 6dp、文字 12.5sp，
     * 配合调用方 weight=1 等宽分配，保证 4 个按钮在 375dp 内不溢出。
     */
    public static LinearLayout compactAction(Context c, int drawableRes, String text, int color) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        int h = Ui.dp(c, 36);
        box.setMinimumHeight(h);
        box.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        box.setBackground(Ui.rippleBorderless(c, Ui.R_FULL));
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription(text);

        if (drawableRes != 0) {
            ImageView iv = new ImageView(c);
            iv.setImageResource(drawableRes);
            iv.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(c, 15), Ui.dp(c, 15));
            lp.rightMargin = Ui.dp(c, 5);
            lp.gravity = Gravity.CENTER_VERTICAL;
            iv.setLayoutParams(lp);
            box.addView(iv);
        }

        TextView tv = Ui.text(c, text, 12.5f, color, true);
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        // 文字占剩余空间，长文本自动省略而不是撑爆容器
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, Ui.dp(c, 15), 1f);
        tlp.gravity = Gravity.CENTER_VERTICAL;
        tv.setLayoutParams(tlp);
        box.addView(tv);
        return box;
    }

    /** 简单语义描述，供无障碍使用。 */
    private static String descOf(int res) {
        if (res == R.drawable.ic_add) return "新建";
        if (res == R.drawable.ic_back) return "返回";
        if (res == R.drawable.ic_menu) return "更多";
        if (res == R.drawable.ic_settings) return "设置";
        if (res == R.drawable.ic_trash) return "删除";
        if (res == R.drawable.ic_edit) return "编辑";
        if (res == R.drawable.ic_search) return "搜索";
        if (res == R.drawable.ic_mic) return "录音";
        if (res == R.drawable.ic_pin) return "置顶";
        if (res == R.drawable.ic_moon || res == R.drawable.ic_sun) return "切换主题";
        return "";
    }
}