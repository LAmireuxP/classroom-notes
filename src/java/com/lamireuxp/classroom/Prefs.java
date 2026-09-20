package com.lamireuxp.classroom;

import android.content.Context;
import android.content.SharedPreferences;

/** 设置存储：主题、AI（OpenAI 兼容）、语音转写。 */
public class Prefs {

    private static final String FILE = "settings";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------- 主题 ----------
    public static boolean dark(Context c) { return sp(c).getBoolean("dark", false); }
    public static void setDark(Context c, boolean v) {
        sp(c).edit().putBoolean("dark", v).apply();
    }

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

    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}