package com.lamireuxp.classroom;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 设置类页面的公共骨架：顶栏 + 可滚动内容 + 就地换主题。
 *
 * 抽出来的原因：设置、AI 设置、语音转写设置三个页面除了中间的内容之外完全一样
 * （顶栏、状态栏内边距、滚动容器、换主题时重新着色），各抄一份只会越抄越偏。
 *
 * 子类只需要实现 title() 和 fillBody()。
 */
public abstract class BaseSettingsActivity extends Activity {

    protected LinearLayout pageRoot;
    /** 内容容器。子类往这里加东西。 */
    protected LinearLayout body;

    /** 上次渲染时的深浅状态，用来判断从别处回来要不要重新着色。 */
    private boolean renderedDark;

    /** 顶栏标题。 */
    protected abstract String title();

    /** 填充页面内容。换主题会重新调用，所以要能从 Prefs 完整重建。 */
    protected abstract void fillBody(LinearLayout body);

    @Override
    protected void onCreate(Bundle b) {
        // 必须在 super.onCreate 之前：应用主题资源
        setTheme(Prefs.isDark(this) ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(b);
        applyTheme();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 主题可能在别处被改过，回来时要重新着色
        if (renderedDark != Ui.isDark(this)) applyTheme();
    }

    @Override
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        // uiMode 在 manifest 的 configChanges 里声明过，系统切深浅色时不会自动重建
        if (Prefs.THEME_SYSTEM.equals(Prefs.themeMode(this))) applyTheme();
    }

    /** 就地应用主题：不重建 Activity，只重新着色并重建内容。 */
    protected void applyTheme() {
        Ui.applyWindowTheme(this);
        renderedDark = Ui.isDark(this);
        buildUi();
    }

    private void buildUi() {
        pageRoot = Ui.column(this);
        pageRoot.setBackgroundColor(Ui.surface(this));
        setContentView(pageRoot);

        pageRoot.addView(topBar());

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(sp);

        body = Ui.column(this);
        int bp = Ui.dp(this, 16);
        body.setPadding(bp, Ui.dp(this, 4), bp, Ui.dp(this, 32));
        sv.addView(body);
        pageRoot.addView(sv);

        fillBody(body);
    }

    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.surface(this));
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        // 状态栏内边距（edge-to-edge 下内容不被状态栏遮挡）
        bar.setPadding(bar.getPaddingLeft(),
                bar.getPaddingTop() + Ui.statusBarHeight(this),
                bar.getPaddingRight(), bar.getPaddingBottom());

        LinearLayout back = Icons.iconButton(this, R.drawable.ic_back, 42,
                Ui.onSurfaceVariant(this));
        back.setContentDescription("返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        bar.addView(back);

        TextView t = Ui.text(this, title(), Ui.T_HEADLINE, Ui.onSurface(this), true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = Ui.dp(this, 8);
        t.setLayoutParams(tp);
        bar.addView(t);

        return bar;
    }

    // ================== 子类复用的小组件 ==================

    /** 分组小标题。 */
    protected View sectionTitle(String text) {
        TextView tv = Ui.text(this, text, Ui.T_LABEL, Ui.primary(this), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 18);
        lp.bottomMargin = Ui.dp(this, 6);
        lp.leftMargin = Ui.dp(this, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 带标签的输入框。 */
    protected android.widget.EditText labeledField(String label, String hint, String value) {
        TextView lb = Ui.text(this, label, Ui.T_LABEL, Ui.onSurfaceVariant(this), true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Ui.dp(this, 8);
        llp.bottomMargin = Ui.dp(this, 6);
        lb.setLayoutParams(llp);
        body.addView(lb);

        android.widget.EditText et = Ui.input(this, hint);
        if (value != null && value.length() > 0) et.setText(value);
        body.addView(et);
        return et;
    }

    /** 底部保存按钮。 */
    protected View saveButton(String text, final Runnable onSave) {
        TextView btn = Ui.filledButton(this, text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 24);
        btn.setLayoutParams(lp);
        btn.setMinHeight(Ui.dp(this, 46));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onSave.run(); }
        });
        return btn;
    }

    /**
     * 「测试连接」按钮：向 {服务地址}/models 发一个 GET。OpenAI 兼容服务基本都实现这个接口，
     * 当场就能知道地址和 Key 通不通，而不是录完一节课才发现 404。
     *
     * 结果写在按钮文案上而不是只弹 Tip——Tip 两秒多就没了，验证结果值得留在屏上让人看仔细。
     */
    protected View connectionTester(final EditText endpointField, final EditText keyField) {
        final TextView btn = Ui.textButton(this, "测试连接（GET /models）");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        btn.setLayoutParams(lp);
        btn.setMinHeight(Ui.dp(this, 44));
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String base = endpointField.getText().toString().trim();
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                if (base.length() == 0) {
                    Tip.error(BaseSettingsActivity.this, "先填写服务地址");
                    return;
                }
                final String url = base;
                final String key = keyField.getText().toString().trim();
                btn.setEnabled(false);
                btn.setText("测试中…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        String ok = null;
                        String err = null;
                        try {
                            ok = Net.probe(url, key);
                        } catch (Throwable e) {
                            err = Net.humanize(e);
                        }
                        final String fOk = ok;
                        final String fErr = err;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                btn.setEnabled(true);
                                if (fOk != null) {
                                    btn.setText("✓ " + fOk);
                                    Tip.success(BaseSettingsActivity.this, "服务连接正常");
                                } else {
                                    btn.setText("✗ 连接失败");
                                    Tip.error(BaseSettingsActivity.this, "连接失败：" + fErr);
                                }
                            }
                        });
                    }
                }).start();
            }
        });
        return btn;
    }
}