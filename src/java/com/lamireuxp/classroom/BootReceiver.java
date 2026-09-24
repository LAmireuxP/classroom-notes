package com.lamireuxp.classroom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机后把提醒重新排一遍。
 *
 * AlarmManager 里的闹钟**不跨重启**——重启（或系统时钟被改）之后全都消失，
 * 而且不会有任何报错。不在这里重排，用户设的提醒就静默失效，
 * 表现是「设置的时候明明开了提醒，后来一次都没响过」。
 *
 * 只认 BOOT_COMPLETED：这个广播是系统受保护的，第三方应用发不出来，
 * 所以这里没有伪造触发的风险。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        Reminders.rescheduleAll(c);
    }
}