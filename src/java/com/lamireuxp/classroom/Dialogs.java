package com.lamireuxp.classroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 弹窗系统 —— 对齐 Material Design 3。
 *
 * 提供三种形态：
 *  1. form()    —— 居中的表单对话框（MD3 Basic Dialog）
 *  2. sheet()   —— 底部抽屉（MD3 Bottom Sheet），用于菜单类操作
 *  3. confirm() —— 确认对话框
 *  4. content() —— 内容预览对话框
 */
public final class Dialogs {

    private Dialogs() {}

    public interface OnSubmit {
        void onSubmit(EditText[] fields);
    }

    // ================== 基础容器 ==================

    private static LinearLayout dialogRoot(Context c) {
        LinearLayout root = Ui.column(c);
        root.setBackground(Ui.round(c, Ui.surfaceHigh(c), Color.TRANSPARENT, Ui.R_XL, 0));
        int p = Ui.dp(c, 24);
        root.setPadding(p, p, p, p);
        return root;
    }

    private static void styleDialog(AlertDialog dlg, boolean bottom) {
        dlg.setCanceledOnTouchOutside(true);
        dlg.show();
        Window w = dlg.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        if (bottom) lp.gravity = Gravity.BOTTOM;
        else lp.gravity = Gravity.CENTER;
        w.setAttributes(lp);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // 底部抽屉进入动画。
        // 原来是固定 translationY 40dp——对高抽屉来说这点位移几乎看不出来，
        // 抽屉像是「凭空出现」。改成从屏幕高度滑入，并统一用 Ui.EASE_STANDARD。
        // 先设一个确定在屏幕外的初值，避免 post 到实际高度时闪一帧。
        if (bottom && w.getDecorView() != null) {
            final View decor = w.getDecorView();
            int screenH = dlg.getContext().getResources().getDisplayMetrics().heightPixels;
            decor.setTranslationY(screenH);
            decor.post(new Runnable() {
                @Override public void run() {
                    decor.animate().translationY(0)
                            .setDuration(Ui.DUR_BASE)
                            .setInterpolator(Ui.EASE_STANDARD)
                            .start();
                }
            });
        }
    }

    // ================== 1. 表单对话框 ==================

    public static void form(final Activity a, String title, String[] labels,
                            String[] hints, String[] values, boolean[] multiline,
                            final OnSubmit submit) {
        final Context c = a;
        LinearLayout root = dialogRoot(c);

        // 标题
        TextView t = Ui.text(c, title, Ui.T_HEADLINE, Ui.onSurface(c), true);
        root.addView(t);

        final EditText[] inputs = new EditText[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView lb = Ui.text(c, labels[i], Ui.T_LABEL,
                    Ui.onSurfaceVariant(c), true);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = Ui.dp(c, i == 0 ? 20 : 16);
            llp.bottomMargin = Ui.dp(c, 6);
            lb.setLayoutParams(llp);
            root.addView(lb);

            EditText et = Ui.input(c, hints[i] == null ? "" : hints[i]);
            if (values != null && values[i] != null) et.setText(values[i]);
            if (multiline != null && multiline[i]) {
                et.setSingleLine(false);
                et.setGravity(Gravity.TOP | Gravity.START);
                et.setMinLines(3);
                et.setMaxLines(10);
            }
            inputs[i] = et;
            root.addView(et);
        }

        // 按钮行（MD3：文字按钮 + 填充按钮，右对齐）
        LinearLayout foot = Ui.row(c);
        foot.setGravity(Gravity.END);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = Ui.dp(c, 8);
        foot.setLayoutParams(fp);

        final AlertDialog dlg = new AlertDialog.Builder(a).create();
        TextView cancel = Ui.textButton(c, "取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        TextView ok = Ui.filledButton(c, "保存");
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submit.onSubmit(inputs); }
        });
        foot.addView(cancel);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.leftMargin = Ui.dp(c, 10);
        foot.addView(ok, op);
        root.addView(foot);

        LinearLayout wrap = Ui.column(c);
        wrap.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        wrap.addView(root);

        ScrollView sv = new ScrollView(c);
        sv.addView(wrap);
        dlg.setView(sv);
        if (a instanceof DialogHost) ((DialogHost) a).setSubmitDialog(dlg);
        styleDialog(dlg, false);
        focusFirst(dlg, true);
    }

    // ================== 2. 底部抽屉 ==================

    /** MD3 Bottom Sheet：从底部滑出的操作面板。 */
    public static AlertDialog sheet(final Activity a, String title, View body) {
        final Context c = a;
        AlertDialog dlg = new AlertDialog.Builder(a).create();

        LinearLayout root = Ui.column(c);
        root.setBackground(Ui.roundTop(c, Ui.surfaceHigh(c), Ui.R_XL));

        // 顶部拖拽条（MD3 标志性元素）
        LinearLayout handleBox = Ui.column(c);
        handleBox.setGravity(Gravity.CENTER);
        handleBox.setPadding(0, Ui.dp(c, 12), 0, 0);
        View handle = new View(c);
        handle.setBackground(Ui.round(c, Ui.outlineVariant(c), 0, Ui.R_FULL, 0));
        handle.setLayoutParams(Ui.lp(Ui.dp(c, 32), Ui.dp(c, 4)));
        handleBox.addView(handle);
        root.addView(handleBox);

        // 标题
        if (title != null && title.length() > 0) {
            TextView t = Ui.text(c, title, Ui.T_TITLE, Ui.onSurface(c), true);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.leftMargin = Ui.dp(c, 24);
            tp.rightMargin = Ui.dp(c, 24);
            tp.topMargin = Ui.dp(c, 18);
            tp.bottomMargin = Ui.dp(c, 8);
            t.setLayoutParams(tp);
            root.addView(t);
        }

        body.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 20));
        root.addView(body);

        // 底部安全间距
        View safe = new View(c);
        safe.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 12)));
        root.addView(safe);

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(false);
        sv.addView(root);
        dlg.setView(sv);
        styleDialog(dlg, true);
        return dlg;
    }

    // ================== 3. 确认对话框 ==================

    public static void confirm(final Activity a, String title, String message,
                               String okText, final Runnable onOk) {
        final Context c = a;
        AlertDialog dlg = new AlertDialog.Builder(a).create();

        LinearLayout root = dialogRoot(c);
        TextView t = Ui.text(c, title, Ui.T_HEADLINE, Ui.onSurface(c), true);
        root.addView(t);

        TextView msg = Ui.text(c, message, Ui.T_BODY, Ui.onSurfaceVariant(c), false);
        msg.setLineSpacing(Ui.dp(c, 4), 1f);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = Ui.dp(c, 12);
        msg.setLayoutParams(mp);
        root.addView(msg);

        LinearLayout foot = Ui.row(c);
        foot.setGravity(Gravity.END);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = Ui.dp(c, 20);
        foot.setLayoutParams(fp);

        TextView cancel = Ui.textButton(c, "取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        TextView ok = Ui.filledButton(c, okText);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dlg.dismiss();
                onOk.run();
            }
        });
        foot.addView(cancel);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.leftMargin = Ui.dp(c, 10);
        foot.addView(ok, op);
        root.addView(foot);

        LinearLayout wrap = Ui.column(c);
        wrap.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        wrap.addView(root);

        dlg.setView(wrap);
        styleDialog(dlg, false);
    }

    // ================== 4. 内容对话框 ==================

    public static AlertDialog content(Activity a, String title, View body,
                                      String okText, final Runnable onOk) {
        Shell s = shell(a, title, body);
        addCancel(s, "关闭");
        if (okText != null && onOk != null) {
            addOk(s, okText, new Saver() {
                @Override public boolean save() { onOk.run(); return true; }
            });
        }
        return s.dlg;
    }

    /** 表单的保存回调：返回 true 表示校验通过、数据已写入，可以关窗了。 */
    public interface Saver {
        boolean save();
    }

    /**
     * 表单对话框：body 由调用方拼——可以放输入框，也可以放选择行（新建待办的优先级就是），
     * 比上面那个数组版灵活。底部是「取消 / 保存」。
     *
     * 与 content() 的差别在按钮语义：**保存不先关窗**，由回调说了算。校验没过时对话框还在，
     * 用户刚填的内容不会因为点早了一下就丢掉。
     */
    public static AlertDialog form(Activity a, String title, View body, String okText,
                                   final Saver onSave) {
        Shell s = shell(a, title, body);
        addCancel(s, "取消");
        addOk(s, okText, onSave);
        if (a instanceof DialogHost) ((DialogHost) a).setSubmitDialog(s.dlg);
        return s.dlg;
    }

    /** 对话框外壳：标题 + 可滚动内容 + 底部按钮行（按钮由调用方往 foot 里加）。 */
    private static final class Shell {
        AlertDialog dlg;
        LinearLayout foot;
    }

    private static Shell shell(Activity a, String title, View body) {
        Context c = a;
        Shell s = new Shell();
        s.dlg = new AlertDialog.Builder(a).create();

        LinearLayout root = dialogRoot(c);
        root.addView(Ui.text(c, title, Ui.T_HEADLINE, Ui.onSurface(c), true));

        ScrollView sv = new ScrollView(c);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(sp);
        body.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 6));
        sv.addView(body);
        root.addView(sv);

        s.foot = Ui.row(c);
        s.foot.setGravity(Gravity.END);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = Ui.dp(c, 14);
        s.foot.setLayoutParams(fp);
        root.addView(s.foot);

        LinearLayout wrap = Ui.column(c);
        wrap.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        wrap.addView(root);

        s.dlg.setView(wrap);
        styleDialog(s.dlg, false);
        return s;
    }

    private static void addCancel(final Shell s, String text) {
        TextView cancel = Ui.textButton(s.dlg.getContext(), text);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { s.dlg.dismiss(); }
        });
        s.foot.addView(cancel);
    }

    private static void addOk(final Shell s, String text, final Saver onSave) {
        Context c = s.dlg.getContext();
        TextView ok = Ui.filledButton(c, text);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (onSave.save()) s.dlg.dismiss();
            }
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.leftMargin = Ui.dp(c, 10);
        s.foot.addView(ok, op);
    }

    // ================== 辅助 ==================

    public static void focusFirst(final AlertDialog dlg, final boolean showKeyboard) {
        if (dlg == null) return;
        View v = dlg.getWindow() == null ? null : dlg.getWindow().getDecorView();
        final EditText et = findEdit(v);
        if (et == null) return;
        et.requestFocus();
        if (showKeyboard) {
            et.postDelayed(new Runnable() {
                @Override public void run() {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    dlg.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et,
                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            }, 140);
        }
    }

    private static EditText findEdit(View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText r = findEdit(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    public interface DialogHost {
        void setSubmitDialog(AlertDialog dlg);
    }
}