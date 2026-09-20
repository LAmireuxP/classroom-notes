package com.lamireuxp.classroom;

/** 唯一 ID 生成：时间戳 + 随机串。 */
public final class Id {

    private Id() {}

    public static String gen() {
        return Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString((int) (Math.random() * 0x7fffffff), 36);
    }
}