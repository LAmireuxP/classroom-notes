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
import android.widget.FrameLayout;
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

    /** 工具类，不实例化：全是静态方法，没有状态。 */
    private Ui() {}

    // ================= 尺寸 =================

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /**
     * 纵向间距的紧凑系数。
     *
     * 各处间距原来按 4/8/12/16/24 的标准网格给，单看每一处都没问题，一屏叠下来就偏松：
     * 一页放不下几条内容，滚动成了常态。纵向统一压到这个比例——左右留白不动，
     * 呼吸感靠横向保持，纵向省下来的高度换成「一眼能多看两行」。
     */
    private static final float V_SCALE = 0.78f;

    /** 纵向尺寸：上下方向的内外边距、间隔都用它，别再直接写 dp。 */
    public static int v(Context c, float dp) {
        return dp(c, dp * V_SCALE);
    }

    /**
     * 可点区域的纵向高度：压缩后也不能低于 44dp，否则点起来开始费劲。
     * 列表行、分段按钮这类「手要戳的地方」用它，纯间距用 {@link #v}。
     */
    public static int vMin(Context c, float dp) {
        return Math.max(dp(c, 44), v(c, dp));
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

    /** 状态栏高度（px）。只在内容确实画到状态栏下面时才需要。 */
    public static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = c.getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return dp(c, 24);
    }

/**
     * 给顶栏补状态栏的内边距——**只在内容真的画到状态栏下面时才补**。
     *
     * 实测（Redmi M2007J3SC / Android 17 / targetSdk 33）：窗口 frame 是整屏
     * [0,0][1080,2400]，但 appBounds 从 y=90 开始——系统已经按状态栏把内容压下去了。
     * 原来无条件再加一次 status_bar_height，等于两份留白，顶栏凭空低了 30dp 左右，
     * 顶栏「总是显得偏下」就是这么来的。
     *
     * 判断依据是可见区域的顶边：>0 说明系统已经让开，不需要补；只有 ==0（真
     * edge-to-edge）才补。必须在布局之后量——onCreate 里这个值还不可靠。
     */
    public static void padStatusBar(final android.app.Activity a, final View bar) {
        bar.post(new Runnable() {
            @Override public void run() {
                android.graphics.Rect r = new android.graphics.Rect();
                a.getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
                if (r.top > 0) return;
                int extra = statusBarHeight(a);
                bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop() + extra,
                        bar.getPaddingRight(), bar.getPaddingBottom());
            }
        });
    }

    /** 导航栏高度（px）；手势导航时通常较小。FAB 的底部边距要用它。 */
    public static int navBarHeight(Context c) {
        int id = c.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) {
            int h = c.getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return dp(c, 48);
    }

    // ================= 右下角 FAB =================

    /**
     * 右下角的加号：主页面建课程，课程页建当前页签的东西（笔记 / 待办）。
     *
     * 抽出来是因为两处的「新建」必须是同一套操作——原来课程页用的是页签下面的文字按钮
     * （「新建笔记」「新建待办」各一个），主页面用的是右下角加号，同一个 App 里两套写法。
     */
    public static LinearLayout fab(Context c, String desc, final Runnable onClick) {
        LinearLayout f = new LinearLayout(c);
        f.setGravity(Gravity.CENTER);
        f.setBackground(ripple(c, primary(c), R_L));
        elevation(f, 6);
        f.setClickable(true);
        f.setFocusable(true);
        f.setContentDescription(desc);
        f.addView(Icons.icon(c, R.drawable.ic_add, onPrimary(c), 24));
        f.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClick.run(); }
        });
        return f;
    }

    /** 把 FAB 摆到右下角（16dp 边距 + 导航栏高度，免得被系统栏压住）。 */
    public static void placeFab(FrameLayout root, View fab) {
        Context c = root.getContext();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(c, 56), dp(c, 56));
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.rightMargin = dp(c, 16);
        lp.bottomMargin = v(c, 16) + navBarHeight(c);
        fab.setLayoutParams(lp);
        root.addView(fab);
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
    /**
     * 语义色快捷方法。命名与 res/values/colors.xml 里的 token 一一对应：
     * 取色时按「当前是深色还是浅色」自动加 light_ / dark_ 前缀（见 tone()），
     * 所以界面代码里不出现具体色值，换主题就是换一套 token。
     *
     * 加新色要三处一起加：colors.xml 的 light_/dark_ 两个值、这里一个快捷方法；
     * 漏掉 dark_ 的话 tone() 会兜底成纯黑/纯白，深色主题下那块会很难看。
     */
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

    /**
     * 同一颜色调成半透明。进度条拿它画底槽：用「自己那一档颜色的浅版」而不是灰槽，
     * 一眼能看出这一段属于哪个优先级、还差多少没做完。
     */
    public static int withAlpha(int color, float f) {
        int a = Math.round(255 * Math.max(0f, Math.min(1f, f)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /**
     * 优先级的语义色：高 = error 红，中 = primary 主题色，低 = success 绿。
     *
     * 主页面进度条、课程页进度条、图例、以及「新建待办」的优先级分段共用这一份映射——
     * 哪一处对不上，颜色就白标了。
     */
    public static int priorityColor(Context c, String priority) {
        if ("high".equals(priority)) return error(c);
        if ("low".equals(priority)) return tone(c, "success");
        return primary(c);
    }

    /** 优先级的短名（进度条、分段控件里用）。 */
    public static String priorityShort(String priority) {
        if ("high".equals(priority)) return "高";
        if ("low".equals(priority)) return "低";
        return "中";
    }

    /** 优先级的全名（待办行、对话框选项里用）。 */
    public static String priorityName(String priority) {
        return priorityShort(priority) + "优先级";
    }

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

    /**
     * 圆形背景（色点、勾选框、脉冲都用它）。
     * 用 OVAL + setSize 而不是给矩形设满圆角：满圆角在非正方形尺寸下会变成胶囊，
     * 而这个方法的所有调用点都默认「给多大就是多大圆」。
     */
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

    /**
     * 带涟漪的可点背景。radiusDp 传 R_FULL 就是胶囊形（列表行、按钮都用它）。
     * 涟漪用系统 RippleDrawable 而不是自己画：触摸反馈要跟手指位置走，
     * 自己实现的那套（按下缩放）只适合做辅助动效，见 pressScale()。
     */
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

    /**
     * 全 App 唯一的文本工厂：字号用 T_* 档位、颜色必须是语义色、bold 控制字重。
     *
     * includeFontPadding(false) 不是可选的美化：TextView 默认会按字体 ascent/descent
     * 留出一圈额外内边距，中文行高会因此比预期高一截——纵向紧排的列表里
     * 这一截会让行与行怎么调都对不齐。
     */
    public static TextView text(Context c, String s, float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s == null ? "" : s);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    /**
     * 列表里的一行摘要：去掉首尾空白、超过 max 字就截断加省略号。
     * 不做「按词边界截断」——中文没有词边界，硬截加省略号反而是最自然的做法。
     * 放在这里是因为首页（搜索结果）和课程页（笔记列表）要用同一套截断规则，
     * 两边各写一份迟早会漂成两个长度。
     */
    public static String preview(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }

        // ================= 容器 =================

    /**
     * MD3 Card（filled 风格）：surfaceContainer 底 + large 圆角 + 极细描边。
     * 这是内容卡片的统一容器。
     */
    public static LinearLayout card(Context c) {
        LinearLayout ll = column(c);
        ll.setBackground(round(c, surfaceContainer(c), hairline(c), R_L, 0.8f));
        int ph = dp(c, 16), pv = v(c, 16);
        ll.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = v(c, 12);
        ll.setLayoutParams(lp);
        return ll;
    }

        public static LinearLayout column(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        return ll;
    }

    /** 横向容器（默认垂直居中）。纵向用 column()——两个方法成对使用，别手写 LinearLayout。 */
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
        int padH = dp(c, 24), padV = v(c, 10);
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
        et.setPadding(p, v(c, 11), p, v(c, 11));
        et.setMinHeight(vMin(c, 48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = v(c, 12);
        et.setLayoutParams(lp);
        return et;
    }

        /** MD3 SearchBar 风格输入框（药丸形，带前导搜索图标）。 */
    public static LinearLayout searchBarWithIcon(Context c, String hint, int iconRes) {
        LinearLayout box = row(c);
        box.setBackground(round(c, surfaceContainer(c), outlineVariant(c), R_FULL, 0.8f));
        int padH = dp(c, 18), padV = v(c, 11);
        box.setPadding(padH, padV, dp(c, 18), padV);
        box.setMinimumHeight(vMin(c, 48));

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
        lp.bottomMargin = v(c, 14);
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
        int padH = dp(c, 10), padV = v(c, 5);
        tv.setPadding(padH, padV, padH, padV);
        tv.setBackground(round(c, containerColor, Color.TRANSPARENT, R_S, 0));
        return tv;
    }

    // ================= 布局参数 =================

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    /** 常用布局参数简写：需要按权重分配宽度时传 w=0 配 weight（列表行的固定写法）。 */
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

    // ================= 待办进度 =================

    /**
     * 待办进度：一个优先级一行（颜色圆点 + 「高 0/1」+ 进度条），纵向排开。
     *
     * 主页面统计卡与课程页共用同一份——两处口径必须一致。每行条的全长 = 该优先级的
     * 待办数，实心 = 已完成占比，底槽用同色的浅版；哪一行有颜色、写着什么数字，
     * 本身就是图例，不用再单独画一行。
     *
     * @param byP Db.todoByPriority() 的结果：[总数, 已完成]，下标 = 高/中/低
     */
    public static View priorityProgress(Context c, int[][] byP) {
        LinearLayout col = column(c);
        boolean first = true;
        for (int i = 0; i < Db.PRIORITY_KEYS.length; i++) {
            if (byP[i][0] == 0) continue;        // 这个优先级没有待办就不占一行
            col.addView(priorityRow(c, Db.PRIORITY_KEYS[i], byP[i][0], byP[i][1], !first));
            first = false;
        }
        return col;
    }

    /**
     * 一个优先级一行：色点 + 「高 0/1」+ 进度条。
     * 标签用权重占固定比例（而不是跟内容走），三条的进度条左端才会对齐；
     * 条内用「已完成 / 未完成」两个带权重的子 View——只放一个权重为完成比的 fill
     * 拿不到按比例的效果（LinearLayout 按权重之和分配剩余空间，独苗会吃掉整段）。
     */
    private static View priorityRow(Context c, String key, int total, int done, boolean gap) {
        int color = priorityColor(c, key);
        LinearLayout row = row(c);
        if (gap) {
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = v(c, 8);
            row.setLayoutParams(rp);
        }

        View dot = new View(c);
        dot.setBackground(circle(color, dp(c, 7)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(c, 7), dp(c, 7));
        dlp.rightMargin = dp(c, 8);
        dot.setLayoutParams(dlp);
        row.addView(dot);

        // 标签按权重占一段固定比例，三条的进度条左端才会对齐（宽度跟内容走就会参差不齐）
        TextView label = text(c, priorityShort(key) + " " + done + "/" + total,
                T_LABEL, onSurfaceVariant(c), false);
        label.setLayoutParams(lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.34f));
        row.addView(label);

        LinearLayout track = new LinearLayout(c);
        track.setLayoutParams(lpW(0, dp(c, 6), 0.66f));
        track.setBackground(round(c, withAlpha(color, 0.18f), Color.TRANSPARENT, R_FULL, 0));
        // 「已完成 / 未完成」两个带权重的子 View：LinearLayout 按权重之和分配剩余空间，
        // 只放一个权重为完成比的 fill 的话，它无论多小都会吃掉整段（老进度条就是这么满的）。
        if (done > 0) {
            View filled = new View(c);
            filled.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, done));
            filled.setBackground(round(c, color, Color.TRANSPARENT, R_FULL, 0));
            track.addView(filled);
        }
        if (total - done > 0) {
            View rest = new View(c);   // 透明，露出同色的浅底槽
            rest.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, total - done));
            track.addView(rest);
        }
        row.addView(track);
        return row;
    }

    // ================= 横向分段单选 =================

    /** 分段控件的选中回调。 */
    public interface Pick {
        void onPick(int index);
    }

    /**
     * 一排横向的单选分段（MD3 segmented button）：等宽分段，选中段是「容器里更亮的
     * 小 pill」。三个互斥选项横着放，比纵向三行省一半高度。
     *
     * dotColors 不为空时给每段前面加一个该色小圆点（优先级的红/蓝/绿就是这么带出来的）。
     */
    public static View segmentedRow(Context c, String[] labels, int[] dotColors, int activeIndex,
                                    final Pick onPick) {
        final int activeBg = isDark(c) ? surfaceHighest(c) : surfaceLowest(c);
        LinearLayout bar = row(c);
        bar.setBackground(round(c, surfaceContainer(c), Color.TRANSPARENT, R_FULL, 0));
        int pad = dp(c, 4);
        bar.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean active = i == activeIndex;

            LinearLayout seg = row(c);
            seg.setGravity(Gravity.CENTER);
            seg.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            seg.setPadding(0, v(c, 10), 0, v(c, 10));
            seg.setMinimumHeight(dp(c, 44));
            if (active) {
                seg.setBackground(ripple(c, activeBg, R_FULL));
                elevation(seg, 1f);
            } else {
                seg.setBackground(ripple(c, Color.TRANSPARENT, R_FULL));
            }
            seg.setClickable(true);
            pressScale(seg);

            if (dotColors != null && dotColors.length > i) {
                View dot = new View(c);
                dot.setBackground(circle(dotColors[i], dp(c, 7)));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(c, 7), dp(c, 7));
                dlp.rightMargin = dp(c, 6);
                dot.setLayoutParams(dlp);
                seg.addView(dot);
            }
            seg.addView(text(c, labels[i], T_BODY,
                    active ? onSurface(c) : onSurfaceVariant(c), active));
            seg.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onPick.onPick(idx); }
            });
            bar.addView(seg);
        }
        return bar;
    }

    // ================= 可选项列表行 =================

    /**
     * 可点选的一行（单选列表）：选中的那行高亮并带对勾。
     *
     * 原来有些地方要用户在输入框里敲 off / server / api、high / medium / low，还得写一段
     * 校验去挡拼错；改成点选就没有拼错的可能，那段校验也不需要了。设置页的「转写方式 /
     * 接口协议 / 鉴权方式」都用这一个。
     */
    public static View optionRow(Context c, String label, String desc, boolean active,
                                 final Runnable onClick) {
        LinearLayout row = row(c);
        row.setPadding(dp(c, 16), v(c, 12), dp(c, 16), v(c, 12));
        row.setBackground(ripple(c, Color.TRANSPARENT, R_S));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(vMin(c, 52));
        pressScale(row);

        LinearLayout mid = column(c);
        mid.setLayoutParams(lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mid.addView(text(c, label, T_BODY + 1, active ? primary(c) : onSurface(c), active));
        if (desc != null && desc.length() > 0) {
            mid.addView(text(c, desc, T_LABEL, onSurfaceVariant(c), false));
        }
        row.addView(mid);

        // 对勾固定 18dp 宽放在行尾，配合 mid 的 weight=1 把文字挤压换行，
        // 不会出现两行说明盖到图标下面
        if (active) {
            ImageView check = Icons.icon(c, R.drawable.ic_check, primary(c), 18);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18));
            clp.leftMargin = dp(c, 12);
            clp.gravity = Gravity.CENTER_VERTICAL;
            check.setLayoutParams(clp);
            row.addView(check);
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClick.run(); }
        });
        return row;
    }
}