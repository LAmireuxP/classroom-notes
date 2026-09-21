package com.lamireuxp.classroom;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
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
     * 一个可点选的选项行（单选列表）。选中的那行高亮并带对勾。
     *
     * 抽出来是因为「转写方式」「接口协议」「鉴权方式」都是同一件事：
     * 原来要用户在输入框里敲 off / server / api，还得写校验去挡拼错；
     * 改成点选就没有拼错的可能了。
     */
    protected View optionRow(String label, String desc, boolean active, final Runnable onClick) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_S));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Ui.dp(this, 52));
        Ui.pressScale(row);

        LinearLayout mid = Ui.column(this);
        mid.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mid.addView(Ui.text(this, label, Ui.T_BODY + 1,
                active ? Ui.primary(this) : Ui.onSurface(this), active));
        if (desc != null && desc.length() > 0) {
            mid.addView(Ui.text(this, desc, Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
        }
        row.addView(mid);

        // 对勾固定 18dp 宽放在行尾，配合 mid 的 weight=1 把文字挤压换行，
        // 不会出现两行说明盖到图标下面
        if (active) {
            android.widget.ImageView check =
                    Icons.icon(this, R.drawable.ic_check, Ui.primary(this), 18);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 18), Ui.dp(this, 18));
            clp.leftMargin = Ui.dp(this, 12);
            clp.gravity = android.view.Gravity.CENTER_VERTICAL;
            check.setLayoutParams(clp);
            row.addView(check);
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClick.run(); }
        });
        return row;
    }

    /** 给「测试连接」提供完整配置：各协议的地址、鉴权、请求体都不一样。 */
    public interface CfgProvider {
        AiProto.Cfg cfg();
    }

    /** 简版：从两个输入框凑一个 OpenAI 兼容配置（语音转写只有这一种协议）。 */
    protected View connectionTester(final EditText endpointField, final EditText keyField) {
        return connectionTester(new CfgProvider() {
            @Override public AiProto.Cfg cfg() {
                AiProto.Cfg g = new AiProto.Cfg();
                g.endpoint = endpointField.getText().toString().trim();
                g.key = keyField.getText().toString().trim();
                return g;
            }
        });
    }

    /**
     * 「测试连接」按钮：当场验证地址 / Key / 模型通不通，而不是录完一节课才发现 404。
     *
     * 探活方式按协议自动选（OpenAI 兼容走 GET /models 不花钱，其它协议发一条最小的对话
     * 请求），但按钮文案不再写明是哪一种——它是重建时才算的，用户改了「自定义路径」之类的
     * 输入框之后不会跟着变，会挂着一个过期的说法。方式写在按钮下方的说明里，那里永远是准的。
     *
     * 结果写在按钮文案上而不是只弹 Tip——Tip 两秒多就没了，验证结果值得留在屏上让人看仔细。
     */
    protected View connectionTester(final CfgProvider provider) {
        final TextView btn = Ui.textButton(this, "测试连接");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        btn.setLayoutParams(lp);
        btn.setMinHeight(Ui.dp(this, 44));
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final AiProto.Cfg cfg = provider.cfg();
                boolean modelsProbe = usesModelsProbe(cfg);
                if (cfg.endpoint.trim().length() == 0) {
                    Tip.error(BaseSettingsActivity.this, "先填写服务地址");
                    return;
                }
                if (AiProto.ERNIE.equals(cfg.id)) {
                    if (cfg.key.trim().length() == 0 || cfg.secret.trim().length() == 0) {
                        Tip.error(BaseSettingsActivity.this, "文心要填 API Key 和 Secret Key 两个");
                        return;
                    }
                } else if (cfg.key.trim().length() == 0 && !"none".equals(cfg.auth)) {
                    Tip.error(BaseSettingsActivity.this, "先填写 API Key");
                    return;
                }
                if (!modelsProbe && cfg.model.trim().length() == 0) {
                    Tip.error(BaseSettingsActivity.this, "先填写模型名");
                    return;
                }
                btn.setEnabled(false);
                btn.setText("测试中…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        String ok = null;
                        String err = null;
                        try {
                            ok = Net.probe(cfg);
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

    /** 这次测活会不会走 GET /models。自定义了请求路径的服务不一定有这个接口。 */
    static boolean usesModelsProbe(AiProto.Cfg c) {
        return AiProto.OPENAI.equals(c.id)
                || (AiProto.CUSTOM.equals(c.id) && c.path.trim().length() == 0);
    }
}