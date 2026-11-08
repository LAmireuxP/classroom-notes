package com.lamireuxp.classroom;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 拼豆图纸编辑器 —— 画布 + 工具 + 色板 + 统计 + 对比 + 图层。
 *
 * 自动保存约定：onPause 落库（编辑器随时可能被系统杀掉，手动保存按钮是反模式）。
 * 撤销栈压的是「像素快照」（含 w/h/pages，改尺寸也能撤销），上限 25 张——
 * 256×256×8 页的图纸一张快照约 512 KB，25 张封顶 13 MB，是可以接受的内存上限。
 * 色板操作（换品牌/合并/清理）走同一套快照：进栈一次，可整体撤销。
 */
public class BeadEditorActivity extends BaseActivity implements Dialogs.DialogHost {

    private static final int REQ_PICK_REF = 101;
    private static final int REQ_EXPORT_PNG = 102;
    private static final int REQ_EXPORT_PDF = 103;
    private static final int REQ_EXPORT_BPAT = 104;

    /** 撤销栈上限：见类注释的内存账。 */
    private static final int UNDO_MAX = 25;

    private Db db;
    private Db.BeadPattern pat;
    private int page;
    private int colorIndex;
    private int tool = BeadCanvasView.TOOL_BRUSH;

    private final List<AlertDialog> openSheets = new ArrayList<AlertDialog>();
    private final List<Mem> undo = new ArrayList<Mem>();
    private final List<Mem> redo = new ArrayList<Mem>();
    /** Dialogs.form 挂上来的对话框：返回键先关它（DialogHost 约定）。 */
    private AlertDialog submitDialog;

    private BeadCanvasView canvas;
    private TextView titleTv, metaTv, pageChip, toolCountTv;
    private LinearLayout undoBtn, redoBtn;
    /** 底栏工具按钮，下标 = 工具号；selectTool 时改配色。 */
    private final LinearLayout[] toolBtns = new LinearLayout[5];
    private LinearLayout swatch;
    /** 参考图缓存（对比模式），懒加载。 */
    private Bitmap refBitmap;
    /** 待导出格式（onActivityResult 里区分），空 = 没有进行中的导出。 */
    private String pendingExport;

    @Override
    public void setSubmitDialog(AlertDialog dlg) { this.submitDialog = dlg; }

    /** 像素快照。w/h/pages 一起存：改尺寸/图层操作也能整体撤销。 */
    private static final class Mem {
        final byte[] px;
        final int w, h, pages;
        Mem(Db.BeadPattern p) {
            px = p.pixels.clone();
            w = p.w;
            h = p.h;
            pages = p.pages;
        }
    }

    @Override
    protected void onCreateUi(Bundle b) {
        db = Db.get(this);
        BeadPalettes.load(this);
        String id = getIntent().getStringExtra("patternId");
        pat = id == null ? null : db.beadPattern(id);
        if (pat == null) {
            // 图纸不存在（被删/传错 id）：直接退回图纸库，不白屏
            finish();
            return;
        }
        // 全空配色表的图纸画不了笔：补一个白色当起手色
        if (pat.colors.isEmpty()) {
            BeadPalettes.Color white = BeadPalettes.nearest("mard", 0xFFFFFF);
            pat.colors.add(white == null ? "universal:W-1" : white.brand + ":" + white.code);
        }
        buildUi();
    }

    @Override
    protected void onRebuildUi() {
        dismissSheets();
        buildUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pat != null) db.saveBeadPattern(pat);
    }

    // ================== 界面 ==================

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.surface(this));

        LinearLayout col = Ui.column(this);
        col.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(col);

        // ---------- 顶栏：返回 / 标题 / 菜单 ----------
        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 8), Ui.v(this, 6), Ui.dp(this, 8), Ui.v(this, 6));

        LinearLayout back = Icons.iconButton(this, R.drawable.ic_back, 44, Ui.onSurface(this));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        LinearLayout mid = Ui.column(this);
        LinearLayout.LayoutParams mp = Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = Ui.dp(this, 4);
        mid.setLayoutParams(mp);
        mid.setClickable(true);
        mid.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { renameDialog(); }
        });
        titleTv = Ui.text(this, pat.title == null || pat.title.length() == 0
                ? "未命名图纸" : pat.title, Ui.T_TITLE, Ui.onSurface(this), true);
        titleTv.setMaxLines(1);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(titleTv);
        metaTv = Ui.text(this, meta(), Ui.T_LABEL, Ui.onSurfaceVariant(this), false);
        mid.addView(metaTv);
        bar.addView(mid);

        LinearLayout menu = Icons.iconButtonFilled(this, R.drawable.ic_menu, 44,
                Ui.surfaceHighest(this), Ui.onSurfaceVariant(this));
        menu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { menuSheet(); }
        });
        bar.addView(menu);
        col.addView(bar);
        col.addView(Ui.topBarHairline(this));

        // ---------- 画布区 ----------
        FrameLayout stage = new FrameLayout(this);
        stage.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        canvas = new BeadCanvasView(this, new BeadCanvasView.Listener() {
            @Override public void onStrokeBegin() { pushUndo(); }
            @Override public void onPatternChanged() { }
            @Override public void onPick(int idx) {
                if (idx < 0) {
                    Tip.error(BeadEditorActivity.this, "这里是空格");
                } else {
                    colorIndex = idx;
                    syncCanvasColors();
                }
            }
        });
        canvas.setPattern(pat, page);
        stage.addView(canvas, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 页指示角标：多页图纸才有存在意义
        pageChip = Ui.text(this, "", Ui.T_LABEL, Ui.onSurface(this), true);
        pageChip.setBackground(Ui.ripple(this, Ui.surfaceHighest(this), Ui.R_FULL));
        pageChip.setPadding(Ui.dp(this, 12), Ui.v(this, 6), Ui.dp(this, 12), Ui.v(this, 6));
        pageChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pagesSheet(); }
        });
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.BOTTOM | Gravity.START;
        pp.leftMargin = Ui.dp(this, 12);
        pp.bottomMargin = Ui.dp(this, 12);
        pageChip.setLayoutParams(pp);
        stage.addView(pageChip);
        col.addView(stage);

        // ---------- 底栏工具 ----------
        col.addView(Ui.topBarHairline(this));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = Ui.row(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(Ui.dp(this, 6), Ui.v(this, 4), Ui.dp(this, 6), Ui.v(this, 4));
        hs.addView(tools);

        undoBtn = roundBtn(R.drawable.ic_undo, "撤销");
        undoBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doUndo(); }
        });
        tools.addView(undoBtn);
        redoBtn = roundBtn(R.drawable.ic_redo, "重做");
        redoBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doRedo(); }
        });
        tools.addView(redoBtn);
        tools.addView(dividerV());

        int[] toolIcons = {R.drawable.ic_brush, R.drawable.ic_eraser, R.drawable.ic_pipette,
                R.drawable.ic_bucket, R.drawable.ic_pan};
        String[] toolDescs = {"画笔", "橡皮", "吸色", "填充", "平移"};
        for (int i = 0; i < toolBtns.length; i++) {
            final int t = i;
            toolBtns[i] = roundBtn(toolIcons[i], toolDescs[i]);
            toolBtns[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTool(t); }
            });
            tools.addView(toolBtns[i]);
        }
        tools.addView(dividerV());

        swatch = roundBtn(0, "当前颜色");
        swatch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { paletteSheet(); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                Ui.dp(this, 36), Ui.dp(this, 36));
        sp.leftMargin = Ui.dp(this, 6);
        sp.gravity = Gravity.CENTER_VERTICAL;
        swatch.setLayoutParams(sp);
        tools.addView(swatch);
        toolCountTv = Ui.text(this, "", Ui.T_CAPTION, Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = Ui.dp(this, 6);
        cp.gravity = Gravity.CENTER_VERTICAL;
        toolCountTv.setLayoutParams(cp);
        tools.addView(toolCountTv);

        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.vMin(this, 56));
        hs.setLayoutParams(hp);
        col.addView(hs);

        setContentView(root);
        selectTool(tool);
        syncCanvasColors();
        refreshMeta();
    }

    /** 顶栏 meta 行：尺寸 · 层数 · 用色数 · 专辑。 */
    private String meta() {
        StringBuilder sb = new StringBuilder();
        sb.append(pat.w).append("×").append(pat.h);
        if (pat.pages > 1) sb.append(" · ").append(pat.pages).append(" 层");
        sb.append(" · ").append(pat.colors.size()).append(" 色");
        if (pat.album != null && pat.album.length() > 0) sb.append(" · ").append(pat.album);
        return sb.toString();
    }

    private void refreshMeta() {
        if (titleTv != null) {
            titleTv.setText(pat.title == null || pat.title.length() == 0 ? "未命名图纸" : pat.title);
            metaTv.setText(meta());
        }
        if (pageChip != null) {
            pageChip.setVisibility(pat.pages > 1 ? View.VISIBLE : View.GONE);
            pageChip.setText("第 " + (page + 1) + "/" + pat.pages + " 层");
        }
        if (swatch != null) {
            int rgb = currentColorRgb();
            swatch.setBackground(Ui.round(this, rgb, 0xFF000000, Ui.dp(this, 2), Ui.dp(this, 44)));
        }
        if (toolCountTv != null) {
            toolCountTv.setText(colorIndex >= 0 && colorIndex < pat.colors.size()
                    ? BeadPalettes.labelOf(pat.colors.get(colorIndex)) : "");
        }
    }

    private int currentColorRgb() {
        if (colorIndex < 0 || colorIndex >= pat.colors.size()) return 0xFF888888;
        return BeadPalettes.rgbOf(pat.colors.get(colorIndex));
    }

    /** 把配色表同步给画布（RGB 数组 + 短色号数组，画布内标注用）。 */
    private void syncCanvasColors() {
        int[] rgbs = new int[pat.colors.size()];
        String[] codes = new String[pat.colors.size()];
        for (int i = 0; i < pat.colors.size(); i++) {
            rgbs[i] = BeadPalettes.rgbOf(pat.colors.get(i));
            String bc = pat.colors.get(i);
            int k = bc.indexOf(':');
            codes[i] = k >= 0 ? bc.substring(k + 1) : bc;
        }
        if (colorIndex >= pat.colors.size()) colorIndex = pat.colors.size() - 1;
        if (colorIndex < 0) colorIndex = 0;
        canvas.setColor(colorIndex, rgbs, codes);
        refreshMeta();
    }

    // ================== 底栏小件 ==================

    /** 圆形工具按钮（44dp）。iconRes=0 时当色块用（背景由 refreshMeta 画）。 */
    private LinearLayout roundBtn(int iconRes, String desc) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        b.setBackground(Ui.rippleBorderless(this, Ui.R_FULL));
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(desc);
        if (iconRes != 0) b.addView(Icons.icon(this, iconRes, Ui.onSurfaceVariant(this), 22));
        return b;
    }

    private View dividerV() {
        View d = new View(this);
        d.setBackgroundColor(Ui.outlineVariant(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, 1), Ui.dp(this, 22));
        lp.leftMargin = Ui.dp(this, 4);
        lp.rightMargin = Ui.dp(this, 4);
        d.setLayoutParams(lp);
        return d;
    }

    private void selectTool(int t) {
        tool = t;
        canvas.setTool(t);
        for (int i = 0; i < toolBtns.length; i++) {
            boolean active = i == t;
            LinearLayout btn = toolBtns[i];
            btn.setBackground(active
                    ? Ui.ripple(this, Ui.secondaryContainer(this), Ui.R_FULL)
                    : Ui.rippleBorderless(this, Ui.R_FULL));
            // 激活态换色：把第一个子 View（图标）重新染色
            if (btn.getChildCount() > 0) {
                ((android.widget.ImageView) btn.getChildAt(0)).setColorFilter(active
                        ? Ui.tone(this, "on_secondary_container")
                        : Ui.onSurfaceVariant(this), android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
        // 画笔在空表上落笔会是空画：切到画笔时保底补色（见 onCreateUi 注释）
        if (t == BeadCanvasView.TOOL_BRUSH && pat.colors.isEmpty()) {
            BeadPalettes.Color white = BeadPalettes.nearest("mard", 0xFFFFFF);
            pat.colors.add(white == null ? "universal:W-1" : white.brand + ":" + white.code);
            colorIndex = 0;
            syncCanvasColors();
        }
    }

    // ================== 撤销 / 重做 ==================

    private void pushUndo() {
        undo.add(new Mem(pat));
        if (undo.size() > UNDO_MAX) undo.remove(0);
        redo.clear();
        updateUndoUi();
    }

    private void doUndo() {
        if (undo.isEmpty()) return;
        redo.add(new Mem(pat));
        restore(undo.remove(undo.size() - 1));
        updateUndoUi();
    }

    private void doRedo() {
        if (redo.isEmpty()) return;
        undo.add(new Mem(pat));
        restore(redo.remove(redo.size() - 1));
        updateUndoUi();
    }

    private void restore(Mem m) {
        boolean dimsChanged = m.w != pat.w || m.h != pat.h || m.pages != pat.pages;
        pat.pixels = m.px.clone();
        pat.w = m.w;
        pat.h = m.h;
        pat.pages = m.pages;
        if (page >= pat.pages) page = pat.pages - 1;
        if (dimsChanged) {
            canvas.setPattern(pat, page);
        } else {
            canvas.invalidate();
        }
        refreshMeta();
    }

    private void updateUndoUi() {
        setEnabled(undoBtn, !undo.isEmpty());
        setEnabled(redoBtn, !redo.isEmpty());
    }

    private void setEnabled(LinearLayout b, boolean on) {
        b.setAlpha(on ? 1f : 0.35f);
        b.setEnabled(on);
        if (b.getChildCount() > 0) {
            ((android.widget.ImageView) b.getChildAt(0)).setColorFilter(
                    on ? Ui.onSurface(this) : Ui.onSurfaceVariant(this),
                    android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    // ================== 菜单与批量操作 ==================

    private void menuSheet() {
        LinearLayout box = Ui.column(this);
        box.setPadding(0, Ui.dp(this, 4), 0, 0);
        box.addView(sheetRow(R.drawable.ic_edit, "重命名 / 专辑", new Runnable() {
            @Override public void run() { renameDialog(); }
        }));
        box.addView(sheetRow(R.drawable.ic_layers, "图层（立体图纸）", new Runnable() {
            @Override public void run() { pagesSheet(); }
        }));
        box.addView(sheetRow(R.drawable.ic_image, "参考图对比", new Runnable() {
            @Override public void run() { compareSheet(); }
        }));
        box.addView(sheetRow(R.drawable.ic_stats, "色号用量统计", new Runnable() {
            @Override public void run() { statsSheet(); }
        }));
        box.addView(sheetRow(R.drawable.ic_bead, "改图纸尺寸", new Runnable() {
            @Override public void run() { resizeDialog(); }
        }));
        box.addView(sheetRow(R.drawable.ic_palette, "整包换品牌", new Runnable() {
            @Override public void run() { remapSheet(); }
        }));
        box.addView(sheetRow(R.drawable.ic_bucket, "合并相近色", new Runnable() {
            @Override public void run() { mergeDialog(); }
        }));
        box.addView(sheetRow(R.drawable.ic_list, "清理未用色", new Runnable() {
            @Override public void run() { cleanupUnused(); }
        }));
        box.addView(sheetRow(R.drawable.ic_export, "导出 PNG", new Runnable() {
            @Override public void run() { export("png"); }
        }));
        box.addView(sheetRow(R.drawable.ic_export, "导出 PDF（含清单）", new Runnable() {
            @Override public void run() { export("pdf"); }
        }));
        box.addView(sheetRow(R.drawable.ic_share, "导出图纸文件（.bpat）", new Runnable() {
            @Override public void run() { export("bpat"); }
        }));
        box.addView(sheetRow(R.drawable.ic_trash, "删除图纸", Ui.error(this), new Runnable() {
            @Override public void run() { confirmDelete(); }
        }));
        showSheet("图纸操作", box);
    }

    private void renameDialog() {
        LinearLayout body = Ui.column(this);
        int padH = Ui.dp(this, 24);
        body.setPadding(padH, 0, padH, 0);
        final EditText title = Ui.input(this, "图纸名");
        title.setText(pat.title);
        body.addView(title);
        final EditText album = Ui.input(this, "专辑（留空 = 不归类）");
        album.setText(pat.album);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Ui.v(this, 12);
        album.setLayoutParams(ap);
        body.addView(album);

        Dialogs.form(this, "重命名", body, "保存", new Dialogs.Saver() {
            @Override public boolean save() {
                pat.title = title.getText().toString().trim();
                pat.album = album.getText().toString().trim();
                db.saveBeadPattern(pat);
                refreshMeta();
                Tip.success(BeadEditorActivity.this, "已保存");
                return true;
            }
        });
    }

    /**
     * 改尺寸：内容从左上角保留，多出的格子补空格。拉伸变形不在编辑器里做——
     * 「按比例重采样」是图片转图纸（BeadConvert）的职责，手改尺寸应该是无损的。
     */
    private void resizeDialog() {
        LinearLayout body = Ui.column(this);
        int padH = Ui.dp(this, 24);
        body.setPadding(padH, 0, padH, 0);
        final EditText we = Ui.input(this, "宽（16 ~ 256）");
        we.setText(String.valueOf(pat.w));
        body.addView(we);
        final EditText he = Ui.input(this, "高（16 ~ 256）");
        he.setText(String.valueOf(pat.h));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.v(this, 12);
        he.setLayoutParams(hp);
        body.addView(he);
        TextView hint = Ui.text(this,
                "现有图案保留在左上角，多出的格子为空格。", Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.v(this, 10);
        hint.setLayoutParams(tp);
        body.addView(hint);

        Dialogs.form(this, "改图纸尺寸", body, "应用", new Dialogs.Saver() {
            @Override public boolean save() {
                int nw, nh;
                try {
                    nw = Integer.parseInt(we.getText().toString().trim());
                    nh = Integer.parseInt(he.getText().toString().trim());
                } catch (NumberFormatException e) {
                    Tip.error(BeadEditorActivity.this, "请输入数字");
                    return false;
                }
                if (nw < BeadConvert.MIN_SIZE || nw > BeadConvert.MAX_SIZE
                        || nh < BeadConvert.MIN_SIZE || nh > BeadConvert.MAX_SIZE) {
                    Tip.error(BeadEditorActivity.this, "尺寸须在 16 ~ 256 之间");
                    return false;
                }
                resizeTo(nw, nh);
                return true;
            }
        });
    }

    private void resizeTo(int nw, int nh) {
        pushUndo();
        byte[] old = pat.pixels;
        int pages = pat.pages;
        byte[] next = new byte[nw * nh * pages];
        java.util.Arrays.fill(next, (byte) 255);
        int copyW = Math.min(pat.w, nw), copyH = Math.min(pat.h, nh);
        for (int pg = 0; pg < pages; pg++) {
            for (int y = 0; y < copyH; y++) {
                System.arraycopy(old, pg * pat.w * pat.h + y * pat.w,
                        next, pg * nw * nh + y * nw, copyW);
            }
        }
        pat.w = nw;
        pat.h = nh;
        pat.pixels = next;
        canvas.setPattern(pat, page);
        refreshMeta();
        Tip.success(this, "已改为 " + nw + "×" + nh);
    }

    /** 整包换品牌：每个现有色号按 RGB 在目标品牌里找最近色，去重后重排像素下标。 */
    private void remapSheet() {
        LinearLayout box = Ui.column(this);
        box.setPadding(0, Ui.dp(this, 4), 0, 0);
        TextView hint = Ui.text(this,
                "把当前配色表整体映射到另一个品牌（按色差最近的实物色号）。", Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.leftMargin = Ui.dp(this, 20);
        hp.rightMargin = Ui.dp(this, 20);
        hp.bottomMargin = Ui.v(this, 8);
        hint.setLayoutParams(hp);
        box.addView(hint);
        String[] ids = BeadPalettes.brandIds();
        for (final String id : ids) {
            box.addView(sheetRow(0, BeadPalettes.brandName(id), new Runnable() {
                @Override public void run() { remapTo(id); }
            }));
        }
        showSheet("换到哪个品牌？", box);
    }

    private void remapTo(String brand) {
        if (pat.colors.isEmpty()) return;
        pushUndo();
        // 旧下标 → 新 brand:code；同一物理色在新表里只留一份
        List<String> next = new ArrayList<String>();
        java.util.Map<Integer, Integer> map = new java.util.HashMap<Integer, Integer>();
        for (int i = 0; i < pat.colors.size(); i++) {
            BeadPalettes.Color c = BeadPalettes.nearest(brand,
                    BeadPalettes.rgbOf(pat.colors.get(i)));
            String target = c == null ? pat.colors.get(i) : c.brand + ":" + c.code;
            int at = next.indexOf(target);
            if (at < 0) {
                at = next.size();
                next.add(target);
            }
            map.put(i, at);
        }
        for (int i = 0; i < pat.pixels.length; i++) {
            int v = pat.pixels[i] & 0xFF;
            pat.pixels[i] = v == BeadCanvasView.EMPTY
                    ? (byte) 255 : (byte) (int) map.get(v);
        }
        pat.colors = next;
        colorIndex = 0;
        syncCanvasColors();
        canvas.invalidate();
        Tip.success(this, "已换到 " + BeadPalettes.brandName(brand));
    }

    /** 合并相近色：ΔE2000 阈值滑条（2~30）。 */
    private void mergeDialog() {
        LinearLayout body = Ui.column(this);
        int padH = Ui.dp(this, 24);
        body.setPadding(padH, 0, padH, 0);
        final TextView val = Ui.text(this, "", Ui.T_BODY, Ui.onSurface(this), true);
        body.addView(val);
        final SeekBar bar = new SeekBar(this);
        bar.setMax(28);                       // 显示值 = 进度 + 2
        bar.setProgress(6);                   // 默认 ΔE 8：肉眼难分的近似色
        val.setText("色差阈值 ΔE " + (bar.getProgress() + 2)
                + "（当前 " + pat.colors.size() + " 色）");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText("色差阈值 ΔE " + (p + 2) + "（当前 " + pat.colors.size() + " 色）");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        body.addView(bar);
        TextView hint = Ui.text(this,
                "把色差小于阈值的颜色合并成先出现的那一种，豆子种类变少、采购更省。", Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.v(this, 10);
        hint.setLayoutParams(hp);
        body.addView(hint);

        Dialogs.form(this, "合并相近色", body, "合并", new Dialogs.Saver() {
            @Override public boolean save() {
                mergeColors(bar.getProgress() + 2);
                return true;
            }
        });
    }

    private void mergeColors(float threshold) {
        if (pat.colors.size() < 2) return;
        pushUndo();
        int n = pat.colors.size();
        // greedy：每个色向后找 ΔE 小于阈值的伙伴并吞并；被吞并的不再当主色
        int[] map = new int[n];
        boolean[] merged = new boolean[n];
        for (int i = 0; i < n; i++) map[i] = i;
        for (int i = 0; i < n; i++) {
            if (merged[i]) continue;
            for (int j = i + 1; j < n; j++) {
                if (merged[j]) continue;
                if (BeadPalettes.deltaE(pat.colors.get(i), pat.colors.get(j)) <= threshold) {
                    map[j] = i;
                    merged[j] = true;
                }
            }
        }
        // 压缩映射（map 可能指向已合并的主色，主色一定未合并）
        List<String> next = new ArrayList<String>();
        int[] remap = new int[n];
        java.util.Map<Integer, Integer> keep = new java.util.HashMap<Integer, Integer>();
        for (int i = 0; i < n; i++) {
            if (!merged[i]) {
                remap[i] = next.size();
                next.add(pat.colors.get(i));
                keep.put(i, remap[i]);
            }
        }
        for (int i = 0; i < n; i++) {
            if (merged[i]) remap[i] = keep.get(map[i]);
        }
        for (int i = 0; i < pat.pixels.length; i++) {
            int v = pat.pixels[i] & 0xFF;
            if (v != BeadCanvasView.EMPTY) pat.pixels[i] = (byte) remap[v];
        }
        pat.colors = next;
        if (colorIndex >= next.size()) colorIndex = 0;
        syncCanvasColors();
        canvas.invalidate();
        Tip.success(this, "已合并为 " + next.size() + " 色");
    }

    /** 清理未用色：所有页都没有引用的色号从配色表移除，下标整体前移。 */
    private void cleanupUnused() {
        int[] use = BeadExport.countUsage(pat, -1, pat.colors.size());
        int deadCount = 0;
        for (int u : use) if (u == 0) deadCount++;
        // 匿名类捕获要求 effectively final：循环里计数的变量单独拷一份给回调用
        final int dead = deadCount;
        if (dead == 0) {
            Tip.success(this, "没有未使用的颜色");
            return;
        }
        Dialogs.confirm(this, "清理未用色",
                "有 " + dead + " 种颜色没有在任何格子里使用，移除它们？", "清理",
                new Runnable() {
                    @Override public void run() {
                        pushUndo();
                        int[] use2 = BeadExport.countUsage(pat, -1, pat.colors.size());
                        List<String> next = new ArrayList<String>();
                        int[] remap = new int[pat.colors.size()];
                        for (int i = 0; i < pat.colors.size(); i++) {
                            if (use2[i] > 0) {
                                remap[i] = next.size();
                                next.add(pat.colors.get(i));
                            } else {
                                remap[i] = -1;
                            }
                        }
                        for (int i = 0; i < pat.pixels.length; i++) {
                            int v = pat.pixels[i] & 0xFF;
                            if (v != BeadCanvasView.EMPTY) pat.pixels[i] = (byte) remap[v];
                        }
                        pat.colors = next;
                        colorIndex = 0;
                        syncCanvasColors();
                        canvas.invalidate();
                        Tip.success(BeadEditorActivity.this, "已移除 " + dead + " 色");
                    }
                });
    }

    private void confirmDelete() {
        Dialogs.confirm(this, "删除图纸",
                "确定删除「" + (pat.title == null || pat.title.length() == 0
                        ? "未命名图纸" : pat.title) + "」？删除后无法恢复。",
                "删除", new Runnable() {
                    @Override public void run() {
                        if (pat.ref != null && pat.ref.length() > 0) {
                            new File(pat.ref).delete();
                        }
                        db.deleteBeadPattern(pat.id);
                        finish();
                    }
                });
    }

    // ================== 色板抽屉 ==================

    /** 当前配色表 + 添加入口。长按某个色 → 设为当前 / 删除该色。 */
    private void paletteSheet() {
        LinearLayout box = Ui.column(this);
        int padH = Ui.dp(this, 16);
        box.setPadding(padH, Ui.dp(this, 4), padH, 0);

        TextView add = Ui.textButton(this, "＋ 添加颜色");
        add.setGravity(Gravity.CENTER);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addColorSheet(null); }
        });
        box.addView(add);

        box.addView(colorGrid(pat.colors, true));
        TextView hint = Ui.text(this,
                "点按选中 · 长按管理", Ui.T_CAPTION, Ui.onSurfaceVariant(this), false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.v(this, 8);
        hint.setLayoutParams(hp);
        box.addView(hint);
        showSheet("配色表（" + pat.colors.size() + " 色）", box);
    }

    /** 色块网格。manage=true 时长按出管理菜单。 */
    private View colorGrid(final List<String> colors, final boolean manage) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(8);
        g.setUseDefaultMargins(false);
        int pad = Ui.dp(this, 3);
        for (int i = 0; i < colors.size(); i++) {
            final int idx = i;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Ui.dp(this, 38);
            lp.height = Ui.dp(this, 38);
            lp.setMargins(pad, pad, pad, pad);
            View sw = new View(this);
            sw.setLayoutParams(lp);
            sw.setBackground(Ui.round(this, BeadPalettes.rgbOf(colors.get(i)),
                    Color.TRANSPARENT, Ui.dp(this, 1), Ui.dp(this, 38)));
            if (idx == colorIndex && manage) {
                // 当前色描一圈主色边：比对得起色板下标
                sw.setBackground(Ui.round(this, BeadPalettes.rgbOf(colors.get(i)),
                        Ui.primary(this), Ui.dp(this, 3), Ui.dp(this, 38)));
            }
            sw.setClickable(true);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (manage) {
                        colorIndex = idx;
                        syncCanvasColors();
                        dismissSheets();
                    } else {
                        pickBrandColor(colors.get(idx));
                    }
                }
            });
            if (manage) {
                sw.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        colorActions(idx);
                        return true;
                    }
                });
            }
            g.addView(sw);
        }
        return g;
    }

    private void colorActions(final int idx) {
        LinearLayout box = Ui.column(this);
        box.addView(sheetRow(R.drawable.ic_check, "设为当前色", new Runnable() {
            @Override public void run() {
                colorIndex = idx;
                syncCanvasColors();
            }
        }));
        box.addView(sheetRow(R.drawable.ic_trash, "删除该色（引用处清空）", Ui.error(this),
                new Runnable() {
                    @Override public void run() { removeColor(idx); }
                }));
        showSheet(BeadPalettes.labelOf(pat.colors.get(idx)), box);
    }

    private void removeColor(int idx) {
        pushUndo();
        // 引用该色的格子清成空格——删色是明确的破坏性操作，语义比「顶替成邻色」可预测
        for (int i = 0; i < pat.pixels.length; i++) {
            if ((pat.pixels[i] & 0xFF) == idx) pat.pixels[i] = (byte) 255;
        }
        pat.colors.remove(idx);
        // 删的是当前色前面的色，下标整体前移一位
        if (colorIndex >= pat.colors.size()) colorIndex = Math.max(0, pat.colors.size() - 1);
        syncCanvasColors();
        canvas.invalidate();
        paletteSheet();
    }

    /**
     * 品牌选色。brand=null 显示品牌选择页；选定品牌后铺该品牌全部色号。
     * 点一个色号：已在配色表 → 直接选中；没有 → 追加进配色表再选中。
     */
    private void addColorSheet(final String brand) {
        LinearLayout box = Ui.column(this);
        int padH = Ui.dp(this, 16);
        box.setPadding(padH, Ui.dp(this, 4), padH, 0);
        if (brand == null) {
            String[] ids = BeadPalettes.brandIds();
            for (final String id : ids) {
                final List<BeadPalettes.Color> list = BeadPalettes.brand(id);
                LinearLayout row = sheetRow(0,
                        BeadPalettes.brandName(id) + "（" + list.size() + " 色）", new Runnable() {
                            @Override public void run() { addColorSheet(id); }
                        });
                box.addView(row);
            }
        } else {
            TextView back = Ui.textButton(this, "‹ 换品牌");
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { addColorSheet(null); }
            });
            box.addView(back);
            List<String> all = new ArrayList<String>();
            for (BeadPalettes.Color c : BeadPalettes.brand(brand)) {
                all.add(c.brand + ":" + c.code);
            }
            box.addView(colorGrid(all, false));
        }
        showSheet(brand == null ? "选品牌" : BeadPalettes.brandName(brand), box);
    }

    private void pickBrandColor(String bc) {
        int at = pat.colors.indexOf(bc);
        if (at < 0) {
            pat.colors.add(bc);
            at = pat.colors.size() - 1;
        }
        colorIndex = at;
        syncCanvasColors();
        dismissSheets();
    }

    // ================== 统计抽屉 ==================

    private void statsSheet() {
        LinearLayout box = Ui.column(this);
        int padH = Ui.dp(this, 20);
        box.setPadding(padH, Ui.dp(this, 4), padH, 0);
        int total = BeadExport.totalBeads(pat);
        int[] use = BeadExport.countUsage(pat, -1, pat.colors.size());
        int kinds = 0;
        for (int u : use) if (u > 0) kinds++;
        TextView head = Ui.text(this,
                "共 " + total + " 颗豆 · " + kinds + " 种颜色" + (pat.pages > 1 ? "（全部层）" : ""),
                Ui.T_BODY, Ui.onSurface(this), true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.bottomMargin = Ui.v(this, 8);
        head.setLayoutParams(hp);
        box.addView(head);
        if (total == 0) {
            box.addView(Ui.text(this, "图纸还是空的", Ui.T_BODY,
                    Ui.onSurfaceVariant(this), false));
        }
        for (int i = 0; i < use.length && total > 0; i++) {
            if (use[i] == 0) continue;
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = Ui.v(this, 6);
            row.setLayoutParams(rp);
            View sw = new View(this);
            sw.setBackground(Ui.round(this, BeadPalettes.rgbOf(pat.colors.get(i)),
                    Ui.outlineVariant(this), Ui.dp(this, 1), Ui.dp(this, 20)));
            row.addView(sw, Ui.lp(Ui.dp(this, 20), Ui.dp(this, 20)));
            TextView name = Ui.text(this, BeadPalettes.labelOf(pat.colors.get(i)),
                    Ui.T_BODY, Ui.onSurface(this), false);
            LinearLayout.LayoutParams np = Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            np.leftMargin = Ui.dp(this, 12);
            name.setLayoutParams(np);
            row.addView(name);
            row.addView(Ui.text(this, use[i] + " 颗 · "
                            + Math.round(use[i] * 100f / total) + "%",
                    Ui.T_BODY, Ui.onSurfaceVariant(this), false));
            box.addView(row);
        }
        showSheet("用量统计", box);
    }

    // ================== 参考图对比 ==================

    private void compareSheet() {
        LinearLayout box = Ui.column(this);
        int padH = Ui.dp(this, 20);
        box.setPadding(padH, Ui.dp(this, 4), padH, 0);
        if (pat.ref == null || pat.ref.length() == 0 || !new File(pat.ref).exists()) {
            TextView pick = Ui.textButton(this, "选择参考图");
            pick.setGravity(Gravity.CENTER);
            pick.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickRefImage(); }
            });
            box.addView(pick);
            box.addView(Ui.text(this,
                    "参考图会以半透明方式叠在画布上，照着描图更省事。", Ui.T_LABEL,
                    Ui.onSurfaceVariant(this), false));
        } else {
            final TextView val = Ui.text(this, "", Ui.T_BODY, Ui.onSurface(this), true);
            final int init = 50;     // 打开对比抽屉默认半透明：一眼能同时看到图和豆
            val.setText("叠加透明度 " + init + "%");
            box.addView(val);
            final SeekBar bar = new SeekBar(this);
            bar.setMax(100);
            bar.setProgress(init);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    val.setText("叠加透明度 " + p + "%");
                    applyOverlay(p / 100f);
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            box.addView(bar);
            LinearLayout row = Ui.row(this);
            TextView change = Ui.textButton(this, "更换参考图");
            change.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickRefImage(); }
            });
            TextView hide = Ui.textButton(this, "隐藏叠加");
            hide.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { applyOverlay(0f); }
            });
            row.addView(change);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.leftMargin = Ui.dp(this, 16);
            row.addView(hide, hp);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = Ui.v(this, 10);
            row.setLayoutParams(rp);
            box.addView(row);
            applyOverlay(init / 100f);       // 抽屉开起来的瞬间叠加就生效
        }
        showSheet("参考图对比", box);
    }

    private void applyOverlay(float alpha) {
        if (refBitmap == null && pat.ref != null && pat.ref.length() > 0) {
            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(pat.ref, bo);
            int sample = 1;
            while (bo.outWidth / (sample * 2) >= 512) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            refBitmap = BitmapFactory.decodeFile(pat.ref, o2);
            if (refBitmap != null) {
                refBitmap = BeadConvert.overlayBitmap(refBitmap, pat.w, pat.h);
            }
        }
        canvas.setOverlay(refBitmap, alpha);
    }

    private void pickRefImage() {
        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
        it.setType("image/*");
        it.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(it, "选择参考图"), REQ_PICK_REF);
    }

    /** 参考图落盘：压到长边 ≤1024 的 JPEG，一张图 < 300 KB，导出备份时一并打包。 */
    private void saveRef(Uri uri) throws Exception {
        Bitmap src = BeadConvert.decodeScaled(this, uri, 1024, 1024);
        File dir = new File(getFilesDir(), "bead-ref");
        dir.mkdirs();
        File dest = new File(dir, pat.id + ".jpg");
        FileOutputStream fos = new FileOutputStream(dest);
        try {
            src.compress(Bitmap.CompressFormat.JPEG, 85, fos);
        } finally {
            fos.close();
        }
        if (pat.ref != null && pat.ref.length() > 0 && !pat.ref.equals(dest.getAbsolutePath())) {
            new File(pat.ref).delete();
        }
        pat.ref = dest.getAbsolutePath();
        refBitmap = null;                    // 下次 applyOverlay 重新解码
        db.saveBeadPattern(pat);
    }

    // ================== 图层（立体图纸） ==================

    private void pagesSheet() {
        LinearLayout box = Ui.column(this);
        box.setPadding(0, Ui.dp(this, 4), 0, 0);
        for (int i = 0; i < pat.pages; i++) {
            final int pg = i;
            box.addView(Ui.optionRow(this, "第 " + (i + 1) + " 层",
                    layerDesc(pg), pg == page, new Runnable() {
                        @Override public void run() {
                            page = pg;
                            canvas.setPattern(pat, page);
                            refreshMeta();
                            dismissSheets();
                        }
                    }));
        }
        box.addView(sheetRow(R.drawable.ic_add, "新增空白层", new Runnable() {
            @Override public void run() { addPage(false); }
        }));
        box.addView(sheetRow(R.drawable.ic_copy, "复制当前层", new Runnable() {
            @Override public void run() { addPage(true); }
        }));
        if (pat.pages > 1) {
            box.addView(sheetRow(R.drawable.ic_trash, "删除当前层", Ui.error(this),
                    new Runnable() {
                        @Override public void run() { deletePage(); }
                    }));
        }
        showSheet("图层 · " + pat.pages + " 层", box);
    }

    private String layerDesc(int pg) {
        int base = pg * pat.w * pat.h;
        int n = 0;
        for (int i = base; i < base + pat.w * pat.h; i++) {
            if ((pat.pixels[i] & 0xFF) != BeadCanvasView.EMPTY) n++;
        }
        return n + " 颗豆";
    }

    private void addPage(boolean copy) {
        pushUndo();
        int pw = pat.w * pat.h;
        byte[] next = new byte[pat.pixels.length + pw];
        System.arraycopy(pat.pixels, 0, next, 0, pat.pixels.length);
        if (copy) {
            System.arraycopy(pat.pixels, page * pw, next, pat.pixels.length, pw);
        } else {
            java.util.Arrays.fill(next, pat.pixels.length, next.length, (byte) 255);
        }
        pat.pixels = next;
        pat.pages++;
        page = pat.pages - 1;
        canvas.setPattern(pat, page);
        refreshMeta();
        Tip.success(this, copy ? "已复制为第 " + pat.pages + " 层"
                : "已新增第 " + pat.pages + " 层");
    }

    private void deletePage() {
        if (pat.pages <= 1) return;
        Dialogs.confirm(this, "删除图层",
                "确定删除第 " + (page + 1) + " 层？该层图案会一起删掉。", "删除",
                new Runnable() {
                    @Override public void run() {
                        pushUndo();
                        int pw = pat.w * pat.h;
                        byte[] next = new byte[pat.pixels.length - pw];
                        int w = 0;
                        for (int pg = 0; pg < pat.pages; pg++) {
                            if (pg == page) continue;
                            System.arraycopy(pat.pixels, pg * pw, next, w * pw, pw);
                            w++;
                        }
                        pat.pixels = next;
                        pat.pages--;
                        if (page >= pat.pages) page = pat.pages - 1;
                        canvas.setPattern(pat, page);
                        refreshMeta();
                    }
                });
    }

    // ================== 导出 ==================

    private void export(String kind) {
        pendingExport = kind;
        String base = pat.title == null || pat.title.length() == 0 ? "拼豆图纸" : pat.title;
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        if ("png".equals(kind)) {
            it.setType("image/png");
            it.putExtra(Intent.EXTRA_TITLE, base + ".png");
            startActivityForResult(it, REQ_EXPORT_PNG);
        } else if ("pdf".equals(kind)) {
            it.setType("application/pdf");
            it.putExtra(Intent.EXTRA_TITLE, base + ".pdf");
            startActivityForResult(it, REQ_EXPORT_PDF);
        } else {
            it.setType("application/octet-stream");
            it.putExtra(Intent.EXTRA_TITLE, base + ".bpat");
            startActivityForResult(it, REQ_EXPORT_BPAT);
        }
    }

    /** 导出在后台线程：256×256 的表 + 编码要几百毫秒，主线程会掉帧。 */
    private void runExport(final Uri uri) {
        final String kind = pendingExport;
        pendingExport = null;
        if (uri == null || kind == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if ("png".equals(kind)) {
                        BeadExport.writePng(BeadEditorActivity.this, uri,
                                BeadExport.renderSheet(pat, page));
                    } else if ("pdf".equals(kind)) {
                        BeadExport.writePdf(BeadEditorActivity.this, uri, pat);
                    } else {
                        JSONObject o = new JSONObject();
                        o.put("format", "bpat-1");
                        o.put("title", pat.title);
                        o.put("album", pat.album);
                        o.put("w", pat.w);
                        o.put("h", pat.h);
                        o.put("pages", pat.pages);
                        o.put("colors", BeadPalettes.colorsToJson(pat.colors));
                        o.put("pixels", Base64.encodeToString(pat.pixels, Base64.NO_WRAP));
                        o.put("savedAt", System.currentTimeMillis());
                        Backup.writeText(BeadEditorActivity.this, uri, o.toString(2));
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() { Tip.success(BeadEditorActivity.this, "已导出"); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Tip.error(BeadEditorActivity.this, "导出失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode >= REQ_EXPORT_PNG) pendingExport = null;
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_PICK_REF) {
            try {
                saveRef(uri);
                Tip.success(this, "参考图已加入");
            } catch (Exception e) {
                Tip.error(this, "参考图保存失败：" + e.getMessage());
            }
        } else if (requestCode == REQ_EXPORT_PNG || requestCode == REQ_EXPORT_PDF
                || requestCode == REQ_EXPORT_BPAT) {
            runExport(uri);
        }
    }

    // ================== 抽屉骨架 ==================

    private AlertDialog showSheet(String title, View body) {
        for (int i = openSheets.size() - 1; i >= 0; i--) {
            if (!openSheets.get(i).isShowing()) openSheets.remove(i);
        }
        AlertDialog dlg = Dialogs.sheet(this, title, body);
        openSheets.add(dlg);
        return dlg;
    }

    private void dismissSheets() {
        for (AlertDialog d : openSheets) {
            if (d.isShowing()) d.dismiss();
        }
        openSheets.clear();
    }

    private LinearLayout sheetRow(int iconRes, String label, final Runnable action) {
        return sheetRow(iconRes, label, Ui.onSurface(this), action);
    }

    private LinearLayout sheetRow(int iconRes, String label, int color, final Runnable action) {
        LinearLayout row = Ui.row(this);
        int padH = Ui.dp(this, 20), padV = Ui.v(this, 14);
        row.setPadding(padH, padV, padH, padV);
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, Ui.R_S));
        row.setClickable(true);
        if (iconRes != 0) row.addView(Icons.icon(this, iconRes, color, 20));
        TextView tv = Ui.text(this, label, Ui.T_BODY + 1, color, false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = Ui.dp(this, 16);
        tv.setLayoutParams(tp);
        row.addView(tv);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismissSheets();
                action.run();
            }
        });
        return row;
    }
}
