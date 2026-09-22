package com.lamireuxp.classroom;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** 设置存储：主题、AI（OpenAI 兼容）、语音转写。 */
public class Prefs {

    private static final String FILE = "settings";

    /**
     * 取偏好文件。用 applicationContext 是有意的：设置读写会从后台线程发生，
     * 拿到 Activity 的 Context 就等于把 Activity 挂在静态引用上，容易漏内存。
     */
    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------- 主题 ----------
    // mode: system | light | dark
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    /**
     * 主题模式：system | light | dark。
     * 默认跟随系统，但 1.0 只有一个 dark 布尔——用 contains("dark") 区分「从没设过」
     * 和「显式设成浅色」：老用户升级上来继续跟随系统，而不是被锁死在浅色。
     */
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

    /** 写入主题模式（调用方负责重建界面：设置页与首页都是就地重绘）。 */
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
    // 默认仍是 DeepSeek（新装即用），但协议是可换的：OpenAI 兼容只是其中一种，
    // 通义原生、百度文心的结构完全不同，见 AiProto。
    // 这些取值都只经 aiCfg / saveAi 进出，不单独对外。
    private static String aiProtocol(Context c) {
        return sp(c).getString("ai_protocol", AiProto.OPENAI);
    }
    /** AI 服务地址。新装默认 DeepSeek（填好 Key 就能用），可按厂商预设改成别家。 */
    private static String aiEndpoint(Context c) {
        return sp(c).getString("ai_endpoint", "https://api.deepseek.com/v1");
    }
    /** AI Key。自建服务常不需要，所以允许为空（由 AiProto.missing 在总结前判断）。 */
    private static String aiKey(Context c) { return sp(c).getString("ai_key", ""); }
    /** 文心协议的 client_secret。 */
    private static String aiSecret(Context c) { return sp(c).getString("ai_secret", ""); }
    /** AI 模型名。与地址一样只对新装给默认值，用户改过就以存的为准。 */
    private static String aiModel(Context c) { return sp(c).getString("ai_model", "deepseek-chat"); }
    /** 自定义协议：请求路径。 */
    private static String aiPath(Context c) { return sp(c).getString("ai_path", ""); }
    /** 自定义协议：响应取值字段路径，如 output.text。 */
    private static String aiField(Context c) { return sp(c).getString("ai_field", ""); }
    /** 自定义协议：bearer | query | none。 */
    private static String aiAuth(Context c) { return sp(c).getString("ai_auth", "bearer"); }

    /** 一次取齐，交给 Net / AiProto 用。 */
    public static AiProto.Cfg aiCfg(Context c) {
        AiProto.Cfg g = new AiProto.Cfg();
        g.id = aiProtocol(c);
        g.endpoint = aiEndpoint(c);
        g.key = aiKey(c);
        g.secret = aiSecret(c);
        g.model = aiModel(c);
        g.path = aiPath(c);
        g.field = aiField(c);
        g.auth = aiAuth(c);
        return g;
    }

    /**
     * 存整套 AI 配置（协议、地址、Key、模型、自定义协议的路径/取值/鉴权）。
     * 空模型名存空值而不是塞默认值：豆包那类要填「接入点 ID」的服务，硬塞一个
     * 默认模型名只会让用户拿到看不懂的报错；空值由 AiProto.missing 在总结前拦下并说清。
     */
    public static void saveAi(Context c, AiProto.Cfg g) {
        sp(c).edit()
                .putString("ai_protocol", g.id)
                .putString("ai_endpoint", trimSlash(g.endpoint))
                .putString("ai_key", g.key.trim())
                .putString("ai_secret", g.secret.trim())
                // 空模型名就存空：豆包那种要填「接入点 ID」的，硬塞一个默认值
                // 只会让人拿到一个看不懂的报错。空值由 AiProto.missing 在总结前拦下。
                .putString("ai_model", g.model.trim())
                .putString("ai_path", g.path.trim())
                .putString("ai_field", g.field.trim())
                .putString("ai_auth", g.auth)
                .apply();
    }

    // ---------- 转写 ----------
    // mode: off | server | api
    public static String tsMode(Context c) { return sp(c).getString("ts_mode", "off"); }
    /** 转写服务地址（默认空：没配就不要尝试上传）。 */
    public static String tsEndpoint(Context c) { return sp(c).getString("ts_endpoint", ""); }
    /** 转写 Key（自建服务常不需要）。 */
    public static String tsKey(Context c) { return sp(c).getString("ts_key", ""); }
    /** 转写模型名，默认 whisper-1（自建 whisper.cpp 与硅基流动都认这个形状）。 */
    public static String tsModel(Context c) { return sp(c).getString("ts_model", "whisper-1"); }

    /**
     * 存转写配置。模型名留空时兜底成 whisper-1——转写只有 OpenAI 兼容一种协议，
     * 这个默认值在自建 whisper.cpp / 硅基流动上都认。
     */
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

    /** 「转写保存时同步给 AI 总结」的开关（默认开）。 */
    public static void setAiSyncFromTs(Context c, boolean on) {
        sp(c).edit().putBoolean("ts_sync_ai", on).apply();
    }

    /**
     * 转写和 AI 总结能不能共用一套地址：只有 AI 那边也是「{地址}/chat/completions」
     * 这种 OpenAI 形状时才行。通义原生、文心的地址跟转写完全不是一回事，
     * 同步过去只会把配好的 AI 设置冲坏。只给 syncAiFromTs 用。
     */
    private static boolean aiSharesTsEndpoint(Context c) {
        String p = aiProtocol(c);
        return AiProto.OPENAI.equals(p)
                || (AiProto.CUSTOM.equals(p) && aiPath(c).trim().length() == 0);
    }

    /** 地址照抄；Key 为空时保留 AI 总结里已有的——自建服务常不用 Key，别把配好的冲掉。 */
    public static void syncAiFromTs(Context c, String endpoint, String key) {
        if (!aiSharesTsEndpoint(c)) return;
        AiProto.Cfg g = aiCfg(c);
        g.endpoint = endpoint;
        String k = key == null ? "" : key.trim();
        if (k.length() > 0) g.key = k;
        saveAi(c, g);
    }

    /** 去掉结尾斜杠。拼接路径前统一走这里，免得出现 `…/v1//chat/completions`。 */
    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}