package com.lamireuxp.classroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
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

    private Db db;
    private ScrollView scroller;
    private LinearLayout contentBox;   // 列表 + 统计的容器（会重建）
    private EditText search;
    private LinearLayout fab;
    private AlertDialog submitDialog;
    /** 当前打开的抽屉。换主题时要把它们一起关掉——对话框不跟随主题。 */
    private final java.util.List<AlertDialog> openSheets = new java.util.ArrayList<AlertDialog>();

    private String query = "";
    /** 上次渲染时的深浅状态，用来判断从设置页回来要不要重新着色。 */
    private boolean renderedDark;

    @Override
    protected void onCreate(Bundle b) {
        // 必须在 super.onCreate 之前：应用主题资源（窗口背景 / 状态栏 / 对话框默认色）。
        // 这里原来漏了，MainActivity 一直吃 manifest 里的浅色主题，
        // 只因为颜色都是代码显式设的才看着正常——状态栏和对话框会不对。
        setTheme(Prefs.isDark(this) ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(b);
        db = Db.get(this);
        applyWindowTheme();
        buildUi();
    }

    /**
     * 显示抽屉并登记。
     *
     * 对话框是独立的 Window，主题在创建那一刻就固定了；换主题时它不会跟着变，
     * 所以 applyTheme() 要把还开着的抽屉一起关掉——否则会出现「界面已经变深色，
     * 抽屉还是白的」这种不一致。
     */
    private AlertDialog showSheet(String title, View body) {
        for (int i = openSheets.size() - 1; i >= 0; i--) {
            if (!openSheets.get(i).isShowing()) openSheets.remove(i);
        }
        AlertDialog dlg = Dialogs.sheet(this, title, body);
        openSheets.add(dlg);
        return dlg;
    }

    private void dismissSheets() {
        for (AlertDialog d : openSheets) {
            if (d.isShowing()) d.dismiss();
        }
        openSheets.clear();
    }

    /**
     * 就地应用主题：不重建 Activity，只把窗口装饰和整页重新着色。
     *
     * 为什么不 recreate()：重建会有一下闪烁，而且会把正在播放的动画打断。
     * 本 App 的颜色全部来自 Ui.tone()（直接读 Prefs）、视图也全是代码构建的，
     * 所以「重新构建一遍」和 recreate 的视觉效果等价，但没有闪烁。
     */
    private void applyTheme() {
        dismissSheets();
        applyWindowTheme();
        buildUi();
        refresh();
    }

    /** 窗口层（状态栏 / 导航栏 / 图标明暗）。 */
    private void applyWindowTheme() {
        Ui.applyWindowTheme(this);
        renderedDark = Ui.isDark(this);
    }

    @Override
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        // manifest 把 uiMode 声明进了 configChanges，系统切深浅色时 Activity
        // 不会自动重建。跟随系统模式下必须自己重绘，否则界面停在旧配色。
        if (Prefs.THEME_SYSTEM.equals(Prefs.themeMode(this))) applyTheme();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 主题可能在设置页被改过，回来时要重新着色（applyTheme 内部会 refresh）
        if (renderedDark != Ui.isDark(this)) {
            applyTheme();
        } else {
            refresh();
        }
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

        // 主题开关（胶囊 + 月亮/太阳，参考 galaxy Toggle-switches）
        // 按下即生效：开关内部写偏好后回调这里就地重绘，不等动画、不重建 Activity
        View themeSwitch = ThemeSwitch.create(this, new Runnable() {
            @Override public void run() { applyTheme(); }
        });
        LinearLayout.LayoutParams tsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsp.rightMargin = Ui.dp(this, 6);
        bar.addView(themeSwitch, tsp);

        // 设置
        LinearLayout settingsBtn = Icons.iconButtonFilled(this, R.drawable.ic_settings,
                44, Ui.surfaceHighest(this), Ui.onSurfaceVariant(this));
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 设置改成独立界面，不再弹底部抽屉
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
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

        // 进度条：整条宽度 = 全部待办，按优先级切成三段，每段自己显示完成度。
        // 原来是一条 primary 单色进度线，只说得出总完成度，看不出没做完的那部分
        // 压在哪个优先级上——而「高中低」才是待办最该被看见的维度。
        if (total > 0) {
            int[][] byP = db.todoByPriority();
            card.addView(priorityBar(byP));
            card.addView(priorityLegend(byP));
        }

        return card;
    }

    /**
     * 三段进度条：一段一个优先级，颜色区分（高=error 红 / 中=primary / 低=success 绿）。
     * 段宽 = 该优先级的待办占比（三段合起来正好是全部待办），段内实心部分 = 已完成的占比，
     * 底槽用同色的浅版。段间留 2dp 缝，看得出是三段而不是一条渐变。
     */
    private View priorityBar(int[][] byP) {
        LinearLayout bar = Ui.row(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        bp.topMargin = Ui.dp(this, 12);
        bar.setLayoutParams(bp);

        boolean first = true;
        for (int i = 0; i < 3; i++) {
            int segTotal = byP[i][0];
            if (segTotal == 0) continue;          // 这个优先级没有待办就不占宽度
            int color = priorityColor(i);

            LinearLayout seg = new LinearLayout(this);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, segTotal);
            if (!first) sp.leftMargin = Ui.dp(this, 2);
            first = false;
            seg.setLayoutParams(sp);
            seg.setBackground(Ui.round(this, Ui.withAlpha(color, 0.18f),
                    Color.TRANSPARENT, Ui.R_FULL, 0));

            // 完成度用「已完成 / 未完成」两个带权重的子 View 表示。
            // 不能只放一个权重为完成比的 fill：LinearLayout 是按**权重之和**分配剩余空间的，
            // 独苗子 View 无论权重多小都会吃掉整段——原来那条单色进度线就是这么写的，
            // 所以它不管完成多少都画成满格（1/2 也是满的）。
            View donePart = new View(this);
            donePart.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, byP[i][1]));
            donePart.setBackground(Ui.round(this, color, Color.TRANSPARENT, Ui.R_FULL, 0));
            seg.addView(donePart);

            int rest = segTotal - byP[i][1];
            if (rest > 0) {
                View restPart = new View(this);   // 透明，露出同色的浅底槽
                restPart.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, rest));
                seg.addView(restPart);
            }
            bar.addView(seg);
        }
        return bar;
    }

    /** 图例：三个颜色不写清楚没人猜得到含义，「高 1/2」把颜色和优先级对上。 */
    private View priorityLegend(int[][] byP) {
        String[] names = {"高", "中", "低"};
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(this, 8);
        row.setLayoutParams(rp);

        for (int i = 0; i < 3; i++) {
            if (byP[i][0] == 0) continue;
            if (row.getChildCount() > 0) {
                View gap = new View(this);
                gap.setLayoutParams(Ui.lp(Ui.dp(this, 14), 1));
                row.addView(gap);
            }
            View dot = new View(this);
            dot.setBackground(Ui.circle(priorityColor(i), Ui.dp(this, 6)));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 6), Ui.dp(this, 6));
            dp.rightMargin = Ui.dp(this, 5);
            dp.gravity = Gravity.CENTER_VERTICAL;
            dot.setLayoutParams(dp);
            row.addView(dot);
            row.addView(Ui.text(this, names[i] + " " + byP[i][1] + "/" + byP[i][0],
                    Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
        }
        return row;
    }

    /** 优先级配色：查 Ui 里那一份映射（高=红 / 中=主题色 / 低=绿），
     * 「新建待办」的选项行用的是同一份，改色只改一处。 */
    private int priorityColor(int idx) {
        return Ui.priorityColor(this, Db.PRIORITY_KEYS[idx]);
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

        showSheet(c.name, box);
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
            @Override public void onClick(View v) {
                // 先收掉抽屉再执行动作。原来不关：点「删除课程」弹确认框时抽屉还压在底下，
                // 确认框关掉后抽屉露出来，标题和内容都对不上了。
                dismissSheets();
                action.run();
            }
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
                            Tip.success(MainActivity.this, "课程已创建");
                        } else {
                            db.saveCourse(editing.id, name, teacher, editing.color);
                            Tip.success(MainActivity.this, "课程已更新");
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
                        Tip.success(MainActivity.this, "课程已删除");
                    }
                });
    }

    private String nextColor() {
        String[] colors = {"#4F5BD5", "#7A5CFF", "#E5484D", "#B45D0C",
                "#0E9F6E", "#2F6FED", "#D63384"};
        return colors[db.courseCount() % colors.length];
    }

}
