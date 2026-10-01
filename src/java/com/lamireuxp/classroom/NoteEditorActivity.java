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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 笔记编辑页 —— 新建 / 编辑笔记的独立页面。
 *
 * 原来用 Dialogs.form 弹窗，现在改成全屏页面：笔记内容是多行长文本，弹窗里
 * 编辑空间太小。全屏页面给正文和重点清单足够的书写空间，和设置页同一套骨架。
 *
 * 配图：每条笔记可以挂若干张图片（拍板书、截 PPT），存 app 内部目录，
 * 文件路径以分号分隔存在 Note.images 字段里。编辑页负责添加和删除，
 * 课程页展开时负责展示。
 *
 * Intent extras：courseId（必填）、noteId（可选——有=编辑、无=新建）。
 * 保存后 finish()，CourseActivity.onResumed() 会自动 renderTabs + renderContent。
 */
public class NoteEditorActivity extends BaseSettingsActivity {

    private static final int REQ_PICK_IMAGE = 301;

    private Db db;
    private String courseId;
    /** 编辑态时持有原笔记；新建态为 null。 */
    private Db.Note editing;
    /** 内存状态：新建时是空 Note，编辑时是原数据的副本。fillBody 从这里填字段。 */
    private Db.Note state;
    private EditText titleField, contentField, keyPointsField;
    /** 配图路径列表（编辑期间的内存状态，保存时写回 state.images）。 */
    private final List<String> imagePaths = new ArrayList<String>();
    /** 图片缩略图行的容器引用（增删图片后要重建）。 */
    private LinearLayout imageContainer;

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
        super.onCreateUi(b);
    }

    @Override
    protected String title() {
        return editing != null ? "编辑笔记" : "新建笔记";
    }

    @Override
    protected void onRebuildUi() {
        capture();
        super.onRebuildUi();
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

    @Override
    protected void fillBody(LinearLayout body) {
        titleField = labeledField("标题", "第一章 极限与连续",
                Ui.nz(state.title));

        // 日期：点选而非手输——手输日期格式太多写法，敲错会让 shortDate 解析出错
        formLabel(body, "日期");
        body.addView(dateRow());

        // 笔记内容：多行
        formLabel(body, "笔记内容");
        contentField = Ui.input(this, "课堂内容、理解与疑问…");
        contentField.setSingleLine(false);
        contentField.setGravity(Gravity.TOP | Gravity.START);
        contentField.setMinLines(8);
        if (state != null && state.content != null) contentField.setText(state.content);
        body.addView(contentField);

        // 重点（每行一条）：多行
        formLabel(body, "重点（每行一条）");
        keyPointsField = Ui.input(this, "每行一条重点");
        keyPointsField.setSingleLine(false);
        keyPointsField.setGravity(Gravity.TOP | Gravity.START);
        keyPointsField.setMinLines(3);
        keyPointsField.setMaxLines(8);
        if (state != null && state.keyPoints != null) {
            keyPointsField.setText(Db.joinString(state.keyPoints));
        }
        body.addView(keyPointsField);

        // 配图：缩略图 + 添加按钮
        formLabel(body, "图片");
        imageContainer = Ui.column(this);
        renderImageRow();
        body.addView(imageContainer);

        body.addView(saveButton("保存", new Runnable() {
            @Override public void run() { save(); }
        }));
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
        int pad = Ui.dp(this, 4);
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
                Ui.outlineVariant(this), Ui.R_S, 1));
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

    /** 日期行：点选打开日期选择器（与待办编辑页同一套交互）。 */
    private View dateRow() {
        LinearLayout row = Ui.row(this);
        int ph = Ui.dp(this, 14), pv = Ui.v(this, 11);
        row.setPadding(ph, pv, ph, pv);
        row.setMinimumHeight(Ui.vMin(this, 48));
        row.setBackground(Ui.ripple(this, Ui.surfaceContainer(this), Ui.R_S));
        row.setClickable(true);
        row.setFocusable(true);

        TextView tv = Ui.text(this, Dates.shortDate(Ui.nz(state.date)),
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

    /** 对话框里的字段标签。 */
    private void formLabel(LinearLayout body, String text) {
        TextView lb = Ui.text(this, text, Ui.T_LABEL,
                Ui.onSurfaceVariant(this), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.v(this, 8);
        lp.bottomMargin = Ui.v(this, 6);
        lb.setLayoutParams(lp);
        body.addView(lb);
    }
}
