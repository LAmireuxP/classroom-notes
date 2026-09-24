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
 * 结构：Top App Bar / SearchBar / （统计摘要卡 + 课程卡片列表）或（跨课程搜索结果）/ FAB。
 * 搜索框有关键词时整页切成结果态，两者不同时出现——搜索是「专注模式」。
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
    /**
     * 首页入口：先定主题（必须在 super.onCreate 之前，否则状态栏与对话框会用错配色），
     * 再打开数据库、建界面。
     */
    protected void onCreate(Bundle b) {
        // 必须在 super.onCreate 之前：应用主题资源（窗口背景 / 状态栏 / 对话框默认色）。
        // 这里原来漏了，MainActivity 一直吃 manifest 里的浅色主题，
        // 只因为颜色都是代码显式设的才看着正常——状态栏和对话框会不对。
        setTheme(Prefs.isDark(this) ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(b);
        db = Db.get(this);
        // 闹钟不跨重启，且部分 ROM 在「强制停止」时会顺手清掉它。
        // 每次启动重新排一遍是这里最省心的兜底：查一次库 + 几次 set，代价可以忽略。
        Reminders.rescheduleAll(this);
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

    /**
     * 关掉所有还开着的底部抽屉。
     *
     * 对话框是独立 Window，主题在创建那一刻就固定了，换主题时它不会跟着变——
     * 不关掉就会出现「界面已经变深色、抽屉还是白的」。顺带把已经自行关闭的
     * 抽屉从列表里清掉，免得越积越多。
     */
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
    /**
     * 系统深浅色变化时自己重绘（manifest 声明了 uiMode，系统不会替我们重建）。
     * 只在「跟随系统」模式下重绘；浅色/深色是用户的显式选择，不该被系统设置影响。
     */
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        // manifest 把 uiMode 声明进了 configChanges，系统切深浅色时 Activity
        // 不会自动重建。跟随系统模式下必须自己重绘，否则界面停在旧配色。
        if (Prefs.THEME_SYSTEM.equals(Prefs.themeMode(this))) applyTheme();
    }

    @Override
    /**
     * 从别处回来：深浅色变了就整体重新着色（applyTheme 内部会重画内容），
     * 否则至少刷新一次列表——课程/笔记可能在课程页被改过。
     */
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
    /** Dialogs.DialogHost 的实现：记住当前表单对话框（返回键与换主题都要用它）。 */
    public void setSubmitDialog(AlertDialog dlg) { this.submitDialog = dlg; }

    @Override
    /**
     * 返回键：有表单对话框开着就先关它（用户预期是「退出输入」而不是退出页面）。
     */
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
        // 状态栏内边距：只在内容确实画到状态栏下面时才补（见 Ui.padStatusBar）
        Ui.padStatusBar(this, bar);
        column.addView(bar);

        // ---------- 可滚动内容 ----------
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scroller.setLayoutParams(sp);

        contentBox = Ui.column(this);
        // 底部那块是留给 FAB 的滚动余量，不压；上面按纵向比例收紧
        int padH = Ui.dp(this, 16), padTop = Ui.v(this, 8), padBottom = Ui.dp(this, 96);
        contentBox.setPadding(padH, padTop, padH, padBottom);
        scroller.addView(contentBox);
        column.addView(scroller);

        // ---------- FAB（MD3 标准：56dp，距边缘 16dp）----------
        // 和课程页共用 Ui.fab / Ui.placeFab：两处的「新建」是同一套操作
        fab = Ui.fab(this, "新建课程", new Runnable() {
            @Override public void run() { courseDialog(null); }
        });
        Ui.placeFab(rootFrame, fab);

        setContentView(rootFrame);
    }

    /**
     * 顶栏：应用名 + 主题开关 + 设置入口。
     * 主题开关放在这里是因为它是最常被点的设置项——为此多点两层进设置页不值得。
     */
    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.surface(this));
        int padH = Ui.dp(this, 16), padV = Ui.v(this, 12);
        bar.setPadding(padH, padV, Ui.dp(this, 8), padV);

        // 标题区：图标 + 大标题
        LinearLayout titleBox = Ui.row(this);
        // 顶栏标志：用设计稿那个「鹿角 + 话筒 + 声波」的单色矢量。
        // 它由 tools/svg-to-vectordrawable.py 从原设计 SVG 生成（描边改纯色——
        // VectorDrawable 不支持描边渐变，而这里本来就要用 SRC_IN 套主题色，渐变没有意义）。
        // 外边两道声波带 strokeAlpha=0.7，SRC_IN 只换颜色不换 alpha，那层「弱化」保住了。
        ImageView logo = Icons.icon(this, R.drawable.ic_brand, Ui.primary(this), 24);
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

    // ================== 渲染 ==================

    private void refresh() {
        contentBox.removeAllViews();

        // ---- 搜索栏（MD3 SearchBar + 前导图标）----
        // 排在待办进度前面：搜索是「现在就要用」的，进度是「顺便看一眼」的。
        // 首页搜的是**全部课程**（hint 里写明范围）——课程页那一条才是课内搜索。
        LinearLayout searchBox = Ui.searchBarWithIcon(this, "搜索全部课程的笔记…",
                R.drawable.ic_search);
        search = Ui.searchInput(searchBox);
        if (query.length() > 0) search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                // 只重建搜索框下面那块。连搜索框一起重建的话，输入焦点和软键盘
                // 会在每敲一个字时掉一次——这正是原来把重建范围死死限定在列表上的原因。
                fillBelowSearch();
            }
        });
        contentBox.addView(searchBox);

        belowBox = Ui.column(this);
        contentBox.addView(belowBox);
        fillBelowSearch();
    }

    private LinearLayout listBox;
    /** 搜索框下面那块（统计卡 + 课程列表，或者搜索结果）。输入时只重建它。 */
    private LinearLayout belowBox;

    /**
     * 搜索框下方的内容：有关键词就是跨课程搜索结果，否则是统计卡 + 课程列表。
     *
     * 两者不同时出现。搜索是「专注模式」——结果下面再挂一份完整的课程列表，
     * 用户会分不清哪块是结果、哪块是导航。
     */
    private void fillBelowSearch() {
        if (belowBox == null) return;
        belowBox.removeAllViews();

        if (query.length() > 0) {
            belowBox.addView(searchResults());
            return;
        }

        // ---- 统计摘要。一门课都没有时不画：那时候还不存在「待办」这件事，
        //      页面上该出现的只有「去建第一门课」这一个引导 ----
        if (db.courseCount() > 0) belowBox.addView(statsSummary());
        belowBox.addView(courseListContainer());
    }

    /** 课程列表容器。 */
    private View courseListContainer() {
        listBox = Ui.column(this);
        fillCourseList();
        return listBox;
    }

    /**
     * 画课程列表：空状态 / 「我的课程」标题行 + 课程行 + 行间分隔线。
     * 每次调用都 removeAllViews() 重建——列表规模在「一屏到几十门」之间，
     * 重建比做增量 diff 简单得多，也不会出现状态残留（搜索词、展开态）。
     */
    private void fillCourseList() {
        listBox.removeAllViews();
        List<Db.Course> courses = db.courses();

        if (courses.isEmpty()) {
            listBox.addView(emptyState(R.drawable.ic_folder, "还没有课程",
                    "点击右下角 + 新建第一门课程"));
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

        // 笔记数跟课程数并排（原来在顶部统计卡里，那里现在只讲待办进度）
        TextView noteCount = Ui.text(this, "· " + db.totals()[0] + " 条笔记",
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = Ui.dp(this, 8);
        noteCount.setLayoutParams(nlp);
        secHead.addView(noteCount);
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shp.topMargin = Ui.v(this, 4);
        shp.bottomMargin = Ui.v(this, 12);
        secHead.setLayoutParams(shp);
        listBox.addView(secHead);

        for (int i = 0; i < courses.size(); i++) {
            listBox.addView(courseCard(courses.get(i)));
            // 行间内嵌分隔线（MD3 列表规范），最后一行不加
            if (i < courses.size() - 1) listBox.addView(divider());
        }
    }

    /**
     * 搜索结果 —— 跨全部课程搜笔记。
     *
     * 每行都带课程名和课程色点：跨课程搜出来的标题如果不标明来自哪门课，
     * 用户得逐个点进去确认，搜索反而比翻课程列表更慢。
     */
    private View searchResults() {
        List<Db.NoteHit> hits = db.searchNotes(query);

        LinearLayout box = Ui.column(this);

        // 计数行：与课程页「共 N 条笔记」同一个位置、同一种语气
        TextView count = Ui.text(this,
                hits.isEmpty() ? "没有匹配的笔记" : "找到 " + hits.size() + " 条笔记",
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = Ui.v(this, 10);
        count.setLayoutParams(cp);
        box.addView(count);

        if (hits.isEmpty()) {
            box.addView(emptyState(R.drawable.ic_md, "没有匹配的笔记",
                    "换个关键词试试，搜索覆盖全部课程的标题、正文和重点"));
            return box;
        }

        for (int i = 0; i < hits.size(); i++) {
            box.addView(searchHit(hits.get(i)));
            if (i < hits.size() - 1) box.addView(divider());
        }
        return box;
    }

    /**
     * 一条搜索结果。点进去打开所属课程，并把那条笔记展开、滚到眼前——
     * 只跳到课程页的话，用户还得在列表里再找一遍，等于没搜。
     */
    private View searchHit(final Db.NoteHit h) {
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.v(this, 2);
        row.setLayoutParams(rlp);
        int padH = Ui.dp(this, 14), padV = Ui.v(this, 14);
        row.setPadding(padH, padV, padH, padV);
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_M));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Ui.vMin(this, 64));

        // 色点用**课程色**而不是主题色：一眼看出这条来自哪门课
        View dot = new View(this);
        dot.setBackground(Ui.circle(Ui.parseColor(h.courseColor, Ui.primary(this)),
                Ui.dp(this, 10)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 10), Ui.dp(this, 10));
        dlp.rightMargin = Ui.dp(this, 14);
        dot.setLayoutParams(dlp);
        row.addView(dot);

        LinearLayout mid = Ui.column(this);
        mid.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 头行：笔记标题 + 日期
        LinearLayout head = Ui.row(this);
        TextView title = Ui.text(this, nz(h.note.title), Ui.T_TITLE,
                Ui.onSurface(this), true);
        title.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title);
        TextView date = Ui.text(this, Dates.shortDate(h.note.date), Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams dtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dtp.leftMargin = Ui.dp(this, 8);
        date.setLayoutParams(dtp);
        head.addView(date);
        mid.addView(head);

        // 次行：课程名 + 正文摘要。课程名放最前面——它是这一行里最需要先看到的信息
        String courseName = (h.courseName == null || h.courseName.length() == 0)
                ? "未分类" : h.courseName;
        TextView meta = Ui.text(this, courseName + " · " + Ui.preview(h.note.content, 60),
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
        meta.setMaxLines(2);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mtp.topMargin = Ui.v(this, 3);
        meta.setLayoutParams(mtp);
        mid.addView(meta);
        row.addView(mid);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent it = new Intent(MainActivity.this, CourseActivity.class);
                it.putExtra("courseId", h.note.courseId);
                it.putExtra("noteId", h.note.id);     // 让课程页把这条展开并滚到眼前
                startActivity(it);
            }
        });
        return row;
    }

    /** 列表行之间的内嵌分隔线（MD3 列表规范），左边留出圆点那一段的宽度。 */
    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(Ui.outlineVariant(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.8f)));
        lp.leftMargin = Ui.dp(this, 38);
        line.setLayoutParams(lp);
        return line;
    }

    /** null 安全取字符串：库里空字段读出来是 null，界面一律按空串处理。 */
    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * 顶部统计摘要 —— 只讲待办：按优先级三行进度（圆点 + 数字 + 条），纵向排开。
     * 整张卡可点，通往「全部待办」页（跨课程、按截止日期分段）。
     *
     * 课程数 / 笔记数挪到「我的课程」标题右边了：那两个数字是「有哪些东西」，
     * 和这里的「做得怎么样」不是一回事，挤在一行里谁也读不痛快。
     *
     * 没有待办时这张卡**照样出现**（原来是不出现的）：它现在是通往全部待办页的唯一入口，
     * 藏起来就等于那个页面没有入口。此时只显示「暂无待办」，不画那三行空进度条。
     */
    private View statsSummary() {
        int[] t = db.totals();
        int done = t[2], total = t[1];

        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.ripple(this, Ui.surfaceContainer(this), Ui.R_M));
        int ph = Ui.dp(this, 14), pv = Ui.v(this, 14);
        card.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.v(this, 16);
        card.setLayoutParams(lp);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AllTodosActivity.class));
            }
        });

        LinearLayout line = Ui.row(this);
        if (total == 0) {
            line.addView(inlineLabel("暂无待办"));
        } else {
            line.addView(inlineNum(done + "/" + total));
            line.addView(inlineLabel(" 待办完成"));
        }
        line.addView(Ui.spacer(this));
        // 「全部」+ 右向箭头：让这张卡看起来可点（它确实可点——通往全部待办页）
        line.addView(inlineLabel("全部"));
        ImageView chevron = Icons.icon(this, R.drawable.ic_chevron_down,
                Ui.onSurfaceVariant(this), 18);
        chevron.setRotation(-90f);      // 下箭头转 90° 当右箭头用，省一个图标资源
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                Ui.dp(this, 18), Ui.dp(this, 18));
        chp.leftMargin = Ui.dp(this, 4);
        chevron.setLayoutParams(chp);
        line.addView(chevron);
        card.addView(line);

        if (total > 0) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = Ui.v(this, 12);
            View bars = Ui.priorityProgress(this, db.todoByPriority());
            bars.setLayoutParams(bp);
            card.addView(bars);
        }

        return card;
    }

    /** 统计行里的数字：用 onSurface 加粗，比旁边的标签更抢眼。 */
    private TextView inlineNum(String s) {
        return Ui.text(this, s, Ui.T_BODY, Ui.onSurface(this), true);
    }

    /** 统计行里的标签文字：弱一号的 onSurfaceVariant。 */
    private TextView inlineLabel(String s) {
        return Ui.text(this, s, Ui.T_BODY, Ui.onSurfaceVariant(this), false);
    }

    /** 课程行 —— 扁平列表行 + 色点标识（不用 border-left 色条，遵守 craft-floor）。 */
    private View courseCard(final Db.Course c) {
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.v(this, 2);
        row.setLayoutParams(rlp);
        int padH = Ui.dp(this, 14), padV = Ui.v(this, 14);
        row.setPadding(padH, padV, Ui.dp(this, 6), padV);
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_M));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Ui.vMin(this, 64));

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
        mtp.topMargin = Ui.v(this, 3);
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

    /**
     * 空状态。空状态要「教学」而不只是「暂无」——给一个能立刻做的动作，
     * 或者一条能立刻试的下一步，而不是一句冷冰冰的提示。
     * 参数化是因为首页现在有两种空状态：一门课都没建，和搜索没有命中。
     */
    private View emptyState(int iconRes, String titleText, String hint) {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER);
        int ph = Ui.dp(this, 48), pv = Ui.v(this, 48);
        box.setPadding(ph, pv, ph, pv);

        LinearLayout iconCircle = new LinearLayout(this);
        iconCircle.setGravity(Gravity.CENTER);
        iconCircle.setBackground(Ui.round(this, Ui.surfaceHigh(this), Color.TRANSPARENT, Ui.R_FULL, 0));
        LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(
                Ui.dp(this, 88), Ui.dp(this, 88));
        icp.gravity = Gravity.CENTER;
        iconCircle.setLayoutParams(icp);
        iconCircle.addView(Icons.icon(this, iconRes, Ui.onSurfaceVariant(this), 40));
        box.addView(iconCircle);

        TextView title = Ui.text(this, titleText, Ui.T_TITLE, Ui.onSurface(this), true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.v(this, 18);
        title.setLayoutParams(tp);
        box.addView(title);

        TextView sub = Ui.text(this, hint, Ui.T_BODY,
                Ui.onSurfaceVariant(this), false);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.v(this, 6);
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

    /** 抽屉行（默认 onSurface 色）：见下面带 color 的重载。 */
    private View menuRow(int iconRes, String label, final Runnable action) {
        return menuRow(iconRes, label, Ui.onSurface(this), action);
    }

    /** 抽屉里的一行：图标 + 文案 + 点击动作（本身是骨架，带颜色的重载见下）。 */
    private View menuRow(int iconRes, String label, int color, final Runnable action) {
        LinearLayout row = Ui.row(this);
        int padH = Ui.dp(this, 20), padV = Ui.v(this, 14);
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

    /**
     * 新建 / 编辑课程。editing 为 null 就是新建。
     * 标识色不在这个表单里选——新课程按 7 色轮转自动分配（见 nextColor），
     * 让用户从「给课程选个颜色」这种小事里解放出来。
     */
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

    /** 关掉当前表单对话框（保存成功后的收尾）。重复调用安全。 */
    private void closeSubmit() {
        if (submitDialog != null) submitDialog.dismiss();
        submitDialog = null;
    }

    /**
     * 删除课程前确认。文案里写明「连笔记和待办一起删」——这是本 App 里牵连最广的操作。
     *
     * 不再写「不可恢复」：现在删除是软删，进回收站（设置 → 回收站）可以整门恢复，
     * 连它的笔记和待办一起回来。说「不可恢复」会让用户白白不敢用，或者删完以为没救了。
     */
    private void confirmDeleteCourse(final Db.Course c) {
        Dialogs.confirm(this, "删除课程",
                "确定删除「" + c.name + "」及其所有笔记和待办？\n"
                        + "删掉的内容会放进回收站，之后可以恢复。",
                "删除", new Runnable() {
                    @Override public void run() {
                        // 先撤这门课下所有待办的提醒，再标记删除：反过来先删的话，
                        // 那些闹钟就成了指向已删除待办的孤儿，到点会弹一条空提醒
                        Reminders.cancelCourse(MainActivity.this, c.id);
                        db.deleteCourse(c.id);
                        refresh();
                        Tip.success(MainActivity.this, "课程已移入回收站");
                    }
                });
    }

    /**
     * 下一门课的标识色：按已有课程数在 7 色里轮转。
     * 只保证「相邻的两门不同色」，不追求全局不重复——用完后循环比调色板用尽更好。
     */
    private String nextColor() {
        String[] colors = {"#4F5BD5", "#7A5CFF", "#E5484D", "#B45D0C",
                "#0E9F6E", "#2F6FED", "#D63384"};
        return colors[db.courseCount() % colors.length];
    }

}
