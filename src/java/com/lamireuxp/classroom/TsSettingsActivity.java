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
    private EditText endpointField;
    private EditText keyField;
    private EditText modelField;

    @Override protected String title() { return "语音转写设置"; }

    @Override protected void fillBody(LinearLayout body) {
        // 只在第一次填内容时从偏好取。不能每次都读——换主题或切方式都会重建 body，
        // 每次都读的话会把用户在界面上刚选的、还没保存的值覆盖回去。
        if (mode == null) mode = Prefs.tsMode(this);

        body.addView(sectionTitle("转写方式"));
        body.addView(modeRow("关闭", "只用系统语音识别，不保留录音", "off"));
        body.addView(modeRow("服务端转写", "把录音上传到自建服务转写", "server"));
        body.addView(modeRow("API 直连", "把录音上传到 API 服务转写", "api"));

        if ("off".equals(mode)) {
            body.addView(sectionTitle("说明"));
            body.addView(Ui.text(this,
                    "关闭后仅使用系统语音识别，识别不出来的内容不会被上传转写。",
                    Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
        } else {
            body.addView(sectionTitle("服务"));
            endpointField = labeledField("服务地址", "https://api.openai.com/v1",
                    Prefs.tsEndpoint(this));
            keyField = labeledField("API Key", "（可选）", Prefs.tsKey(this));
            modelField = labeledField("模型名", "whisper-1", Prefs.tsModel(this));
        }

        // 保存按钮任何模式下都要有——「关闭」也是一种需要提交的状态。
        // 否则已经是 api 的用户切回 off 时找不到提交按钮，等于关不掉。
        body.addView(saveButton("保存", new Runnable() {
            @Override public void run() { save(); }
        }));
    }

    /** 切换方式会改变下面显示的字段，所以要点完重建一次 body。 */
    private View modeRow(String label, String desc, final String value) {
        final boolean active = value.equals(mode);

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
        mid.addView(Ui.text(this, desc, Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
        row.addView(mid);

        if (active) row.addView(Icons.icon(this, R.drawable.ic_check, Ui.primary(this), 18));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (value.equals(mode)) return;
                // 只改内存里的选择并重建，不落盘——落盘统一由「保存」做。
                //
                // 一开始我在这里直接写偏好，结果是：在「关闭」下点「API 直连」时，
                // 服务地址那几个字段还没渲染出来（endpointField 是 null），
                // 于是存下 mode=api + endpoint="" 这种自相矛盾的组合，
                // 云转写必然失败。改成只在保存时写，就不会有半保存的状态。
                mode = value;
                applyTheme();   // 重建 body，让下面的字段跟着显示/隐藏
            }
        });
        return row;
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
        Prefs.saveTs(this, mode, endpoint, keyField.getText().toString(),
                modelField.getText().toString());
        Tip.success(this, "转写设置已保存");
        finish();
    }
}