package com.lamireuxp.classroom;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 待办提醒：AlarmManager 排闹钟 + 到点发通知。
 *
 * 为什么用 setAndAllowWhileIdle 而不是 setExactAndAllowWhileIdle：
 * 精确闹钟从 API 31 起要 SCHEDULE_EXACT_ALARM，而 Android 14 之后这个权限对
 * 新装应用**默认是拒绝的**，得引导用户去系统设置里单独开。为了一个「作业快到期了」
 * 的提醒去要走一遍权限流程不值得，而且被拒之后提醒会彻底不响——比晚几分钟糟得多。
 * setAndAllowWhileIdle 不需要任何权限，且**允许在 Doze 里触发**（普通 set() 在 Doze
 * 期间不响，可能被压到用户下一次拿起手机），代价只是系统可能把它挪后几分钟。
 */
public final class Reminders {

    /** 通知渠道 id。渠道一旦建好，其重要性就只能由用户改，所以创建时要选对。 */
    private static final String CHANNEL_ID = "todo_reminders";
    private static final String ACTION_FIRE = "com.lamireuxp.classroom.REMIND";

    /** 工具类，不实例化。 */
    private Reminders() {}

    // ---------------- 排 / 取消 ----------------

    /**
     * 给一条待办排提醒。以下情况视为「不该响」，顺手把已有的闹钟撤掉：
     * 已完成、提醒时间为 0（没设）、时间已经过去。
     *
     * 每次都先 cancel 再 set：改过时间的旧闹钟不会被新的覆盖掉——
     * PendingIntent 相同时系统会替换，但提醒时间可能被改到「之后又改回来」，
     * 显式撤一次最省心。
     */
    public static void schedule(Context c, Db.Todo t) {
        if (t == null) return;
        cancel(c, t.id);
        if (t.completed || t.remindAt <= 0) return;
        if (t.remindAt <= System.currentTimeMillis()) return;

        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 23) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.remindAt, firePending(c, t.id));
        } else {
            am.set(AlarmManager.RTC_WAKEUP, t.remindAt, firePending(c, t.id));
        }
    }

    /**
     * 撤销一条待办的提醒，并把可能已经发出去的通知一并收掉。
     * 完成 / 删除 / 改时间都要走这里——只撤闹钟不撤通知的话，用户勾掉了待办，
     * 通知栏里那条还挂着，点进去是一条已经不存在的任务。
     */
    public static void cancel(Context c, String todoId) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            PendingIntent pi = firePending(c, todoId);
            am.cancel(pi);
            pi.cancel();
        }
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(reqCode(todoId));
    }

    /**
     * 按库里的数据重排所有提醒。开机、系统时间被改、应用升级后都要调一次——
     * AlarmManager 的闹钟**不跨重启**，不重排的话重启之后所有提醒静默失效。
     *
     * 另外国产 ROM（MIUI / ColorOS 等）在被「强制停止」时会把应用的闹钟一起清掉，
     * 且不会有任何提示。所以主页面每次启动也顺手重排一遍：一次查询加几次 set 而已，
     * 换来的是「提醒到底还活着吗」这件事不用用户操心。
     */
    public static void rescheduleAll(Context c) {
        Db db = Db.get(c);
        for (Db.Todo t : db.pendingReminders(System.currentTimeMillis())) {
            schedule(c, t);
        }
    }

    /**
     * 撤掉某门课下所有待办的提醒。删课程之前必须调——
     * 那些待办马上就不存在了，留着闹钟只会在到点时弹出查不到数据的空提醒。
     */
    public static void cancelCourse(Context c, String courseId) {
        for (Db.Todo t : Db.get(c).todos(courseId)) cancel(c, t.id);
    }

    // ---------------- 到点 ----------------

    /**
     * 闹钟回调：取回待办并发通知。由 {@link ReminderReceiver} 调用。
     *
     * 触发时重新查一次库（而不是把标题塞进 Intent）：闹钟可能在待办被删掉之后
     * 才响，用 Intent 里的旧快照会弹出一条早已不存在的任务。
     */
    static void fire(Context c, String todoId) {
        if (todoId == null) return;
        Db db = Db.get(c);
        Db.Todo t = db.todo(todoId);
        if (t == null || t.completed) return;      // 已被删掉或已完成 → 不打扰

        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannel(c, nm);
        // API 33+ 用户可以在系统里关掉通知权限。关了就安静地不响——
        // 这时候去弹「请允许通知」反而是在提醒时间点骚扰用户，权限引导放在设置提醒那一刻做。
        if (!canNotify(c)) return;

        Db.Course course = db.course(t.courseId);
        String courseName = (course == null || course.name == null || course.name.length() == 0)
                ? "待办" : course.name;

        Intent open = new Intent(c, CourseActivity.class);
        open.putExtra("courseId", t.courseId);
        open.putExtra("tab", "todos");             // 直接落在待办页签上
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent tap = PendingIntent.getActivity(c, reqCode(todoId), open, piFlags());

        String title = (t.title == null || t.title.length() == 0) ? "待办提醒" : t.title;
        StringBuilder body = new StringBuilder(courseName);
        if (t.due != null && t.due.length() > 0) body.append(" · 截止 ").append(t.due);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(c, CHANNEL_ID)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(body.toString())
                .setAutoCancel(true)
                .setContentIntent(tap)
                .setShowWhen(true);
        nm.notify(reqCode(todoId), b.build());
    }

    // ---------------- 权限 / 渠道 ----------------

    /** 现在能不能发通知（API 33 起是运行时权限；低版本默认可以）。 */
    public static boolean canNotify(Context c) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 渠道只需要建一次；已存在时这个调用是空操作，不会覆盖用户改过的重要性。 */
    private static void ensureChannel(Context c, NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "待办提醒",
                // HIGH 才会浮在屏幕上（heads-up）。这是用户自己设的到点提醒，
                // 悄无声息地躺在通知栏里等于没提醒。
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("待办到点时的提醒");
        nm.createNotificationChannel(ch);
    }

    private static Intent fireIntent(Context c, String todoId) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(ACTION_FIRE);
        i.putExtra("todoId", todoId);
        return i;
    }

    /**
     * 到点广播用的 PendingIntent。排闹钟和撤闹钟**必须**拿到等价的那一个
     * （同 action、同 requestCode、同组件），否则撤的是另一个对象，闹钟照样会响。
     */
    private static PendingIntent firePending(Context c, String todoId) {
        return PendingIntent.getBroadcast(c, reqCode(todoId), fireIntent(c, todoId), piFlags());
    }

    /**
     * 通知 id / PendingIntent requestCode 都从待办 id 派生，一条待办固定对应一个号码——
     * 这样重复排、撤、发通知都作用在同一个对象上，不会堆出一串重复提醒。
     * hashCode 理论上可能撞号，但撞了最多是两条待办的提醒互相覆盖，代价可接受；
     * 换成持久化的自增序号要另外建表，不划算。
     */
    private static int reqCode(String todoId) {
        return todoId == null ? 0 : (todoId.hashCode() & 0x7fffffff);
    }

    /** FLAG_IMMUTABLE 是 API 23 才有的；minSdk 22 上要退回去不给这个 flag。 */
    private static int piFlags() {
        return Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }
}