package com.lamireuxp.classroom;

import java.util.Calendar;

/** 日期工具。 */
public final class Dates {

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

        public static String clock(long ms) {
        long total = ms / 1000;
        long m = total / 60;
        long s = total % 60;
        return String.format("%02d:%02d", m, s);
    }
}