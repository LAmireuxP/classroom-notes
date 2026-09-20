package com.lamireuxp.classroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 首页 —— Material Design 3 布局。
 *
 * 结构：Top App Bar / 统计摘要卡 / SearchBar / 课程卡片列表 / FAB / 底部工具行
 */
public class MainActivity extends Activity implements Dialogs.DialogHost {

    private static final int REQ_EXPORT_JSON = 101;
    private static final int REQ_EXPORT_MD = 102;
    private static final int REQ_IMPORT_JSON = 103;

    private Db db;
    private ScrollView scroller;
    private LinearLayout contentBox;   // 列表 + 统计的容器（会重建）
    private EditText search;
    private LinearLayout fab;
    private AlertDialog submitDialog;

    private String query = "";
    private String pendingExport;
    private String pendingName;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = Db.get(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void setSubmitDialog(AlertDialog dlg) { this.submitDialog = dlg; }

    @Override
    public void onBackPressed() {
        if (submitDialog != null && submitDialog.isShowing()) {
            submitDialog.dismiss();
            submitDialog = null;
            return;
        }
        super.onBackPressed();
    }

    // ================== UI ==================

    private void buildUi() {
        // 根容器：FrameLayout（为了把 FAB 悬浮在右下）
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Ui.surface(this));
        rootFrame.setFitsSystemWindows(false);

        LinearLayout column = Ui.column(this);
        column.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootFrame.addView(column);

        // ---------- Top App Bar ----------
        View bar = topBar();
        // 状态栏内边距（edge-to-edge 下内容不被状态栏遮挡）
        bar.setPadding(bar.getPaddingLeft(),
                bar.getPaddingTop() + statusBarHeight(),
                bar.getPaddingRight(), bar.getPaddingBottom());
        column.addView(bar);

        // ---------- 可滚动内容 ----------
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scroller.setLayoutParams(sp);

        contentBox = Ui.column(this);
        int padH = Ui.dp(this, 16), padTop = Ui.dp(this, 8), padBottom = Ui.dp(this, 96);
        contentBox.setPadding(padH, padTop, padH, padBottom);
        scroller.addView(contentBox);
        column.addView(scroller);

        // ---------- FAB（MD3 标准：56dp，距边缘 16dp）----------
        fab = createFab();
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                Ui.dp(this, 56), Ui.dp(this, 56));
        fp.gravity = Gravity.END | Gravity.BOTTOM;
        fp.rightMargin = Ui.dp(this, 16);
        // 底部 = 16dp + 导航栏高度，确保不被系统栏遮挡
        fp.bottomMargin = Ui.dp(this, 16) + navBarHeight();
        fab.setLayoutParams(fp);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { courseDialog(null); }
        });
        rootFrame.addView(fab);

        setContentView(rootFrame);
    }

    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.surface(this));
        int padH = Ui.dp(this, 16), padV = Ui.dp(this, 12);
        bar.setPadding(padH, padV, Ui.dp(this, 8), padV);

        // 标题区：图标 + 大标题
        LinearLayout titleBox = Ui.row(this);
        ImageView logo = Icons.icon(this, R.drawable.ic_star, Ui.primary(this), 24);
        titleBox.addView(logo);
        TextView title = Ui.text(this, "课堂整理", Ui.T_HEADLINE, Ui.onSurface(this), true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = Ui.dp(this, 10);
        title.setLayoutParams(tp);
        titleBox.addView(title);
        titleBox.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(titleBox);

        // 主题开关（胶囊滑块 + 图标交叉淡入，参考 galaxy Toggle-switches）
        View themeSwitch = ThemeSwitch.create(this);
        LinearLayout.LayoutParams tsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsp.rightMargin = Ui.dp(this, 6);
        bar.addView(themeSwitch, tsp);

        // 设置
        LinearLayout settingsBtn = Icons.iconButtonFilled(this, R.drawable.ic_settings,
                44, Ui.surfaceHighest(this), Ui.onSurfaceVariant(this));
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettingsSheet(); }
        });
        bar.addView(settingsBtn);

        return bar;
    }

    private LinearLayout createFab() {
        LinearLayout f = new LinearLayout(this);
        f.setGravity(Gravity.CENTER);
        f.setBackground(Ui.ripple(this, Ui.primary(this), Ui.R_L));
        Ui.elevation(f, 6);
        f.setClickable(true);
        f.setFocusable(true);
        f.setContentDescription("新建课程");
        f.addView(Icons.icon(this, R.drawable.ic_add, Ui.onPrimary(this), 24));
        return f;
    }

    // ---------- 系统栏高度（edge-to-edge inset）----------

    /** 状态栏高度（px）。 */
    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return Ui.dp(this, 24);
    }

    /** 导航栏高度（px）；手势导航时通常较小。 */
    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) {
            int h = getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return Ui.dp(this, 24);
    }

    // ================== 渲染 ==================

    private void refresh() {
        contentBox.removeAllViews();

        List<Db.Course> courses = db.courses();

        // ---- 统计摘要（仅在有课程时显示）----
        if (!courses.isEmpty()) contentBox.addView(statsSummary());

        // ---- 搜索栏（MD3 SearchBar + 前导图标）----
        LinearLayout searchBox = Ui.searchBarWithIcon(this, "搜索笔记…", R.drawable.ic_search);
        search = Ui.searchInput(searchBox);
        if (query.length() > 0) search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                refreshCourseList();
            }
        });
        contentBox.addView(searchBox);

        contentBox.addView(courseListContainer());
    }

    private LinearLayout listBox;

    private View courseListContainer() {
        listBox = Ui.column(this);
        fillCourseList();
        return listBox;
    }

    private void refreshCourseList() {
        if (listBox == null) return;
        fillCourseList();
    }

    private void fillCourseList() {
        listBox.removeAllViews();
        List<Db.Course> courses = db.courses();

        if (courses.isEmpty()) {
            listBox.addView(emptyState());
            return;
        }

        // 章节标题
        LinearLayout secHead = Ui.row(this);
        TextView secTitle = Ui.text(this, "我的课程", Ui.T_TITLE, Ui.onSurface(this), true);
        secHead.addView(secTitle);
        secHead.addView(Ui.spacer(this));
        TextView secCount = Ui.tonalChip(this, courses.size() + " 门",
                Ui.secondaryContainer(this), Ui.tone(this, "on_secondary_container"));
        secHead.addView(secCount);
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shp.topMargin = Ui.dp(this, 4);
        shp.bottomMargin = Ui.dp(this, 12);
        secHead.setLayoutParams(shp);
        listBox.addView(secHead);

        for (int i = 0; i < courses.size(); i++) {
            listBox.addView(courseCard(courses.get(i)));
            // 行间内嵌分隔线（MD3 列表规范），最后一行不加
            if (i < courses.size() - 1) {
                View line = new View(this);
                line.setBackgroundColor(Ui.outlineVariant(this));
                LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.8f)));
                lineLp.leftMargin = Ui.dp(this, 38);
                line.setLayoutParams(lineLp);
                listBox.addView(line);
            }
        }
    }

    /** 顶部统计摘要 —— 紧凑内联，不做 hero-metric 模板（craft-floor 禁令）。 */
    private View statsSummary() {
        int[] t = db.totals();
        int done = t[2], total = t[1];
        int courses = db.courseCount();

        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.round(this, Ui.surfaceContainer(this), Color.TRANSPARENT, Ui.R_M, 0));
        int p = Ui.dp(this, 14);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 16);
        card.setLayoutParams(lp);

        // 一行内联摘要，数字用 onSurface 强调，标签用 onSurfaceVariant
        LinearLayout line = Ui.row(this);
        line.addView(inlineNum(String.valueOf(courses)));
        line.addView(inlineLabel(" 门课程"));
        line.addView(inlineDot());
        line.addView(inlineNum(String.valueOf(t[0])));
        line.addView(inlineLabel(" 条笔记"));
        line.addView(inlineDot());
        line.addView(inlineNum(done + "/" + total));
        line.addView(inlineLabel(" 待办完成"));
        card.addView(line);

        // 进度条（细线，表达状态而非装饰）
        if (total > 0) {
            int pct = Math.round(done * 100f / total);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 4));
            bp.topMargin = Ui.dp(this, 12);

            LinearLayout track = new LinearLayout(this);
            track.setLayoutParams(bp);
            track.setBackground(Ui.round(this, Ui.outlineVariant(this), Color.TRANSPARENT,
                    Ui.R_FULL, 0));

            View fill = new View(this);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(0.001f, pct / 100f));
            fill.setLayoutParams(fp);
            fill.setBackground(Ui.round(this, Ui.primary(this), Color.TRANSPARENT, Ui.R_FULL, 0));
            track.addView(fill);
            card.addView(track);
        }

        return card;
    }

    private TextView inlineNum(String s) {
        return Ui.text(this, s, Ui.T_BODY, Ui.onSurface(this), true);
    }

    private TextView inlineLabel(String s) {
        return Ui.text(this, s, Ui.T_BODY, Ui.onSurfaceVariant(this), false);
    }

    private View inlineDot() {
        View dot = new View(this);
        dot.setBackground(Ui.circle(Ui.outlineVariant(this), Ui.dp(this, 3)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, 3), Ui.dp(this, 3));
        lp.leftMargin = Ui.dp(this, 8);
        lp.rightMargin = Ui.dp(this, 2);
        dot.setLayoutParams(lp);
        return dot;
    }

    /** 课程行 —— 扁平列表行 + 色点标识（不用 border-left 色条，遵守 craft-floor）。 */
    private View courseCard(final Db.Course c) {
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(this, 2);
        row.setLayoutParams(rlp);
        int pad = Ui.dp(this, 14);
        row.setPadding(pad, pad, Ui.dp(this, 6), pad);
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_M));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Ui.dp(this, 64));

        // 色点（课程标识，非色条）
        View dot = new View(this);
        dot.setBackground(Ui.circle(Ui.parseColor(c.color, Ui.primary(this)), Ui.dp(this, 10)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 10), Ui.dp(this, 10));
        dlp.rightMargin = Ui.dp(this, 14);
        dot.setLayoutParams(dlp);
        row.addView(dot);

        // 标题 + 元信息
        LinearLayout mid = Ui.column(this);
        LinearLayout.LayoutParams mp = Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mid.setLayoutParams(mp);

        TextView name = Ui.text(this, c.name, Ui.T_TITLE, Ui.onSurface(this), true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(name);

        String meta = (c.noteCount == 0 && c.todoCount == 0)
                ? "暂无内容"
                : "笔记 " + c.noteCount + " · 待办 " + c.doneCount + "/" + c.todoCount;
        String teacher = (c.teacher != null && c.teacher.length() > 0) ? c.teacher + " · " : "";
        TextView metaTv = Ui.text(this, teacher + meta, Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mtp.topMargin = Ui.dp(this, 3);
        metaTv.setLayoutParams(mtp);
        mid.addView(metaTv);
        row.addView(mid);

        // 溢出菜单
        LinearLayout more = Icons.iconButton(this, R.drawable.ic_menu, 40,
                Ui.onSurfaceVariant(this));
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { courseMenu(c); }
        });
        row.addView(more);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent it = new Intent(MainActivity.this, CourseActivity.class);
                it.putExtra("courseId", c.id);
                startActivity(it);
            }
        });
        return row;
    }

    private View emptyState() {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 48);
        box.setPadding(p, p, p, p);

        LinearLayout iconCircle = new LinearLayout(this);
        iconCircle.setGravity(Gravity.CENTER);
        iconCircle.setBackground(Ui.round(this, Ui.surfaceHigh(this), Color.TRANSPARENT, Ui.R_FULL, 0));
        int s = Ui.dp(this, 88);
        iconCircle.setLayoutParams(Ui.lp(s, s));
        iconCircle.addView(Icons.icon(this, R.drawable.ic_folder, Ui.onSurfaceVariant(this), 40));

        LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(
                Ui.dp(this, 88), Ui.dp(this, 88));
        icp.gravity = Gravity.CENTER;
        iconCircle.setLayoutParams(icp);
        box.addView(iconCircle);

        TextView title = Ui.text(this, "还没有课程", Ui.T_TITLE, Ui.onSurface(this), true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.dp(this, 18);
        title.setLayoutParams(tp);
        box.addView(title);

        TextView sub = Ui.text(this, "点击右下角 + 新建第一门课程", Ui.T_BODY,
                Ui.onSurfaceVariant(this), false);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(this, 6);
        sub.setLayoutParams(sp);
        box.addView(sub);

        return box;
    }

    // ================== 菜单 / 弹窗 ==================

    private void courseMenu(final Db.Course c) {
        LinearLayout box = Ui.column(this);
        box.setPadding(0, Ui.dp(this, 4), 0, 0);

        box.addView(menuRow(R.drawable.ic_edit, "编辑课程", new Runnable() {
            @Override public void run() { courseDialog(c); }
        }));
        box.addView(menuRow(R.drawable.ic_trash, "删除课程", Ui.error(this), new Runnable() {
            @Override public void run() { confirmDeleteCourse(c); }
        }));

        Dialogs.sheet(this, c.name, box);
    }

    private View menuRow(int iconRes, String label, final Runnable action) {
        return menuRow(iconRes, label, Ui.onSurface(this), action);
    }

    private View menuRow(int iconRes, String label, int color, final Runnable action) {
        LinearLayout row = Ui.row(this);
        int padH = Ui.dp(this, 20), padV = Ui.dp(this, 14);
        row.setPadding(padH, padV, padH, padV);
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_S));
        row.setClickable(true);
        row.addView(Icons.icon(this, iconRes, color, 20));
        TextView tv = Ui.text(this, label, Ui.T_BODY + 1, color, false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = Ui.dp(this, 16);
        tv.setLayoutParams(tp);
        row.addView(tv);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return row;
    }

    private void courseDialog(final Db.Course editing) {
        Dialogs.form(this,
                editing == null ? "新建课程" : "编辑课程",
                new String[]{"课程名称", "授课教师（选填）"},
                new String[]{"例如：高等数学", "例如：王老师"},
                new String[]{editing == null ? "" : editing.name,
                        editing == null ? "" : editing.teacher},
                new boolean[]{false, false},
                new Dialogs.OnSubmit() {
                    @Override public void onSubmit(EditText[] f) {
                        String name = f[0].getText().toString().trim();
                        if (name.length() == 0) {
                            Tip.error(MainActivity.this, "请填写课程名称");
                            return;
                        }
                        String teacher = f[1].getText().toString().trim();
                        if (editing == null) {
                            db.saveCourse(Id.gen(), name, teacher, nextColor());
                            Tip.show(MainActivity.this, "课程已创建");
                        } else {
                            db.saveCourse(editing.id, name, teacher, editing.color);
                            Tip.show(MainActivity.this, "课程已更新");
                        }
                        closeSubmit();
                        refresh();
                    }
                });
    }

    private void closeSubmit() {
        if (submitDialog != null) submitDialog.dismiss();
        submitDialog = null;
    }

    private void confirmDeleteCourse(final Db.Course c) {
        Dialogs.confirm(this, "删除课程",
                "确定删除「" + c.name + "」及其所有笔记和待办？此操作不可恢复。",
                "删除", new Runnable() {
                    @Override public void run() {
                        db.deleteCourse(c.id);
                        refresh();
                        Tip.show(MainActivity.this, "课程已删除");
                    }
                });
    }

    private String nextColor() {
        String[] colors = {"#4F5BD5", "#7A5CFF", "#E5484D", "#B45D0C",
                "#0E9F6E", "#2F6FED", "#D63384"};
        return colors[db.courseCount() % colors.length];
    }

    // ================== 设置 ==================

    private void openSettingsSheet() {
        LinearLayout box = Ui.column(this);
        box.setPadding(0, Ui.dp(this, 4), 0, 0);

        box.addView(menuRow(R.drawable.ic_ai, "AI 总结设置", new Runnable() {
            @Override public void run() { openAiSettings(); }
        }));
        box.addView(menuRow(R.drawable.ic_mic, "语音转写设置", new Runnable() {
            @Override public void run() { openTsSettings(); }
        }));
        box.addView(Ui.divider(this));
        box.addView(menuRow(R.drawable.ic_export, "导出 JSON 备份", new Runnable() {
            @Override public void run() { doExportJson(); }
        }));
        box.addView(menuRow(R.drawable.ic_md, "导出 Markdown", new Runnable() {
            @Override public void run() { doExportMd(); }
        }));
        box.addView(menuRow(R.drawable.ic_import, "导入 JSON 备份", new Runnable() {
            @Override public void run() { doImportJson(); }
        }));

        Dialogs.sheet(this, "设置与数据", box);
    }

    private void openAiSettings() {
        Dialogs.form(this, "AI 总结设置",
                new String[]{"API 地址", "API Key", "模型"},
                new String[]{"https://api.deepseek.com/v1", "sk-…", "deepseek-chat"},
                new String[]{Prefs.aiEndpoint(this), Prefs.aiKey(this), Prefs.aiModel(this)},
                new boolean[]{false, false, false},
                new Dialogs.OnSubmit() {
                    @Override public void onSubmit(EditText[] f) {
                        Prefs.saveAi(MainActivity.this,
                                f[0].getText().toString(), f[1].getText().toString(),
                                f[2].getText().toString());
                        closeSubmit();
                        Tip.show(MainActivity.this, "AI 设置已保存");
                    }
                });
    }

    private void openTsSettings() {
        Dialogs.form(this, "语音转写设置",
                new String[]{"转写方式（off / server / api）", "服务地址", "API Key", "模型名"},
                new String[]{"off", "http://127.0.0.1:8080/v1", "（可选）", "whisper-1"},
                new String[]{Prefs.tsMode(this), Prefs.tsEndpoint(this),
                        Prefs.tsKey(this), Prefs.tsModel(this)},
                new boolean[]{false, false, false, false},
                new Dialogs.OnSubmit() {
                    @Override public void onSubmit(EditText[] f) {
                        String mode = f[0].getText().toString().trim();
                        if (!mode.equals("off") && !mode.equals("server") && !mode.equals("api")) {
                            Tip.error(MainActivity.this, "转写方式只能是 off / server / api");
                            return;
                        }
                        String endpoint = f[1].getText().toString().trim();
                        if (!mode.equals("off") && endpoint.length() == 0) {
                            Tip.error(MainActivity.this, "该模式需要填写服务地址");
                            return;
                        }
                        Prefs.saveTs(MainActivity.this, mode, endpoint,
                                f[2].getText().toString(), f[3].getText().toString());
                        closeSubmit();
                        Tip.show(MainActivity.this, "转写设置已保存");
                    }
                });
    }

    // ================== 导入导出 ==================

    private void doExportJson() {
        try {
            pendingExport = Backup.exportJson(this);
            pendingName = "classroom-backup-" + Dates.today() + ".json";
            Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("application/json");
            it.putExtra(Intent.EXTRA_TITLE, pendingName);
            startActivityForResult(it, REQ_EXPORT_JSON);
        } catch (Throwable e) {
            Tip.error(this, "导出失败：" + e.getMessage());
        }
    }

    private void doExportMd() {
        try {
            if (db.totals()[0] == 0) {
                Tip.error(this, "暂无笔记可导出");
                return;
            }
            pendingExport = Backup.exportMarkdown(this);
            pendingName = "classroom-notes-" + Dates.today() + ".md";
            Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("text/markdown");
            it.putExtra(Intent.EXTRA_TITLE, pendingName);
            startActivityForResult(it, REQ_EXPORT_MD);
        } catch (Throwable e) {
            Tip.error(this, "导出失败：" + e.getMessage());
        }
    }

    private void doImportJson() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        startActivityForResult(it, REQ_IMPORT_JSON);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            pendingExport = null;
            return;
        }
        android.net.Uri uri = data.getData();
        try {
            if (req == REQ_EXPORT_JSON || req == REQ_EXPORT_MD) {
                Backup.writeText(this, uri, pendingExport);
                pendingExport = null;
                Tip.show(this, "已导出到所选位置");
            } else if (req == REQ_IMPORT_JSON) {
                final String text = Backup.readText(this, uri);
                Dialogs.confirm(this, "导入备份",
                        "将清空当前所有数据并导入所选备份，确定继续？",
                        "导入", new Runnable() {
                            @Override public void run() {
                                try {
                                    int n = Backup.importJson(MainActivity.this, text);
                                    refresh();
                                    Tip.show(MainActivity.this, "已导入 " + n + " 门课程");
                                } catch (Throwable e) {
                                    Tip.error(MainActivity.this, "导入失败：" + e.getMessage());
                                }
                            }
                        });
            }
        } catch (Throwable e) {
            Tip.error(this, "操作失败：" + e.getMessage());
        }
    }
}