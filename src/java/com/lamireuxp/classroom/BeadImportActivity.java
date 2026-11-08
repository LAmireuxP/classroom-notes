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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 图片 → 拼豆图纸的参数页。进页面就拉起系统选图器（这是这个页面存在的唯一理由），
 * 选完图铺参数面板：尺寸 / 色数 / 品牌 / 抖动，全部滑条即时可调，
 * 「生成图纸」在后台线程跑 BeadConvert（256×256 全流程 < 100ms，中端机），落库后进编辑器。
 */
public class BeadImportActivity extends BaseActivity {

    private static final int REQ_PICK_IMG = 201;

    private Uri picked;
    private Bitmap preview;
    private int srcW, srcH;
    private boolean launched;

    // 参数（滑条与开关的镜像，buildParams 与回调都读写它们）
    private int w = 48, h = 48, maxColors = 24;
    private String brand = "mard";
    private boolean dither = false;
    private boolean lockRatio = true;

    private LinearLayout content;
    private EditText titleInput;
    private TextView wVal, hVal, cVal;
    private SeekBar hBar;
    private String title = "";

    @Override
    protected void onCreateUi(Bundle b) {
        BeadPalettes.load(this);
        buildWaiting();
        pickImage();
    }

    @Override
    protected void onRebuildUi() {
        if (picked == null) {
            buildWaiting();
        } else {
            buildParams();
        }
    }

    private void pickImage() {
        if (launched) return;
        launched = true;
        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
        it.setType("image/*");
        it.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(it, "选择要转换的图片"), REQ_PICK_IMG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMG) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            // 没选图 = 没有参数可调，这个页面就没有存在意义
            finish();
            return;
        }
        picked = data.getData();
        loadPreview();
        buildParams();
    }

    /** 解码预览图 + 记下原图宽高比（锁定比例滑条要用）。 */
    private void loadPreview() {
        try {
            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            java.io.InputStream in = getContentResolver().openInputStream(picked);
            BitmapFactory.decodeStream(in, null, bo);
            if (in != null) in.close();
            srcW = bo.outWidth;
            srcH = bo.outHeight;
            int sample = 1;
            while (Math.max(srcW, srcH) / (sample * 2) >= 720) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            java.io.InputStream in2 = getContentResolver().openInputStream(picked);
            preview = BitmapFactory.decodeStream(in2, null, o2);
            if (in2 != null) in2.close();
        } catch (Exception e) {
            preview = null;
        }
        if (srcW <= 0 || srcH <= 0) {
            Tip.error(this, "无法读取这张图片");
            finish();
        }
    }

    private void buildWaiting() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.surface(this));
        LinearLayout bar = topBar();
        root.addView(bar);
        root.addView(Ui.topBarHairline(this));
        root.addView(Ui.emptyState(this, R.drawable.ic_bead, "选择一张图片",
                "系统选图器已打开，取消将返回"));
        setContentView(root);
    }

    private void buildParams() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.surface(this));
        LinearLayout bar = topBar();
        root.addView(bar);
        root.addView(Ui.topBarHairline(this));

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content = Ui.column(this);
        int padH = Ui.dp(this, 16);
        content.setPadding(padH, Ui.v(this, 12), padH, Ui.dp(this, 32));
        sc.addView(content);
        root.addView(sc);
        setContentView(root);
        fillParams();
    }

    private LinearLayout topBar() {
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
        mid.addView(Ui.text(this, "图片转图纸", Ui.T_TITLE, Ui.onSurface(this), true));
        mid.addView(Ui.text(this, srcW > 0 ? "原图 " + srcW + "×" + srcH : "",
                Ui.T_LABEL, Ui.onSurfaceVariant(this), false));
        bar.addView(mid);
        return bar;
    }

    private void fillParams() {
        content.removeAllViews();
        int padH = Ui.dp(this, 16);

        // ---------- 预览 ----------
        if (preview != null) {
            FrameLayoutLike holder = new FrameLayoutLike(this);
            holder.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 180)));
            holder.setBackgroundColor(Ui.surfaceLowest(this));
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(preview);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            holder.addView(iv, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            content.addView(holder);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 180));
            hp.bottomMargin = Ui.v(this, 12);
            holder.setLayoutParams(hp);
        }

        // ---------- 图纸名 ----------
        titleInput = Ui.input(this, "图纸名");
        if (title.length() == 0) {
            title = "图纸 " + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date());
        }
        titleInput.setText(title);
        // 标题实时记进字段：品牌切换等会整页重建，用户的输入不能丢
        titleInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                title = s.toString();
            }
        });
        content.addView(titleInput);

        // ---------- 尺寸滑条 ----------
        sectionTitle("图纸尺寸（豆板格数）");
        wVal = sliderRow("宽", w, BeadConvert.MIN_SIZE, BeadConvert.MAX_SIZE,
                new SliderCb() {
                    @Override public void onValue(int v) {
                        w = v;
                        if (lockRatio && srcH > 0) {
                            h = Math.max(BeadConvert.MIN_SIZE, Math.min(BeadConvert.MAX_SIZE,
                                    Math.round(v * (float) srcH / srcW)));
                            // 锁比例联动：只刷高滑条的进度与标签，不重建页面——
                            // 拖动中重建会打断手势，滑条会「跳」
                            if (hBar != null) hBar.setProgress(h - BeadConvert.MIN_SIZE);
                            if (hVal != null) hVal.setText("高 " + h);
                        }
                    }
                });
        hVal = sliderRow("高", h, BeadConvert.MIN_SIZE, BeadConvert.MAX_SIZE,
                new SliderCb() {
                    @Override public void onValue(int v) {
                        h = v;
                    }
                });

        final CheckBox lock = new CheckBox(this);
        lock.setText("锁定原图比例");
        lock.setChecked(lockRatio);
        lock.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                lockRatio = c;
                if (c && srcW > 0) {
                    h = Math.max(BeadConvert.MIN_SIZE, Math.min(BeadConvert.MAX_SIZE,
                            Math.round(w * (float) srcH / srcW)));
                    if (hBar != null) hBar.setProgress(h - BeadConvert.MIN_SIZE);
                    if (hVal != null) hVal.setText("高 " + h);
                }
            }
        });
        content.addView(lock);

        // ---------- 色数 ----------
        sectionTitle("颜色数量（2 ~ 64）");
        cVal = sliderRow("色数", maxColors, 2, 64, new SliderCb() {
            @Override public void onValue(int v) { maxColors = v; }
        });

        // ---------- 品牌 ----------
        sectionTitle("豆子品牌");
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(this);
        for (final String id : BeadPalettes.brandIds()) {
            TextView chip = Ui.text(this, BeadPalettes.brandName(id), Ui.T_BODY,
                    id.equals(brand) ? Ui.onPrimary(this) : Ui.onSurface(this), true);
            chip.setBackground(id.equals(brand)
                    ? Ui.ripple(this, Ui.primary(this), Ui.R_FULL)
                    : Ui.ripple(this, Ui.surfaceContainer(this), Ui.R_FULL));
            chip.setPadding(Ui.dp(this, 14), Ui.v(this, 7), Ui.dp(this, 14), Ui.v(this, 7));
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    brand = id;
                    fillParams();
                }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.rightMargin = Ui.dp(this, 8);
            chips.addView(chip, cp);
        }
        hs.addView(chips);
        content.addView(hs);

        // ---------- 抖动 ----------
        sectionTitle("过渡方式");
        final CheckBox di = new CheckBox(this);
        di.setText("抖动过渡（色彩更平滑，颗粒感更强）");
        di.setChecked(dither);
        di.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                dither = c;
            }
        });
        content.addView(di);

        // ---------- 重新选图 / 生成 ----------
        TextView repick = Ui.textButton(this, "重新选图");
        repick.setGravity(Gravity.CENTER);
        repick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                launched = false;
                pickImage();
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.v(this, 16);
        repick.setLayoutParams(rp);
        content.addView(repick);

        TextView go = Ui.filledButton(this, "生成图纸");
        go.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.topMargin = Ui.v(this, 8);
        go.setLayoutParams(gp);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { generate(); }
        });
        content.addView(go);

        TextView hint = Ui.text(this,
                "转换在后台进行：中位数取色 → 品牌最近色匹配 →（可选）误差扩散。",
                Ui.T_CAPTION, Ui.onSurfaceVariant(this), false);
        hint.setPadding(padH, Ui.v(this, 12), padH, 0);
        hint.setGravity(Gravity.CENTER);
        content.addView(hint);
    }

    private void sectionTitle(String s) {
        TextView t = Ui.text(this, s, Ui.T_LABEL, Ui.onSurfaceVariant(this), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.v(this, 16);
        lp.bottomMargin = Ui.v(this, 6);
        t.setLayoutParams(lp);
        content.addView(t);
    }

    /** 一行「标签 值 + 滑条」。回调把显示值映射回真实值（进度 = 值 - min）。 */
    private TextView sliderRow(String label, int value, final int min, final int max,
                               final SliderCb cb) {
        final TextView val = Ui.text(this, label + " " + value, Ui.T_BODY,
                Ui.onSurface(this), true);
        content.addView(val);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        if ("高".equals(label)) hBar = bar;
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                cb.onValue(p + min);
                // 标签跟手刷新；cb 改了别的参数（锁比例联动高）也在这里一并反映
                if (val != null) val.setText(label + " " + (p + min));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        content.addView(bar);
        return val;
    }

    private interface SliderCb {
        void onValue(int v);
    }

    /** FrameLayout 的占位（本 App 不引依赖，内部类名字显式一点）。 */
    private static class FrameLayoutLike extends android.widget.FrameLayout {
        FrameLayoutLike(android.content.Context c) { super(c); }
    }

    // ================== 生成 ==================

    private void generate() {
        final String t = title == null ? "" : title.trim();
        final Db db = Db.get(this);
        goBusy(true);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BeadConvert.Params pa = new BeadConvert.Params();
                    pa.w = w;
                    pa.h = h;
                    pa.maxColors = maxColors;
                    pa.brand = brand;
                    pa.dither = dither;
                    Bitmap src = BeadConvert.decodeScaled(BeadImportActivity.this,
                            picked, pa.w, pa.h);
                    final Db.BeadPattern pat = BeadConvert.generate(
                            t.length() == 0 ? "未命名图纸" : t, src, pa);
                    saveRef(pat);
                    db.saveBeadPattern(pat);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Intent it = new Intent(BeadImportActivity.this,
                                    BeadEditorActivity.class);
                            it.putExtra("patternId", pat.id);
                            startActivity(it);
                            finish();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            goBusy(false);
                            Tip.error(BeadImportActivity.this,
                                    "转换失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** 参考图与图纸同源：转完顺手存一份压缩图，编辑器里开对比模式直接可用。 */
    private void saveRef(Db.BeadPattern pat) throws Exception {
        Bitmap src = BeadConvert.decodeScaled(this, picked, 1024, 1024);
        File dir = new File(getFilesDir(), "bead-ref");
        dir.mkdirs();
        File dest = new File(dir, pat.id + ".jpg");
        FileOutputStream fos = new FileOutputStream(dest);
        try {
            src.compress(Bitmap.CompressFormat.JPEG, 85, fos);
        } finally {
            fos.close();
        }
        pat.ref = dest.getAbsolutePath();
    }

    private void goBusy(boolean busy) {
        // 生成中把入口禁掉：双击会开出两张一样的图纸
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            v.setEnabled(!busy);
            v.setAlpha(busy ? 0.5f : 1f);
        }
        if (busy) Tip.success(this, "正在转换…");
    }
}
