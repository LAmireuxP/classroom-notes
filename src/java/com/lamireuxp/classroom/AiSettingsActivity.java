package com.lamireuxp.classroom;

import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * AI 总结设置 —— 独立界面（原来是 MainActivity 里的表单对话框）。
 *
 * 对话框的问题：键盘弹起时会跟对话框抢空间、输入到一半点外面就没了，
 * 而且改主题时对话框不跟着变。独立页面没这些事。
 */
public class AiSettingsActivity extends BaseSettingsActivity {

    private EditText endpointField;
    private EditText keyField;
    private EditText modelField;

    @Override protected String title() { return "AI 总结设置"; }

    @Override protected void fillBody(LinearLayout body) {
        body.addView(sectionTitle("接口"));
        endpointField = labeledField("API 地址", "https://api.deepseek.com/v1",
                Prefs.aiEndpoint(this));
        keyField = labeledField("API Key", "sk-…", Prefs.aiKey(this));
        modelField = labeledField("模型", "deepseek-chat", Prefs.aiModel(this));

        body.addView(saveButton("保存", new Runnable() {
            @Override public void run() { save(); }
        }));
    }

    /**
     * 换主题会重建整个 body，输入到一半的内容不能丢。
     * 重建前先把当前值捞出来，重建后再填回去。
     */
    @Override protected void applyTheme() {
        String ep = text(endpointField);
        String key = text(keyField);
        String model = text(modelField);
        super.applyTheme();
        if (ep != null) endpointField.setText(ep);
        if (key != null) keyField.setText(key);
        if (model != null) modelField.setText(model);
    }

    private static String text(EditText et) {
        return et == null ? null : et.getText().toString();
    }

    private void save() {
        String endpoint = endpointField.getText().toString().trim();
        if (endpoint.length() == 0) {
            Tip.error(this, "请填写 API 地址");
            return;
        }
        Prefs.saveAi(this, endpoint, keyField.getText().toString(),
                modelField.getText().toString());
        Tip.show(this, "AI 设置已保存");
        finish();
    }
}