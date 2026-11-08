package com.lamireuxp.classroom;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 拼豆图纸库 —— 列表 / 搜索 / 收藏 / 专辑 / 复刻 / 导入导出。
 *
 * 缩略图带 LruCache（键 = id:updated）：滑动列表时同一张图纸不重复渲染，
 * 编辑回来 updated 变了自然换新图。缓存 miss 的渲染是同步的——单张最多
 * 6.5 万格、一次 paint 调用 < 10ms，比上异步任务队列再 invalidate 简单可靠。
 */
public class BeadHomeActivity extends BaseActivity implements Dialogs.DialogHost {

    private static final int REQ_IMPORT_BPAT = 301;
    private static final int REQ_EXPORT_PNG = 302;
    private static final int REQ_EXPORT_PDF = 303;
    private static final int REQ_EXPORT_BPAT = 304;

    private Db db;
    private String query = "";
    private boolean favOnly;
    /** null = 全部专辑；"" = 未分类；其他 = 指定专辑。 */
    private String albumFilter;
    private LinearLayout listBox;
    private final List<AlertDialog> openSheets = new ArrayList<AlertDialog>();
    /** Dialogs.form 挂上来的对话框：返回键先关它（DialogHost 约定）。 */
    private AlertDialog submitDialog;
    private final LruCache<String, Bitmap> thumbs =
            new LruCache<String, Bitmap>(80);

    @Override
    public void setSubmitDialog(AlertDialog dlg) { this.submitDialog = dlg; }
    /** 待导出：格式 + 图纸 id（onActivityResult 里按这个继续）。 */
    private String pendingExport, pendingId;

    @Override
    protected void onCreateUi(Bundle b) {
        db = Db.get(this);
        BeadPalettes.load(this);
        buildUi();
    }

    @Override
    protected void onRebuildUi() {
        dismissSheets();
        buildUi();
    }

    @Override
    protected void onResumed() {
        refresh();
    }

    private void buildUi() {
        LinearLayout col = Ui.column(this);
        col.setBackgroundColor(Ui.surface(this));
        col.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------- 顶栏 ----------
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
        mid.addView(Ui.text(this, "拼豆图纸", Ui.T_TITLE, Ui.onSurface(this), true));
        mid.addView(Ui.text(this, db.beadPatternCount() + " 张图纸", Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false));
        bar.addView(mid);
        col.addView(bar);
        col.addView(Ui.topBarHairline(this));

        // ---------- 搜索 + 筛选 ----------
        LinearLayout padBox = Ui.column(this);
        padBox.setPadding(Ui.dp(this, 16), Ui.v(this, 10), Ui.dp(this, 16), 0);
        LinearLayout searchBox = Ui.searchBarWithIcon(this, "搜索图纸名…", R.drawable.ic_search);
        EditText search = Ui.searchInput(searchBox);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                refresh();
            }
        });
        padBox.addView(searchBox);
        padBox.addView(filterChips());
        col.addView(padBox);

        // ---------- 列表 ----------
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setClipToPadding(false);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        listBox = Ui.column(this);
        listBox.setPadding(Ui.dp(this, 16), Ui.v(this, 8), Ui.dp(this, 16), Ui.dp(this, 96));
        sc.addView(listBox);
        col.addView(sc);

        LinearLayout fab = Ui.fab(this, "新建图纸", new Runnable() {
            @Override public void run() { createSheet(); }
        });
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Ui.surface(this));
        root.addView(col, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Ui.placeFab(root, fab);
        setContentView(root);
        refresh();
    }

    /** 收藏 chip + 专辑 chip 两行。 */
    private View filterChips() {
        LinearLayout box = Ui.column(this);
        LinearLayout row1 = Ui.row(this);
        row1.addView(chip("全部", !favOnly, new Runnable() {
            @Override public void run() { favOnly = false; buildUi(); }
        }));
        row1.addView(chip("★ 收藏", favOnly, new Runnable() {
            @Override public void run() { favOnly = true; buildUi(); }
        }));
        box.addView(row1);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row2 = Ui.row(this);
        row2.addView(chip("全部专辑", albumFilter == null, new Runnable() {
            @Override public void run() { albumFilter = null; buildUi(); }
        }));
        row2.addView(chip("未分类", albumFilter != null && albumFilter.length() == 0,
                new Runnable() {
                    @Override public void run() { albumFilter = ""; buildUi(); }
                }));
        for (String a : db.beadAlbums()) {
            final String album = a;
            row2.addView(chip(a, a.equals(albumFilter), new Runnable() {
                @Override public void run() {
                    albumFilter = album;
                    buildUi();
                }
            }));
        }
        row2.addView(chip("管理", false, new Runnable() {
            @Override public void run() { albumManageSheet(); }
        }));
        hs.addView(row2);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.v(this, 8);
        hs.setLayoutParams(hp);
        box.addView(hs);
        return box;
    }

    private TextView chip(String label, boolean active, final Runnable action) {
        TextView t = Ui.text(this, label, Ui.T_LABEL, active
                ? Ui.onPrimary(this) : Ui.onSurface(this), true);
        t.setBackground(active
                ? Ui.ripple(this, Ui.primary(this), Ui.R_FULL)
                : Ui.ripple(this, Ui.surfaceContainer(this), Ui.R_FULL));
        t.setPadding(Ui.dp(this, 12), Ui.v(this, 6), Ui.dp(this, 12), Ui.v(this, 6));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private void refresh() {
        if (listBox == null) return;
        listBox.removeAllViews();
        List<Db.BeadPattern> list = db.beadPatterns(query, albumFilter, favOnly);
        if (list.isEmpty()) {
            listBox.addView(Ui.emptyState(this, R.drawable.ic_bead,
                    query.length() > 0 ? "没有匹配的图纸" : "还没有图纸",
                    "点右下角 + 新建空白、图片转图纸或导入 .bpat 文件"));
            return;
        }
        for (Db.BeadPattern p : list) listBox.addView(patternCard(p));
    }

    private View patternCard(final Db.BeadPattern p) {
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.v(this, 10);
        row.setLayoutParams(rlp);
        int padV = Ui.v(this, 12);
        row.setPadding(Ui.dp(this, 12), padV, Ui.dp(this, 8), padV);
        row.setBackground(Ui.ripple(this, Ui.surfaceContainer(this), Ui.R_M));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Ui.vMin(this, 72));
        Ui.pressScale(row);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openEditor(p.id); }
        });

        // 缩略图
        String key = p.id + ":" + p.updated;
        Bitmap bmp = thumbs.get(key);
        if (bmp == null) {
            bmp = BeadExport.renderThumb(p, 128);
            thumbs.put(key, bmp);
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int ts = Ui.dp(this, 64);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ts, ts);
        tp.rightMargin = Ui.dp(this, 12);
        iv.setLayoutParams(tp);
        iv.setBackground(Ui.round(this, Ui.surfaceLowest(this), 0, 0, Ui.R_S));
        iv.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
        row.addView(iv);

        // 标题 + 元信息
        LinearLayout mid = Ui.column(this);
        mid.setLayoutParams(Ui.lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = Ui.text(this, p.title == null || p.title.length() == 0
                ? "未命名图纸" : p.title, Ui.T_TITLE, Ui.onSurface(this), true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(name);
        StringBuilder meta = new StringBuilder();
        meta.append(p.w).append("×").append(p.h);
        if (p.pages > 1) meta.append(" · ").append(p.pages).append(" 层");
        meta.append(" · ").append(p.colors.size()).append(" 色");
        if (p.album != null && p.album.length() > 0) meta.append(" · ").append(p.album);
        meta.append("\n").append(new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                .format(new Date(p.updated)));
        TextView metaTv = Ui.text(this, meta.toString(), Ui.T_LABEL,
                Ui.onSurfaceVariant(this), false);
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mtp.topMargin = Ui.v(this, 3);
        metaTv.setLayoutParams(mtp);
        mid.addView(metaTv);
        row.addView(mid);

        // 收藏星标
        LinearLayout fav = Icons.iconButton(this, R.drawable.ic_star, 40,
                p.fav ? Ui.primary(this) : Ui.onSurfaceVariant(this));
        fav.setContentDescription(p.fav ? "取消收藏" : "收藏");
        fav.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                db.setBeadFav(p.id, !p.fav);
                refresh();
            }
        });
        row.addView(fav);

        LinearLayout more = Icons.iconButton(this, R.drawable.ic_menu, 40,
                Ui.onSurfaceVariant(this));
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { patternMenu(p.id); }
        });
        row.addView(more);
        return row;
    }

    private void openEditor(String id) {
        Intent it = new Intent(this, BeadEditorActivity.class);
        it.putExtra("patternId", id);
        startActivity(it);
    }

    // ================== 新建入口 ==================

    private void createSheet() {
        LinearLayout box = Ui.column(this);
        box.setPadding(0, Ui.dp(this, 4), 0, 0);
        box.addView(sheetRow(R.drawable.ic_add, "新建空白图纸", new Runnable() {
            @Override public void run() { blankDialog(); }
        }));
        box.addView(sheetRow(R.drawable.ic_bead, "从图片转换", new Runnable() {
            @Override public void run() {
                startActivity(new Intent(BeadHomeActivity.this, BeadImportActivity.class));
            }
        }));
        box.addView(sheetRow(R.drawable.ic_import, "导入图纸文件（.bpat）", new Runnable() {
            @Override public void run() {
                Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                it.setType("*/*");
                it.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(it, REQ_IMPORT_BPAT);
            }
        }));
        showSheet("新建图纸", box);
    }

    private void blankDialog() {
        LinearLayout body = Ui.column(this);
        int padH = Ui.dp(this, 24);
        body.setPadding(padH, 0, padH, 0);
        final EditText title = Ui.input(this, "图纸名");
        title.setText("新图纸");
        body.addView(title);
        final EditText we = Ui.input(this, "宽（16 ~ 256）");
        we.setText("48");
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = Ui.v(this, 12);
        we.setLayoutParams(wp);
        body.addView(we);
        final EditText he = Ui.input(this, "高（16 ~ 256）");
        he.setText("48");
        he.setLayoutParams(wp);
        body.addView(he);

        Dialogs.form(this, "新建空白图纸", body, "创建", new Dialogs.Saver() {
            @Override public boolean save() {
                int w = 48, h = 48;
                try {
                    w = Integer.parseInt(we.getText().toString().trim());
                    h = Integer.parseInt(he.getText().toString().trim());
                } catch (NumberFormatException ignored) { }
                if (w < BeadConvert.MIN_SIZE || w > BeadConvert.MAX_SIZE
                        || h < BeadConvert.MIN_SIZE || h > BeadConvert.MAX_SIZE) {
                    Tip.error(BeadHomeActivity.this, "尺寸须在 16 ~ 256 之间");
                    return false;
                }
                Db.BeadPattern p = BeadConvert.blank(
                        title.getText().toString().trim(), w, h);
                db.saveBeadPattern(p);
                openEditor(p.id);
                return true;
            }
        });
    }

    // ================== 单张图纸菜单 ==================

    private void patternMenu(final String id) {
        final Db.BeadPattern p = db.beadPattern(id);
        if (p == null) return;
        LinearLayout box = Ui.column(this);
        box.setPadding(0, Ui.dp(this, 4), 0, 0);
        box.addView(sheetRow(R.drawable.ic_edit, "编辑", new Runnable() {
            @Override public void run() { openEditor(id); }
        }));
        box.addView(sheetRow(R.drawable.ic_star, p.fav ? "取消收藏" : "收藏", new Runnable() {
            @Override public void run() {
                db.setBeadFav(id, !p.fav);
                refresh();
            }
        }));
        box.addView(sheetRow(R.drawable.ic_copy, "复刻一份", new Runnable() {
            @Override public void run() { duplicate(p); }
        }));
        box.addView(sheetRow(R.drawable.ic_folder, "移动到专辑", new Runnable() {
            @Override public void run() { moveAlbumSheet(p); }
        }));
        box.addView(sheetRow(R.drawable.ic_export, "导出 PNG", new Runnable() {
            @Override public void run() { exportFromHome(id, "png"); }
        }));
        box.addView(sheetRow(R.drawable.ic_export, "导出 PDF", new Runnable() {
            @Override public void run() { exportFromHome(id, "pdf"); }
        }));
        box.addView(sheetRow(R.drawable.ic_share, "导出 .bpat", new Runnable() {
            @Override public void run() { exportFromHome(id, "bpat"); }
        }));
        box.addView(sheetRow(R.drawable.ic_trash, "删除", Ui.error(this), new Runnable() {
            @Override public void run() { confirmDelete(p); }
        }));
        showSheet(p.title == null || p.title.length() == 0 ? "未命名图纸" : p.title, box);
    }

    private void duplicate(Db.BeadPattern src) {
        Db.BeadPattern p = new Db.BeadPattern();
        p.id = Id.gen();
        p.title = (src.title == null || src.title.length() == 0 ? "未命名图纸" : src.title)
                + " 副本";
        p.album = src.album;
        p.w = src.w;
        p.h = src.h;
        p.pages = src.pages;
        p.pixels = src.pixels.clone();
        p.colors = new ArrayList<String>(src.colors);
        p.ref = "";                          // 参考图文件是按 id 命名的，副本不带
        p.fav = false;
        p.created = p.updated = System.currentTimeMillis();
        db.saveBeadPattern(p);
        refresh();
        Tip.success(this, "已复刻");
    }

    private void moveAlbumSheet(final Db.BeadPattern p) {
        LinearLayout box = Ui.column(this);
        box.setPadding(0, Ui.dp(this, 4), 0, 0);
        final List<String> albums = db.beadAlbums();
        for (final String a : albums) {
            box.addView(sheetRow(R.drawable.ic_folder, a, new Runnable() {
                @Override public void run() { setAlbum(p, a); }
            }));
        }
        box.addView(sheetRow(R.drawable.ic_close, "移出专辑（未分类）", new Runnable() {
            @Override public void run() { setAlbum(p, ""); }
        }));
        box.addView(sheetRow(R.drawable.ic_add, "新专辑…", new Runnable() {
            @Override public void run() { newAlbumDialog(p, albums); }
        }));
        showSheet("移动「" + (p.title == null ? "" : p.title) + "」", box);
    }

    private void setAlbum(Db.BeadPattern p, String album) {
        p.album = album;
        db.saveBeadPattern(p);
        refresh();
        Tip.success(this, album.length() == 0 ? "已移出专辑" : "已移入「" + album + "」");
    }

    private void newAlbumDialog(final Db.BeadPattern p, List<String> exist) {
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        final EditText name = Ui.input(this, "专辑名");
        body.addView(name);
        Dialogs.form(this, "新专辑", body, "确定", new Dialogs.Saver() {
            @Override public boolean save() {
                String a = name.getText().toString().trim();
                if (a.length() == 0) {
                    Tip.error(BeadHomeActivity.this, "专辑名不能为空");
                    return false;
                }
                setAlbum(p, a);
                return true;
            }
        });
    }

    /** 专辑管理：重命名 / 清空（批量改所有图纸的 album 字段）。 */
    private void albumManageSheet() {
        LinearLayout box = Ui.column(this);
        box.setPadding(0, Ui.dp(this, 4), 0, 0);
        List<String> albums = db.beadAlbums();
        if (albums.isEmpty()) {
            TextView t = Ui.text(this, "还没有专辑。在图纸菜单里「移动到专辑」即可创建。",
                    Ui.T_BODY, Ui.onSurfaceVariant(this), false);
            int padH = Ui.dp(this, 20);
            t.setPadding(padH, Ui.v(this, 12), padH, Ui.v(this, 12));
            box.addView(t);
        }
        for (final String a : albums) {
            box.addView(sheetRow(R.drawable.ic_edit, "重命名「" + a + "」", new Runnable() {
                @Override public void run() { renameAlbumDialog(a); }
            }));
            box.addView(sheetRow(R.drawable.ic_trash, "删除「" + a + "」（图纸移到未分类）",
                    Ui.error(this), new Runnable() {
                        @Override public void run() {
                            Db.get(BeadHomeActivity.this).exec("UPDATE bead_patterns"
                                            + " SET album='' WHERE album=?",
                                    new Object[]{a});
                            refresh();
                            Tip.success(BeadHomeActivity.this, "专辑已删除");
                        }
                    }));
        }
        showSheet("管理专辑", box);
    }

    private void renameAlbumDialog(final String oldName) {
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        final EditText name = Ui.input(this, "专辑名");
        name.setText(oldName);
        body.addView(name);
        Dialogs.form(this, "重命名专辑", body, "保存", new Dialogs.Saver() {
            @Override public boolean save() {
                String a = name.getText().toString().trim();
                if (a.length() == 0) {
                    Tip.error(BeadHomeActivity.this, "专辑名不能为空");
                    return false;
                }
                Db.get(BeadHomeActivity.this).exec(
                        "UPDATE bead_patterns SET album=? WHERE album=?",
                        new Object[]{a, oldName});
                if (oldName.equals(albumFilter)) albumFilter = a;
                buildUi();
                return true;
            }
        });
    }

    private void confirmDelete(final Db.BeadPattern p) {
        Dialogs.confirm(this, "删除图纸",
                "确定删除「" + (p.title == null || p.title.length() == 0
                        ? "未命名图纸" : p.title) + "」？删除后无法恢复。",
                "删除", new Runnable() {
                    @Override public void run() {
                        if (p.ref != null && p.ref.length() > 0) new File(p.ref).delete();
                        db.deleteBeadPattern(p.id);
                        refresh();
                        Tip.success(BeadHomeActivity.this, "图纸已删除");
                    }
                });
    }

    // ================== 导入导出 ==================

    private void exportFromHome(String id, String kind) {
        pendingExport = kind;
        pendingId = id;
        Db.BeadPattern p = db.beadPattern(id);
        String base = p == null || p.title == null || p.title.length() == 0
                ? "拼豆图纸" : p.title;
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

    /** 图纸文件（.bpat）导入：解析 + 校验 + 落库，任何一步失败都不写库。 */
    private void importBpat(Uri uri) {
        try {
            JSONObject o = new JSONObject(Backup.readText(this, uri));
            String format = o.optString("format", "");
            if (!"bpat-1".equals(format)) throw new Exception("不是图纸文件（.bpat）");
            int w = o.optInt("w", 0), h = o.optInt("h", 0), pages = Math.max(1, o.optInt("pages", 1));
            if (w < BeadConvert.MIN_SIZE || w > BeadConvert.MAX_SIZE
                    || h < BeadConvert.MIN_SIZE || h > BeadConvert.MAX_SIZE) {
                throw new Exception("图纸尺寸不合法：" + w + "×" + h);
            }
            byte[] pixels = Base64.decode(o.optString("pixels", ""), Base64.DEFAULT);
            List<String> colors = BeadPalettes.colorsFromJson(o.optString("colors", "[]"));
            if (colors.isEmpty()) throw new Exception("图纸没有配色表");
            Db.BeadPattern p = new Db.BeadPattern();
            p.id = Id.gen();
            p.title = o.optString("title", "导入图纸");
            p.album = "";
            p.w = w;
            p.h = h;
            p.pages = pages;
            int need = w * h * pages;
            if (pixels.length != need) throw new Exception("像素数据长度不符");
            // 下标越界的格子清成空格：手工编辑过的文件可能有脏数据
            for (int i = 0; i < pixels.length; i++) {
                int v = pixels[i] & 0xFF;
                if (v != BeadCanvasView.EMPTY && v >= colors.size()) pixels[i] = (byte) 255;
            }
            p.pixels = pixels;
            p.colors = colors;
            p.created = p.updated = System.currentTimeMillis();
            db.saveBeadPattern(p);
            refresh();
            Tip.success(this, "已导入「" + p.title + "」");
        } catch (Exception e) {
            Tip.error(this, "导入失败：" + e.getMessage());
        }
    }

    private void runHomeExport(final Uri uri) {
        final String kind = pendingExport;
        final String id = pendingId;
        pendingExport = null;
        pendingId = null;
        if (uri == null || kind == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Db.BeadPattern p = db.beadPattern(id);
                    if (p == null) throw new Exception("图纸不存在");
                    if ("png".equals(kind)) {
                        BeadExport.writePng(BeadHomeActivity.this, uri,
                                BeadExport.renderSheet(p, 0));
                    } else if ("pdf".equals(kind)) {
                        BeadExport.writePdf(BeadHomeActivity.this, uri, p);
                    } else {
                        JSONObject o = new JSONObject();
                        o.put("format", "bpat-1");
                        o.put("title", p.title);
                        o.put("album", p.album);
                        o.put("w", p.w);
                        o.put("h", p.h);
                        o.put("pages", p.pages);
                        o.put("colors", BeadPalettes.colorsToJson(p.colors));
                        o.put("pixels", Base64.encodeToString(p.pixels, Base64.NO_WRAP));
                        o.put("savedAt", System.currentTimeMillis());
                        Backup.writeText(BeadHomeActivity.this, uri, o.toString(2));
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() { Tip.success(BeadHomeActivity.this, "已导出"); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Tip.error(BeadHomeActivity.this, "导出失败：" + e.getMessage());
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
            if (requestCode >= REQ_EXPORT_PNG) {
                pendingExport = null;
                pendingId = null;
            }
            return;
        }
        if (requestCode == REQ_IMPORT_BPAT) {
            importBpat(data.getData());
        } else if (requestCode == REQ_EXPORT_PNG || requestCode == REQ_EXPORT_PDF
                || requestCode == REQ_EXPORT_BPAT) {
            runHomeExport(data.getData());
        }
    }

    // ================== 抽屉骨架（与编辑器同一套） ==================

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
