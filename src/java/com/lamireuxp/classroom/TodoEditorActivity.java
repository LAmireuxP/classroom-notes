package com.lamireuxp.classroom;

import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 待办编辑页 —— 新建 / 编辑待办的独立页面。
 *
 * 原来用 Dialogs.form 弹窗（View body 版），后来改成全屏页面。待办表单里
 * 有优先级分段选择和提醒选择器，选了之后要重建表单体——弹窗里重建会让
 * 焦点丢失，全屏页面用 BaseSettingsActivity 的 reapplyTheme() 重建，
 * 和 AiSettingsActivity 切协议同一套 capture → rebuild → restore 模式。
 *
 * 1.7 起支持**编辑已有待办**（todoId extra）并带**备注**字段——原来建错了
 * 只能删掉重来，作业要求、考试范围这类补充信息也没地方放。
 *
 * Intent extras：courseId（必填）、todoId（可选——有=编辑、无=新建）。
 * 保存后 finish()，CourseActivity.onResumed() 会自动 renderTabs + renderContent。
 */
public class TodoEditorActivity extends BaseSettingsActivity {

    private Db db;
    private String courseId;
    /** 编辑态时持有原待办；新建态为 null。 */
    private Db.Todo editing;

    /** 表单状态：优先级 / 提醒的选择会触发重建表单，已填的值要先捞回这里。 */
    private static final class Form {
        String title = "";
        String note = "";
        String due = Dates.today();
        String priority = "medium";
        long remindAt;
        EditText titleField;
        EditText noteField;
    }

    private final Form form = new Form();
    private LinearLayout formBody;   // 优先级 / 提醒选择后只重建这一块，不重建整页

    @Override
    protected void onCreateUi(Bundle b) {
        db = Db.get(this);
        courseId = getIntent().getStringExtra("courseId");
        String todoId = getIntent().getStringExtra("todoId");
        if (todoId != null && todoId.length() > 0) {
            editing = db.todo(todoId);
        }
        if (editing != null) {
            // 编辑态：原数据进表单。doneAt 留在 editing 对象上，保存时原样写回。
            form.title = Ui.nz(editing.title);
            form.note = Ui.nz(editing.note);
            form.due = (editing.due != null && editing.due.length() > 0)
                    ? editing.due : Dates.today();
            form.priority = (editing.priority != null && editing.priority.length() > 0)
                    ? editing.priority : "medium";
            form.remindAt = editing.remindAt;
        }
        super.onCreateUi(b);
    }

    @Override
    protected String title() {
        return editing != null ? "编辑待办" : "新建待办";
    }

    /** 换主题重建前先把输入框里的值捞回 form——输入到一半的内容不能丢。 */
    @Override
    protected void onRebuildUi() {
        capture();
        super.onRebuildUi();
    }

    private void capture() {
        if (form.titleField != null) form.title = form.titleField.getText().toString();
        if (form.noteField != null) form.note = form.noteField.getText().toString();
    }

    @Override
    protected void fillBody(LinearLayout body) {
        form.titleField = labeledField("任务内容", "完成第三章习题", form.title);

        // 备注：多行，选填——作业要求、考试范围、老师口头强调的东西
        formLabel(body, "备注（选填）");
        form.noteField = Ui.input(this, "作业要求、考试范围…");
        form.noteField.setSingleLine(false);
        form.noteField.setGravity(Gravity.TOP | Gravity.START);
        form.noteField.setMinLines(2);
        form.noteField.setMaxLines(6);
        if (form.note.length() > 0) form.noteField.setText(form.note);
        body.addView(form.noteField);

        // 截止日期 / 优先级 / 提醒都是「点选」而非手输——放在一个独立容器里，
        // 选了之后只重建这个容器，不重建整页（重建整页会让刚填的标题丢焦点）
        formBody = Ui.column(this);
        LinearLayout.LayoutParams fbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fbp.topMargin = Ui.v(this, 8);
        formBody.setLayoutParams(fbp);
        body.addView(formBody);
        renderSelectors();

        body.addView(saveButton("保存", new Runnable() {
            @Override public void run() { save(); }
        }));
    }

    /** 截止日期 + 优先级分段 + 提醒行。选了任一项后只重建这一块。 */
    private void renderSelectors() {
        // 先把 EditText 里的值捞回 form（重建会销毁旧 EditText）
        capture();

        formBody.removeAllViews();

        // 截止日期：点选而非手输——手输日期格式太多写法，敲错会存进一个无效值。
        // 提醒本来就用日期选择器，截止日期没理由不用。
        formLabel(formBody, "截止日期");
        formBody.addView(dueRow());

        // 优先级
        formLabel(formBody, "优先级");
        formBody.addView(Ui.segmentedRow(this, new String[]{"高", "中", "低"},
                new int[]{Ui.priorityColor(this, "high"), Ui.priorityColor(this, "medium"),
                        Ui.priorityColor(this, "low")},
                Db.priorityIndex(form.priority), new Ui.Pick() {
                    @Override public void onPick(int index) {
                        String key = Db.PRIORITY_KEYS[index];
                        if (key.equals(form.priority)) return;
                        form.priority = key;
                        renderSelectors();
                    }
                }));

        // 提醒
        formLabel(formBody, "提醒");
        formBody.addView(remindRow());

        // 编辑态：建一条元信息，用户知道这条是什么时候建的
        if (editing != null && editing.created > 0) {
            formLabel(formBody, "创建于 " + Dates.stamp(editing.created)
                    + (editing.completed && editing.doneAt > 0
                       ? " · 完成于 " + Dates.stamp(editing.doneAt) : ""));
        }
    }

    /** 截止日期行：点选打开日期选择器（和提醒用同一套交互）。 */
    private View dueRow() {
        LinearLayout row = Ui.row(this);
        int ph = Ui.dp(this, 14), pv = Ui.v(this, 11);
        row.setPadding(ph, pv, ph, pv);
        row.setMinimumHeight(Ui.vMin(this, 48));
        row.setBackground(Ui.ripple(this, Ui.surfaceContainer(this), Ui.R_S));
        row.setClickable(true);
        row.setFocusable(true);

        TextView tv = Ui.text(this, "截止　" + Dates.shortDate(form.due),
                Ui.T_BODY + 1, Ui.onSurface(this), false);
        tv.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickDueDate(); }
        });
        return row;
    }

    /** 日期选择器——只选日期，不选时间（截止日期不像提醒需要精确到分钟）。 */
    private void pickDueDate() {
        // form.due 是 "YYYY-MM-DD"，拆开给 DatePicker 当初始值
        String[] parts = form.due.split("-");
        int y = parts.length >= 1 ? Integer.parseInt(parts[0]) : 2026;
        int m = parts.length >= 2 ? Integer.parseInt(parts[1]) - 1 : 0;  // 0-indexed
        int d = parts.length >= 3 ? Integer.parseInt(parts[2]) : 1;
        new android.app.DatePickerDialog(this,
                new android.app.DatePickerDialog.OnDateSetListener() {
                    @Override public void onDateSet(android.widget.DatePicker dp,
                                                    int yy, int mm, int dd) {
                        form.due = String.format("%04d-%02d-%02d", yy, mm + 1, dd);
                        renderSelectors();
                    }
                }, y, m, d).show();
    }

    /** 提醒行：没设时是「不提醒（点这里设一个）」，设了就显示时刻 + 清除按钮。 */
    private View remindRow() {
        LinearLayout row = Ui.row(this);
        int ph = Ui.dp(this, 14), pv = Ui.v(this, 11);
        row.setPadding(ph, pv, ph, pv);
        row.setMinimumHeight(Ui.vMin(this, 48));
        row.setBackground(Ui.ripple(this, Ui.surfaceContainer(this), Ui.R_S));
        row.setClickable(true);
        row.setFocusable(true);

        TextView tv = Ui.text(this,
                form.remindAt > 0 ? "提醒时间　" + Dates.stamp(form.remindAt) : "不提醒（点这里设一个）",
                Ui.T_BODY + 1,
                form.remindAt > 0 ? Ui.onSurface(this) : Ui.onSurfaceVariant(this), false);
        tv.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        if (form.remindAt > 0) {
            LinearLayout clear = Icons.iconButton(this, R.drawable.ic_close, 36, Ui.outline(this));
            clear.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    form.remindAt = 0;
                    renderSelectors();
                }
            });
            row.addView(clear);
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickRemind(); }
        });
        return row;
    }

    /** 先选日期、再选时间（两个系统对话框串起来）。 */
    private void pickRemind() {
        long base = form.remindAt > 0 ? form.remindAt : System.currentTimeMillis() + 3600000L;
        final int[] p = new int[5];
        Dates.split(base, p);
        new android.app.DatePickerDialog(this,
                new android.app.DatePickerDialog.OnDateSetListener() {
                    @Override public void onDateSet(android.widget.DatePicker dp,
                                                    final int y, final int m, final int d) {
                        new android.app.TimePickerDialog(TodoEditorActivity.this,
                                new android.app.TimePickerDialog.OnTimeSetListener() {
                                    @Override public void onTimeSet(android.widget.TimePicker tp,
                                                                    int hh, int mm) {
                                        form.remindAt = Dates.at(y, m, d, hh, mm);
                                        if (form.remindAt > System.currentTimeMillis()) {
                                            ensureNotifyPermission();
                                        }
                                        renderSelectors();
                                    }
                                }, p[3], p[4], true).show();
                    }
                }, p[0], p[1], p[2]).show();
    }

    /** API 33+ 要用户点头才发得出通知。放在「刚设完一个未来的提醒」这一刻问最合理。 */
    private void ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (Reminders.canNotify(this)) return;
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 202);
    }

    /** 对话框里的字段标签。 */
    private void formLabel(LinearLayout body, String text) {
        TextView lb = Ui.text(this, text, Ui.T_LABEL, Ui.onSurfaceVariant(this), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.v(this, 16);
        lp.bottomMargin = Ui.v(this, 6);
        lb.setLayoutParams(lp);
        body.addView(lb);
    }

    /** 保存：校验任务内容 → 组装 Todo → 落库 → 排闹钟 → 提示 → finish。 */
    private void save() {
        capture();
        String title = form.title.trim();
        if (title.length() == 0) {
            Tip.error(this, "请填写任务内容");
            return;
        }
        // 编辑态在原对象上改（保住 id / created / doneAt）；新建态全新一条
        Db.Todo todo = editing != null ? editing : new Db.Todo();
        if (editing == null) todo.id = Id.gen();
        todo.courseId = courseId;
        todo.title = title;
        todo.note = form.note;
        todo.due = form.due;
        todo.priority = form.priority;
        todo.remindAt = form.remindAt;
        db.saveTodo(todo);
        // 存完立刻重排闹钟——编辑改了提醒时刻、或把已完成的又改回进行中，
        // 都要让闹钟跟上库里最新的值（schedule 内部先 cancel 再 set，不会残留旧的）
        Reminders.schedule(this, todo);
        if (form.remindAt > 0 && form.remindAt <= System.currentTimeMillis() && !todo.completed) {
            Tip.error(this, editing != null ? "已保存，但提醒时间已过，不会提醒" : "已创建，但提醒时间已过，不会提醒");
        } else {
            Tip.success(this, editing != null ? "待办已更新" : "待办已创建");
        }
        finish();
    }
}
