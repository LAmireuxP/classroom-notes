package com.lamireuxp.classroom;

import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * 设置页 —— 独立界面，不再是 MainActivity 上的底部抽屉。
 *
 * 拆出来的原因：抽屉在高内容量下要自己滚动，子项还得靠「抽屉上再叠一层抽屉」，
 * 层级越叠越乱；而且对话框是独立 Window，换主题时不会跟着变，会出现
 * 「界面已经变深色、抽屉还是白的」这种不一致。独立页面没这些问题。
 *
 * 结构：外观（主题三选一，内联）/ 服务（跳子页面）/ 数据
 */
public class SettingsActivity extends BaseSettingsActivity {

    private static final int REQ_EXPORT_JSON = 201;
    private static final int REQ_EXPORT_MD = 202;
    private static final int REQ_IMPORT_JSON = 203;

    private String pendingExport;

    @Override protected String title() { return "设置"; }

    @Override protected void fillBody(LinearLayout body) {
        // ---- 外观 ----
        body.addView(sectionTitle("外观"));
        body.addView(themeRow("跟随系统", "随系统的深浅色自动切换", Prefs.THEME_SYSTEM));
        body.addView(themeRow("浅色", "始终使用浅色", Prefs.THEME_LIGHT));
        body.addView(themeRow("深色", "始终使用深色", Prefs.THEME_DARK));

        // ---- 服务 ----
        body.addView(sectionTitle("服务"));
        body.addView(navRow(R.drawable.ic_ai, "AI 总结设置", "接口协议、地址、Key、模型",
                AiSettingsActivity.class));
        body.addView(navRow(R.drawable.ic_mic, "语音转写设置", "转写方式与云端服务",
                TsSettingsActivity.class));

        // ---- 数据 ----
        body.addView(sectionTitle("数据"));
        body.addView(actionRow(R.drawable.ic_export, "导出 JSON 备份", new Runnable() {
            @Override public void run() { doExportJson(); }
        }));
        body.addView(actionRow(R.drawable.ic_md, "导出 Markdown", new Runnable() {
            @Override public void run() { doExportMd(); }
        }));
        body.addView(actionRow(R.drawable.ic_import, "导入 JSON 备份", new Runnable() {
            @Override public void run() { doImportJson(); }
        }));
    }

    /** 主题三选一的行：当前项打勾，点了立即生效。 */
    private View themeRow(String label, String desc, final String mode) {
        final boolean active = mode.equals(Prefs.themeMode(this));

        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 16), Ui.v(this, 12), Ui.dp(this, 16), Ui.v(this, 12));
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_S));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Ui.vMin(this, 52));
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
                if (mode.equals(Prefs.themeMode(SettingsActivity.this))) return;
                Prefs.setThemeMode(SettingsActivity.this, mode);
                applyTheme();
            }
        });
        return row;
    }

    /** 跳转型行（右侧带箭头），打开子设置页面。 */
    private View navRow(int iconRes, String label, String sub, final Class<?> target) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 16), Ui.v(this, 12), Ui.dp(this, 16), Ui.v(this, 12));
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_S));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Ui.vMin(this, 56));
        Ui.pressScale(row);

        row.addView(Icons.icon(this, iconRes, Ui.onSurfaceVariant(this), 20));

        LinearLayout mid = Ui.column(this);
        LinearLayout.LayoutParams mp = Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = Ui.dp(this, 14);
        mid.setLayoutParams(mp);
        mid.addView(Ui.text(this, label, Ui.T_BODY + 1, Ui.onSurface(this), false));
        mid.addView(Ui.text(this, sub, Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
        row.addView(mid);

        // 复用下箭头，逆时针转 90° 变成「>」——省一个图标资源
        android.widget.ImageView chevron =
                Icons.icon(this, R.drawable.ic_chevron_down, Ui.onSurfaceVariant(this), 16);
        chevron.setRotation(-90f);
        row.addView(chevron);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, target));
            }
        });
        return row;
    }

    /** 动作行（无箭头、无副标题）。 */
    private View actionRow(int iconRes, String label, final Runnable action) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 16), Ui.v(this, 12), Ui.dp(this, 16), Ui.v(this, 12));
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_S));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Ui.vMin(this, 52));
        Ui.pressScale(row);

        row.addView(Icons.icon(this, iconRes, Ui.onSurface(this), 20));
        android.widget.TextView tv = Ui.text(this, label, Ui.T_BODY + 1, Ui.onSurface(this), false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = Ui.dp(this, 14);
        tv.setLayoutParams(tp);
        row.addView(tv);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return row;
    }

    // ================== 导入导出 ==================

    /**
     * 导出 JSON：先把内容生成好放进 pendingExport，再用 SAF 让用户选保存位置。
     * 顺序不能反——SAF 返回时数据已经准备好，直接写；先选位置再生成的话，
     * 用户在文件选择器里等的那几秒是白等的，而且失败时还得回头清理空文件。
     */
    private void doExportJson() {
        try {
            pendingExport = Backup.exportJson(this);
            Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("application/json");
            it.putExtra(Intent.EXTRA_TITLE, "classroom-backup-" + Dates.today() + ".json");
            startActivityForResult(it, REQ_EXPORT_JSON);
        } catch (Throwable e) {
            Tip.error(this, "导出失败：" + e.getMessage());
        }
    }

    /** 导出 Markdown：没有笔记时直接拦下，别让用户走完文件选择器才得到一份空文件。 */
    private void doExportMd() {
        try {
            if (Db.get(this).totals()[0] == 0) {
                Tip.error(this, "暂无笔记可导出");
                return;
            }
            pendingExport = Backup.exportMarkdown(this);
            Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("text/markdown");
            it.putExtra(Intent.EXTRA_TITLE, "classroom-notes-" + Dates.today() + ".md");
            startActivityForResult(it, REQ_EXPORT_MD);
        } catch (Throwable e) {
            Tip.error(this, "导出失败：" + e.getMessage());
        }
    }

    /**
     * 导入 JSON：只负责选文件，真正的解析与替换在 onActivityResult 里（要先确认）。
     * 类型用 * / * 而不是 application/json：各家文件管理器对 json 的 MIME 判定不一致，
     * 限定类型会让部分设备上刚下载的备份文件变灰选不中。
     */
    private void doImportJson() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        startActivityForResult(it, REQ_IMPORT_JSON);
    }

    @Override
    /**
     * SAF 回调：导出就直接写，导入要先读文本、弹确认框（导入是覆盖式清空重建）。
     * 用户取消（result != RESULT_OK）时清掉 pendingExport，免得下次导出写进旧内容。
     */
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
                Tip.success(this, "已导出到所选位置");
            } else if (req == REQ_IMPORT_JSON) {
                final String text = Backup.readText(this, uri);
                Dialogs.confirm(this, "导入备份",
                        "将清空当前所有数据并导入所选备份，确定继续？",
                        "导入", new Runnable() {
                            @Override public void run() {
                                try {
                                    int n = Backup.importJson(SettingsActivity.this, text);
                                    Tip.success(SettingsActivity.this, "已导入 " + n + " 门课程");
                                } catch (Throwable e) {
                                    Tip.error(SettingsActivity.this, "导入失败：" + e.getMessage());
                                }
                            }
                        });
            }
        } catch (Throwable e) {
            Tip.error(this, "操作失败：" + e.getMessage());
        }
    }
}