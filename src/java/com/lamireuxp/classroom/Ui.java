package com.lamireuxp.classroom;

import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * UI 工具 —— 严格对齐 Material Design 3 设计规范。
 *
 * 参考规范（m3.material.io）：
 *  - 颜色：语义色系统（primary / primaryContainer / surface / surfaceContainer…）
 *  - 间距：4 / 8 / 12 / 16 / 24 dp 网格
 *  - 圆角：4(dp极小) / 8(small) / 12(medium) / 16(large) / 28(extraLarge) / 999(full)
 *  - 层次：用 surfaceContainer 分级 + 阴影（elevation）表达，而非粗描边
 */
public final class Ui {

    private Ui() {}

    // ================= 尺寸 =================

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** MD3 圆角分级（dp） */
    public static final float R_XS = 4;
    public static final float R_S = 8;
    public static final float R_M = 12;
    public static final float R_L = 16;
    public static final float R_XL = 28;
    public static final float R_FULL = 999;

    // ================= 动效 =================

    /**
     * 缓动词汇表。
     *
     * 之前 Tip 和 ThemeSwitch 各自 new 一个 DecelerateInterpolator —— 同一个 App 里
     * 出现两种状态变化曲线，是「缺少设计系统」的典型症状。这里统一收口。
     *
     * 数值取自 uiverse-io/galaxy 里跨文件最一致的几条 cubic-bezier；Android 的
     * PathInterpolator 就是同一条三次贝塞尔，可以逐位照抄。
     */
    public static final TimeInterpolator EASE_STANDARD =
            new PathInterpolator(0.23f, 1f, 0.32f, 1f);          // 通用状态变化
    public static final TimeInterpolator EASE_BACK =
            new PathInterpolator(0.68f, -0.55f, 0.27f, 1.55f);   // 带回弹：弹层、开关

    /** 时长三档：状态 / 变换 / 进入。 */
    public static final long DUR_FAST = 150;
    public static final long DUR_BASE = 250;
    public static final long DUR_SLOW = 400;

        /**
     * 按下时轻微缩放。涟漪是「填充」，这个是「形变」，两者叠加才有实感。
     *
     * 用 StateListAnimator 而不是 OnTouchListener。
     * Android 没有 getOnTouchListener()，一旦 setOnTouchListener 占掉这个位置，
     * 调用方再想挂自己的触摸监听就会把按压动画顶掉；反过来也一样——两个
     * 想监听触摸的人只能活一个。StateListAnimator 是系统处理「按压形变」的正规
     * 机制：它挂在 state_pressed 上，完全不碰触摸链路，涟漪和调用方的手势都在。
     *
     * 缩放属于渲染期变换、不触发重排，放在列表里也安全。
     */
    public static void pressScale(final View v) {
        ObjectAnimator down = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat("scaleX", 0.97f),
                PropertyValuesHolder.ofFloat("scaleY", 0.97f));
        down.setDuration(DUR_FAST);
        down.setInterpolator(EASE_STANDARD);

        ObjectAnimator up = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat("scaleX", 1f),
                PropertyValuesHolder.ofFloat("scaleY", 1f));
        up.setDuration(DUR_FAST);
        up.setInterpolator(EASE_STANDARD);

        StateListAnimator sla = new StateListAnimator();
        sla.addState(new int[]{android.R.attr.state_pressed}, down);
        sla.addState(new int[]{}, up);
        v.setStateListAnimator(sla);
    }

    // ================= 窗口层主题 =================

    /**
     * 同步窗口层主题：状态栏 / 导航栏底色 + 图标明暗。
     *
     * 主题资源（R.style.AppTheme[_Dark]）只在 setTheme() 那一刻生效，
     * 之后再改主题就得自己同步这几项，否则会出现「界面已经变深色、
     * 状态栏图标还是黑的」这种不一致。
     */
    public static void applyWindowTheme(android.app.Activity a) {
        boolean dark = isDark(a);
        a.getWindow().setStatusBarColor(surface(a));
        a.getWindow().setNavigationBarColor(surface(a));
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            View decor = a.getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(flags);
        }
    }

    /** 状态栏高度（px）。edge-to-edge 下给顶栏加内边距用。 */
    public static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = c.getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return dp(c, 24);
    }

    /**
     * 循环扩散脉冲：scale + alpha，只走渲染期变换、不改 layout，所以不会重排。
     * 多圈给不同 startDelay 就是相位错开的扩散波。
     *
     * 这是**常驻循环**动画——只用在明确的「进行中」状态上，并且视图被移除时
     * 调用方必须 cancel，否则动画会一直跑下去（漏内存 + 白耗帧）。
     */
    public static ObjectAnimator pulse(View v, long startDelayMs) {
        v.setScaleX(0.4f);
        v.setScaleY(0.4f);
        v.setAlpha(0.7f);
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat("scaleX", 0.4f, 2.2f),
                PropertyValuesHolder.ofFloat("scaleY", 0.4f, 2.2f),
                PropertyValuesHolder.ofFloat("alpha", 0.7f, 0f));
        a.setDuration(1200);
        a.setStartDelay(startDelayMs);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        a.start();
        return a;
    }

    // ================= 主题色解析 =================

    /** 是否深色（跟随 App 内开关，不跟随系统）。 */
    public static boolean isDark(Context c) {
        return Prefs.dark(c);
    }

    /**
     * 取 MD3 语义色。传入语义名（如 "primary"），
     * 自动按当前主题映射到 light_ / dark_ 资源。
     */
    public static int tone(Context c, String semantic) {
        String name = (isDark(c) ? "dark_" : "light_") + semantic;
        int id = c.getResources().getIdentifier(name, "color", c.getPackageName());
        if (id == 0) return isDark(c) ? Color.WHITE : Color.BLACK;
        try {
            return c.getResources().getColor(id);
        } catch (Throwable e) {
            return isDark(c) ? Color.WHITE : Color.BLACK;
        }
    }

        // ---- 语义色快捷方法 ----

    public static int primary(Context c) { return tone(c, "primary"); }
    public static int onPrimary(Context c) { return tone(c, "on_primary"); }
    public static int primaryContainer(Context c) { return tone(c, "primary_container"); }
    public static int onPrimaryContainer(Context c) { return tone(c, "on_primary_container"); }
    public static int secondaryContainer(Context c) { return tone(c, "secondary_container"); }
    public static int surface(Context c) { return tone(c, "surface"); }
        public static int surfaceLowest(Context c) { return tone(c, "surface_container_lowest"); }
    public static int surfaceContainer(Context c) { return tone(c, "surface_container"); }
    public static int surfaceHigh(Context c) { return tone(c, "surface_container_high"); }
    public static int surfaceHighest(Context c) { return tone(c, "surface_container_highest"); }
    public static int onSurface(Context c) { return tone(c, "on_surface"); }
    public static int onSurfaceVariant(Context c) { return tone(c, "on_surface_variant"); }
    public static int outline(Context c) { return tone(c, "outline"); }
    public static int outlineVariant(Context c) { return tone(c, "outline_variant"); }

    /**
     * 卡片描边。
     * 深色下用更暗的一档（dark_hairline），否则 #45464F 相对 surface 亮太多，
     * 卡片看起来像被「灰框」框住。浅色下沿用 outline_variant。
     */
    public static int hairline(Context c) {
        return isDark(c) ? tone(c, "hairline") : outlineVariant(c);
    }
    public static int error(Context c) { return tone(c, "error"); }
        public static int onErrorContainer(Context c) { return tone(c, "on_error_container"); }
        public static int inverseSurface(Context c) { return tone(c, "inverse_surface"); }
    public static int inverseOnSurface(Context c) { return tone(c, "inverse_on_surface"); }

    // ================= 形状 / 背景 =================

    public static GradientDrawable round(Context c, int fillColor, int strokeColor,
                                         float radiusDp, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0 && strokeColor != Color.TRANSPARENT) {
            d.setStroke(dp(c, strokeDp), strokeColor);
        }
        return d;
    }

    public static GradientDrawable circle(int color, int sizePx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setSize(sizePx, sizePx);
        return d;
    }

    /** 仅顶部圆角（MD3 Bottom Sheet 用）。 */
    public static GradientDrawable roundTop(Context c, int fillColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        float r = dp(c, radiusDp);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return d;
    }

    public static RippleDrawable ripple(Context c, int fillColor, float radiusDp) {
        GradientDrawable base = round(c, fillColor, Color.TRANSPARENT, radiusDp, 0);
        GradientDrawable mask = round(c, Color.WHITE, Color.TRANSPARENT, radiusDp, 0);
        int pr = isDark(c) ? 0x33FFFFFF : 0x1A000000;
        return new RippleDrawable(ColorStateList.valueOf(pr), base, mask);
    }

    /** 无底色的水波纹（用于图标按钮），保留圆形涟漪。 */
    public static RippleDrawable rippleBorderless(Context c, float radiusDp) {
        GradientDrawable mask = round(c, Color.WHITE, Color.TRANSPARENT, radiusDp, 0);
        int pr = isDark(c) ? 0x33FFFFFF : 0x1F000000;
        return new RippleDrawable(ColorStateList.valueOf(pr), null, mask);
    }

    /** Outlined 按钮的涟漪（透明底 + 描边）。 */
    public static RippleDrawable outlinedRipple(Context c) {
        int stroke = isDark(c) ? outline(c) : outline(c);
        GradientDrawable base = round(c, Color.TRANSPARENT, stroke, R_S, 1f);
        GradientDrawable mask = round(c, Color.WHITE, Color.TRANSPARENT, R_S, 0);
        int pr = isDark(c) ? 0x33FFFFFF : 0x1A000000;
        return new RippleDrawable(ColorStateList.valueOf(pr), base, mask);
    }

            /** 取任意十六进制色（用于课程自定义色）。 */
    public static int parseColor(String hex, int fallback) {
        try {
            return Color.parseColor(hex);
        } catch (Throwable e) {
            return fallback;
        }
    }

    /** MD3 海拔阴影（elevation）。 */
    public static void elevation(View v, float dpVal) {
        if (v == null) return;
        v.setElevation(dp(v.getContext(), dpVal));
    }

    // ================= 文本 =================

    /** MD3 字体排版档位 */
    public static final float T_DISPLAY = 26;
    public static final float T_HEADLINE = 20;
    public static final float T_TITLE = 16;
    public static final float T_BODY = 14;
    public static final float T_LABEL = 12;
    public static final float T_CAPTION = 11;

    public static TextView text(Context c, String s, float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s == null ? "" : s);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setIncludeFontPadding(false);
        return tv;
    }

        // ================= 容器 =================

    /**
     * MD3 Card（filled 风格）：surfaceContainer 底 + large 圆角 + 极细描边。
     * 这是内容卡片的统一容器。
     */
    public static LinearLayout card(Context c) {
        LinearLayout ll = column(c);
        ll.setBackground(round(c, surfaceContainer(c), hairline(c), R_L, 0.8f));
        int p = dp(c, 16);
        ll.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        ll.setLayoutParams(lp);
        return ll;
    }

        public static LinearLayout column(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        return ll;
    }

    public static LinearLayout row(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        return ll;
    }

    // ================= 按钮（MD3 四种风格） =================
    //
    // 圆角用 R_S(8dp) 而不是 R_FULL：胶囊形按钮是通用 Material 的观感，
    // 文档型生产力应用用矩形更沉稳（Notion 的 DESIGN.md 明确把
    // "矩形而非胶囊" 列为区分于竞品的品牌特征）。全圆只留给 chip / tab / FAB。

    /** Filled Button —— 最高强调 */
    public static TextView filledButton(Context c, String text) {
        TextView tv = baseButton(c, text);
        tv.setTextColor(onPrimary(c));
        tv.setBackground(ripple(c, primary(c), R_S));
        return tv;
    }

            /** Text Button —— 最低强调 */
    public static TextView textButton(Context c, String text) {
        TextView tv = baseButton(c, text);
        tv.setTextColor(primary(c));
        tv.setBackground(ripple(c, Color.TRANSPARENT, R_S));
        return tv;
    }

        private static TextView baseButton(Context c, String text) {
        TextView tv = text(c, text, T_BODY, onSurface(c), true);
        tv.setGravity(Gravity.CENTER);
        int padH = dp(c, 24), padV = dp(c, 10);
        tv.setPadding(padH, padV, padH, padV);
        tv.setMinHeight(dp(c, 40));
        tv.setClickable(true);
        tv.setFocusable(true);
        pressScale(tv);
        return tv;
    }

        // ================= 输入框 =================

    /** MD3 Outlined TextField */
    public static EditText input(Context c, String hint) {
        EditText et = new EditText(c);
        et.setHint(hint);
        et.setTextSize(T_BODY + 1);
        et.setTextColor(onSurface(c));
        et.setHintTextColor(onSurfaceVariant(c));
        et.setBackground(round(c, surfaceContainer(c), outline(c), R_S, 1f));
        int p = dp(c, 14);
        et.setPadding(p, dp(c, 11), p, dp(c, 11));
        et.setMinHeight(dp(c, 48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        et.setLayoutParams(lp);
        return et;
    }

        /** MD3 SearchBar 风格输入框（药丸形，带前导搜索图标）。 */
    public static LinearLayout searchBarWithIcon(Context c, String hint, int iconRes) {
        LinearLayout box = row(c);
        box.setBackground(round(c, surfaceContainer(c), outlineVariant(c), R_FULL, 0.8f));
        int padH = dp(c, 18), padV = dp(c, 11);
        box.setPadding(padH, padV, dp(c, 18), padV);
        box.setMinimumHeight(dp(c, 48));

        ImageView icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setColorFilter(onSurfaceVariant(c), android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20));
        ip.rightMargin = dp(c, 12);
        icon.setLayoutParams(ip);
        box.addView(icon);

        EditText et = new EditText(c);
        et.setHint(hint);
        et.setTextSize(T_BODY + 1);
        et.setTextColor(onSurface(c));
        et.setHintTextColor(onSurfaceVariant(c));
        et.setSingleLine(true);
        et.setBackground(null);
        et.setPadding(0, 0, 0, 0);
        et.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(et);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 14);
        box.setLayoutParams(lp);

        // 把输入框挂到 box 上，方便外部取用
        box.setTag(et);
        return box;
    }

    /** 从 searchBarWithIcon 的容器里取出 EditText。 */
    public static EditText searchInput(LinearLayout box) {
        return (EditText) box.getTag();
    }

    // ================= 芯片 Chip =================

        /** 带容器色的 Chip */
    public static TextView tonalChip(Context c, String text, int containerColor, int onColor) {
        TextView tv = text(c, text, T_LABEL + 0.5f, onColor, true);
        tv.setGravity(Gravity.CENTER);
        int padH = dp(c, 10), padV = dp(c, 5);
        tv.setPadding(padH, padV, padH, padV);
        tv.setBackground(round(c, containerColor, Color.TRANSPARENT, R_S, 0));
        return tv;
    }

    // ================= 布局参数 =================

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpW(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

        // ================= 分隔 / 间距 =================

        /** 弹性占位（把后续元素推到行尾）。 */
    public static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(lpW(0, 1, 1f));
        return v;
    }

        }