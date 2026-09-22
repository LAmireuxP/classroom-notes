package com.lamireuxp.classroom;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * 设备侧「跨应用语音识别」授权的入口。
 *
 * 为什么需要它：国产 ROM 的系统识别服务不只看麦克风权限。小米 AsrService 里有一道
 * CTA / 机型白名单判定（日志里的 isCTAAllow=…）——没在厂商语音助手里同意过
 * 「跨应用语音识别」的调用方会被直接拒绝，服务随即自毁；OPPO、vivo 等也各有开关。
 * 这不是 App 能自己打开的权限，只能在厂商那一侧同意一次。
 *
 * 所以这里给用户指一条真能走通的路：先试厂商语音助手（授权入口就在它里面），
 * 退一步打开系统的「语音输入」设置页；两条都走不通才返回 false，由调用方改口
 * 引导用户走云端转写。
 *
 * manifest 的 &lt;queries&gt; 里必须声明下面这些包名和 intent：targetSdk>=30 的
 * 包可见性过滤会让 getLaunchIntentForPackage 与隐式 Intent 全部解析不到。
 */
public final class VoiceAuth {

    /** 工具类，不实例化：只有「按顺序试几个入口」这一个动作。 */
    private VoiceAuth() {}

    /**
     * 各厂商语音助手的包名，按顺序尝试。
     *
     * 顺序不代表重要性——同一厂商的旧版/新版包名不同（OPPO 的 coloros / heytap，
     * vivo 的 voiceassist / ai），都列上，谁在就用谁。设备上没有的会自然跳过。
     */
    private static final String[] ASSISTANTS = {
            "com.miui.voiceassist",            // 小米 / 澎湃 OS
            "com.heytap.speechassist",         // OPPO / 一加（新版）
            "com.coloros.speechassist",        // OPPO（ColorOS 旧版）
            "com.vivo.voiceassist",            // vivo
            "com.vivo.ai",                     // vivo（旧版）
            "com.huawei.vassistant",           // 华为
            "com.hihonor.vassistant",          // 荣耀
            "com.meizu.voiceassist",           // 魅族
            "com.samsung.android.bixby.agent", // 三星
    };

    /** 系统「语音输入」设置页（Voice input & output settings）。 */
    private static final String ACTION_VOICE_INPUT_SETTINGS = "android.settings.VOICE_INPUT_SETTINGS";

    /**
     * 小米识别服务自己的授权弹窗（实测 dumpsys：导出，带这个 action 的 intent-filter）。
     * adb `am start -a com.xiaomi.mibrain.speech.cta` 验证过能被外部拉起来——
     * 与其让用户去小爱同学里自己翻，不如直接把该翻的那一页拉到他面前。
     */
    private static final String XIAOMI_ASR_PKG = "com.xiaomi.mibrain.speech";
    private static final String XIAOMI_ASR_CTA = "com.xiaomi.mibrain.speech.cta";

    /**
     * 打开最可能是「识别放行开关」的地方。
     *
     * @return 是否成功打开了某个界面。false 说明这台设备上一条都没找到，
     *         调用方应该改为引导用户开云端转写。
     */
    public static boolean open(Context c) {
        // 1) 小米：直接拉识别服务自带的授权弹窗
        try {
            Intent cta = new Intent(XIAOMI_ASR_CTA);
            cta.setPackage(XIAOMI_ASR_PKG);
            cta.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(cta);
            return true;
        } catch (Throwable ignored) {
        }
        // 2) 厂商语音助手主页
        PackageManager pm = c.getPackageManager();
        for (String pkg : ASSISTANTS) {
            try {
                Intent it = pm.getLaunchIntentForPackage(pkg);
                if (it == null) continue;
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(it);
                return true;
            } catch (Throwable ignored) {
                // 包在但起不来（被禁用/被冻结）就继续试下一个
            }
        }
        // 3) 系统语音输入设置页兜底
        try {
            Intent it = new Intent(ACTION_VOICE_INPUT_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(it);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }
}