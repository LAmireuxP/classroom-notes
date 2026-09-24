package com.lamireuxp.classroom;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 回收站 —— 删除的东西在这里等着，可以恢复，也可以彻底清掉。
 *
 * 为什么要有这一页：此前所有删除都是 `DELETE`，确认框自己都写着「不可恢复」。
 * 误触一下「删除课程」就是一个学期的笔记连锅端走，而这类误操作在手机上非常容易发生
 * （列表行的删除按钮就在大拇指底下）。现在删除只写一个标记位，数据还在表里。
 *
 * 整门课删除时，它的笔记与待办用的是同一个删除时间戳（见 Db.deleteCourse），
 * 所以恢复课程能把它们原样凑回来；而用户此前单独删过的笔记不会跟着复活——
 * 那一条的时间戳不同，会继续留在回收站里。
 */
public class TrashActivity extends Activity {

    private Db db;
    private LinearLayout content;
    /** 上次渲染时的深浅状态，用来判断从别处回来要不要重新着色。 */
    private boolean renderedDark;

    @Override
    protected void onCreate(Bundle b) {
        // 必须在 super.onCreate 之前：应用主题资源（窗口背景 / 状态栏 / 对话框默认色）
        setTheme(Prefs.isDark(this) ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(b);
        db = Db.get(this);
        applyWindowTheme();
        buildUi();
    }

    /** 窗口层（状态栏 / 导航栏 / 图标明暗）。 */
    private void applyWindowTheme() {
        Ui.applyWindowTheme(this);
        renderedDark = Ui.isDark(this);
    }

    @Override
    /** 跟随系统模式下系统切深浅色时自己重绘（manifest 声明了 uiMode，系统不会重建）。 */
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        if (Prefs.THEME_SYSTEM.equals(Prefs.themeMode(this))) {
            applyWindowTheme();
            buildUi();
            render();
        }
    }

    @Override
    /** 回来时重画：内容可能刚在别处被恢复或删掉。 */
    protected void onResume() {
        super.onResume();
        if (renderedDark != Ui.isDark(this)) {
            applyWindowTheme();
            buildUi();
        }
        render();
    }

    // ================== UI ==================

    private void buildUi() {
        LinearLayout page = Ui.column(this);
        page.setBackgroundColor(Ui.surface(this));

        View bar = topBar();
        Ui.padStatusBar(this, bar);
        page.addView(bar);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content = Ui.column(this);
        int padH = Ui.dp(this, 16);
        content.setPadding(padH, Ui.v(this, 4), padH, Ui.dp(this, 24));
        sv.addView(content);
        page.addView(sv);

        setContentView(page);
    }

    /**
     * 顶栏：返回 + 标题 + 「清空」。清空放在这里而不是列表底部：
     * 它是不可恢复的操作，摆在页头、离误触的拇指最远。
     */
    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.surface(this));
        bar.setPadding(Ui.dp(this, 6), Ui.v(this, 10), Ui.dp(this, 12), Ui.v(this, 10));

        LinearLayout back = Icons.iconButton(this, R.drawable.ic_back, 42,
                Ui.onSurfaceVariant(this));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        bar.addView(back);

        TextView title = Ui.text(this, "回收站", Ui.T_HEADLINE, Ui.onSurface(this), true);
        title.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams tp = (LinearLayout.LayoutParams) title.getLayoutParams();
        tp.leftMargin = Ui.dp(this, 8);
        bar.addView(title);

        // 回收站空的时候「清空」没有意义，不显示
        if (db.trashCount() > 0) {
            TextView empty = Ui.textButton(this, "清空");
            empty.setTextColor(Ui.error(this));
            empty.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { confirmEmpty(); }
            });
            bar.addView(empty);
        }

        return bar;
    }

    // ================== 渲染 ==================

    private void render() {
        content.removeAllViews();

        List<Db.TrashItem> items = db.trash();
        if (items.isEmpty()) {
            content.addView(emptyState());
            return;
        }

        TextView count = Ui.text(this, items.size() + " 项待清理", Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Ui.v(this, 10);
        count.setLayoutParams(clp);
        content.addView(count);

        TextView hint = Ui.text(this,
                "删除的内容会一直留在这里，直到你恢复或彻底清空",
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Ui.v(this, 8);
        hint.setLayoutParams(hlp);
        content.addView(hint);

        for (Db.TrashItem it : items) content.addView(trashRow(it));
    }

    /** 一行回收站内容：类型图标 + 标题 + 副标题 + 删除时刻 + 恢复 / 彻底删除。 */
    private View trashRow(final Db.TrashItem it) {
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.v(this, 4);
        row.setLayoutParams(lp);
        int ph = Ui.dp(this, 12), padV = Ui.v(this, 12);
        row.setPadding(ph, padV, Ui.dp(this, 8), padV);
        row.setBackground(Ui.round(this, Ui.surfaceContainer(this), Color.TRANSPARENT, Ui.R_M, 0));

        int icon = Db.TrashItem.KIND_COURSE.equals(it.kind) ? R.drawable.ic_folder
                : (Db.TrashItem.KIND_NOTE.equals(it.kind) ? R.drawable.ic_md : R.drawable.ic_list);
        row.addView(Icons.icon(this, icon, Ui.onSurfaceVariant(this), 20));

        LinearLayout mid = Ui.column(this);
        LinearLayout.LayoutParams mp = Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = Ui.dp(this, 12);
        mid.setLayoutParams(mp);

        TextView title = Ui.text(this, nz(it.title), Ui.T_BODY + 1, Ui.onSurface(this), false);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(title);

        TextView sub = Ui.text(this, it.subtitle + " · 删除于 " + Dates.stamp(it.deletedAt),
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.v(this, 2);
        sub.setLayoutParams(sp);
        mid.addView(sub);
        row.addView(mid);

        LinearLayout restore = Icons.iconButton(this, R.drawable.ic_import, 40,
                Ui.primary(this));
        restore.setContentDescription("恢复");
        restore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { restore(it); }
        });
        row.addView(restore);

        LinearLayout purge = Icons.iconButton(this, R.drawable.ic_trash, 40, Ui.error(this));
        purge.setContentDescription("彻底删除");
        purge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmPurge(it); }
        });
        row.addView(purge);

        return row;
    }

    private View emptyState() {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 32);
        box.setPadding(p, Ui.v(this, 56), p, Ui.v(this, 56));

        LinearLayout circle = new LinearLayout(this);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(Ui.round(this, Ui.surfaceHigh(this), Color.TRANSPARENT,
                Ui.R_FULL, 0));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                Ui.dp(this, 72), Ui.dp(this, 72));
        clp.gravity = Gravity.CENTER;
        circle.setLayoutParams(clp);
        circle.addView(Icons.icon(this, R.drawable.ic_check, Ui.onSurfaceVariant(this), 32));
        box.addView(circle);

        TextView title = Ui.text(this, "回收站是空的", Ui.T_TITLE, Ui.onSurface(this), true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.v(this, 16);
        title.setLayoutParams(tp);
        box.addView(title);

        TextView hint = Ui.text(this, "删掉的课程、笔记和待办会先放到这里，可以再恢复",
                Ui.T_BODY, Ui.onSurfaceVariant(this), false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.v(this, 6);
        hint.setLayoutParams(hp);
        box.addView(hint);

        return box;
    }

    // ================== 动作 ==================

    /**
     * 恢复。恢复待办时顺手重排它的提醒：待办在回收站里躺着的这段时间，
     * 闹钟早被撤掉了（见各处的 Reminders.cancel），不重排的话恢复之后它永远不会响。
     * 提醒时刻已经过去的，Reminders.schedule 内部会跳过，不会立刻炸一条通知出来。
     *
     * 另外：笔记 / 待办所属的课程若也在回收站里，要**连课程一起恢复**。
     * 否则这条内容恢复后无处可去——课程列表按 deleted_at 把它藏了，搜索结果点进去
     * 又因为课程取不到而直接关页面，等于恢复了却还是找不到。文案里说明这一点，
     * 免得用户以为「我只恢复了一条笔记，怎么整门课都回来了」。
     */
    private void restore(final Db.TrashItem it) {
        if (Db.TrashItem.KIND_COURSE.equals(it.kind)) {
            db.restoreCourse(it.id);
            // 课程里的待办也一起回来了，它们的提醒同样要重排
            for (Db.Todo t : db.todos(it.id)) Reminders.schedule(this, t);
            Tip.success(this, "课程已恢复，笔记与待办一并回来了");
        } else {
            boolean courseCameBack = false;
            if (db.isCourseTrashed(it.courseId)) {
                db.restoreCourse(it.courseId);
                for (Db.Todo t : db.todos(it.courseId)) Reminders.schedule(this, t);
                courseCameBack = true;
            }
            if (Db.TrashItem.KIND_NOTE.equals(it.kind)) {
                db.restoreNote(it.id);
            } else {
                db.restoreTodo(it.id);
                Db.Todo t = db.todo(it.id);
                if (t != null) Reminders.schedule(this, t);
            }
            Tip.success(this, courseCameBack
                    ? "已恢复（它所属的课程也一起回来了）"
                    : "已恢复");
        }
        render();
        buildUi();       // 顶栏的「清空」按钮要跟着出现或消失
    }

    /** 彻底删除前确认：这一步真的不可恢复，文案必须说清。 */
    private void confirmPurge(final Db.TrashItem it) {
        String what = Db.TrashItem.KIND_COURSE.equals(it.kind) ? "课程（含它的笔记与待办）"
                : (Db.TrashItem.KIND_NOTE.equals(it.kind) ? "笔记" : "待办");
        Dialogs.confirm(this, "彻底删除",
                "将永久删除这条" + what + "，无法恢复。确定继续？",
                "彻底删除", new Runnable() {
                    @Override public void run() {
                        if (Db.TrashItem.KIND_COURSE.equals(it.kind)) {
                            // 先把待办的提醒撤掉：那些待办马上就不存在了
                            Reminders.cancelCourse(TrashActivity.this, it.id);
                            db.purgeCourse(it.id);
                        } else if (Db.TrashItem.KIND_NOTE.equals(it.kind)) {
                            db.purgeNote(it.id);
                        } else {
                            Reminders.cancel(TrashActivity.this, it.id);
                            db.purgeTodo(it.id);
                        }
                        render();
                        buildUi();
                        Tip.success(TrashActivity.this, "已永久删除");
                    }
                });
    }

    /** 清空回收站：只清当前在回收站里的，正常数据不受影响。 */
    private void confirmEmpty() {
        final int n = db.trashCount();
        Dialogs.confirm(this, "清空回收站",
                "将永久删除回收站里的 " + n + " 项内容，无法恢复。确定继续？",
                "清空", new Runnable() {
                    @Override public void run() {
                        // 回收站里的待办可能还挂着闹钟，先全部撤掉再清数据
                        for (Db.TrashItem it : db.trash()) {
                            if (Db.TrashItem.KIND_COURSE.equals(it.kind)) {
                                Reminders.cancelCourse(TrashActivity.this, it.id);
                            } else if (Db.TrashItem.KIND_TODO.equals(it.kind)) {
                                Reminders.cancel(TrashActivity.this, it.id);
                            }
                        }
                        db.emptyTrash();
                        render();
                        buildUi();
                        Tip.success(TrashActivity.this, "回收站已清空");
                    }
                });
    }

    /** null 安全取字符串：库里空字段读出来是 null，界面一律按空串处理。 */
    private static String nz(String s) { return s == null ? "" : s; }
}