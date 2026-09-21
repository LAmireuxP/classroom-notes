package com.lamireuxp.classroom;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 课程详情 —— Material Design 3。
 *
 * 结构：Top App Bar（含返回）/ Segmented Button 切换 / 笔记与待办列表 / 录音面板
 *
 * 设计取舍（依据 impeccable operate.md）：
 *  - 用 MD3 Segmented Button 代替自造 tab
 *  - 列表行用分隔线而非堆叠卡片（避免"卡片套卡片"反模式）
 *  - 强调色只用于主操作与选中态
 */
public class CourseActivity extends Activity implements Dialogs.DialogHost {

    private static final int REQ_MIC = 201;

    private Db db;
    private String courseId;
    private Db.Course course;

    private LinearLayout content;
    private LinearLayout tabBar;
    private LinearLayout recPanel;
    /** 页面根容器。加载条挂在这里而不是 content 上——content 会被 renderContent() 反复清空重建。 */
    private LinearLayout pageRoot;

    private String tab = "notes";
    private String query = "";
    private String expandedNoteId = null;
    private AlertDialog submitDialog;
    /** 厂商授权引导框正开着——同一次录音里连续报错只弹一次。 */
    private boolean consentGuideShowing;

    private SpeechSession speech;
    private Handler ticker;
    private Runnable tickTask;
    private TextView recTimerView;
    private TextView recTextView;
    /** 联网期间的常驻加载条（AI 总结 / 云转写）。 */
    private Loading.Handle loading;
    /** 录音脉冲的动画句柄。常驻循环动画，视图被移除时必须 cancel。 */
    private final java.util.List<android.animation.ObjectAnimator> recPulses =
            new java.util.ArrayList<android.animation.ObjectAnimator>();

    private void cancelRecPulses() {
        for (android.animation.ObjectAnimator a : recPulses) {
            try { a.cancel(); } catch (Throwable ignored) {}
        }
        recPulses.clear();
    }

    @Override
    protected void onCreate(Bundle b) {
        // 必须在 super.onCreate 之前：应用主题资源
        setTheme(Prefs.isDark(this) ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(b);
        db = Db.get(this);
        courseId = getIntent().getStringExtra("courseId");
        course = db.course(courseId);
        if (course == null) { finish(); return; }
        applyWindowTheme();
        buildUi();
    }

    /** 窗口层（状态栏 / 导航栏 / 图标明暗）。 */
    private void applyWindowTheme() {
        Ui.applyWindowTheme(this);
    }

    @Override
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        // uiMode 在 manifest 的 configChanges 里声明过，系统切深浅色时不会自动重建。
        // 跟随系统模式下必须自己重建；正在录音时先不动，免得把录音会话弄丢。
        if (Prefs.THEME_SYSTEM.equals(Prefs.themeMode(this)) && !isRecording()) recreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isRecording()) renderContent();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speech != null) speech.cancel();
        stopTicker();
        hideLoading();
        cancelRecPulses();
    }

    /**
     * 显示常驻加载条。
     *
     * 原来是 Tip.show(...)——Tip 到点自动消失，之后 AI 那 120 秒（转写 180 秒）
     * 的超时窗口里界面完全静止，用户不知道是在跑还是卡死。
     * 注意必须在每次 renderContent() 之前 hideLoading()，否则视图被移除后
     * 无限动画还在跑。
     */
    private void showLoading(String text) {
        hideLoading();
        if (pageRoot == null) return;
        loading = Loading.show(this, text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = Ui.dp(this, 16);
        lp.setMargins(m, 0, m, Ui.dp(this, 16));
        loading.view.setLayoutParams(lp);
        pageRoot.addView(loading.view);
    }

    private void hideLoading() {
        if (loading != null) {
            loading.stop();
            loading = null;
        }
    }

    @Override
    public void setSubmitDialog(AlertDialog dlg) { this.submitDialog = dlg; }

    @Override
    public void onBackPressed() {
        if (isRecording()) {
            Dialogs.confirm(this, "正在录音",
                    "录音尚未保存，确定退出并结束录音？", "结束并退出",
                    new Runnable() {
                        @Override public void run() {
                            if (speech != null) speech.cancel();
                            CourseActivity.super.onBackPressed();
                        }
                    });
            return;
        }
        if (submitDialog != null && submitDialog.isShowing()) {
            submitDialog.dismiss();
            submitDialog = null;
            return;
        }
        super.onBackPressed();
    }

    // ================== UI ==================

    private void buildUi() {
        LinearLayout page = Ui.column(this);
        page.setBackgroundColor(Ui.surface(this));
        setContentView(page);
        pageRoot = page;

        // 顶栏 + 状态栏内边距
        View tb = topBar();
        tb.setPadding(tb.getPaddingLeft(),
                tb.getPaddingTop() + statusBarHeight(),
                tb.getPaddingRight(), tb.getPaddingBottom());
        page.addView(tb);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(sp);

        LinearLayout body = Ui.column(this);
        int bp = Ui.dp(this, 16);
        body.setPadding(bp, Ui.dp(this, 4), bp, Ui.dp(this, 32));
        sv.addView(body);
        page.addView(sv);

        // 搜索（带图标）
        LinearLayout searchBox = Ui.searchBarWithIcon(this, "在「" + course.name + "」中搜索",
                R.drawable.ic_search);
        EditText searchInput = Ui.searchInput(searchBox);
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                query = s.toString().trim();
                if ("notes".equals(tab) && !isRecording()) renderContent();
            }
        });
        body.addView(searchBox);

        tabBar = Ui.row(this);
        body.addView(tabBar);

        content = Ui.column(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.dp(this, 16);
        content.setLayoutParams(cp);
        body.addView(content);

        renderTabs();
        renderContent();
    }

    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.surface(this));
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));

        LinearLayout back = Icons.iconButton(this, R.drawable.ic_back, 42,
                Ui.onSurfaceVariant(this));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        bar.addView(back);

        LinearLayout titleBox = Ui.column(this);
        LinearLayout.LayoutParams tbp = Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tbp.leftMargin = Ui.dp(this, 8);
        titleBox.setLayoutParams(tbp);

        TextView title = Ui.text(this, course.name, Ui.T_HEADLINE, Ui.onSurface(this), true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBox.addView(title);

        if (course.teacher != null && course.teacher.length() > 0) {
            TextView sub = Ui.text(this, course.teacher, Ui.T_LABEL,
                    Ui.onSurfaceVariant(this), false);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = Ui.dp(this, 2);
            sub.setLayoutParams(sp);
            titleBox.addView(sub);
        }
        bar.addView(titleBox);

        // 编辑课程
        LinearLayout edit = Icons.iconButton(this, R.drawable.ic_edit, 42,
                Ui.onSurfaceVariant(this));
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editCourse(); }
        });
        bar.addView(edit);

        return bar;
    }

    private void editCourse() {
        Dialogs.form(this, "编辑课程",
                new String[]{"课程名称", "授课教师"},
                new String[]{"课程名称", "教师姓名"},
                new String[]{nz(course.name), nz(course.teacher)},
                new boolean[]{false, false},
                new Dialogs.OnSubmit() {
                    @Override public void onSubmit(EditText[] f) {
                        String name = f[0].getText().toString().trim();
                        if (name.length() == 0) { Tip.error(CourseActivity.this, "请填写课程名称"); return; }
                        db.saveCourse(course.id, name, f[1].getText().toString().trim(),
                                course.color);
                        course = db.course(courseId);
                        if (submitDialog != null) submitDialog.dismiss();
                        submitDialog = null;
                        buildUi();
                        Tip.success(CourseActivity.this, "课程已更新");
                    }
                });
    }

    // ================== Segmented Button ==================

    private void renderTabs() {
        tabBar.removeAllViews();
        tabBar.setBackground(Ui.round(this, Ui.surfaceContainer(this),
                Color.TRANSPARENT, Ui.R_FULL, 0));
        tabBar.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));

        tabBar.addView(segment("笔记", "notes", countNotes()));
        tabBar.addView(segment("待办", "todos", countOpenTodos()));
    }

    /** MD3 Segmented Button 的单个分段。 */
    private View segment(String label, final String key, int count) {
        boolean active = tab.equals(key);

        // Cal.com 的 nav-pill-group：外层是浅色分段容器，选中段是「容器里的一枚
        // 更亮的小 pill」，而不是整块主色实底。原来选中段直接铺 primary，
        // 是整个 App 里 primary 被滥用最明显的一处。
        // 亮/暗方向相反：浅色主题下「更亮」= surface_container_lowest（白），
        // 深色主题下「更亮」= surface_container_highest。这也是 MD3 用 surface
        // 分级表达海拔的用法。
        final int activeBg = Ui.isDark(this) ? Ui.surfaceHighest(this) : Ui.surfaceLowest(this);

        LinearLayout seg = Ui.row(this);
        seg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        seg.setLayoutParams(lp);
        int padV = Ui.dp(this, 10);
        seg.setPadding(0, padV, 0, padV);
        seg.setMinimumHeight(Ui.dp(this, 44));

        if (active) {
            seg.setBackground(Ui.ripple(this, activeBg, Ui.R_FULL));
            Ui.elevation(seg, 1f);          // 容器内的一点点浮起
        } else {
            seg.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_FULL));
        }
        seg.setClickable(true);
        Ui.pressScale(seg);

        TextView tv = Ui.text(this, label + "  " + count, Ui.T_BODY,
                active ? Ui.onSurface(this) : Ui.onSurfaceVariant(this), active);
        seg.addView(tv);

        seg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (isRecording()) return;
                if (tab.equals(key)) return;
                tab = key;
                expandedNoteId = null;
                renderTabs();
                renderContent();
            }
        });
        return seg;
    }

    private int countNotes() {
        int n = 0;
        for (Db.Note x : db.notes(courseId, null)) n++;
        return n;
    }

    private int countOpenTodos() {
        int n = 0;
        for (Db.Todo t : db.todos(courseId)) if (!t.completed) n++;
        return n;
    }

    private void renderContent() {
        content.removeAllViews();
        recPanel = null;
        cancelRecPulses();
        if ("notes".equals(tab)) renderNotes();
        else renderTodos();
    }

    // ================== 笔记 ==================

    private void renderNotes() {
        // 操作行：录音（neutral）+ 新建（filled）
        // 录音原来是 tonal（紫底），和 filled 的「新建笔记」并排就成了两个紫按钮抢焦点。
        // 一行里只留一个有色按钮，"录音" 降为中性但保留存在感。
        LinearLayout actions = Ui.row(this);
        actions.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40)));
        LinearLayout recBtn = Icons.iconTextButton(this, R.drawable.ic_mic, "录音", 4);
        recBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startRecordingFlow(); }
        });
        actions.addView(recBtn);
        actions.addView(Ui.spacer(this));

        LinearLayout addBtn = Icons.iconTextButton(this, R.drawable.ic_add, "新建笔记", 0);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { noteDialog(null); }
        });
        actions.addView(addBtn);

        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.bottomMargin = Ui.dp(this, 18);
        actions.setLayoutParams(ap);
        content.addView(actions);

        List<Db.Note> notes = db.notes(courseId, query.length() > 0 ? query : null);

        // 计数摘要
        TextView count = Ui.text(this, "共 " + notes.size() + " 条笔记", Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Ui.dp(this, 10);
        count.setLayoutParams(clp);
        content.addView(count);

        if (notes.isEmpty()) {
            content.addView(emptyState(R.drawable.ic_md,
                    query.length() > 0 ? "没有匹配的笔记" : "记录第一节课",
                    query.length() > 0 ? "试试换个关键词" : "点「新建笔记」写下课堂要点"));
            return;
        }
        for (Db.Note n : notes) content.addView(noteRow(n));
    }

    /**
     * 笔记行 —— 用平面列表行而非嵌套卡片。
     * 展开态只增加操作按钮，不改变容器层级。
     */
    private View noteRow(final Db.Note n) {
        final boolean expanded = n.id.equals(expandedNoteId);

        LinearLayout row = Ui.column(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(this, 4);
        row.setLayoutParams(rlp);
        row.setBackground(Ui.ripple(this, expanded ? Ui.surfaceContainer(this)
                : Color.TRANSPARENT, Ui.R_M));
        int pad = Ui.dp(this, 12);
        row.setPadding(pad, pad, pad, pad);

        // ---- 头行：置顶 + 标题 + 日期 ----
        LinearLayout head = Ui.row(this);

        if (n.pinned) {
            ImageView pin = Icons.icon(this, R.drawable.ic_pin, Ui.primary(this), 15);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 15), Ui.dp(this, 15));
            plp.rightMargin = Ui.dp(this, 7);
            pin.setLayoutParams(plp);
            head.addView(pin);
        }

        // 状态圆点（替代彩色 border-left —— 遵守 craft-floor 禁令）
        if (!n.pinned) {
            View dot = new View(this);
            dot.setBackground(Ui.circle(Ui.primary(this), Ui.dp(this, 7)));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 7), Ui.dp(this, 7));
            dlp.rightMargin = Ui.dp(this, 10);
            dot.setLayoutParams(dlp);
            head.addView(dot);
        }

        TextView title = Ui.text(this, nz(n.title), Ui.T_TITLE, Ui.onSurface(this), true);
        LinearLayout.LayoutParams tp = Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tp);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title);

        TextView date = Ui.text(this, Dates.shortDate(n.date), Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false);
        head.addView(date);

        // 展开指示箭头。原来完全没有这个标识，折叠态和展开态的头行长得一模一样，
        // 用户不知道这条笔记能点开。
        ImageView chevron = Icons.icon(this, R.drawable.ic_chevron_down,
                Ui.onSurfaceVariant(this), 18);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 18), Ui.dp(this, 18));
        chlp.leftMargin = Ui.dp(this, 6);
        chevron.setLayoutParams(chlp);
        chevron.setRotation(expanded ? 180f : 0f);
        head.addView(chevron);
        row.addView(head);

        // ---- 正文预览 / 全文 ----
        String text = nz(n.content);
        TextView bodyTv = Ui.text(this, expanded ? text : preview(text), Ui.T_BODY,
                Ui.onSurfaceVariant(this), false);
        // 行高 14sp + 5dp ≈ 1.57，对齐 Notion body-md 的 1.55。
        // 原来是 3dp（≈1.43），中文长段落读起来偏挤。
        bodyTv.setLineSpacing(Ui.dp(this, 5), 1f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(this, 6);
        bp.leftMargin = Ui.dp(this, 17);
        bodyTv.setLayoutParams(bp);
        row.addView(bodyTv);

        // ---- 重点（展开时才显示详情）----
        if (expanded && n.keyPoints != null && !n.keyPoints.isEmpty()) {
            LinearLayout kpBox = Ui.column(this);
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            kp.topMargin = Ui.dp(this, 10);
            kp.leftMargin = Ui.dp(this, 17);
            kpBox.setLayoutParams(kp);
            kpBox.setBackground(Ui.round(this, Ui.surfaceContainer(this),
                    Color.TRANSPARENT, Ui.R_S, 0));
            int kpad = Ui.dp(this, 12);
            kpBox.setPadding(kpad, kpad, kpad, kpad);

            TextView kTitle = Ui.text(this, "重点", Ui.T_LABEL, Ui.primary(this), true);
            kpBox.addView(kTitle);
            for (String k : n.keyPoints) {
                TextView item = Ui.text(this, "· " + k, Ui.T_BODY,
                        Ui.onSurfaceVariant(this), false);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ip.topMargin = Ui.dp(this, 4);
                item.setLayoutParams(ip);
                kpBox.addView(item);
            }
            row.addView(kpBox);
        }

        // ---- 展开后的操作行（等宽分配，防溢出）----
        if (expanded) {
            LinearLayout ops = Ui.row(this);
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 36));
            op.topMargin = Ui.dp(this, 12);
            ops.setLayoutParams(op);

            ops.addView(actionCell(R.drawable.ic_pin, n.pinned ? "取消置顶" : "置顶",
                    Ui.primary(this), new Runnable() {
                        @Override public void run() {
                            db.setNotePinned(n.id, !n.pinned);
                            renderContent();
                        }
                    }));
            ops.addView(actionCell(R.drawable.ic_edit, "编辑", Ui.primary(this),
                    new Runnable() {
                        @Override public void run() { noteDialog(n); }
                    }));
            ops.addView(actionCell(R.drawable.ic_ai, "AI 总结", Ui.primary(this),
                    new Runnable() {
                        @Override public void run() { aiSummary(n); }
                    }));
            ops.addView(actionCell(R.drawable.ic_trash, "删除", Ui.error(this),
                    new Runnable() {
                        @Override public void run() { confirmDeleteNote(n); }
                    }));
            row.addView(ops);
        }

        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                expandedNoteId = expanded ? null : n.id;
                renderContent();
            }
        });
        return row;
    }

        /**
     * 等宽操作单元 —— 每个按钮占 1/4 宽，解决"删除被挤出屏幕"问题。
     */
    private View actionCell(int iconRes, String label, int color, final Runnable action) {
        LinearLayout box = Icons.compactAction(this, iconRes, label, color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 36), 1f);
        box.setLayoutParams(lp);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return box;
    }

        private void confirmDeleteNote(final Db.Note n) {
        Dialogs.confirm(this, "删除笔记", "确定删除「" + nz(n.title) + "」？此操作不可恢复。",
                "删除", new Runnable() {
                    @Override public void run() {
                        db.deleteNote(n.id);
                        if (n.id.equals(expandedNoteId)) expandedNoteId = null;
                        renderTabs();
                        renderContent();
                        Tip.success(CourseActivity.this, "笔记已删除");
                    }
                });
    }

    private String preview(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.length() <= 90) return t;
        return t.substring(0, 90) + "…";
    }

    // ================== 待办 ==================

    private void renderTodos() {
        LinearLayout actions = Ui.row(this);
        actions.addView(Ui.spacer(this));
        LinearLayout addBtn = Icons.iconTextButton(this, R.drawable.ic_add, "新建待办", 0);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { todoDialog(); }
        });
        actions.addView(addBtn);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.bottomMargin = Ui.dp(this, 18);
        actions.setLayoutParams(ap);
        content.addView(actions);

        List<Db.Todo> todos = db.todos(courseId);
        if (todos.isEmpty()) {
            content.addView(emptyState(R.drawable.ic_list, "添加第一个待办",
                    "把作业和复习拆成小任务，逐个完成"));
            return;
        }

        int open = 0;
        for (Db.Todo t : todos) if (!t.completed) open++;
        TextView summary = Ui.text(this, open + " 项未完成 · 共 " + todos.size() + " 项",
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = Ui.dp(this, 10);
        summary.setLayoutParams(slp);
        content.addView(summary);

        boolean headerTodo = false, headerDone = false;
        for (Db.Todo t : todos) {
            if (!t.completed && !headerTodo) {
                headerTodo = true;
                content.addView(sectionLabel("进行中"));
            } else if (t.completed && !headerDone) {
                headerDone = true;
                content.addView(sectionLabel("已完成"));
            }
            content.addView(todoRow(t));
        }
    }

    private View sectionLabel(String text) {
        TextView tv = Ui.text(this, text, Ui.T_LABEL, Ui.onSurfaceVariant(this), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        lp.bottomMargin = Ui.dp(this, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View todoRow(final Db.Todo t) {
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 2);
        row.setLayoutParams(lp);
        int pad = Ui.dp(this, 12);
        row.setPadding(pad, pad, pad, pad);
        row.setMinimumHeight(Ui.dp(this, 56));

        // MD3 Checkbox：圆形勾选
        final LinearLayout checkBox = Ui.row(this);
        checkBox.setGravity(Gravity.CENTER);
        final int size = Ui.dp(this, 24);
        checkBox.setLayoutParams(Ui.lp(size, size));
        if (t.completed) {
            checkBox.setBackground(Ui.circle(Ui.primary(this), size));
        } else {
            checkBox.setBackground(Ui.round(this, Color.TRANSPARENT,
                    Ui.outline(this), Ui.R_FULL, 1.5f));
        }
        if (t.completed) {
            checkBox.addView(Icons.icon(this, R.drawable.ic_check, Ui.onPrimary(this), 15));
        }
        checkBox.setClickable(true);
        checkBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                db.setTodoCompleted(t.id, !t.completed);
                renderTabs();
                renderContent();
                if (!t.completed) Tip.success(CourseActivity.this, "已完成");
            }
        });
        row.addView(checkBox);

        LinearLayout mid = Ui.column(this);
        LinearLayout.LayoutParams mp = Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = Ui.dp(this, 14);
        mid.setLayoutParams(mp);

        TextView title = Ui.text(this, nz(t.title), Ui.T_BODY + 1,
                t.completed ? Ui.onSurfaceVariant(this) : Ui.onSurface(this), false);
        if (t.completed) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        mid.addView(title);

        String sub = priorityLabel(t.priority);
        if (t.due != null && t.due.length() > 0) sub += " · 截止 " + Dates.shortDate(t.due);
        TextView subTv = Ui.text(this, sub, Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(this, 2);
        subTv.setLayoutParams(sp);
        mid.addView(subTv);
        row.addView(mid);

        LinearLayout del = Icons.iconButton(this, R.drawable.ic_trash, 40, Ui.outline(this));
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                db.deleteTodo(t.id);
                renderTabs();
                renderContent();
                Tip.success(CourseActivity.this, "待办已删除");
            }
        });
        row.addView(del);
        return row;
    }

    private String priorityLabel(String p) {
        if ("high".equals(p)) return "高优先级";
        if ("low".equals(p)) return "低优先级";
        return "中优先级";
    }

    /** 空状态要教学，而不只是"暂无"（operate.md 规范）。 */
    private View emptyState(int iconRes, String title, String hint) {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 32);
        box.setPadding(p, Ui.dp(this, 40), p, Ui.dp(this, 40));

        LinearLayout circle = new LinearLayout(this);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(Ui.round(this, Ui.surfaceHigh(this),
                Color.TRANSPARENT, Ui.R_FULL, 0));
        int s = Ui.dp(this, 72);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(s, s);
        clp.gravity = Gravity.CENTER;
        circle.setLayoutParams(clp);
        circle.addView(Icons.icon(this, iconRes, Ui.onSurfaceVariant(this), 32));
        box.addView(circle);

        TextView tv = Ui.text(this, title, Ui.T_TITLE, Ui.onSurface(this), true);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.dp(this, 16);
        tv.setLayoutParams(tp);
        box.addView(tv);

        TextView hintTv = Ui.text(this, hint, Ui.T_BODY, Ui.onSurfaceVariant(this), false);
        hintTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.dp(this, 6);
        hintTv.setLayoutParams(hp);
        box.addView(hintTv);

        return box;
    }

    // ================== 新建 / 编辑 ==================

    private void noteDialog(final Db.Note editing) {
        final boolean isEdit = editing != null;
        Dialogs.form(this, isEdit ? "编辑笔记" : "新建笔记",
                new String[]{"标题", "日期", "笔记内容", "重点（每行一条）"},
                new String[]{"第一章 极限与连续", Dates.today(), "课堂内容、理解与疑问…", "每行一条重点"},
                new String[]{
                        isEdit ? nz(editing.title) : "",
                        isEdit ? nz(editing.date) : Dates.today(),
                        isEdit ? nz(editing.content) : "",
                        isEdit && editing.keyPoints != null ? Db.joinString(editing.keyPoints) : ""
                },
                new boolean[]{false, false, true, true},
                new Dialogs.OnSubmit() {
                    @Override public void onSubmit(EditText[] f) {
                        String title = f[0].getText().toString().trim();
                        if (title.length() == 0) {
                            Tip.error(CourseActivity.this, "请填写标题");
                            return;
                        }
                        Db.Note n = isEdit ? editing : new Db.Note();
                        n.courseId = courseId;
                        n.title = title;
                        String d = f[1].getText().toString().trim();
                        n.date = d.length() == 0 ? Dates.today() : d;
                        n.content = f[2].getText().toString().trim();
                        n.keyPoints = Db.splitString(f[3].getText().toString());
                        if (!isEdit) n.id = Id.gen();
                        db.saveNote(n);
                        if (submitDialog != null) submitDialog.dismiss();
                        submitDialog = null;
                        renderTabs();
                        renderContent();
                        Tip.success(CourseActivity.this, isEdit ? "笔记已更新" : "笔记已创建");
                    }
                });
    }

    /**
     * 新建待办。优先级是点选而不是手输——原来要在输入框里敲 high / medium / low，
     * 敲错一个字母会被静默改写成「中优先级」（那段校验只是把它改掉，用户并不知道自己写错了），
     * 而且高/中/低那套颜色也没法在输入框里表达。
     */
    private void todoDialog() {
        final LinearLayout box = Ui.column(this);
        final TodoForm form = new TodoForm();
        final Runnable render = new Runnable() {
            @Override public void run() { renderTodoForm(box, form); }
        };
        render.run();

        final AlertDialog dlg = Dialogs.form(this, "新建待办", box, "保存", new Dialogs.Saver() {
            @Override public boolean save() { return saveTodo(form); }
        });
        Dialogs.focusFirst(dlg, true);
    }

    /** 新建待办的表单状态。点优先级会重建表单，已填的内容得先活在这里。 */
    private static final class TodoForm {
        String title = "";
        String due = Dates.today();
        String priority = "medium";
        EditText titleField;
        EditText dueField;
    }

    private void renderTodoForm(LinearLayout box, TodoForm f) {
        if (f.titleField != null) f.title = f.titleField.getText().toString();
        if (f.dueField != null) f.due = f.dueField.getText().toString();
        box.removeAllViews();
        f.titleField = formField(box, "任务内容", "完成第三章习题", f.title);
        f.dueField = formField(box, "截止日期", Dates.today(), f.due);
        formLabel(box, "优先级");
        final Runnable rerender = new Runnable() {
            @Override public void run() { renderTodoForm(box, f); }
        };
        box.addView(priorityOption("high", "最要紧的，先做这个", f, rerender));
        box.addView(priorityOption("medium", "常规待办", f, rerender));
        box.addView(priorityOption("low", "有空再说", f, rerender));
    }

    /** 对话框里的字段标签。 */
    private void formLabel(LinearLayout box, String text) {
        TextView lb = Ui.text(this, text, Ui.T_LABEL, Ui.onSurfaceVariant(this), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 16);
        lp.bottomMargin = Ui.dp(this, 6);
        lb.setLayoutParams(lp);
        box.addView(lb);
    }

    private EditText formField(LinearLayout box, String label, String hint, String value) {
        formLabel(box, label);
        EditText et = Ui.input(this, hint);
        if (value != null && value.length() > 0) et.setText(value);
        box.addView(et);
        return et;
    }

    /**
     * 一行优先级选项。圆点颜色和主页面进度条、图例共用 Ui.priorityColor 那一份映射，
     * 三处必须对得上——不然图例是红的、选项里是蓝的，颜色就白标了。
     */
    private View priorityOption(final String key, String desc, final TodoForm f,
                                final Runnable rerender) {
        return Ui.optionRow(this, priorityLabel(key), desc, key.equals(f.priority),
                Ui.priorityColor(this, key), new Runnable() {
                    @Override public void run() {
                        if (key.equals(f.priority)) return;
                        f.priority = key;
                        rerender.run();
                    }
                });
    }

    private boolean saveTodo(TodoForm f) {
        String title = f.titleField.getText().toString().trim();
        if (title.length() == 0) {
            Tip.error(this, "请填写任务内容");
            return false;   // false = 不关窗，刚填的内容还在
        }
        Db.Todo todo = new Db.Todo();
        todo.id = Id.gen();
        todo.courseId = courseId;
        todo.title = title;
        todo.due = f.dueField.getText().toString().trim();
        todo.priority = f.priority;
        db.saveTodo(todo);
        submitDialog = null;
        renderTabs();
        renderContent();
        Tip.success(this, "待办已创建");
        return true;
    }

    // ================== 录音 ==================

    private boolean isRecording() { return speech != null && speech.isRecording(); }

    private void startRecordingFlow() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        reallyStartRecording();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                reallyStartRecording();
            } else {
                Tip.error(this, "未获得麦克风权限，无法录音");
            }
        }
    }

    private void reallyStartRecording() {
        final String mode = Prefs.tsMode(this);
        final boolean recordAudio = !"off".equals(mode);
        // 新一轮录音：授权引导框可以再弹一次（点了「稍后」又继续录的用户，不该被永久免打扰）
        consentGuideShowing = false;

        speech = new SpeechSession(this, new SpeechSession.Listener() {
            @Override public void onPartial(String text) {
                if (recTextView != null) recTextView.setText(text);
            }

            @Override public void onFinished(String finalText, byte[] audio) {
                stopTicker();
                speech = null;
                if (finalText != null && finalText.trim().length() > 0) {
                    openRecordedNote(finalText.trim());
                } else if (audio != null && audio.length > 0) {
                    transcribeThenNote(audio);
                } else {
                    Tip.error(CourseActivity.this, "本次录音没有识别到语音内容");
                }
                renderTabs();
                renderContent();
            }

            /**
             * 出错时的出路，按「哪条路走得通」排优先级：
             *
             *  - 识别被厂商拦下（小米 CTA / 机型白名单这类）时，「去设置」是死路——
             *    系统设置里根本没有能让第三方 App 通过的开关。这种设备唯一可能恢复
             *    实时识别的是同意一次厂商的「跨应用识别」协议，所以直接弹引导框、
             *    一键把授权页拉起来（见 showConsentGuide）。
             *  - 只是缺服务 / 服务没配好时，才把用户送去开云端转写。
             *
             * 两条都没有的普通错误就只报错，不硬塞一个点了没用的按钮。
             */
            @Override public void onError(String message, boolean fatal, boolean cloudFallback) {
                if (SpeechSession.recognizeBlocked()) {
                    showConsentGuide();
                } else if (cloudFallback) {
                    Tip.errorAction(CourseActivity.this, message, "去设置",
                            new Runnable() {
                                @Override public void run() { openTsSettings(); }
                            });
                } else {
                    Tip.error(CourseActivity.this, message);
                }
                if (fatal) {
                    // 只有「没开录音兜底」时才会收到 fatal（见 SpeechSession.giveUpRecognition）：
                    // 开着兜底的话会话会降级成纯录音继续跑。所以这里的 cancel() 删不到正在录的音频。
                    stopTicker();
                    if (speech != null) speech.cancel();
                    speech = null;
                    renderContent();
                }
            }
        });

        // start() 可能同步就失败（比如设备没有识别服务）。那样 onError 已经回调过、
        // 提示也弹了，这里绝不能再往下渲染录音面板——否则会留下一个假的「正在录音」。
        if (!speech.start(recordAudio)) {
            speech = null;
            return;
        }
        renderRecordingPanel();
        startTicker();
    }

    private void renderRecordingPanel() {
        content.removeAllViews();
        recPanel = Ui.card(this);
        recPanel.setGravity(Gravity.CENTER);

        LinearLayout statusRow = Ui.row(this);
        statusRow.setGravity(Gravity.CENTER);

        // 录音指示：三圈相位错开的扩散脉冲，而不是一个静止红点。
        // 静态圆点看不出「正在进行」，脉冲才把状态说明白。
        // 用 scale + alpha（渲染期变换），不触发重排，所以不会跟每秒的计时刷新打架。
        int ring = Ui.dp(this, 18);
        android.widget.FrameLayout dotBox = new android.widget.FrameLayout(this);
        dotBox.setLayoutParams(Ui.lp(ring, ring));
        dotBox.setClipChildren(false);   // 扩散时脉冲会超出自身范围，别裁掉
        statusRow.setClipChildren(false);

        for (int i = 0; i < 3; i++) {
            View r = new View(this);
            r.setBackground(Ui.circle(Ui.error(this), ring));
            r.setLayoutParams(new android.widget.FrameLayout.LayoutParams(ring, ring));
            dotBox.addView(r);
            recPulses.add(Ui.pulse(r, i * 400L));
        }

        View core = new View(this);
        core.setBackground(Ui.circle(Ui.error(this), Ui.dp(this, 10)));
        android.widget.FrameLayout.LayoutParams clp =
                new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10));
        clp.gravity = Gravity.CENTER;
        core.setLayoutParams(clp);
        dotBox.addView(core);
        statusRow.addView(dotBox);

        TextView label = Ui.text(this, "  正在录音", Ui.T_TITLE, Ui.onSurface(this), true);
        statusRow.addView(label);

        recTimerView = Ui.text(this, "  00:00", Ui.T_TITLE, Ui.onSurfaceVariant(this), false);
        statusRow.addView(recTimerView);
        recPanel.addView(statusRow);

        recTextView = Ui.text(this, "", Ui.T_BODY, Ui.onSurfaceVariant(this), false);
        recTextView.setMinLines(3);
        recTextView.setLineSpacing(Ui.dp(this, 4), 1f);
        LinearLayout.LayoutParams rtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rtp.topMargin = Ui.dp(this, 16);
        recTextView.setLayoutParams(rtp);
        recPanel.addView(recTextView);

        if (!"off".equals(Prefs.tsMode(this))) {
            TextView hint = Ui.text(this, "已启用转写后端：无实时结果时将自动转写录音",
                    Ui.T_CAPTION, Ui.onSurfaceVariant(this), false);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.topMargin = Ui.dp(this, 10);
            hint.setLayoutParams(hp);
            recPanel.addView(hint);
        }

        LinearLayout ctrl = Ui.row(this);
        ctrl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ctp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctp.topMargin = Ui.dp(this, 18);
        ctrl.setLayoutParams(ctp);

        final LinearLayout pause = Icons.iconTextButton(this, 0, "暂停", 1);
        pause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (speech == null) return;
                speech.setPaused(!speech.isPaused());
                ((TextView) pause.getChildAt(0)).setText(speech.isPaused() ? "继续" : "暂停");
            }
        });
        ctrl.addView(pause);

        LinearLayout stop = Icons.iconTextButton(this, R.drawable.ic_check, "完成", 0);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (speech != null) speech.stop();
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = Ui.dp(this, 10);
        ctrl.addView(stop, sp);
        recPanel.addView(ctrl);

        content.addView(recPanel);
    }

    private void startTicker() {
        ticker = new Handler(Looper.getMainLooper());
        tickTask = new Runnable() {
            @Override public void run() {
                if (speech != null && speech.isRecording() && recTimerView != null) {
                    recTimerView.setText("  " + Dates.clock(speech.elapsedMs()));
                }
                if (speech != null && speech.isRecording()) ticker.postDelayed(this, 500);
            }
        };
        ticker.postDelayed(tickTask, 500);
    }

    private void stopTicker() {
        if (ticker != null && tickTask != null) ticker.removeCallbacks(tickTask);
        ticker = null;
    }

    private void openRecordedNote(String text) {
        Db.Note n = new Db.Note();
        n.id = Id.gen();
        n.courseId = courseId;
        n.title = course.name + " 录音笔记 " + Dates.today();
        n.date = Dates.today();
        n.content = text;
        n.keyPoints = Extract.keyPoints(text);
        db.saveNote(n);
        Tip.success(this, "录音笔记已保存");
    }

    /** 打开语音转写设置页——识别不可用时，云转写是唯一的出路。 */
    private void openTsSettings() {
        startActivity(new Intent(this, TsSettingsActivity.class));
    }

    /**
     * 被厂商拦下时主动弹出的授权引导框。
     *
     * 之前只是把「去授权」挂在一个 6 秒就消失的提示条上——用户没注意就过去了，
     * 换设备时等于功能直接坏掉。授权是恢复实时识别的**唯一**出路，必须用模态框
     * 挡在用户面前让他二选一，而不是赌他看得见小提示。
     *
     * 每次开始录音重置一次，保证「点了取消 → 再录 → 再问」还能出现，
     * 但同一次录音里连续多个错误只弹一次。
     */
    private void showConsentGuide() {
        if (consentGuideShowing) return;
        consentGuideShowing = true;
        Dialogs.confirm(this, "语音识别被系统拦住了",
                "这台设备的语音识别要求先同意「跨应用识别」隐私协议（小米：小爱同学），"
                        + "只给麦克风权限不够。\n\n「去同意」会打开厂商的授权页面；"
                        + "暂时不弄的话，本次仍能录音，结束后走云端转写出文字。",
                "去同意", new Runnable() {
                    @Override public void run() { openVoiceAuth(); }
                });
    }

    /**
     * 去厂商授权页。拉起来就清掉「被拦下」标记——授权很可能就在这一步完成，
     * 回来后再点录音必须重新试一次系统识别，不能被一次旧失败永久堵死。
     * 一条入口都没找到时说清下一步，别让用户对着没反应的按钮发呆。
     */
    private void openVoiceAuth() {
        SpeechSession.clearRecognizeBlocked();
        if (!VoiceAuth.open(this)) {
            Tip.error(this, "没找到语音助手的入口，可用「设置 → 语音转写设置」改用云端转写");
        }
    }

    private void transcribeThenNote(final byte[] audio) {
        final String ep = Prefs.tsEndpoint(this);
        final String key = Prefs.tsKey(this);
        final String model = Prefs.tsModel(this);
        if ("off".equals(Prefs.tsMode(this)) || ep.length() == 0) {
            Tip.error(this, "未识别到文字，且云端转写未开启（设置 → 语音转写设置）");
            return;
        }
        showLoading("正在上传录音并转写…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String text = Net.transcribe(ep, key, model, audio, "recording.m4a");
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            hideLoading();
                            openRecordedNote(text);
                            renderTabs();
                            renderContent();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            hideLoading();
                            Tip.error(CourseActivity.this,
                                    "转写失败：" + Net.humanize(e));
                        }
                    });
                }
            }
        }).start();
    }

    // ================== AI 总结 ==================

    private void aiSummary(final Db.Note n) {
        if (n.content == null || n.content.trim().length() == 0) {
            Tip.error(this, "笔记内容为空");
            return;
        }
        final AiProto.Cfg cfg = Prefs.aiCfg(this);
        String miss = AiProto.missing(cfg);
        if (miss != null) {
            Tip.error(this, miss + "（设置 → AI 总结）");
            return;
        }
        showLoading("正在请求 AI 总结…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Net.AiResult r = Net.summarize(cfg, n.title, n.content);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            hideLoading();
                            showAiResult(n, r);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            hideLoading();
                            Tip.error(CourseActivity.this,
                                    "AI 总结失败：" + Net.humanize(e));
                        }
                    });
                }
            }
        }).start();
    }

    private void showAiResult(final Db.Note n, final Net.AiResult r) {
        LinearLayout box = Ui.column(this);

        TextView sum = Ui.text(this, r.summary == null ? "" : r.summary, Ui.T_BODY,
                Ui.onSurface(this), false);
        sum.setLineSpacing(Ui.dp(this, 4), 1f);
        box.addView(sum);

        if (r.keyPoints != null && !r.keyPoints.isEmpty()) {
            TextView kpTitle = Ui.text(this, "重点提炼", Ui.T_LABEL, Ui.primary(this), true);
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            kp.topMargin = Ui.dp(this, 16);
            kpTitle.setLayoutParams(kp);
            box.addView(kpTitle);

            for (String k : r.keyPoints) {
                TextView item = Ui.text(this, "· " + k, Ui.T_BODY,
                        Ui.onSurfaceVariant(this), false);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ip.topMargin = Ui.dp(this, 5);
                item.setLayoutParams(ip);
                box.addView(item);
            }
        }

        Dialogs.content(this, "AI 总结结果", box, "应用到笔记", new Runnable() {
            @Override public void run() {
                n.content = r.summary;
                n.keyPoints = r.keyPoints;
                db.saveNote(n);
                renderContent();
                Tip.success(CourseActivity.this, "AI 总结已应用");
            }
        });
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** 状态栏高度。 */
    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return Ui.dp(this, 24);
    }
}