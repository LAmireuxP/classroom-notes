package com.lamireuxp.classroom;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;

/**
 * 笔记编辑页 —— 新建 / 编辑笔记的独立页面。
 *
 * 原来用 Dialogs.form 弹窗，现在改成全屏页面：笔记内容是多行长文本，弹窗里
 * 编辑空间太小（弹窗高度封顶、内部 ScrollView 滚动体验差）；全屏页面给正文
 * 和重点清单足够的书写空间，和设置页同一套骨架（BaseSettingsActivity）。
 *
 * Intent extras：courseId（必填）、noteId（可选——有=编辑、无=新建）。
 * 保存后 finish()，CourseActivity.onResumed() 会自动 renderTabs + renderContent。
 */
public class NoteEditorActivity extends BaseSettingsActivity {

    private Db db;
    private String courseId;
    /** 编辑态时持有原笔记；新建态为 null。 */
    private Db.Note editing;
    /** 内存状态：新建时是空 Note，编辑时是原数据的副本。fillBody 从这里填字段。 */
    private Db.Note state;
    private EditText titleField, contentField, keyPointsField;

    @Override
    protected void onCreateUi(Bundle b) {
        db = Db.get(this);
        courseId = getIntent().getStringExtra("courseId");
        String noteId = getIntent().getStringExtra("noteId");
        if (noteId != null && noteId.length() > 0) {
            editing = db.note(noteId);
        }
        // state 始终非 null：编辑态取原值，新建态取空串
        state = editing != null ? editing : new Db.Note();
        super.onCreateUi(b);
    }

    @Override
    protected String title() {
        return editing != null ? "编辑笔记" : "新建笔记";
    }

    @Override
    /** 换主题重建前先把输入框里的值捞回 state——输入到一半的内容不能丢。 */
    protected void onRebuildUi() {
        capture();
        super.onRebuildUi();
    }

    /** 把输入框里的值捞回 state。 */
    private void capture() {
        if (titleField == null) return;
        state.title = titleField.getText().toString();
        state.content = contentField.getText().toString();
        state.keyPoints = Db.splitString(keyPointsField.getText().toString());
    }

    @Override
    protected void fillBody(LinearLayout body) {
        titleField = labeledField("标题", "第一章 极限与连续",
                state != null ? Ui.nz(state.title) : "");

        // 日期：点选而非手输——手输日期格式太多写法，敲错会让 shortDate 解析出错
        formLabel(body, "日期");
        body.addView(dateRow());

        // 笔记内容：多行，撑满剩余空间——全屏页面里正文是主体，给足书写面积
        formLabel(body, "笔记内容");
        contentField = Ui.input(this, "课堂内容、理解与疑问…");
        contentField.setSingleLine(false);
        contentField.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        contentField.setMinLines(8);
        // 用 weight=1 让正文占满滚动容器里标题/日期/重点/保存按钮之外的所有空间
        contentField.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (state != null && state.content != null) contentField.setText(state.content);
        body.addView(contentField);

        // 重点（每行一条）：多行，固定高度（比正文小，它是附属清单不是主体）
        formLabel(body, "重点（每行一条）");
        keyPointsField = Ui.input(this, "每行一条重点");
        keyPointsField.setSingleLine(false);
        keyPointsField.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        keyPointsField.setMinLines(3);
        keyPointsField.setMaxLines(8);
        if (state != null && state.keyPoints != null) {
            keyPointsField.setText(Db.joinString(state.keyPoints));
        }
        body.addView(keyPointsField);

        body.addView(saveButton("保存", new Runnable() {
            @Override public void run() { save(); }
        }));
    }

    /** 对话框里的字段标签。BaseSettingsActivity 的 labeledField 内部已带标签，
     *  但多行字段那里需要单独调，所以保留这个和基类一致的小工具。 */
    private void formLabel(LinearLayout body, String text) {
        android.widget.TextView lb = Ui.text(this, text, Ui.T_LABEL,
                Ui.onSurfaceVariant(this), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.v(this, 8);
        lp.bottomMargin = Ui.v(this, 6);
        lb.setLayoutParams(lp);
        body.addView(lb);
    }

    /** 日期行：点选打开日期选择器（与待办编辑页同一套交互）。 */
    private View dateRow() {
        LinearLayout row = Ui.row(this);
        int ph = Ui.dp(this, 14), pv = Ui.v(this, 11);
        row.setPadding(ph, pv, ph, pv);
        row.setMinimumHeight(Ui.vMin(this, 48));
        row.setBackground(Ui.ripple(this, Ui.surfaceContainer(this), Ui.R_S));
        row.setClickable(true);
        row.setFocusable(true);

        TextView tv = Ui.text(this, Dates.shortDate(state != null ? Ui.nz(state.date) : Dates.today()),
                Ui.T_BODY + 1, Ui.onSurface(this), false);
        tv.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickDate(); }
        });
        return row;
    }

    /** 日期选择器——只选日期。 */
    private void pickDate() {
        String d = state != null ? Ui.nz(state.date) : Dates.today();
        String[] parts = d.split("-");
        int y = parts.length >= 1 ? Integer.parseInt(parts[0]) : 2026;
        int m = parts.length >= 2 ? Integer.parseInt(parts[1]) - 1 : 0;
        int day = parts.length >= 3 ? Integer.parseInt(parts[2]) : 1;
        new android.app.DatePickerDialog(this,
                new android.app.DatePickerDialog.OnDateSetListener() {
                    @Override public void onDateSet(android.widget.DatePicker dp,
                                                    int yy, int mm, int dd) {
                        state.date = String.format("%04d-%02d-%02d", yy, mm + 1, dd);
                        reapplyTheme();   // 重建以刷新日期行的显示
                    }
                }, y, m, day).show();
    }

    /** 保存：校验标题 → 组装 Note → 落库 → 提示 → finish。 */
    private void save() {
        String title = titleField.getText().toString().trim();
        if (title.length() == 0) {
            Tip.error(this, "请填写标题");
            return;
        }
        Db.Note n = editing != null ? editing : new Db.Note();
        n.courseId = courseId;
        n.title = title;
        n.date = state.date;
        n.content = contentField.getText().toString().trim();
        n.keyPoints = Db.splitString(keyPointsField.getText().toString());
        if (editing == null) n.id = Id.gen();
        db.saveNote(n);
        Tip.success(this, editing != null ? "笔记已更新" : "笔记已创建");
        finish();
    }
}
