package com.lamireuxp.classroom;

import java.util.ArrayList;
import java.util.List;

/** 关键词提取：AI 不可用时的本地兜底方案（改进自原版）。 */
public final class Extract {

    /** 工具类，不实例化。 */
    private Extract() {}

    private static final String[] KEYWORDS = {
            "重要", "重点", "关键", "注意", "记住", "总结", "核心",
            "必须", "一定要", "考点", "考试", "强调", "特别", "尤其",
            "需要掌握", "一定记住", "定义", "公式", "结论", "因此", "所以"
    };

    /**
     * 按关键词与句式从正文里挑出像重点的句子。
     *
     * 这是「没有 AI 也要能用」的兜底：录音转写出文字后先把重点标出来，
     * 用户配好 AI 再跑一次总结即可。宁可多挑几句，也不要漏掉考点。
     */
    public static List<String> keyPoints(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.trim().length() == 0) return out;
        String[] sentences = text.split("[。！？?!\\n；;]+");
        List<int[]> scored = new ArrayList<int[]>(); // {score, index}
        List<String> kept = new ArrayList<String>();
        for (String raw : sentences) {
            String s = raw.trim();
            if (s.length() <= 4) continue;
            int score = 0;
            for (String k : KEYWORDS) {
                if (s.contains(k)) score += 3;
            }
            if (s.length() > 15 && s.length() < 80) score += 1;
            kept.add(s);
            scored.add(new int[]{score, kept.size() - 1});
        }
        // 按分数降序（简单冒泡，句子数量有限）
        for (int i = 0; i < scored.size(); i++) {
            for (int j = i + 1; j < scored.size(); j++) {
                if (scored.get(j)[0] > scored.get(i)[0]) {
                    int[] t = scored.get(i);
                    scored.set(i, scored.get(j));
                    scored.set(j, t);
                }
            }
        }
        for (int[] item : scored) {
            if (out.size() >= 5) break;
            if (item[0] >= 1) out.add(kept.get(item[1]));
        }
        return out;
    }
}