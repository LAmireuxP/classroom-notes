package com.lamireuxp.classroom;

import java.util.Calendar;

/** 日期工具。 */
public final class Dates {

    /** 工具类，不实例化：只做日期格式化。 */
    private Dates() {}

    public static String today() {
        Calendar c = Calendar.getInstance();
        return String.format("%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 2026-09-18 → 09/18 */
    public static String shortDate(String iso) {
        if (iso == null || iso.length() < 10) return iso == null ? "" : iso;
        String[] p = iso.split("-");
        if (p.length < 3) return iso;
        return p[1] + "/" + p[2];
    }

    /**
     * 今天往后（或往前）n 天的 ISO 日期，n 可为负。
     *
     * 用日历加减而不是「今天 + n 毫秒」：夏令时、闰秒那类日子一天的毫秒数不是常量，
     * 直接加 86400000 会偏一天。Calendar 走的是日历语义，不会踩这个坑。
     */
    public static String plusDays(int n) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, n);
        return String.format("%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** epoch 毫秒 → "HH:mm"（提醒时间在列表里的显示）。 */
    public static String hm(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** epoch 毫秒 → "MM/DD HH:mm"（提醒时间的完整显示）。 */
    public static String stamp(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format("%02d/%02d %02d:%02d",
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /**
     * 把「年月日 + 时分」拼成 epoch 毫秒。用于把日期选择器与时间选择器的结果合成提醒时刻。
     * 秒与毫秒清零：对齐到分钟，方便显示也便于比较。
     */
    public static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(year, month, day, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 从 epoch 毫秒里取出年月日时分，写回 out（长度至少 5：年、月、日、时、分）。 */
    public static void split(long ms, int[] out) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        out[0] = c.get(Calendar.YEAR);
        out[1] = c.get(Calendar.MONTH);
        out[2] = c.get(Calendar.DAY_OF_MONTH);
        out[3] = c.get(Calendar.HOUR_OF_DAY);
        out[4] = c.get(Calendar.MINUTE);
    }

        public static String clock(long ms) {
        long total = ms / 1000;
        long m = total / 60;
        long s = total % 60;
        return String.format("%02d:%02d", m, s);
    }
}