package com.lamireuxp.classroom;

import android.app.Activity;
import android.content.Intent;
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
 * 全部待办 —— 跨课程回答「我接下来要交什么」。
 *
 * 为什么单独做一页：首页那张统计卡只给出「做了多少」的进度，课程页的待办列表又各自为政，
 * 想知道「最近有什么事」得挨个课程点进去。这一页把未完成的待办按**截止日期**归到
 * 已过期 / 今天 / 明天 / 本周内 / 以后 / 无截止日期 六段，顺序就是该先做什么的顺序。
 *
 * 只列**未完成**的：已经勾掉的不是「要做的事」，混在里面只会稀释重点。
 */
public class AllTodosActivity extends Activity {

    private Db db;
    private LinearLayout content;
    private ScrollView scroller;
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
    /** 回来时重画：待办可能在课程页被勾掉或删掉了，主题也可能改过。 */
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

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content = Ui.column(this);
        int padH = Ui.dp(this, 16);
        // 底部不留 FAB 余量：这一页没有新建入口（建待办必须先有课程归属）
        content.setPadding(padH, Ui.v(this, 4), padH, Ui.dp(this, 24));
        scroller.addView(content);
        page.addView(scroller);

        setContentView(page);
    }

    /** 顶栏：返回 + 标题。没有 FAB——新建待办必须先确定属于哪门课，入口留在课程页。 */
    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.surface(this));
        bar.setPadding(Ui.dp(this, 6), Ui.v(this, 10), Ui.dp(this, 16), Ui.v(this, 10));

        LinearLayout back = Icons.iconButton(this, R.drawable.ic_back, 42,
                Ui.onSurfaceVariant(this));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        bar.addView(back);

        TextView title = Ui.text(this, "全部待办", Ui.T_HEADLINE, Ui.onSurface(this), true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = Ui.dp(this, 8);
        title.setLayoutParams(tp);
        bar.addView(title);

        return bar;
    }

    // ================== 渲染 ==================

    private void render() {
        content.removeAllViews();

        List<Db.TodoHit> hits = db.openTodos();
        if (hits.isEmpty()) {
            content.addView(emptyState());
            return;
        }

        TextView count = Ui.text(this, hits.size() + " 项未完成", Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Ui.v(this, 10);
        count.setLayoutParams(clp);
        content.addView(count);

        // 按优先级的三段进度（全部课程口径，和首页那张卡同一个 Ui.priorityProgress）
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.bottomMargin = Ui.v(this, 6);
        View progress = Ui.priorityProgress(this, db.todoByPriority());
        progress.setLayoutParams(plp);
        content.addView(progress);

        // 分段：顺序就是「该先做什么」的顺序
        String today = Dates.today();
        String tomorrow = Dates.plusDays(1);
        String weekEnd = Dates.plusDays(7);
        // 已开始的那一段：null 表示还没输出过标题
        String current = null;
        for (Db.TodoHit h : hits) {
            String bucket = bucketOf(nz(h.todo.due), today, tomorrow, weekEnd);
            if (!bucket.equals(current)) {
                current = bucket;
                content.addView(sectionLabel(bucket));
            }
            content.addView(todoRow(h));
        }
    }

    /**
     * 一条待办落在哪一段。due 是 ISO 日期文本，字典序即时间序，可以直接比较大小。
     * 空 due 单独成段并排在最后：它没有时间压力，不该混进「今天」里制造焦虑。
     */
    private static String bucketOf(String due, String today, String tomorrow, String weekEnd) {
        if (due.length() == 0) return "无截止日期";
        if (due.compareTo(today) < 0) return "已过期";
        if (due.equals(today)) return "今天";
        if (due.equals(tomorrow)) return "明天";
        if (due.compareTo(weekEnd) <= 0) return "本周内";
        return "以后";
    }

    /** 分段标题（带该段条数，让「已过期 3 项」这种信息一眼可见）。 */
    private View sectionLabel(String text) {
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.v(this, 16);
        rlp.bottomMargin = Ui.v(this, 4);
        row.setLayoutParams(rlp);

        boolean overdue = "已过期".equals(text);
        TextView tv = Ui.text(this, text, Ui.T_LABEL,
                overdue ? Ui.error(this) : Ui.onSurfaceVariant(this), true);
        row.addView(tv);
        return row;
    }

    /**
     * 一行待办：课程色点 + 勾选框 + 标题 + 「课程名 · 优先级 · 截止 · 提醒」+ 删除。
     * 色点用**课程色**：一屏里混着好几门课的任务，颜色是最快的归属线索。
     */
    private View todoRow(final Db.TodoHit h) {
        final Db.Todo t = h.todo;

        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.v(this, 2);
        row.setLayoutParams(lp);
        int ph = Ui.dp(this, 10), padV = Ui.v(this, 12);
        row.setPadding(ph, padV, Ui.dp(this, 6), padV);
        row.setMinimumHeight(Ui.vMin(this, 56));
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_M));

        // 课程色点
        View dot = new View(this);
        dot.setBackground(Ui.circle(Ui.parseColor(h.courseColor, Ui.primary(this)),
                Ui.dp(this, 8)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 8), Ui.dp(this, 8));
        dlp.rightMargin = Ui.dp(this, 12);
        dot.setLayoutParams(dlp);
        row.addView(dot);

        // MD3 圆形勾选框（与课程页同一套）：点一下即完成，并撤掉它的提醒
        final LinearLayout checkBox = Ui.row(this);
        checkBox.setGravity(Gravity.CENTER);
        final int size = Ui.dp(this, 24);
        checkBox.setLayoutParams(Ui.lp(size, size));
        checkBox.setBackground(Ui.round(this, Color.TRANSPARENT, Ui.outline(this),
                Ui.R_FULL, 1.5f));
        checkBox.setClickable(true);
        checkBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                db.setTodoCompleted(t.id, true);
                // 勾掉就不该再提醒：不撤的话到点还会弹一条已经做完的任务
                Reminders.cancel(AllTodosActivity.this, t.id);
                render();
                Tip.success(AllTodosActivity.this, "已完成");
            }
        });
        row.addView(checkBox);

        LinearLayout mid = Ui.column(this);
        LinearLayout.LayoutParams mp = Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = Ui.dp(this, 12);
        mid.setLayoutParams(mp);

        TextView title = Ui.text(this, nz(t.title), Ui.T_BODY + 1, Ui.onSurface(this), false);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(title);

        StringBuilder sub = new StringBuilder();
        sub.append(h.courseName == null || h.courseName.length() == 0 ? "未分类" : h.courseName);
        sub.append(" · ").append(Ui.priorityName(t.priority));
        if (nz(t.due).length() > 0) sub.append(" · 截止 ").append(Dates.shortDate(t.due));
        if (t.remindAt > 0) sub.append(" · 提醒 ").append(Dates.stamp(t.remindAt));
        TextView subTv = Ui.text(this, sub.toString(), Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.v(this, 2);
        subTv.setLayoutParams(sp);
        mid.addView(subTv);
        row.addView(mid);

        LinearLayout del = Icons.iconButton(this, R.drawable.ic_trash, 40, Ui.outline(this));
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                db.deleteTodo(t.id);
                Reminders.cancel(AllTodosActivity.this, t.id);   // 删了就别再提醒
                render();
                Tip.success(AllTodosActivity.this, "待办已移入回收站");
            }
        });
        row.addView(del);

        // 整行点击进课程页的待办页签：色点和课程名都在说「这条属于某门课」，
        // 点它就该去那门课看上下文，而不是在这里就地编辑（这里没有课程上下文）
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent it = new Intent(AllTodosActivity.this, CourseActivity.class);
                it.putExtra("courseId", t.courseId);
                it.putExtra("tab", "todos");
                startActivity(it);
            }
        });
        return row;
    }

    /** 空状态：没有未完成待办是好事，文案要给出下一步而不是一句「暂无」。 */
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

        TextView title = Ui.text(this, "没有未完成的待办", Ui.T_TITLE, Ui.onSurface(this), true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.v(this, 16);
        title.setLayoutParams(tp);
        box.addView(title);

        TextView hint = Ui.text(this, "在课程页的「待办」里点右下角加号添加", Ui.T_BODY,
                Ui.onSurfaceVariant(this), false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.v(this, 6);
        hint.setLayoutParams(hp);
        box.addView(hint);

        return box;
    }

    /** null 安全取字符串：库里空字段读出来是 null，界面一律按空串处理。 */
    private static String nz(String s) { return s == null ? "" : s; }
}