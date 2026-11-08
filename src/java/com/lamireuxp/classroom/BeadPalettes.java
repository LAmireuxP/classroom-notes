package com.lamireuxp.classroom;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 拼豆品牌色库 —— 内置 10 套品牌色板，共 1249 个实物色号。
 *
 * 数据来源：BeiYa Bead Brand Color Code Dataset
 * (https://github.com/ikunyang1314-code/bead-color-data)，CC BY 4.0 授权。
 * 打包为 gzip 压缩的 CSV（res/raw/bead_colors.csv.gz，约 12 KB），首次使用时解压进内存；
 * 每个色号在载入时预算一次 CIE Lab 值，最近色匹配用 CIEDE2000 全量扫描——
 * 单个品牌最多 221 色，一次匹配 221 次 ΔE 运算，量级可以接受（生成图纸时是
 * 「代表色数 × 品牌」的次数，不是每像素 × 全品牌，见 BeadConvert）。
 *
 * 色号在图纸里存成 "brand:code" 字符串（如 "mard:A1"），换品牌/合并相近色
 * 只改这个列表与像素下标，图纸数据本身不携带 RGB。
 */
public final class BeadPalettes {

    private BeadPalettes() {}

    /** 一个色号：品牌 + 色号 + 中文名 + RGB + Lab（载入时预算）。 */
    public static final class Color {
        public final String brand, code, name;
        public final int rgb;
        public final float L, A, B;

        Color(String brand, String code, String name, int rgb) {
            this.brand = brand;
            this.code = code;
            this.name = name;
            this.rgb = rgb;
            float[] lab = toLab(rgb);
            L = lab[0];
            A = lab[1];
            B = lab[2];
        }
    }

    /** 品牌清单（UI 展示顺序即此顺序）：国产 MARD 排最前，通用色排最后。 */
    private static final String[][] BRANDS = {
            {"mard", "MARD"},
            {"artkal", "Artkal"},
            {"perler", "Perler"},
            {"hama", "Hama"},
            {"artkal-c", "Artkal C（迷你）"},
            {"artkal-hard", "Artkal 硬豆"},
            {"artkal-soft", "Artkal 软豆"},
            {"nabbi", "Nabbi"},
            {"ikea", "IKEA Pyssla"},
            {"universal", "通用色"},
    };

    private static Map<String, List<Color>> sByBrand;
    private static Map<String, Color> sLookup;

    /** 解压 + 解析色库。幂等：第二次调用直接返回。 */
    public static synchronized void load(Context c) {
        if (sByBrand != null) return;
        Map<String, List<Color>> byBrand = new HashMap<String, List<Color>>();
        Map<String, Color> lookup = new HashMap<String, Color>();
        try {
            InputStream in = new java.util.zip.GZIPInputStream(
                    c.getResources().openRawResource(R.raw.bead_colors_csv));
            String csv = readAll(in);
            for (String line : csv.split("\n")) {
                // brand,code,name,hex,r,g,b —— 无表头；name/hex 只作展示兜底，
                // 颜色真值用 r/g/b（与 hex 同源，省一次解析）
                String[] f = line.split(",", -1);
                if (f.length < 7 || f[0].equals("brand")) continue;
                try {
                    int rgb = (clampByte(f[4]) << 16) | (clampByte(f[5]) << 8) | clampByte(f[6]);
                    Color col = new Color(f[0], f[1], f[2], rgb);
                    List<Color> list = byBrand.get(f[0]);
                    if (list == null) {
                        list = new ArrayList<Color>();
                        byBrand.put(f[0], list);
                    }
                    list.add(col);
                    lookup.put(f[0] + ":" + f[1], col);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            // 色库资源损坏时色板为空：界面还能开，只是匹配不出颜色。
            // 不抛异常——一个资源问题不该把整个 App 拖死。
        }
        sByBrand = byBrand;
        sLookup = lookup;
    }

    private static int clampByte(String s) {
        int v = Integer.parseInt(s.trim());
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 品牌 id 列表（展示顺序）。 */
    public static String[] brandIds() {
        String[] out = new String[BRANDS.length];
        for (int i = 0; i < BRANDS.length; i++) out[i] = BRANDS[i][0];
        return out;
    }

    /** 品牌 UI 名；未知 id 原样返回。 */
    public static String brandName(String id) {
        for (String[] b : BRANDS) if (b[0].equals(id)) return b[1];
        return id;
    }

    /** 品牌色号列表；空品牌（资源损坏）返回空列表而不是 null。 */
    public static List<Color> brand(String id) {
        List<Color> l = sByBrand.get(id);
        return l == null ? new ArrayList<Color>() : l;
    }

    /** "brand:code" → 色号对象；不认识返回 null（渲染时按灰色兜底）。 */
    public static Color get(String brandCode) {
        return sLookup.get(brandCode);
    }

    /** 品牌内最近色（CIEDE2000）。品牌为空返回 null。 */
    public static Color nearest(String brand, int rgb) {
        List<Color> list = brand(brand);
        if (list.isEmpty()) return null;
        float[] lab = toLab(rgb);
        Color best = null;
        float bestE = Float.MAX_VALUE;
        for (Color col : list) {
            float e = deltaE(lab[0], lab[1], lab[2], col.L, col.A, col.B);
            if (e < bestE) {
                bestE = e;
                best = col;
            }
        }
        return best;
    }

    /** 两个 "brand:code" 之间的 ΔE2000；任一不认识按最大差处理。 */
    public static float deltaE(String a, String b) {
        Color ca = get(a), cb = get(b);
        if (ca == null || cb == null) return Float.MAX_VALUE;
        return deltaE(ca.L, ca.A, ca.B, cb.L, cb.A, cb.B);
    }

    // ---------------- 颜色空间 ----------------

    /** sRGB → CIE Lab（D65）。每次调用 3 次 pow，载入与匹配合计的量级可控。 */
    public static float[] toLab(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0;
        double g = ((rgb >> 8) & 0xFF) / 255.0;
        double b = (rgb & 0xFF) / 255.0;
        r = r <= 0.04045 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.04045 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.04045 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
        double fx = pivot(x), fy = pivot(y), fz = pivot(z);
        return new float[]{(float) (116 * fy - 16), (float) (500 * (fx - fy)),
                (float) (200 * (fy - fz))};
    }

    private static double pivot(double t) {
        return t > 0.008856 ? Math.cbrt(t) : (t / 0.12841855 + 4.0 / 29.0);
    }

    /**
     * CIEDE2000 色差。公式按 Sharma 2005 实现说明逐项写，不简化成 CIE76——
     * 拼豆配色里大量低饱和近似色，CIE76 在蓝紫区误差大，合并相近色会误伤。
     */
    public static float deltaE(float L1, float a1, float b1, float L2, float a2, float b2) {
        double C1 = Math.sqrt(a1 * (double) a1 + b1 * (double) b1);
        double C2 = Math.sqrt(a2 * (double) a2 + b2 * (double) b2);
        double Cbar = (C1 + C2) / 2.0;
        double c7 = Math.pow(Cbar, 7);
        double G = 0.5 * (1 - Math.sqrt(c7 / (c7 + Math.pow(25, 7))));
        double a1p = (1 + G) * a1, a2p = (1 + G) * a2;
        double C1p = Math.sqrt(a1p * a1p + b1 * (double) b1);
        double C2p = Math.sqrt(a2p * a2p + b2 * (double) b2);
        double h1p = hue(b1, a1p), h2p = hue(b2, a2p);

        double dL = L2 - L1;
        double dC = C2p - C1p;
        double dh = 0;
        if (C1p * C2p != 0) {
            dh = h2p - h1p;
            if (dh > 180) dh -= 360;
            else if (dh < -180) dh += 360;
        }
        double dH = 2 * Math.sqrt(C1p * C2p) * Math.sin(Math.toRadians(dh) / 2);

        double Lbar = (L1 + L2) / 2.0;
        double Cbarp = (C1p + C2p) / 2.0;
        double hbar = h1p + h2p;
        if (C1p * C2p != 0) {
            if (Math.abs(h1p - h2p) <= 180) hbar /= 2.0;
            else if (hbar < 360) hbar = (h1p + h2p + 360) / 2.0;
            else hbar = (h1p + h2p - 360) / 2.0;
        }
        double T = 1 - 0.17 * Math.cos(Math.toRadians(hbar - 30))
                + 0.24 * Math.cos(Math.toRadians(2 * hbar))
                + 0.32 * Math.cos(Math.toRadians(3 * hbar + 6))
                - 0.20 * Math.cos(Math.toRadians(4 * hbar - 63));
        double dTheta = 30 * Math.exp(-Math.pow((hbar - 275) / 25, 2));
        double c7p = Math.pow(Cbarp, 7);
        double RC = 2 * Math.sqrt(c7p / (c7p + Math.pow(25, 7)));
        double SL = 1 + 0.015 * Math.pow(Lbar - 50, 2) / Math.sqrt(20 + Math.pow(Lbar - 50, 2));
        double SC = 1 + 0.045 * Cbarp;
        double SH = 1 + 0.015 * Cbarp * T;
        double RT = -Math.sin(Math.toRadians(2 * dTheta)) * RC;
        return (float) Math.sqrt(
                sq(dL / SL) + sq(dC / SC) + sq(dH / SH) + RT * (dC / SC) * (dH / SH));
    }

    private static double hue(double b, double ap) {
        if (ap == 0 && b == 0) return 0;
        double h = Math.toDegrees(Math.atan2(b, ap));
        return h >= 0 ? h : h + 360;
    }

    private static double sq(double v) { return v * v; }

    // ---------------- 图纸配色序列化 ----------------

    /**
     * 图纸颜色表 ↔ JSON。存 "brand:code" 数组；空表存 "[]"。
     * 解析失败按空表处理——图纸像素全指空色时画出来是全空网格，不至于崩溃。
     */
    public static String colorsToJson(List<String> colors) {
        JSONArray arr = new JSONArray();
        if (colors != null) for (String s : colors) arr.put(s == null ? "" : s);
        return arr.toString();
    }

    public static List<String> colorsFromJson(String json) {
        List<String> out = new ArrayList<String>();
        if (json == null || json.length() == 0) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i, ""));
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** 色号的显示色（不认识的 brand:code 按中灰兜底，保证画布/图例不缺块）。 */
    public static int rgbOf(String brandCode) {
        Color c = get(brandCode);
        return c == null ? 0xFF888888 : c.rgb;
    }

    /** 色号的展示名：色号 + 中文名（有中文名时）。 */
    public static String labelOf(String brandCode) {
        Color c = get(brandCode);
        String code = brandCode == null ? "" : brandCode.substring(brandCode.indexOf(':') + 1);
        if (c == null) return code;
        return c.name == null || c.name.length() == 0 ? code : code + " " + c.name;
    }
}
