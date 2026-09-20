package com.lamireuxp.classroom;

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

    /** 兼容旧 API：按颜色资源 ID 取色（自动做 light/dark 映射）。 */
    public static int color(Context c, int res) {
        if (res == 0) return isDark(c) ? Color.WHITE : Color.BLACK;
        try {
            String name = c.getResources().getResourceEntryName(res);
            if (name != null && (name.startsWith("light_") || name.startsWith("dark_"))) {
                String semantic = name.substring(name.indexOf('_') + 1);
                return tone(c, semantic);
            }
            return c.getResources().getColor(res);
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
    public static int surfaceLow(Context c) { return tone(c, "surface_container_low"); }
    public static int surfaceContainer(Context c) { return tone(c, "surface_container"); }
    public static int surfaceHigh(Context c) { return tone(c, "surface_container_high"); }
    public static int surfaceHighest(Context c) { return tone(c, "surface_container_highest"); }
    public static int onSurface(Context c) { return tone(c, "on_surface"); }
    public static int onSurfaceVariant(Context c) { return tone(c, "on_surface_variant"); }
    public static int outline(Context c) { return tone(c, "outline"); }
    public static int outlineVariant(Context c) { return tone(c, "outline_variant"); }
    public static int error(Context c) { return tone(c, "error"); }
    public static int errorContainer(Context c) { return tone(c, "error_container"); }
    public static int onErrorContainer(Context c) { return tone(c, "on_error_container"); }
    public static int tertiary(Context c) { return tone(c, "tertiary"); }
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
        GradientDrawable base = round(c, Color.TRANSPARENT, stroke, R_FULL, 1f);
        GradientDrawable mask = round(c, Color.WHITE, Color.TRANSPARENT, R_FULL, 0);
        int pr = isDark(c) ? 0x33FFFFFF : 0x1A000000;
        return new RippleDrawable(ColorStateList.valueOf(pr), base, mask);
    }

    /** 成功色（绿）—— MD3 未定义，按规范用 tertiary 系扩展。 */
    public static int success(Context c) {
        return isDark(c) ? 0xFF6DD58C : 0xFF0F9D58;
    }

    /** 警告色（橙）。 */
    public static int warning(Context c) {
        return isDark(c) ? 0xFFFFB77C : 0xFFB26A00;
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

    /** 兼容旧签名：按语义色资源 ID 取名（R.color.xxx 已废弃，这里允许传语义色 int）。 */
    public static TextView label(Context c, String s, float sizeSp, int colorRes, boolean bold) {
        return text(c, s, sizeSp, color(c, colorRes), bold);
    }

    // ================= 容器 =================

    /**
     * MD3 Card（filled 风格）：surfaceContainer 底 + large 圆角 + 极细描边。
     * 这是内容卡片的统一容器。
     */
    public static LinearLayout card(Context c) {
        LinearLayout ll = column(c);
        ll.setBackground(round(c, surfaceContainer(c), outlineVariant(c), R_L, 0.8f));
        int p = dp(c, 16);
        ll.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        ll.setLayoutParams(lp);
        return ll;
    }

    /** MD3 Outlined Card：透明底 + 描边。 */
    public static LinearLayout outlinedCard(Context c) {
        LinearLayout ll = column(c);
        ll.setBackground(round(c, surfaceLow(c), outlineVariant(c), R_L, 1f));
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

    /** Filled Button —— 最高强调 */
    public static TextView filledButton(Context c, String text) {
        TextView tv = baseButton(c, text);
        tv.setTextColor(onPrimary(c));
        tv.setBackground(ripple(c, primary(c), R_FULL));
        return tv;
    }

    /** Tonal Button —— 中等强调（secondaryContainer） */
    public static TextView tonalButton(Context c, String text) {
        TextView tv = baseButton(c, text);
        tv.setTextColor(tone(c, "on_secondary_container"));
        tv.setBackground(ripple(c, secondaryContainer(c), R_FULL));
        return tv;
    }

    /** Outlined Button —— 低强调 */
    public static TextView outlinedButton(Context c, String text) {
        TextView tv = baseButton(c, text);
        tv.setTextColor(primary(c));
        tv.setBackground(ripple(c, Color.TRANSPARENT, R_FULL));
        tv.setBackground(roundStrokeRipple(c, primary(c)));
        return tv;
    }

    /** Text Button —— 最低强调 */
    public static TextView textButton(Context c, String text) {
        TextView tv = baseButton(c, text);
        tv.setTextColor(primary(c));
        tv.setBackground(ripple(c, Color.TRANSPARENT, R_FULL));
        return tv;
    }

    private static RippleDrawable roundStrokeRipple(Context c, int strokeColor) {
        GradientDrawable base = round(c, Color.TRANSPARENT, strokeColor, R_FULL, 1f);
        GradientDrawable mask = round(c, Color.WHITE, Color.TRANSPARENT, R_FULL, 0);
        int pr = isDark(c) ? 0x33FFFFFF : 0x1A000000;
        return new RippleDrawable(ColorStateList.valueOf(pr), base, mask);
    }

    private static TextView baseButton(Context c, String text) {
        TextView tv = text(c, text, T_BODY, onSurface(c), true);
        tv.setGravity(Gravity.CENTER);
        int padH = dp(c, 24), padV = dp(c, 10);
        tv.setPadding(padH, padV, padH, padV);
        tv.setMinHeight(dp(c, 40));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    /** 兼容旧调用：button(c, text, primary) */
    public static TextView button(Context c, String text, boolean primary) {
        return primary ? filledButton(c, text) : tonalButton(c, text);
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

    /** MD3 SearchBar 风格输入框（药丸形） */
    public static EditText searchBar(Context c, String hint) {
        EditText et = new EditText(c);
        et.setHint(hint);
        et.setTextSize(T_BODY + 1);
        et.setTextColor(onSurface(c));
        et.setHintTextColor(onSurfaceVariant(c));
        et.setSingleLine(true);
        et.setBackground(round(c, surfaceContainer(c), outlineVariant(c), R_FULL, 0.8f));
        int p = dp(c, 20);
        et.setPadding(p, dp(c, 12), p, dp(c, 12));
        et.setMinHeight(dp(c, 46));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 14);
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

    /** MD3 Assist Chip */
    public static TextView chip(Context c, String text) {
        TextView tv = text(c, text, T_LABEL + 0.5f, onSurfaceVariant(c), false);
        tv.setGravity(Gravity.CENTER);
        int padH = dp(c, 12), padV = dp(c, 6);
        tv.setPadding(padH, padV, padH, padV);
        tv.setBackground(round(c, Color.TRANSPARENT, outline(c), R_S, 1f));
        return tv;
    }

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

    public static LinearLayout.LayoutParams lpMargin(int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top;
        p.bottomMargin = bottom;
        return p;
    }

    // ================= 分隔 / 间距 =================

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(outlineVariant(c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.8f)));
        lp.topMargin = dp(c, 8);
        lp.bottomMargin = dp(c, 8);
        v.setLayoutParams(lp);
        return v;
    }

    /** 弹性占位（把后续元素推到行尾）。 */
    public static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(lpW(0, 1, 1f));
        return v;
    }

    /** 固定尺寸占位。 */
    public static View gap(Context c, int wDp, int hDp) {
        View v = new View(c);
        v.setLayoutParams(lp(dp(c, wDp), dp(c, hDp)));
        return v;
    }

    /** 纵向留白。 */
    public static View vSpace(Context c, int dpVal) {
        View v = new View(c);
        v.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, dpVal)));
        return v;
    }
}