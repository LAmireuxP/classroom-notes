package com.lamireuxp.classroom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 提醒闹钟的落点。AlarmManager 到点后发到这里，再交给 {@link Reminders#fire}。
 *
 * 只做转发、不在这里写业务：receiver 的 onReceive 在主线程上、只有约 10 秒预算，
 * 而 fire() 里要查库、发通知，逻辑集中在 Reminders 里也便于别处（如开机重排）复用。
 */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null) return;
        Reminders.fire(c, i.getStringExtra("todoId"));
    }
}