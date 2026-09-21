package com.lamireuxp.classroom;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** 设置存储：主题、AI（OpenAI 兼容）、语音转写。 */
public class Prefs {

    private static final String FILE = "settings";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------- 主题 ----------
    // mode: system | light | dark
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    public static String themeMode(Context c) {
        SharedPreferences p = sp(c);
        String m = p.getString("theme_mode", null);
        if (m != null) return m;
        // 迁移旧版本：老版本只有一个 dark 布尔（默认 false）。
        // 用 contains 区分「从没设过」和「显式设成浅色」——
        // 从没设过就跟随系统，别把人锁死在浅色上。
        if (p.contains("dark")) {
            return p.getBoolean("dark", false) ? THEME_DARK : THEME_LIGHT;
        }
        return THEME_SYSTEM;
    }

    public static void setThemeMode(Context c, String mode) {
        sp(c).edit().putString("theme_mode", mode).apply();
    }

    /** 当前实际是否深色。system 模式下去读系统配置，而不是固定值。 */
    public static boolean isDark(Context c) {
        String m = themeMode(c);
        if (THEME_DARK.equals(m)) return true;
        if (THEME_LIGHT.equals(m)) return false;
        int night = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 兼容旧调用点：语义等同 isDark（会解析 system 模式）。 */
    public static boolean dark(Context c) { return isDark(c); }

    // ---------- AI（课堂总结） ----------
    public static String aiEndpoint(Context c) {
        return sp(c).getString("ai_endpoint", "https://api.deepseek.com/v1");
    }
    public static String aiKey(Context c) { return sp(c).getString("ai_key", ""); }
    public static String aiModel(Context c) { return sp(c).getString("ai_model", "deepseek-chat"); }

    public static void saveAi(Context c, String endpoint, String key, String model) {
        sp(c).edit()
                .putString("ai_endpoint", trimSlash(endpoint))
                .putString("ai_key", key.trim())
                .putString("ai_model", model.trim().length() == 0 ? "deepseek-chat" : model.trim())
                .apply();
    }

    // ---------- 转写 ----------
    // mode: off | server | api
    public static String tsMode(Context c) { return sp(c).getString("ts_mode", "off"); }
    public static String tsEndpoint(Context c) { return sp(c).getString("ts_endpoint", ""); }
    public static String tsKey(Context c) { return sp(c).getString("ts_key", ""); }
    public static String tsModel(Context c) { return sp(c).getString("ts_model", "whisper-1"); }

    public static void saveTs(Context c, String mode, String endpoint, String key, String model) {
        sp(c).edit()
                .putString("ts_mode", mode)
                .putString("ts_endpoint", trimSlash(endpoint))
                .putString("ts_key", key.trim())
                .putString("ts_model", model.trim().length() == 0 ? "whisper-1" : model.trim())
                .apply();
    }

    // ---------- 转写 → AI 总结的单向同步 ----------
    // 两个设置页填的本来就是同一类东西（OpenAI 兼容的地址 + Key）。保存转写时顺带抄给
    // AI 总结；反过来 AI 总结改了不影响转写。开关默认开，用户可在转写设置里关掉。
    public static boolean aiSyncFromTs(Context c) { return sp(c).getBoolean("ts_sync_ai", true); }

    public static void setAiSyncFromTs(Context c, boolean on) {
        sp(c).edit().putBoolean("ts_sync_ai", on).apply();
    }

    /** 地址照抄；Key 为空时保留 AI 总结里已有的——自建服务常不用 Key，别把配好的冲掉。 */
    public static void syncAiFromTs(Context c, String endpoint, String key) {
        String k = key == null ? "" : key.trim();
        saveAi(c, endpoint, k.length() == 0 ? aiKey(c) : k, aiModel(c));
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}