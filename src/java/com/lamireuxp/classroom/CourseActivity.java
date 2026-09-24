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
import android.widget.FrameLayout;
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
    /** API 33+ 的通知权限申请码。和麦克风分开，回调里才能分辨是哪一项被拒。 */
    private static final int REQ_NOTIF = 202;

    private Db db;
    private String courseId;
    private Db.Course course;

    private LinearLayout content;
    private LinearLayout tabBar;
    private LinearLayout recPanel;
    /** 页面根容器。加载条挂在这里而不是 content 上——content 会被 renderContent() 反复清空重建。 */
    private LinearLayout pageRoot;
    /** 正文滚动容器。从首页搜索结果跳进来时要把那条笔记滚到眼前。 */
    private ScrollView scroller;

    private String tab = "notes";
    private String query = "";
    private String expandedNoteId = null;
    /** 从首页搜索结果跳进来时，要展开并滚到眼前的那条笔记；滚过一次就清掉。 */
    private String focusNoteId;
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

    /**
     * 停掉录音脉冲动画。
     * 常驻循环动画即使视图被移除也会继续跑（白耗帧 + 漏引用），所以每次重建内容之前
     * 都必须调它；单个动画取消失败不影响其它，包 try 继续。
     */
    private void cancelRecPulses() {
        for (android.animation.ObjectAnimator a : recPulses) {
            try { a.cancel(); } catch (Throwable ignored) {}
        }
        recPulses.clear();
    }

    @Override
    /**
     * 进入课程页：先定主题，再取课程数据，最后建界面。
     * 课程取不到就直接 finish()——比如用户在首页删了这门课再按返回键回来，
     * 硬撑下去只会在渲染时到处空指针。
     */
    protected void onCreate(Bundle b) {
        // 必须在 super.onCreate 之前：应用主题资源
        setTheme(Prefs.isDark(this) ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(b);
        db = Db.get(this);
        courseId = getIntent().getStringExtra("courseId");
        course = db.course(courseId);
        if (course == null) { finish(); return; }
        // 从首页搜索结果点进来会带 noteId：直接把那条展开，用户不用在列表里再找一遍
        focusNoteId = getIntent().getStringExtra("noteId");
        if (focusNoteId != null) expandedNoteId = focusNoteId;
        // 提醒通知 / 全部待办页会带 tab=todos，落在待办页签上而不是默认的笔记
        String wantTab = getIntent().getStringExtra("tab");
        if ("todos".equals(wantTab)) tab = "todos";
        applyWindowTheme();
        buildUi();
    }

    /** 窗口层（状态栏 / 导航栏 / 图标明暗）。 */
    private void applyWindowTheme() {
        Ui.applyWindowTheme(this);
    }

    @Override
    /**
     * 系统深浅色变化时自己重建（manifest 里声明了 uiMode，系统不会替我们重建）。
     * 只在「跟随系统」模式下重建；正在录音时不动，免得把录音会话弄丢。
     */
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        // uiMode 在 manifest 的 configChanges 里声明过，系统切深浅色时不会自动重建。
        // 跟随系统模式下必须自己重建；正在录音时先不动，免得把录音会话弄丢。
        if (Prefs.THEME_SYSTEM.equals(Prefs.themeMode(this)) && !isRecording()) recreate();
    }

    @Override
    /**
     * 回到前台重画一次：内容可能被别处改过（从设置页回来、导入备份、改了 AI 配置）。
     * 录音中不重画——那会把正在显示的录音面板顶掉。
     */
    protected void onResume() {
        super.onResume();
        if (!isRecording()) renderContent();
    }

    @Override
    /**
     * 释放：中止语音会话（会连临时录音文件一起删）、停计时器、停加载条、停脉冲动画。
     * speech.cancel() 而不是 stop()：用户已经离开这个页面，不该再回调出笔记。
     */
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

    /**
     * 关掉常驻加载条。必须在每次 renderContent() 之前调用：加载条里是无限循环的微光动画，
     * 视图被移除后还在跑就成了漏网动画。
     */
    private void hideLoading() {
        if (loading != null) {
            loading.stop();
            loading = null;
        }
    }

    @Override
    /** Dialogs.DialogHost 的实现：记住当前表单对话框，换主题时要把它一起关掉（对话框不跟随主题）。 */
    public void setSubmitDialog(AlertDialog dlg) { this.submitDialog = dlg; }

    @Override
    /**
     * 返回键：录音中先确认。录音是「不可恢复」的输入，误退出等于白录一节课；
     * 确认后结束会话，识别到的文字照样会存成笔记。
     */
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
        // 外面套一层 FrameLayout：FAB 要浮在内容右下角，和主页面同一套操作
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Ui.surface(this));
        LinearLayout page = Ui.column(this);
        rootFrame.addView(page);
        setContentView(rootFrame);
        pageRoot = page;

        // 顶栏 + 状态栏内边距（只在内容确实画到状态栏下面时才补）
        View tb = topBar();
        Ui.padStatusBar(this, tb);
        page.addView(tb);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(sp);
        scroller = sv;

        LinearLayout body = Ui.column(this);
        int bp = Ui.dp(this, 16);
        // 底部留出 FAB 的滚动余量，最后一行才不会被加号压住
        body.setPadding(bp, Ui.dp(this, 4), bp, Ui.dp(this, 96));
        sv.addView(body);
        page.addView(sv);

        // FAB：建当前页签对应的东西——笔记页签建笔记，待办页签建待办
        Ui.placeFab(rootFrame, Ui.fab(this, "新建", new Runnable() {
            @Override public void run() {
                if ("notes".equals(tab)) noteDialog(null);
                else todoDialog();
            }
        }));

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
        // 页签在上、搜索框在下：切换内容类型比搜索更「外层」，
        // 搜索只作用于当前那一类内容
        tabBar = Ui.row(this);
        body.addView(tabBar);

        LinearLayout.LayoutParams sbp =
                (LinearLayout.LayoutParams) searchBox.getLayoutParams();
        sbp.topMargin = Ui.v(this, 10);          // 与页签的间距（下方边距在 Ui 里已给）
        body.addView(searchBox);

        content = Ui.column(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.v(this, 16);
        content.setLayoutParams(cp);
        body.addView(content);

        renderTabs();
        renderContent();
    }

    /** 顶栏：返回 + 课程名（过长截断）+ 编辑入口。 */
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
            sp.topMargin = Ui.v(this, 2);
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

    /** 编辑课程：改名与教师（标识色不在课程页改，避免和首页的色点语义重复）。 */
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
        tabBar.setPadding(Ui.dp(this, 4), Ui.v(this, 4), Ui.dp(this, 4), Ui.v(this, 4));

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
        int padV = Ui.v(this, 10);
        seg.setPadding(0, padV, 0, padV);
        seg.setMinimumHeight(Ui.vMin(this, 44));

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

    /** 笔记页签上的数字（全部笔记，不区分是否含重点）。 */
    private int countNotes() {
        int n = 0;
        for (Db.Note x : db.notes(courseId, null)) n++;
        return n;
    }

    /**
     * 待办页签上的数字：**只算未完成**。
     * 页签上的数字回答的是「这门课还有多少事」，把已完成的也算进去就没意义了。
     */
    private int countOpenTodos() {
        int n = 0;
        for (Db.Todo t : db.todos(courseId)) if (!t.completed) n++;
        return n;
    }

    /**
     * 按当前页签重建内容区。
     * 每次重建都先清掉脉冲动画与录音面板引用：旧视图被移除后，那些句柄就是野引用了。
     */
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
        // 「新建笔记」挪到右下角 FAB 了（和主页面一致），这一行只剩录音

        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.bottomMargin = Ui.v(this, 18);
        actions.setLayoutParams(ap);
        content.addView(actions);

        List<Db.Note> notes = db.notes(courseId, query.length() > 0 ? query : null);

        // 计数摘要
        TextView count = Ui.text(this, "共 " + notes.size() + " 条笔记", Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Ui.v(this, 10);
        count.setLayoutParams(clp);
        content.addView(count);

        if (notes.isEmpty()) {
            content.addView(emptyState(R.drawable.ic_md,
                    query.length() > 0 ? "没有匹配的笔记" : "记录第一节课",
                    query.length() > 0 ? "试试换个关键词" : "点右下角加号写下课堂要点"));
            return;
        }
        View focus = null;
        for (Db.Note n : notes) {
            View rv = noteRow(n);
            content.addView(rv);
            if (n.id.equals(focusNoteId)) focus = rv;
        }
        scrollToRow(focus);
    }

    /**
     * 把某一行滚到可视区顶部（从首页搜索结果跳进来时用）。
     *
     * 为什么不在 renderNotes 里直接 scrollTo：那一刻视图还没测量过，所有 getTop()
     * 都还是 0，算出来必然是 0——表现就是「完全没滚」。所以挂一个 preDraw 回调，
     * 等布局完成再算。onPreDraw 一定发生在布局之后、绘制之前，是拿得到真实坐标的最早时机
     * （post() 不行：它排出去的任务很可能还在首次布局之前）。
     *
     * 三个坑，都是真机实测踩出来的：
     *
     *  1. getTop() 是相对**各自父容器**的。要逐级累加到 ScrollView 的直接子节点为止，
     *     只取行自己的 getTop() 会漏掉 TabBar 与搜索框那一段高度，停在错的位置。
     *
     *  2. 视图已挂载时 getViewTreeObserver() 返回的是**整个窗口共享**的那一个观察者，
     *     回调会在每帧绘制前都来一次；而 onResume() 又会重建一次列表、再注册一个。
     *     不设「只生效一次」的闸，残留回调就会反复触发滚动动画，用户手动滚动会被
     *     一直拽回来。所以每个回调自带一次性开关，并且确认自己那一行还在树上才动手
     *     （被重建掉的那一份坐标已经作废，交给新视图去做）。
     *
     *  3. focusNoteId 要等到真滚了才清：onResume() 那次重建会把这一行换成新视图，
     *     清早了新视图就认不出该滚哪一行，而旧视图上的回调也没机会执行，最终谁都不滚。
     */
    private void scrollToRow(final View target) {
        if (target == null || scroller == null) return;
        final boolean[] done = new boolean[1];
        target.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (done[0]) return true;          // 本回调只生效一次
                done[0] = true;
                try {
                    target.getViewTreeObserver().removeOnPreDrawListener(this);
                } catch (Throwable ignored) { /* 摘不掉也不影响下面的判断 */ }

                View body = scroller.getChildAt(0);
                // 这一行已经被 onResume 那次重建换掉了：坐标作废，让新视图去滚
                if (body == null || !isDescendant(target, body)) return true;
                if (focusNoteId == null) return true;   // 已经滚过了

                int y = 0;
                View v = target;
                while (v != null && v != body) {
                    y += v.getTop();
                    android.view.ViewParent p = v.getParent();
                    v = (p instanceof View) ? (View) p : null;
                }
                focusNoteId = null;
                // 目标靠近列表末尾时，内容高度不够，ScrollView 会自动钳到最大滚动量，
                // 这一行落在视口中下部而不是顶端——这是正常的，不是算错了。
                scroller.smoothScrollTo(0, Math.max(0, y - Ui.dp(CourseActivity.this, 8)));
                return true;      // true = 继续这次绘制，不要拦
            }
        });
    }

    /** target 是否还挂在 root 这棵子树上（行被重建后，旧引用就不在树上了）。 */
    private static boolean isDescendant(View target, View root) {
        View v = target;
        while (v != null) {
            if (v == root) return true;
            android.view.ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        return false;
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
        rlp.bottomMargin = Ui.v(this, 4);
        row.setLayoutParams(rlp);
        row.setBackground(Ui.ripple(this, expanded ? Ui.surfaceContainer(this)
                : Color.TRANSPARENT, Ui.R_M));
        int ph = Ui.dp(this, 12), padV = Ui.v(this, 12);
        row.setPadding(ph, padV, ph, padV);

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
        bp.topMargin = Ui.v(this, 6);
        bp.leftMargin = Ui.dp(this, 17);
        bodyTv.setLayoutParams(bp);
        row.addView(bodyTv);

        // ---- 重点（展开时才显示详情）----
        if (expanded && n.keyPoints != null && !n.keyPoints.isEmpty()) {
            LinearLayout kpBox = Ui.column(this);
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            kp.topMargin = Ui.v(this, 10);
            kp.leftMargin = Ui.dp(this, 17);
            kpBox.setLayoutParams(kp);
            kpBox.setBackground(Ui.round(this, Ui.surfaceContainer(this),
                    Color.TRANSPARENT, Ui.R_S, 0));
            int kph = Ui.dp(this, 12), kpv = Ui.v(this, 12);
            kpBox.setPadding(kph, kpv, kph, kpv);

            TextView kTitle = Ui.text(this, "重点", Ui.T_LABEL, Ui.primary(this), true);
            kpBox.addView(kTitle);
            for (String k : n.keyPoints) {
                TextView item = Ui.text(this, "· " + k, Ui.T_BODY,
                        Ui.onSurfaceVariant(this), false);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ip.topMargin = Ui.v(this, 4);
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
            op.topMargin = Ui.v(this, 12);
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
        // 不写「不可恢复」：删除是软删，进回收站可以恢复。
        // 提示语里点明去哪恢复——用户最需要「我刚才删错了」的答案就在这一句里。
        Dialogs.confirm(this, "删除笔记", "确定删除「" + nz(n.title) + "」？\n"
                        + "删掉的笔记会放进回收站，之后可以恢复。",
                "删除", new Runnable() {
                    @Override public void run() {
                        db.deleteNote(n.id);
                        if (n.id.equals(expandedNoteId)) expandedNoteId = null;
                        renderTabs();
                        renderContent();
                        Tip.success(CourseActivity.this,
                                "笔记已移入回收站（设置 → 回收站 可恢复）");
                    }
                });
    }

    /**
     * 列表里的一行摘要：压掉换行、截到 90 字。
     * 截断规则本体在 Ui.preview —— 首页搜索结果也用同一套，免得两处长度漂开。
     */
    private String preview(String text) {
        return Ui.preview(text, 90);
    }

    // ================== 待办 ==================

    private void renderTodos() {
        List<Db.Todo> todos = db.todos(courseId);
        if (todos.isEmpty()) {
            content.addView(emptyState(R.drawable.ic_list, "添加第一个待办",
                    "点右下角加号，把作业和复习拆成小任务"));
            return;
        }

        int open = 0;
        for (Db.Todo t : todos) if (!t.completed) open++;
        TextView summary = Ui.text(this, open + " 项未完成 · 共 " + todos.size() + " 项",
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = Ui.v(this, 10);
        summary.setLayoutParams(slp);
        content.addView(summary);

        // 这门课的按优先级进度（只统计当前课程）。主页面那张卡是全部课程的口径，
        // 两处用的是同一个 Ui.priorityProgress，样子和颜色一致。
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.bottomMargin = Ui.v(this, 10);
        View progress = Ui.priorityProgress(this, db.todoByPriority(courseId));
        progress.setLayoutParams(plp);
        content.addView(progress);

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

    /** 分组小标题（「进行中」/「已完成」），比正文小一号、带上下间距。 */
    private View sectionLabel(String text) {
        TextView tv = Ui.text(this, text, Ui.T_LABEL, Ui.onSurfaceVariant(this), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.v(this, 14);
        lp.bottomMargin = Ui.v(this, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    /**
     * 一行待办：左侧圆形勾选框（点一下切换完成）+ 标题（完成后加删除线并变浅）
     * + 「优先级 · 截止」副标题 + 右侧删除。
     * 颜色与图标都走 Ui 的语义色，深浅主题自动跟着变。
     */
    private View todoRow(final Db.Todo t) {
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.v(this, 2);
        row.setLayoutParams(lp);
        int ph = Ui.dp(this, 12), padV = Ui.v(this, 12);
        row.setPadding(ph, padV, ph, padV);
        row.setMinimumHeight(Ui.vMin(this, 56));

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
                // 勾掉就不该再提醒：不撤的话到点还会弹一条已经做完的任务。
                // 取消勾选不重排——原来的提醒时刻多半已经过去了，重排只会立刻响一声。
                if (!t.completed) Reminders.cancel(CourseActivity.this, t.id);
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
        // 提醒时刻也写进副标题：设过提醒的待办要一眼能看出来，
        // 否则用户不确定「到底设上了没有」，只能再点开表单确认。
        if (t.remindAt > 0) sub += " · 提醒 " + Dates.stamp(t.remindAt);
        TextView subTv = Ui.text(this, sub, Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
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
                Reminders.cancel(CourseActivity.this, t.id);   // 删了就别再提醒
                renderTabs();
                renderContent();
                Tip.success(CourseActivity.this, "待办已移入回收站");
            }
        });
        row.addView(del);
        return row;
    }

    /**
     * 优先级全名（「高优先级」）。名字与颜色的映射都在 Ui 里，和主页进度条、
     * 新建待办的选项行共用一份——三处各写一套迟早对不上。
     */
    private String priorityLabel(String p) {
        // 名字和颜色都归 Ui 管，进度条、图例、分段选项共用一份，免得各写一套
        return Ui.priorityName(p);
    }

    /** 空状态要教学，而不只是"暂无"（operate.md 规范）。 */
    private View emptyState(int iconRes, String title, String hint) {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 32);
        box.setPadding(p, Ui.v(this, 40), p, Ui.v(this, 40));

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
        tp.topMargin = Ui.v(this, 16);
        tv.setLayoutParams(tp);
        box.addView(tv);

        TextView hintTv = Ui.text(this, hint, Ui.T_BODY, Ui.onSurfaceVariant(this), false);
        hintTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.v(this, 6);
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
        /** 提醒时间（epoch 毫秒）。0 = 不提醒。 */
        long remindAt;
        EditText titleField;
        EditText dueField;
    }

    /**
     * 重建待办表单。点优先级、改提醒都会走到这里，所以先把输入框里的值收回 TodoForm 再重画，
     * 否则用户刚敲的任务内容会被「重建」清掉。
     */
    private void renderTodoForm(final LinearLayout box, final TodoForm f) {
        if (f.titleField != null) f.title = f.titleField.getText().toString();
        if (f.dueField != null) f.due = f.dueField.getText().toString();
        box.removeAllViews();
        f.titleField = formField(box, "任务内容", "完成第三章习题", f.title);
        f.dueField = formField(box, "截止日期", Dates.today(), f.due);

        formLabel(box, "优先级");
        // 横排三段而不是纵向三行：三个互斥选项占一行，省一半高度。
        // 圆点颜色与进度条、图例同源（Ui.priorityColor），选中的字色也会跟着变。
        box.addView(Ui.segmentedRow(this, new String[]{"高", "中", "低"},
                new int[]{Ui.priorityColor(this, "high"), Ui.priorityColor(this, "medium"),
                        Ui.priorityColor(this, "low")},
                Db.priorityIndex(f.priority), new Ui.Pick() {
                    @Override public void onPick(int index) {
                        String key = Db.PRIORITY_KEYS[index];
                        if (key.equals(f.priority)) return;
                        f.priority = key;
                        renderTodoForm(box, f);
                    }
                }));

        formLabel(box, "提醒");
        box.addView(remindRow(box, f));
    }

    /**
     * 提醒行：没设时是「不提醒（点这里设一个）」，设了就显示时刻、右侧多一个清除按钮。
     *
     * 用系统自带的日期 / 时间选择器而不是让用户手输：日期时间格式太多写法，
     * 手输写错要么被当成无效值、要么落到一个意想不到的时刻上，选择器不会有这种歧义。
     */
    private View remindRow(final LinearLayout box, final TodoForm f) {
        LinearLayout row = Ui.row(this);
        int ph = Ui.dp(this, 14), pv = Ui.v(this, 11);
        row.setPadding(ph, pv, ph, pv);
        row.setMinimumHeight(Ui.vMin(this, 48));
        row.setBackground(Ui.ripple(this, Ui.surfaceContainer(this), Ui.R_S));
        row.setClickable(true);
        row.setFocusable(true);

        TextView tv = Ui.text(this,
                f.remindAt > 0 ? "提醒时间　" + Dates.stamp(f.remindAt) : "不提醒（点这里设一个）",
                Ui.T_BODY + 1,
                f.remindAt > 0 ? Ui.onSurface(this) : Ui.onSurfaceVariant(this), false);
        tv.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        if (f.remindAt > 0) {
            LinearLayout clear = Icons.iconButton(this, R.drawable.ic_close, 36, Ui.outline(this));
            clear.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    f.remindAt = 0;
                    renderTodoForm(box, f);
                }
            });
            row.addView(clear);
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickRemind(box, f); }
        });
        return row;
    }

    /**
     * 先选日期、再选时间（两个系统对话框串起来）。
     * 默认值：已经设过就从原值开始改；没设过用「现在往后一小时」——
     * 提醒总是设给未来的事，从当前这一刻开始调很容易一不小心设成过去。
     */
    private void pickRemind(final LinearLayout box, final TodoForm f) {
        long base = f.remindAt > 0 ? f.remindAt : System.currentTimeMillis() + 3600000L;
        final int[] p = new int[5];
        Dates.split(base, p);
        new android.app.DatePickerDialog(this,
                new android.app.DatePickerDialog.OnDateSetListener() {
                    @Override public void onDateSet(android.widget.DatePicker dp,
                                                    final int y, final int m, final int d) {
                        new android.app.TimePickerDialog(CourseActivity.this,
                                new android.app.TimePickerDialog.OnTimeSetListener() {
                                    @Override public void onTimeSet(android.widget.TimePicker tp,
                                                                    int hh, int mm) {
                                        f.remindAt = Dates.at(y, m, d, hh, mm);
                                        if (f.remindAt > System.currentTimeMillis()) {
                                            ensureNotifyPermission();
                                        }
                                        renderTodoForm(box, f);
                                    }
                                }, p[3], p[4], true).show();
                    }
                }, p[0], p[1], p[2]).show();
    }

    /**
     * API 33+ 要用户点头才发得出通知。放在「刚设完一个未来的提醒」这一刻问最合理：
     * 用户此刻清楚这个权限是干什么用的；反过来，一进 App 就弹权限框会被当成骚扰。
     */
    private void ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (Reminders.canNotify(this)) return;
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
    }

    /** 对话框里的字段标签。 */
    private void formLabel(LinearLayout box, String text) {
        TextView lb = Ui.text(this, text, Ui.T_LABEL, Ui.onSurfaceVariant(this), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.v(this, 16);
        lp.bottomMargin = Ui.v(this, 6);
        lb.setLayoutParams(lp);
        box.addView(lb);
    }

    /** 对话框里的「标签 + 输入框」，返回输入框供调用方取值。 */
    private EditText formField(LinearLayout box, String label, String hint, String value) {
        formLabel(box, label);
        EditText et = Ui.input(this, hint);
        if (value != null && value.length() > 0) et.setText(value);
        box.addView(et);
        return et;
    }

    /**
     * 落库。返回 false 表示没保存（对话框不关，用户刚填的内容还在）——
     * 这与 Dialogs.form 的 Saver 约定配套：只有 true 才关窗。
     */
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
        todo.remindAt = f.remindAt;
        db.saveTodo(todo);
        // 存完立刻排闹钟。等回到前台再排的话，用户设完马上杀进程或重启，
        // 这一刻的提醒就丢了（闹钟本身也不跨重启，但没排过就更谈不上重排）。
        Reminders.schedule(this, todo);
        submitDialog = null;
        renderTabs();
        renderContent();
        if (f.remindAt > 0 && f.remindAt <= System.currentTimeMillis()) {
            // 存是照存，但必须说清楚：否则用户以为设上了，到点什么都不会发生
            Tip.error(this, "已创建，但提醒时间已过，不会提醒");
        } else {
            Tip.success(this, "待办已创建");
        }
        return true;
    }

    // ================== 录音 ==================

    private boolean isRecording() { return speech != null && speech.isRecording(); }

    /**
     * 录音入口：先要麦克风权限（API 23+ 运行时申请），拿到之后才真正开始。
     * 权限与识别服务是两码事——给了权限识别仍可能被厂商策略拦下，见 reallyStartRecording。
     */
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
    /** 权限回调：给了就开录；被拒绝时说清「缺什么、怎么办」，别让按钮点了没反应。 */
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                reallyStartRecording();
            } else {
                Tip.error(this, "未获得麦克风权限，无法录音");
            }
        } else if (req == REQ_NOTIF) {
            // 提醒本身已经存下了（闹钟排得进去），只是发不出通知。说清这一点，
            // 别让用户以为「提醒没设上」而反复重设。
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!ok) Tip.error(this, "未允许通知，提醒到点不会弹出；可在系统设置里再开");
        }
    }

    /**
     * 真正开始录音：建语音会话并把三条回调接好。
     *
     *  - onPartial：实时文字直接刷到录音面板上；
     *  - onFinished：有文字就开成笔记，没文字但有音频就走云转写，两者都没有才报「没识别到」；
     *  - onError：按「哪条路走得通」排优先级——被厂商拦下时弹授权引导（系统设置里
     *    根本没有开关），只是缺服务/没配好才引导去开云转写。
     *
     * 是否同时录音取决于转写模式：只关了云端转写（off）才不保留音频。
     */
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

    /**
     * 录音面板：扩散脉冲（在录）+ 计时 + 实时识别文字 + 暂停/结束。
     * 这块是临时的——它替换掉内容区，结束或取消后 renderContent() 会重建回列表。
     */
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
        rtp.topMargin = Ui.v(this, 16);
        recTextView.setLayoutParams(rtp);
        recPanel.addView(recTextView);

        if (!"off".equals(Prefs.tsMode(this))) {
            TextView hint = Ui.text(this, "已启用转写后端：无实时结果时将自动转写录音",
                    Ui.T_CAPTION, Ui.onSurfaceVariant(this), false);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.topMargin = Ui.v(this, 10);
            hint.setLayoutParams(hp);
            recPanel.addView(hint);
        }

        LinearLayout ctrl = Ui.row(this);
        ctrl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ctp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctp.topMargin = Ui.v(this, 18);
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

    /**
     * 每 500ms 刷新一次计时（比 1s 更跟手，又不至于每帧都改文本）。
     * 会话结束就自己停下，不留常驻定时器。
     */
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

    /** 停掉计时刷新。重复调用安全（会话结束时可能从多处触发）。 */
    private void stopTicker() {
        if (ticker != null && tickTask != null) ticker.removeCallbacks(tickTask);
        ticker = null;
    }

    /**
     * 把识别（或转写）出来的文字开成一条新笔记，标题带课程名与日期。
     * 重点清单先用本地关键词提取兜底——用户想更细的可以随后跑一次 AI 总结。
     */
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

    /**
     * 上传录音走云端转写，成功后同样开成笔记。
     * 这是「这台设备识别不可用」时唯一的出字途径（见 README 里各家 ROM 的差异），
     * 所以失败提示要具体：地址/Key 的问题、404（服务没有转写接口）都分别说清。
     */
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

    /**
     * AI 总结结果对话框：摘要 + 重点清单 + 「应用到笔记」。
     * 应用是**覆盖式**写入（摘要替换正文、重点替换重点清单），所以按钮文案写「应用」，
     * 而不是让人以为只是「看看」。
     */
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
            kp.topMargin = Ui.v(this, 16);
            kpTitle.setLayoutParams(kp);
            box.addView(kpTitle);

            for (String k : r.keyPoints) {
                TextView item = Ui.text(this, "· " + k, Ui.T_BODY,
                        Ui.onSurfaceVariant(this), false);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ip.topMargin = Ui.v(this, 5);
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

    /** null 安全取字符串（课程名、教师等字段可能为空）。 */
    private static String nz(String s) { return s == null ? "" : s; }

}