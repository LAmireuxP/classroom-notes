package com.lamireuxp.classroom;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 笔记编辑页 —— 沉浸式全屏编辑器（参考华为笔记）。
 *
 * 1.7 之前是「标签 + 输入框 + 标签 + 输入框」的表单式布局（继承 BaseSettingsActivity），
 * 像设置页，不像编辑器——字段标签占地方、正文被挤、视觉重心落在表单结构而不是内容上。
 *
 * 现在改成沉浸式：
 *  - 顶栏极简（返回 + 保存图标），无标题
 *  - 标题大字（T_DISPLAY）直接可编辑，无标签行
 *  - 日期弱化小字 + 日历图标，点选改
 *  - 正文铺满，无标签、无边框，行高放宽
 *  - 重点用「—— 重点 ——」分隔线引导，每行一条
 *  - 配图内联在正文之后，横排缩略图 + 添加
 *  - 底部浮动工具栏：日期 / 重点 / 图片 / 保存，键盘弹起时跟到键盘上方
 *
 * 数据层（Note 字段、Db.saveNote）不动，只改界面。
 */
public class NoteEditorActivity extends BaseActivity {

    private static final int REQ_PICK_IMAGE = 301;

    private Db db;
    private String courseId;
    /** 编辑态时持有原笔记；新建态为 null。 */
    private Db.Note editing;
    /** 内存状态：新建时是空 Note，编辑时是原数据的副本。capture 从这里填字段。 */
    private Db.Note state;
    private EditText titleField, contentField, keyPointsField;
    /** 配图路径列表（编辑期间的内存状态，保存时写回 state.images）。 */
    private final List<String> imagePaths = new ArrayList<String>();
    /** 配图缩略图行的容器引用（增删图片后要重建）。 */
    private LinearLayout imageContainer;
    /** 底部浮动工具栏（跟键盘）。 */
    private LinearLayout toolbar;
    /** 滚动容器（工具栏贴边时要知道它的位置）。 */
    private ScrollView scroller;
    /** 内容根（加 padding 给底部工具栏让位）。 */
    private LinearLayout contentRoot;

    @Override
    protected void onCreateUi(Bundle b) {
        db = Db.get(this);
        courseId = getIntent().getStringExtra("courseId");
        String noteId = getIntent().getStringExtra("noteId");
        if (noteId != null && noteId.length() > 0) {
            editing = db.note(noteId);
        }
        state = editing != null ? editing : new Db.Note();
        if (state.date == null || state.date.length() == 0) state.date = Dates.today();
        loadImages(state.images);
        buildUi();
    }

    @Override
    protected void onRebuildUi() {
        capture();
        buildUi();
    }

    /** 把输入框里的值捞回 state（图片路径本身就在 state.images 里，不需要捞）。 */
    private void capture() {
        if (titleField == null) return;
        state.title = titleField.getText().toString();
        state.content = contentField.getText().toString();
        state.keyPoints = Db.splitString(keyPointsField.getText().toString());
    }

    /** 把 Note.images 字段解析成路径列表。 */
    private void loadImages(String images) {
        imagePaths.clear();
        if (images == null || images.length() == 0) return;
        for (String p : images.split(";")) {
            if (p.length() > 0) imagePaths.add(p);
        }
    }

    /** 把路径列表写回 state.images（保存时落库）。 */
    private void saveImagesToState() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < imagePaths.size(); i++) {
            if (i > 0) sb.append(";");
            sb.append(imagePaths.get(i));
        }
        state.images = sb.toString();
    }

    // ================== 界面搭建 ==================

    /**
     * 沉浸式骨架：顶栏（极简）+ 滚动内容 + 底部浮动工具栏。
     * 不复用 BaseSettingsActivity——它的顶栏有标题、body 有统一 padding、
     * 没有底部工具栏，这些在沉浸编辑器里都不合适。
     */
    private void buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.surface(this));
        setContentView(root);
        Ui.padStatusBar(this, topBar(root));

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scroller.setLayoutParams(sp);

        contentRoot = Ui.column(this);
        int hp = Ui.dp(this, 20);
        contentRoot.setPadding(hp, Ui.v(this, 8), hp, Ui.v(this, 80));
        scroller.addView(contentRoot);
        root.addView(scroller);

        fillContent(contentRoot);
        root.addView(bottomToolbar());
    }

    /** 顶栏：返回 + 右侧保存图标。无标题——沉浸式让内容自己说话。 */
    private View topBar(LinearLayout root) {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.surface(this));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 6), Ui.v(this, 10), Ui.dp(this, 8), Ui.v(this, 10));
        Ui.padStatusBar(this, bar);

        LinearLayout back = Icons.iconButton(this, R.drawable.ic_back, 42,
                Ui.onSurfaceVariant(this));
        back.setContentDescription("返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        bar.addView(back);

        bar.addView(Ui.spacer(this));

        LinearLayout save = Icons.iconButton(this, R.drawable.ic_check, 42,
                Ui.primary(this));
        save.setContentDescription("保存");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        bar.addView(save);

        root.addView(bar);
        // 沉浸式：顶栏与内容之间不加分隔线，靠留白过渡（华为笔记风格）
        return bar;
    }

    /**
     * 内容区：标题（大字）→ 日期（弱化）→ 正文 → 重点 → 配图。
     * 全程无分隔线、无字段标签——靠留白和字号区分内容流（华为笔记风格）。
     */
    private void fillContent(LinearLayout body) {
        // 标题：大字、加粗、无边框、直接编辑
        titleField = new EditText(this);
        titleField.setHint("标题");
        titleField.setTextSize(Ui.T_DISPLAY);
        titleField.setTextColor(Ui.onSurface(this));
        titleField.setHintTextColor(Ui.onSurfaceVariant(this));
        titleField.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleField.setBackground(null);
        titleField.setSingleLine(true);
        titleField.setMaxLines(2);
        titleField.setEllipsize(null);
        titleField.setPadding(Ui.dp(this, 0), Ui.v(this, 4), Ui.dp(this, 0), Ui.v(this, 6));
        if (state != null && state.title != null) titleField.setText(state.title);
        body.addView(titleField);

        // 日期：弱化小字，点选改
        body.addView(dateRow());

        // 正文：17sp + 1.5+ 行高（Apple 的阅读节奏——正文比 UI 大一号才叫「读」）
        contentField = new EditText(this);
        contentField.setHint("课堂内容、理解与疑问…");
        contentField.setTextSize(17);
        contentField.setTextColor(Ui.onSurface(this));
        contentField.setHintTextColor(Ui.onSurfaceVariant(this));
        contentField.setBackground(null);
        contentField.setSingleLine(false);
        contentField.setGravity(Gravity.TOP | Gravity.START);
        contentField.setMinLines(6);
        contentField.setLineSpacing(Ui.v(this, 5), 1f);
        contentField.setPadding(Ui.dp(this, 0), Ui.v(this, 16), Ui.dp(this, 0), Ui.v(this, 8));
        if (state != null && state.content != null) contentField.setText(state.content);
        body.addView(contentField);

        // 重点：留白引导 + 多行输入，不加分隔线标题
        keyPointsField = new EditText(this);
        keyPointsField.setHint("重点（每行一条）");
        keyPointsField.setTextSize(16);
        keyPointsField.setTextColor(Ui.onSurface(this));
        keyPointsField.setHintTextColor(Ui.onSurfaceVariant(this));
        keyPointsField.setBackground(null);
        keyPointsField.setSingleLine(false);
        keyPointsField.setGravity(Gravity.TOP | Gravity.START);
        keyPointsField.setMinLines(3);
        keyPointsField.setLineSpacing(Ui.v(this, 4), 1f);
        keyPointsField.setPadding(Ui.dp(this, 0), Ui.v(this, 12), Ui.dp(this, 0), Ui.v(this, 8));
        if (state != null && state.keyPoints != null) {
            keyPointsField.setText(Db.joinString(state.keyPoints));
        }
        body.addView(keyPointsField);

        // 配图：横排缩略图 + 添加，无标题
        imageContainer = Ui.column(this);
        imageContainer.setPadding(Ui.dp(this, 0), Ui.v(this, 8), Ui.dp(this, 0), Ui.v(this, 8));
        renderImageRow();
        body.addView(imageContainer);
    }

    /** 日期行：弱化小字，点选打开日期选择器（华为笔记风格——无图标，纯文字）。 */
    private View dateRow() {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 0), Ui.v(this, 2), Ui.dp(this, 0), Ui.v(this, 10));
        row.setClickable(true);
        row.setFocusable(false);

        TextView tv = Ui.text(this, Dates.shortDate(Ui.nz(state.date)),
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
        row.addView(tv);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickDate(); }
        });
        return row;
    }

    /** 一条极细分隔线。 */
    private View hairline(int color) {
        View v = new View(this);
        v.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
        v.setLayoutParams(lp);
        return v;
    }

    // ================== 配图 ==================

    /** 图片行：已有图片的缩略图（可删）+ 添加按钮，横排可滚动。 */
    private void renderImageRow() {
        imageContainer.removeAllViews();

        HorizontalScrollView hscroll = new HorizontalScrollView(this);
        hscroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 2);
        row.setPadding(pad, pad, pad, pad);

        // 已有图片的缩略图
        for (int i = 0; i < imagePaths.size(); i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 80), Ui.dp(this, 80));
            clp.rightMargin = Ui.dp(this, 8);
            cell.setLayoutParams(clp);

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(decodeThumb(imagePaths.get(idx), Ui.dp(this, 80)));
            cell.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 右上角删除按钮
            ImageView del = new ImageView(this);
            del.setImageResource(R.drawable.ic_close);
            del.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            del.setBackground(Ui.circle(0xB0333333, Ui.dp(this, 20)));
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                    Ui.dp(this, 22), Ui.dp(this, 22), Gravity.TOP | Gravity.END);
            dlp.rightMargin = Ui.dp(this, 4);
            dlp.topMargin = Ui.dp(this, 4);
            del.setLayoutParams(dlp);
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { removeImage(idx); }
            });
            cell.addView(del);

            row.addView(cell);
        }

        // 添加按钮：虚线框感的 + 号
        LinearLayout addBtn = new LinearLayout(this);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setBackground(Ui.round(this, Ui.surfaceContainer(this),
                Ui.outlineVariant(this), Ui.R_S, 1f));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                Ui.dp(this, 80), Ui.dp(this, 80));
        addBtn.setLayoutParams(alp);
        addBtn.setClickable(true);
        addBtn.setFocusable(true);
        addBtn.addView(Icons.icon(this, R.drawable.ic_add, Ui.onSurfaceVariant(this), 28));
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickImage(); }
        });
        row.addView(addBtn);

        hscroll.addView(row);
        imageContainer.addView(hscroll);
    }

    /** 从相册选图。 */
    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(
                Intent.createChooser(intent, "选择图片"), REQ_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            String path = db.saveNoteImage(this, uri);
            if (path != null) {
                imagePaths.add(path);
                saveImagesToState();
                renderImageRow();
            } else {
                Tip.error(this, "图片加载失败");
            }
        }
    }

    private void removeImage(int index) {
        if (index < 0 || index >= imagePaths.size()) return;
        db.deleteNoteImage(imagePaths.get(index));
        imagePaths.remove(index);
        saveImagesToState();
        renderImageRow();
    }

    /** 解码一张缩略图（长边压到 targetPx，避免全尺寸解码吃内存）。 */
    private Bitmap decodeThumb(String path, int targetPx) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        int sample = 1;
        int maxDim = Math.max(opts.outWidth, opts.outHeight);
        while (maxDim / sample > targetPx * 2) sample *= 2;
        opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, opts);
    }

    // ================== 日期 ==================

    /** 日期选择器——只选日期。 */
    private void pickDate() {
        String d = Ui.nz(state.date);
        String[] parts = d.split("-");
        int y = parts.length >= 1 ? Integer.parseInt(parts[0]) : 2026;
        int m = parts.length >= 2 ? Integer.parseInt(parts[1]) - 1 : 0;
        int day = parts.length >= 3 ? Integer.parseInt(parts[2]) : 1;
        new android.app.DatePickerDialog(this,
                new android.app.DatePickerDialog.OnDateSetListener() {
                    @Override public void onDateSet(android.widget.DatePicker dp,
                                                    int yy, int mm, int dd) {
                        state.date = String.format("%04d-%02d-%02d", yy, mm + 1, dd);
                        reapplyTheme();
                    }
                }, y, m, day).show();
    }

    // ================== 底部浮动工具栏 ==================

    /**
     * 底部工具栏：日期 / 重点 / 图片 / 保存。
     * 纯图标、大间距、无文字标签——极简，像华为笔记键盘上方那条工具条。
     */
    private View bottomToolbar() {
        toolbar = Ui.column(this);
        toolbar.setBackgroundColor(Ui.surface(this));

        toolbar.addView(hairline(Ui.outlineVariant(this)));

        LinearLayout btnRow = Ui.row(this);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        int ph = Ui.dp(this, 16), pv = Ui.v(this, 10);
        btnRow.setPadding(ph, pv, ph, pv);

        btnRow.addView(toolIcon(R.drawable.ic_calendar, "日期", new Runnable() {
            @Override public void run() { pickDate(); }
        }));
        btnRow.addView(toolIcon(R.drawable.ic_star, "重点", new Runnable() {
            @Override public void run() { focusField(keyPointsField); }
        }));
        btnRow.addView(toolIcon(R.drawable.ic_add, "图片", new Runnable() {
            @Override public void run() { pickImage(); }
        }));
        // 不放保存按钮——顶栏 ✓ 已经是保存入口，底部只做工具，职责清晰
        btnRow.addView(Ui.spacer(this));
        toolbar.addView(btnRow);
        return toolbar;
    }

    /** 工具栏的纯图标按钮（无文字），大点击区。 */
    private View toolIcon(int iconRes, String desc, final Runnable action) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_S));
        int pad = Ui.dp(this, 12);
        b.setPadding(pad, Ui.v(this, 8), pad, Ui.v(this, 8));
        b.setMinimumWidth(Ui.vMin(this, 44));
        b.setMinimumHeight(Ui.vMin(this, 44));

        ImageView iv = Icons.icon(this, iconRes, Ui.onSurfaceVariant(this), 24);
        b.addView(iv);
        b.setContentDescription(desc);

        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return b;
    }

    /** 保存按钮：填充色，强调主操作。 */
    private View saveButton() {
        TextView btn = Ui.filledButton(this, "保存");
        btn.setMinHeight(Ui.vMin(this, 40));
        btn.setPadding(Ui.dp(this, 24), Ui.v(this, 8), Ui.dp(this, 24), Ui.v(this, 8));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        return btn;
    }

    /** 把焦点移到指定字段并弹键盘。 */
    private void focusField(EditText et) {
        et.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(et, 0);
    }

    // ================== 保存 ==================

    /** 保存：校验标题 → 组装 Note → 落库 → 提示 → finish。 */
    private void save() {
        capture();   // 先把当前输入捞回 state（含 images）
        String title = state.title.trim();
        if (title.length() == 0) {
            Tip.error(this, "请填写标题");
            return;
        }
        Db.Note n = editing != null ? editing : new Db.Note();
        n.courseId = courseId;
        n.title = title;
        n.date = state.date;
        n.content = state.content;
        n.keyPoints = state.keyPoints;
        n.images = state.images;
        if (editing == null) n.id = Id.gen();
        db.saveNote(n);
        Tip.success(this, editing != null ? "笔记已更新" : "笔记已创建");
        finish();
    }
}
