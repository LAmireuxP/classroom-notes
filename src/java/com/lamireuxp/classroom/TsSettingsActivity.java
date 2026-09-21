package com.lamireuxp.classroom;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * 语音转写设置 —— 独立界面（原来是表单对话框）。
 *
 * 顺带把「转写方式」从手输字符串改成了选择器：原来要用户在输入框里敲
 * off / server / api，还得写一段校验去挡拼错的情况。改成点选就没有拼错的可能了，
 * 那段校验也不需要了。
 *
 * 服务地址等字段只在非 off 时出现——关着的时候摆三个用不上的输入框只会让人困惑。
 */
public class TsSettingsActivity extends BaseSettingsActivity {

    private String mode;
    /** 「把地址和 Key 同步给 AI 总结」开关。off 模式下不显示（没有可同步的内容）。 */
    private boolean syncAi = true;
    private EditText endpointField;
    private EditText keyField;
    private EditText modelField;

    @Override protected String title() { return "语音转写设置"; }

    @Override protected void fillBody(LinearLayout body) {
        // 只在第一次填内容时从偏好取。不能每次都读——换主题或切方式都会重建 body，
        // 每次都读的话会把用户在界面上刚选的、还没保存的值覆盖回去。
        if (mode == null) {
            mode = Prefs.tsMode(this);
            syncAi = Prefs.aiSyncFromTs(this);
        }

        body.addView(sectionTitle("转写方式"));
        body.addView(optionRow("关闭", "只用系统语音识别，不保留录音", "off".equals(mode),
                new Runnable() {
                    @Override public void run() { pickMode("off"); }
                }));
        body.addView(optionRow("服务端转写", "把录音上传到自建服务转写", "server".equals(mode),
                new Runnable() {
                    @Override public void run() { pickMode("server"); }
                }));
        body.addView(optionRow("API 直连", "把录音上传到 API 服务转写", "api".equals(mode),
                new Runnable() {
                    @Override public void run() { pickMode("api"); }
                }));

        if ("off".equals(mode)) {
            body.addView(sectionTitle("说明"));
            body.addView(Ui.text(this,
                    "关闭后仅使用系统语音识别，识别不出来的内容不会被上传转写。",
                    Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
        } else {
            body.addView(sectionTitle("服务"));
            endpointField = labeledField("服务地址", "https://api.siliconflow.cn/v1",
                    Prefs.tsEndpoint(this));
            keyField = labeledField("API Key", "（可选）", Prefs.tsKey(this));
            modelField = labeledField("模型名", "whisper-1 / FunAudioLLM/SenseVoiceSmall",
                    Prefs.tsModel(this));
            body.addView(sectionTitle("连接"));
            body.addView(connectionTester(endpointField, keyField));
            body.addView(Ui.text(this,
                    "地址填 API 根地址（不是完整接口路径）。转写走 OpenAI 兼容的 "
                            + "/audio/transcriptions：自建 FunASR / whisper.cpp、硅基流动的 "
                            + "SenseVoice 都能用；需要签名的原生 ASR（讯飞、通义）和只代理对话的"
                            + "中转站不提供这个接口。「测试连接」用 GET /models 探活，不消耗额度。",
                    Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
            body.addView(syncRow());
        }

        // 保存按钮任何模式下都要有——「关闭」也是一种需要提交的状态。
        // 否则已经是 api 的用户切回 off 时找不到提交按钮，等于关不掉。
        body.addView(saveButton("保存", new Runnable() {
            @Override public void run() { save(); }
        }));

        // 常驻的授权入口：识别被厂商拦下时录音页会弹引导，但用户也可能当时点了「稍后」，
        // 之后想自己弄就得能找到地方——没有这个入口，这条路实际上等于只响一次。
        body.addView(sectionTitle("系统语音识别授权"));
        body.addView(Ui.text(this,
                "小米 / OPPO / vivo 等机型要求先在厂商语音助手里同意「跨应用识别」，"
                        + "只给麦克风权限不够。点下面按钮打开授权页，同意一次后重新录音即可实时出字。",
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
        body.addView(authRow());
    }

    /** 「打开授权页」一行：依次尝试厂商识别服务的授权页、语音助手、系统语音设置。 */
    private View authRow() {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 16), Ui.v(this, 12), Ui.dp(this, 16), Ui.v(this, 12));
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_S));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Ui.vMin(this, 52));
        Ui.pressScale(row);

        LinearLayout mid = Ui.column(this);
        mid.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mid.addView(Ui.text(this, "打开厂商授权页", Ui.T_BODY + 1, Ui.primary(this), true));
        mid.addView(Ui.text(this, "小米会直接拉起小爱同学识别服务的授权弹窗",
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
        row.addView(mid);
        android.widget.ImageView chevron =
                Icons.icon(this, R.drawable.ic_chevron_down, Ui.onSurfaceVariant(this), 16);
        chevron.setRotation(-90f);   // 下箭头转 90° = 「进入」的 >，和设置页其它行一致
        row.addView(chevron);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 手动来过这条路，就把「被拦下」标记清掉——下次录音重新试系统识别
                SpeechSession.clearRecognizeBlocked();
                if (!VoiceAuth.open(TsSettingsActivity.this)) {
                    Tip.error(TsSettingsActivity.this,
                            "这台设备没找到语音助手 / 授权页，可改用云端转写");
                }
            }
        });
        return row;
    }

    /**
     * 切换方式会改变下面显示的字段，所以要点完重建一次 body。
     *
     * 只改内存里的选择并重建，不落盘——落盘统一由「保存」做。
     * 一开始我在这里直接写偏好，结果是：在「关闭」下点「API 直连」时，
     * 服务地址那几个字段还没渲染出来（endpointField 是 null），
     * 于是存下 mode=api + endpoint="" 这种自相矛盾的组合，云转写必然失败。
     * 改成只在保存时写，就不会有半保存的状态。
     */
    private void pickMode(String value) {
        if (value.equals(mode)) return;
        mode = value;
        applyTheme();
    }

    /** 换主题或切方式会重建 body，输入到一半的内容不能丢。 */
    @Override protected void applyTheme() {
        String ep = fieldText(endpointField, null);
        String key = fieldText(keyField, null);
        String model = fieldText(modelField, null);
        super.applyTheme();
        if (ep != null) endpointField.setText(ep);
        if (key != null) keyField.setText(key);
        if (model != null) modelField.setText(model);
    }

    private static String fieldText(EditText et, String fallback) {
        return et == null ? fallback : et.getText().toString();
    }

    /**
     * 「同步给 AI 总结」开关行。两个设置页要填的是同一类东西（OpenAI 兼容的地址 + Key），
     * 分别填两遍纯属重复；但同步必须是**单向**的——AI 总结那边改了不该回流到转写，
     * 否则一边填个纯对话 API（DeepSeek 之类）就会把转写配置悄悄弄坏。
     */
    private View syncRow() {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 16), Ui.v(this, 12), Ui.dp(this, 16), Ui.v(this, 12));
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_S));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Ui.vMin(this, 52));
        Ui.pressScale(row);

        LinearLayout mid = Ui.column(this);
        mid.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mid.addView(Ui.text(this, "同步给「AI 总结」设置", Ui.T_BODY + 1,
                syncAi ? Ui.primary(this) : Ui.onSurface(this), syncAi));
        mid.addView(Ui.text(this, "保存时抄地址和 Key 给 AI 总结（模型名不动）；AI 总结选了"
                        + "通义原生 / 文心协议时地址不是同一套，不抄",
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
        row.addView(mid);
        // 对勾固定 18dp 宽放在行尾，配合 mid 的 weight=1 把文字挤压换行，
        // 不会再出现两行说明文字盖到图标下面
        android.widget.ImageView check = Icons.icon(this, R.drawable.ic_check, Ui.primary(this), 18);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                Ui.dp(this, 18), Ui.dp(this, 18));
        clp.leftMargin = Ui.dp(this, 12);
        clp.gravity = android.view.Gravity.CENTER_VERTICAL;
        check.setLayoutParams(clp);
        check.setVisibility(syncAi ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        row.addView(check);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                syncAi = !syncAi;   // 和 modeRow 一样：只改内存，落盘统一在「保存」
                applyTheme();
            }
        });
        return row;
    }

    private void save() {
        if ("off".equals(mode)) {
            // 关掉时保留地址等配置，下次开回来不用重填
            Prefs.saveTs(this, "off", Prefs.tsEndpoint(this), Prefs.tsKey(this),
                    Prefs.tsModel(this));
            Tip.success(this, "已关闭云端转写");
            finish();
            return;
        }
        String endpoint = endpointField.getText().toString().trim();
        if (endpoint.length() == 0) {
            Tip.error(this, "该方式需要填写服务地址");
            return;
        }
        String key = keyField.getText().toString().trim();
        Prefs.saveTs(this, mode, endpoint, key, modelField.getText().toString());
        Prefs.setAiSyncFromTs(this, syncAi);
        if (syncAi) Prefs.syncAiFromTs(this, endpoint, key);
        Tip.success(this, syncAi ? "转写设置已保存，已同步到 AI 总结" : "转写设置已保存");
        finish();
    }
}