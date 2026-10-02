package com.lamireuxp.classroom;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 已归档课程列表 —— 上完的课都收在这里。
 *
 * 归档 ≠ 删除：数据原样保留，笔记、待办、配图一个不少，只是退出首页列表、
 * 搜索、统计和提醒。这门课想再翻时从这里进；想拿回首页点「恢复」。
 *
 * 骨架复用 BaseSettingsActivity（顶栏 + 滚动正文），和设置页同一套视觉。
 */
public class ArchivedCoursesActivity extends BaseSettingsActivity {

    @Override protected String title() { return "已归档课程"; }

    @Override
    protected void fillBody(LinearLayout body) {
        List<Db.Course> list = Db.get(this).archivedCourses();

        if (list.isEmpty()) {
            body.addView(Ui.emptyState(this, R.drawable.ic_archive, "没有归档的课程",
                    "在首页课程菜单里选「归档课程」，上完的课就会收在这里"));
            return;
        }

        TextView count = Ui.text(this, "共 " + list.size() + " 门，数据都还在",
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = Ui.v(this, 10);
        count.setLayoutParams(cp);
        body.addView(count);

        for (int i = 0; i < list.size(); i++) {
            body.addView(courseRow(list.get(i)));
        }
    }

    /** 一门归档课：色点 + 名称/教师/计数 + 右侧「恢复」。点行本体进课程页。 */
    private View courseRow(final Db.Course c) {
        LinearLayout row = Ui.row(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.v(this, 12), Ui.dp(this, 16), Ui.v(this, 12));
        row.setBackground(Ui.ripple(this, Ui.surfaceContainer(this), Ui.R_M));
        row.setClickable(true);
        row.setFocusable(true);
        Ui.pressScale(row);

        View dot = new View(this);
        dot.setBackground(Ui.circle(colorOf(c.color), Ui.dp(this, 5)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 10), Ui.dp(this, 10));
        dot.setLayoutParams(dlp);
        row.addView(dot);

        LinearLayout mid = Ui.column(this);
        LinearLayout.LayoutParams mp = Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = Ui.dp(this, 14);
        mid.setLayoutParams(mp);
        mid.addView(Ui.text(this, c.name, Ui.T_BODY + 1, Ui.onSurface(this), true));
        String meta = (c.teacher != null && c.teacher.length() > 0 ? c.teacher + " · " : "")
                + c.noteCount + " 条笔记 · " + c.todoCount + " 个待办";
        mid.addView(Ui.text(this, meta, Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
        row.addView(mid);

        TextView restore = Ui.textButton(this, "恢复");
        restore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { restore(c); }
        });
        row.addView(restore);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent it = new Intent(ArchivedCoursesActivity.this, CourseActivity.class);
                it.putExtra("courseId", c.id);
                startActivity(it);
            }
        });
        return row;
    }

    /**
     * 取消归档。提醒重排：归档时撤掉的闹钟，这里把还没过期、没完成的补回来
     * （rescheduleAll 按库里活课的待办重排，归档课已经不在其中）。
     */
    private void restore(Db.Course c) {
        Db.get(this).unarchiveCourse(c.id);
        Reminders.rescheduleAll(this);
        Tip.success(this, "「" + c.name + "」已恢复");
        if (Db.get(this).archivedCount() == 0) {
            finish();          // 恢复完最后一门，这页没有内容了，直接回首页
        } else {
            reapplyTheme();    // 就地重建列表
        }
    }

    /** 课程色字符串 → int（空值 / 解析失败回中性灰，不让坏数据弄崩页面）。 */
    private int colorOf(String hex) {
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return 0x888888;
        }
    }
}
