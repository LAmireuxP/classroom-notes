package com.lamireuxp.classroom;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * AI 总结设置 —— 独立界面（原来是 MainActivity 里的表单对话框）。
 *
 * 对话框的问题：键盘弹起时会跟对话框抢空间、输入到一半点外面就没了，
 * 而且改主题时对话框不跟着变。独立页面没这些事。
 *
 * 1.3 起这里不再假设「全世界都是 OpenAI」：先选协议（OpenAI 兼容 / 通义原生 /
 * 百度文心 / 自定义），字段跟着协议变——文心多要一个 Secret Key，自定义要填
 * 请求路径与取值字段。加上厂商预设，换一家服务商是「点一下」而不是「查半天文档」。
 */
public class AiSettingsActivity extends BaseSettingsActivity {

    /** 界面上的当前值都先活在这里，重建（换主题 / 切协议）时按它重新渲染。 */
    private AiProto.Cfg cur = new AiProto.Cfg();
    private boolean loaded;
    /** 最近一次套用预设时的注意事项，留在页面上提醒。 */
    private String note = "";

    private EditText endpointField;
    private EditText keyField;
    private EditText secretField;
    private EditText modelField;
    private EditText pathField;
    private EditText fieldField;

    @Override protected String title() { return "AI 总结设置"; }

    @Override protected void fillBody(LinearLayout body) {
        if (!loaded) {
            cur = Prefs.aiCfg(this);
            loaded = true;
        }

        body.addView(sectionTitle("服务商"));
        body.addView(presetRow());

        body.addView(sectionTitle("接口协议"));
        String[] ids = AiProto.ids();
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            body.addView(optionRow(AiProto.label(id), AiProto.desc(id),
                    id.equals(cur.id), new Runnable() {
                        @Override public void run() {
                            if (id.equals(cur.id)) return;
                            capture();          // 先把已输入的内容收进内存，再切
                            cur.id = id;
                            cur.path = "";       // 换协议就回到该协议的默认路径与取值
                            cur.field = "";
                            cur.auth = "bearer";
                            note = "";
                            applyTheme();
                        }
                    }));
        }

        body.addView(sectionTitle("服务"));
        endpointField = labeledField("API 地址", endpointHint(), cur.endpoint);
        keyField = labeledField(AiProto.ERNIE.equals(cur.id) ? "API Key（client_id）" : "API Key",
                "sk-…", cur.key);
        if (AiProto.ERNIE.equals(cur.id)) {
            secretField = labeledField("Secret Key（client_secret）", "控制台里的 Secret Key",
                    cur.secret);
        }
        modelField = labeledField("模型", modelHint(), cur.model);

        if (AiProto.CUSTOM.equals(cur.id)) {
            pathField = labeledField("请求路径", "/v1/chat/completions（可带查询参数）", cur.path);
            fieldField = labeledField("响应取值字段", "output.text；留空则按 OpenAI 形状找",
                    cur.field);
            body.addView(sectionTitle("鉴权方式"));
            body.addView(authRow("Bearer", "Authorization: Bearer <Key>", "bearer"));
            body.addView(authRow("查询参数", "?access_token=<Key>，也是不少网关的做法", "query"));
            body.addView(authRow("不带鉴权", "内网自建、不校验 Key 的服务", "none"));
        }

        body.addView(sectionTitle("连接"));
        body.addView(connectionTester(new CfgProvider() {
            @Override public AiProto.Cfg cfg() { return AiSettingsActivity.this.cfg(); }
        }));
        body.addView(hint("OpenAI 兼容的地址用 GET /models 探活（不花钱）；通义原生、百度文心、"
                + "以及自定义了请求路径的服务会发一条最小的对话请求。结果写在按钮上。"));

        if (note.length() > 0) body.addView(hint(note));
        // 说清两个设置的关系：转写保存时会把地址 + Key 抄过来（可在转写设置里关掉），
        // 这里改了不会反向影响转写——不然用户会以为两处永远必须手填两遍一样的。
        // 协议不同时地址本来就不是一回事（通义原生 / 文心），那边不会同步。
        body.addView(hint("「语音转写设置 → API 直连」保存时会把地址和 Key 同步到这里"
                + "（可在转写设置里关闭）；这里的修改不会影响转写。"
                + "选了通义原生 / 百度文心时，地址与转写不是同一套，不做同步。"));

        body.addView(saveButton("保存", new Runnable() {
            @Override public void run() { save(); }
        }));
    }

    /** 一段说明文字，比正文小一号。 */
    private View hint(String text) {
        TextView tv = Ui.text(this, text, Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.v(this, 10);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 「填入厂商预设」一行：地址、协议、模型一起填好，省掉查文档。 */
    private View presetRow() {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 16), Ui.v(this, 12), Ui.dp(this, 16), Ui.v(this, 12));
        row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, Ui.R_S));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Ui.vMin(this, 52));
        Ui.pressScale(row);

        LinearLayout mid = Ui.column(this);
        mid.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mid.addView(Ui.text(this, "填入厂商预设", Ui.T_BODY + 1, Ui.primary(this), true));
        mid.addView(Ui.text(this, "DeepSeek / 通义 / 智谱 / Kimi / 混元 / 豆包 / 讯飞 / 文心…",
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
        row.addView(mid);

        android.widget.ImageView chevron =
                Icons.icon(this, R.drawable.ic_chevron_down, Ui.onSurfaceVariant(this), 16);
        chevron.setRotation(-90f);   // 下箭头转 90° = 「进入」的 >，和设置页其它行一致
        row.addView(chevron);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPresets(); }
        });
        return row;
    }

    private void showPresets() {
        final LinearLayout list = Ui.column(this);
        final AlertDialog dlg = Dialogs.sheet(this, "选择厂商", list);
        java.util.List<AiProto.Preset> ps = AiProto.presets();
        for (int i = 0; i < ps.size(); i++) {
            final AiProto.Preset p = ps.get(i);
            list.addView(optionRow(p.name, p.note, false, new Runnable() {
                @Override public void run() {
                    dlg.dismiss();
                    applyPreset(p);
                }
            }));
        }
    }

    /**
     * 套用预设：只动地址、协议和模型，Key 之类照旧——换个服务商不该把已经填好的
     * 凭证清掉。模型名留空的预设（豆包要填接入点 ID）就清空，免得留着一个
     * 属于上一家的模型名，最后错在「模型不存在」上。
     */
    private void applyPreset(AiProto.Preset p) {
        capture();
        cur.id = p.protocol;
        cur.endpoint = p.endpoint;
        cur.model = p.model;
        cur.path = "";
        cur.field = "";
        cur.auth = "bearer";
        note = p.note;
        applyTheme();
        Tip.success(this, "已填入「" + p.name + "」预设");
    }

    /** 鉴权方式一行。 */
    private View authRow(String label, String desc, final String value) {
        return optionRow(label, desc, value.equals(cur.auth), new Runnable() {
            @Override public void run() {
                if (value.equals(cur.auth)) return;
                capture();
                cur.auth = value;
                applyTheme();
            }
        });
    }

    private String endpointHint() {
        if (AiProto.DASHSCOPE.equals(cur.id)) return "https://dashscope.aliyuncs.com";
        if (AiProto.ERNIE.equals(cur.id)) return "https://aip.baidubce.com";
        if (AiProto.CUSTOM.equals(cur.id)) return "https://服务商地址/v1";
        return "https://api.deepseek.com/v1";
    }

    private String modelHint() {
        if (AiProto.DASHSCOPE.equals(cur.id)) return "如 qwen-plus / qwen-max";
        if (AiProto.ERNIE.equals(cur.id)) return "如 ernie_speed / completions";
        if (AiProto.CUSTOM.equals(cur.id)) return "服务商文档里的模型名";
        return "如 deepseek-chat / qwen-plus / glm-4-flash";
    }

    /** 界面上的值（优先输入框，其次内存）组成配置。 */
    private AiProto.Cfg cfg() {
        AiProto.Cfg g = new AiProto.Cfg();
        g.id = cur.id;
        g.endpoint = txt(endpointField, cur.endpoint);
        g.key = txt(keyField, cur.key);
        g.secret = txt(secretField, cur.secret);
        g.model = txt(modelField, cur.model);
        g.path = txt(pathField, cur.path);
        g.field = txt(fieldField, cur.field);
        g.auth = cur.auth;
        return g;
    }

    private static String txt(EditText et, String fallback) {
        return et == null ? fallback : et.getText().toString();
    }

    /** 换主题或切协议会重建整个 body，输入到一半的内容不能丢。 */
    private void capture() {
        AiProto.Cfg g = cfg();
        // id / auth 是选择项，已经在 cur 里；这里只回收输入框
        cur.endpoint = g.endpoint;
        cur.key = g.key;
        cur.secret = g.secret;
        cur.model = g.model;
        cur.path = g.path;
        cur.field = g.field;
    }

    @Override protected void applyTheme() {
        capture();
        super.applyTheme();
    }

    private void save() {
        AiProto.Cfg g = cfg();
        if (g.endpoint.trim().length() == 0) {
            Tip.error(this, "请填写 API 地址");
            return;
        }
        String miss = AiProto.missing(g);
        if (miss != null) {
            Tip.error(this, miss);
            return;
        }
        Prefs.saveAi(this, g);
        Tip.success(this, "AI 设置已保存");
        finish();
    }
}