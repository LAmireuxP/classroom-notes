# UI 设计参照与改造方案

面向本项目的 UI 设计参考提炼。三个参照源：

| 仓库 | 星数 | 定位 | 用法 |
| --- | --- | --- | --- |
| [VoltAgent/awesome-design-md](https://github.com/VoltAgent/awesome-design-md) | 116k | 74 份品牌设计系统 `DESIGN.md` 合集 | **学设计规范** |
| [guillermolg00/morphicons](https://github.com/guillermolg00/morphicons) | 2.6k | stroke-based 图标的通用形变 | **学形变规则** |
| [uiverse-io/galaxy](https://github.com/uiverse-io/galaxy) | 13k | 3000+ 社区 UI 片段 | **学交互手法** |

**三个都是 Web 方向（HTML/CSS/JS/React），不能直接引入。** 本项目是纯 Java 手写 View
体系、零第三方依赖、APK 约 175 KB，这个约束不破。

---

## 0. 项目现状（所有建议的锚点）

| 事实 | 影响 |
| --- | --- |
| `aapt2 compile --dir res` + `javac -source 8` + `d8 --min-api 22`，无 Gradle / 无 AndroidX | 只能用手写 View + `android.*` 框架 API；**没有** `androidx.dynamicanimation` / `VectorDrawableCompat` / `ViewPager2` |
| `res/` 下没有 `anim/`、`animator/` 目录 | 目前零资源级动画。加这两个目录 aapt2 会自动编译，**不需要改构建脚本** |
| 21 个图标**全是 filled path**（`fillColor` + `SRC_IN` 染色），无一使用 stroke | morphicons 是 stroke-based 库，**形变算法不能照搬**——但它的对齐/旋转规则可以 |
| 全项目动画原本只有 2 处：`Tip.java`（`DecelerateInterpolator` 200ms）、`ThemeSwitch.java`（280ms + `postDelayed(DUR-40)`） | 缓动词汇表和时长阶梯是最大的空白，也是最便宜的收益点 |
| `ThemeSwitch.java` 头注释已写「设计手法参考 uiverse-io/galaxy 的 Toggle-switches」 | galaxy 已是本项目既定参照源，可继续沿用同一套词汇 |

---

## 1. VoltAgent/awesome-design-md

### 收录内容

`design-md/` 下 74 个目录，每个含 `DESIGN.md` + `preview.html` + `preview-dark.html`。分类覆盖
AI/LLM（Claude、Mistral、xAI）、开发工具（Cursor、Raycast、Vercel）、后端（Supabase、MongoDB、Sentry）、
生产力（**Notion**、Linear、Cal.com、Zapier）、设计工具（Figma、Framer、Miro）、
金融（Stripe、Coinbase）、电商（Airbnb、Shopify、Nike）、汽车（Tesla、BMW）等。

每份文件的 9 个 section：Visual Theme / Color Palette & Roles / Typography Rules / Component Stylings /
Layout Principles / Depth & Elevation / Do's and Don'ts / Responsive Behavior / Agent Prompt Guide。
**front matter 是 YAML token（`{colors.primary}` 可被组件反向引用）**——token 和组件在同一份
文件里建立引用关系，而非两套东西。这是它最值得学的结构。

### 【首选】Notion —— 品类对口，补的正好是缺的那一层

**配色逻辑是三级结构，不是「紫色按钮」：**

1. **暖中性墨色阶 6 级**（本项目最大缺口）
   `ink #1a1a1a` → `charcoal #37352f` → `slate #5d5b54` → `steel #787671` → `stone #a4a097` → `muted #bbb8b1`
2. **9 个 pastel tint 面**（`card-tint-peach/rose/mint/lavender/sky/...`）**只作卡片背景**，
   用来编码「内容类别」，绝不作正文色
3. **1 个饱和动作色** `primary #5645d4`，只给唯一的主动作

本项目现在只有 2 级文字色（`on_surface #1B1B1F`、`on_surface_variant #45464F`），而
`#45464F` 和 `outline #767680` 之间是空的——Notion 的 steel/stone 正好落在这个空档。

**可照做的色值**（按 Notion 的明度位置推出，需在真机验收对比度）：

| 新 token | light 候选 | dark 候选 | 对应 Notion | 用途 |
| --- | --- | --- | --- | --- |
| `text_body` | `#37383F` | `#CFCCD2` | charcoal `#37352f` | 笔记正文（比标题低半档） |
| `text_tertiary` | `#6E6F79` | `#9A9AA4` | steel `#787671` | 元信息、日期、计数 |
| `text_faint` | `#9C9DA6` | `#82838C` | stone `#a4a097` | 占位提示 |

对比度实测：`steel #787671` 对白 = **4.54:1**（刚好过 WCAG AA 4.5）；
`stone #a4a097` 对白 = **2.61:1**（**不过 AA**）。规则：

> `text_tertiary` 可以承载 14sp 正文级信息；`text_faint` 只能用于 ≥18sp 加粗或纯装饰标签。

本项目 `on_surface_variant #45464F` 对 `#FEFBFF` 是 **9.16:1**，余量很大，完全负担得起再补两级。

**字阶：**

| token | size | weight | lineHeight | letterSpacing |
| --- | --- | --- | --- | --- |
| hero-display | 80 | 600 | 1.05 | -2px |
| heading-2 | 36 | 600 | 1.20 | -0.5px |
| heading-4 | 22 | 600 | 1.30 | 0 |
| heading-5 | 18 | 600 | 1.40 | 0 |
| body-md | 16 | 400 | **1.55** | 0 |
| body-sm | 14 | 400 | 1.50 | 0 |
| caption | 13 | 400 | 1.40 | 0 |
| micro | 12 | 500 | 1.40 | 0 |
| **micro-uppercase** | **11** | **600** | 1.40 | **+1px** |
| button-md | 14 | 500 | 1.30 | 0 |

三条规则：
1. **字重只有 3 档**：600 标题 / 500 按钮 / 400 正文。
2. **负字距只出现在 display 档**（-2px@80 → -0.5px@48），body 档为 0。**不要在 16sp 标题上加负字距。**
3. **正文行高 1.55。**

**间距 / 圆角：**

- 间距基座 **4px**：4/8/12/16/20/24/32/40，section 48/64/96/120
- 圆角：`xs4 sm6 md8 lg12 xl16 xxl20 xxxl24 full`
- 组件映射：**按钮 = 8px，卡片 = 12px，pill 只给 badge 和 pill-tab**

> Notion 的 Do's 明确写着 *"Apply `{rounded.md}` (8px) to buttons — Notion uses rectangles, not pills"*，
> Don't 写着 *"Don't use pill-shaped buttons; Notion's geometry is rectangular-sober"*，并把它列为
> 区分于竞品的品牌特征。

**阴影 5 级 → Android 映射**（1px 模糊 ≈ 1dp elevation）：

| 级别 | Notion | Android |
| --- | --- | --- |
| 0 flat | 无阴影，1px hairline | `Ui.card()` 现状即正确 |
| 1 | `rgba(15,15,15,.04) 0 1px 2px` | `elevation 1dp` |
| 2 卡片 | `rgba(15,15,15,.08) 0 4px 12px` | `elevation 3dp` |
| 3 mockup | `rgba(15,15,15,.20) 0 24px 48px -8px` | `elevation 10dp` + `translationZ` |
| 4 模态 | `rgba(15,15,15,.16) 0 16px 48px -8px` | 底部 sheet |

> 文档型卡片永远走 level 0（hairline），阴影只留给模态和浮层。

**组件手法：**

1. **`text-input-focused`：边框从 `1px hairline-strong` 变 `2px primary`。**
   Linear 也给了同一条规则（`2px primary-focus at 50%`）——**两个仓库独立同结论**。
   实现注意：`GradientDrawable.setStroke()` 会改 drawable 尺寸导致重排，**只动颜色不动宽度是零风险方案**。
2. **`badge-tag-*`：底 = 浅 tint，字 = 同色相深版，圆角 6px，padding 2px 8px，13px/600。**
   对用户自选课程色尤其重要——`MainActivity` 的色点直接用原始色，用户选了 `#FFFF00` 时
   「该色作底 + 白字」不可读。确定性解法（零依赖，手写 lerp）：
   ```java
   /** tint 底：把色相压到浅面；deep 字：同色相压深。 */
   public static int tintOf(int color, int surface) { return blend(color, surface, 0.14f); }
   public static int deepOf(int color, boolean dark) {
       return blend(color, dark ? Color.WHITE : Color.BLACK, dark ? 0.35f : 0.45f);
   }
   private static int blend(int a, int b, float k) {
       return Color.rgb(
           Math.round(Color.red(a)   + (Color.red(b)   - Color.red(a))   * k),
           Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * k),
           Math.round(Color.blue(a)  + (Color.blue(b)  - Color.blue(a))  * k));
   }
   ```

### 【次选】Linear —— 深色模式的「阶梯代替阴影」教科书

整站只有**一个**彩色强调（`#5e6ad2` lavender），且**只出现在 3 个地方**：brand mark、focus ring、主 CTA。
其余全靠一条四级 surface 阶梯：

```
canvas #010102 → surface-1 #0f1011 → surface-2 #141516 → surface-3 #18191a → surface-4 #191a1b
hairline #23252a / hairline-strong #34343a / hairline-tertiary #3e3e44
ink #f7f8f8 / ink-muted #d0d6e0 / ink-subtle #8a8f98 / ink-tertiary #62666d
```

1. **深色下严禁靠阴影表达层次。** 原文：*"Linear's depth is carried by surface ladder + hairline borders.
   The brand resists drop shadows on dark almost entirely."* 本项目 `Ui.card()` 方向是对的。
2. **hairline 应该相对 surface 只高约 +20**（#23252a on #0f1011）。
   本项目 `dark_outline_variant #45464F` on `dark_surface_container #1F1F23` 高了 **+38**——
   **深色下卡片描边偏亮，看起来像「灰框」而不是「轻微分离」**。
   → 建议新增 `dark_hairline = #2E2F35` 专给深色卡片描边。
3. **单一强调色纪律。** 本项目 `primary` 目前出现在至少 10 处（filled 按钮底、outlined/text 按钮字、
   chip 边、置顶图标、状态圆点、输入 focus、tab 选中底、「重点」标签、actionCell 图标…）。
   → 收缩到**两个角色：唯一主动作 + focus/选中指示**。

**字距规律：负字距近似按字号线性衰减，到 14px 归零。**

| size | letterSpacing | 占字号比 |
| --- | --- | --- |
| 80px | -3.0px | -3.75% |
| 40 | -1.0 | -2.5% |
| 22 | -0.4 | -1.8% |
| 18 | -0.1 | -0.6% |
| 16 | -0.05 | -0.3% |
| 14 | 0 | 0 |

> `T_BODY 14` 及以下**不该有任何字距调整**；`T_TITLE 16` 最多 -0.05px；`T_DISPLAY 26` 可给 -0.2px。
> 反过来，**小标签要用正字距**：Linear 的 `eyebrow` 是 `13px/500/+0.4px`，解释为
> *"positive tracking against the negative-tracked display marks the eyebrow as taxonomy"*。
> 这与 Notion 的 micro-uppercase(+1px@11px) 互证。

### 【第三】Cal.com —— 中性优先的强调阶梯

primary CTA 是 **`#111111` 近黑，不是品牌色**。整套近乎无彩，**颜色只出现在语义徽章**上。

1. **用「强调阶梯」而不是「颜色阶梯」。** 本项目 `renderNotes()` 操作行里，
   「录音」（tonal 紫底）和「新建笔记」（filled 紫底）**并排放着两个紫按钮**。
   Cal.com 做法：一行里只留**一个**有色按钮。
2. **`nav-pill-group` → 真正的分段控件。** 原文：*"A small pill-radius wrapper around 2-3 sub-nav segments.
   Background `surface-soft` with internal padding 6px, rounded pill. Active segment renders as a
   white-canvas pill with a subtle drop shadow inside the wrapper."*
   本项目 `renderTabs()` 现在是选中 tab 直接变 `primary` 实底紫色。改成：
   - 外层容器 `surface_container_low` + `R_FULL` + 内 padding 4dp
   - 选中 pill 用 `surface_container_highest` 底 + `on_surface` 字（**不是 primary**）
   - 加 `ValueAnimator` 让 pill 有 200ms 位移过渡
3. **尺寸互证**：Cal.com `button-primary` = 40px 高 / padding 12×20 / radius 8px，
   `button-icon-circular` = 36×36。本项目 `Icons.BTN_HEIGHT = 40`、`BTN_PAD_H = 20`、
   `compactAction` 36dp——**完全对上，不用改**，只剩圆角不符。
4. **阴影表** `0 1px 2px rgba(0,0,0,.05)` / `0 4px 12px rgba(0,0,0,.08)` 与 Notion level 1/2 几乎一致
   → Android `elevation 1dp / 3dp` 可放心用作全局两级。

### 【次要】Superhuman

间距基座 8px 配 2/4/12 子档；`ink #292827` 暖灰，明确 "never pure black"；
圆角 `xs4 sm6 md8（按钮）lg12（卡片）xl16（模态）`——**第三个仓库给出同一张圆角表，可直接当硬规范**。

### 明确不适合本项目的

所有 display 字号与 hero band（移动端列表页没有 hero）· 各家 hero 排版/插画/mesh wire 装饰 ·
Nike 的巨型大写 Futura、Vodafone 的 monumental uppercase（拉丁字设计，中文不适用）·
汽车/金融/复古网络的 8 个品牌 · 各家 breakpoint 折叠策略（单 Activity 手机布局无断点需求）·
`DESIGN.md` 的 YAML front matter 格式本身（本项目等价物已是 `colors.xml` + `Ui.java`）。

---

## 2. guillermolg00/morphicons

### 原理（读 `src/core/*.ts`）

管线六步，全是纯函数，`src/core` 完全不碰 DOM：

1. **parse** `d` 字符串 → 命令节点
2. **normalize**
3. **resample**：把每条子路径**按弧长均匀重采样成同样的 N 个点**；关键不变量是
   **anchored corners——原路径的顶点在重采样后仍是精确采样点**（静止帧零失真）
4. **correspondence**：
   - 子路径配对：条数相等用最小代价**排列**（`PERM_MAX=8` 内穷举 + 剪枝，超过走贪心）；
     条数不等用**满射**（`SURJ_MAX=1e5`），代价 = `dist(质心) + 0.35·|Δ弧长|`。
     **满射保证没有任何子路径凭空出现或消失**——多出来的原地复制（"cell division"）
   - 单条子路径内部：试**两个行进方向**；若含闭合环，再试**全部 N 个圆周偏移**
5. **Procrustes（闭式，只用 `atan2`，不用 SVD）**：求最优相似变换 (θ, σ)
   ```
   θ* = atan2(Sxy − Syx, Sxx + Syy)
   σ* = (cosθ·(Sxx+Syy) + sinθ·(Sxy−Syx)) / Σ|a|²
   res = sqrt(max(0, σ²·na − 2σ·num + nb) / nb)
   ```
   打分 `score = res + λ·|θ|/π`，λ = 0.05——因为**线段这类「反演下对称」的形状两个方向残差相同、
   但旋转结果不同**，λ 用来挑最小旋转。
   - **全局混合**：若整个图标在**同一个**相似变换下同余（全局 `res < GLOBAL_EPS = 5e-3`），
     所有子路径**共享同一个 (θ, σ)**，并启用 **block transport**：中途质心沿共享相似变换绕全局质心
     走弧线，而非直线 lerp——否则箭头的头会在中途「塌向」杆
6. **polar interpolation**：把 B 先搬到 A 的坐标系 `R(−θ)·(b − c_B)/σ`，
   然后**旋转、缩放、质心分别插值**——而不是对原始坐标线性 lerp。这是「旋转会自己浮现」的根源

**核心卖点：旋转是涌现的，不是手写的。** 已用测试钉死的不变量：

| 对 | 结果 |
| --- | --- |
| arrow-right → arrow-down | **θ = 90°, σ = 1, res = 0**（没人声明过「这是旋转组」） |
| plus → x | \|θ\| = 45°, σ ≈ 1.212 |
| menu → x | **\|θ\| = 45°（最小旋转，绝不是 135°）** ← λ 的作用 |
| square → diamond | \|θ\| = 45°, σ = √½ |
| 端点精确 | `interp(plan,0) = A`, `interp(plan,1) = B`，误差 **< 1e-9** |
| 打断 | 中途重新 plan 会**保留弹簧速度**，任何一帧都不出 NaN |
| 静止帧 | **canonical snap：原样输出输入字符串本身** |

另外它的**降低动效策略是显式的**：`reducedMotion = "never"(默认) | "user" | "always"`，
默认**不**跟随系统（作者理由：图标形变属于「短促、有信息量」的微动效，自动降级会让用户觉得库坏了）。
这个「策略显式、默认不降级但可一键降级」的立场值得抄。

### 不能照搬的部分

| 项 | 原因 |
| --- | --- |
| 完整的任意图标形变 | 需要 路径解析 + 弧长重采样 + 对应关系匹配 + Procrustes + 序列化，约 **600–900 行 Java**。且本项目图标是 **filled silhouette**（mic 的支架是带孔的自相交轮廓），不是 stroke，`fillType` 语义更麻烦 |
| `Path.approximate(float)` | **API 26**，minSdk 22 需版本守卫 |
| `Path.interpolate(Path,float,Path)` | **API 34**，不可用 |
| `androidx.dynamicanimation` / Lottie / ShapeShifter 运行时 | 违反零依赖硬约束 |
| `src/adapters/`、5 个框架绑定、SSR | 全是 Web 生态 |

### 可以移植的是**规则**，不是代码

| # | 规则 | Android 落地 |
| --- | --- | --- |
| R1 | **端点精确 / 静止帧 canonical snap** | 动画结束后不要停在「算出来的近似值」上。`withEndAction(() -> iv.setImageResource(静态图标))` |
| R2 | **打断 = 从中途形状重新起飞，保留速度** | `ValueAnimator.ofFloat(当前值, 目标值)`，不要 `ofFloat(0f,1f)`；`ViewPropertyAnimator` 天然支持重定向 |
| R3 | **旋转取最小角** | 展开/收起箭头永远转 **90° 或 180°**，不要「抄近路」转 270° |
| R4 | **同余时共享变换** | 多个元素一起动时给**同一个** interpolator 实例和同一个 duration，别各自 `new` |
| R5 | **动效降级是显式策略** | `ValueAnimator.areAnimatorsEnabled()`（**API 26**，需守卫；22–25 读 `Settings.Global.ANIMATOR_DURATION_SCALE`） |
| R6 | **微动效用弹簧，不用「时长+曲线」** | 见方案 C(3) |

### 三个动画方案

#### 方案 A：麦克风 → 停止（录音按钮）—— 参数化同余形变

mic 的机身和停止方块**都是圆角矩形**，这是**同余问题**（congruence）——
morphicons 的立场就是「同余时应旋转/缩放，不要插值原始坐标」。所以直接**参数化重建**，
不需要解析器、不需要重采样。

从 `ic_mic.xml` 读出的真实几何：
- 机身：`M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66-1.34,-3-3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z`
  → 圆角胶囊，中心 **(12, 8)**，halfW **3**，halfH **6**，r **3**（全圆）
- 支架：马蹄 + 竖杆
- 目标停止块：中心 **(12, 12)**，halfW **5**，halfH **5**，r **2**

五个参数线性插值即可，且**终值精确**：

```java
public final class RecordMorphView extends View {
    private final Path body = new Path(), stand = new Path();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float t = 0f;          // 0 = 麦克风, 1 = 停止
    private boolean stop = false;

    public RecordMorphView(Context c, int tint) {
        super(c);
        p.setStyle(Paint.Style.FILL);
        p.setColor(tint);
        buildStand(stand);          // 静态路径，只构一次
    }

    /** 打断安全：从「当前 t」起飞，不是从 0/1 起飞（morphicons R2）。 */
    public void setStop(boolean v) {
        if (stop == v) return;
        stop = v;
        ValueAnimator a = ValueAnimator.ofFloat(t, v ? 1f : 0f);
        a.setDuration(Ui.DUR_BASE);
        a.setInterpolator(Ui.EASE_STANDARD);
        a.addUpdateListener(an -> { t = (Float) an.getAnimatedValue(); invalidate(); });
        a.start();
    }

    @Override protected void onDraw(Canvas cv) {
        float s = Math.min(getWidth(), getHeight()) / 24f;   // 24 单位网格 → 像素
        cv.save();
        cv.scale(s, s);

        body.reset();
        body.addRoundRect(new RectF(
                12f - lerp(3f, 5f, t),  lerp(8f, 12f, t) - lerp(6f, 5f, t),
                12f + lerp(3f, 5f, t),  lerp(8f, 12f, t) + lerp(6f, 5f, t)),
                lerp(3f, 2f, t), lerp(3f, 2f, t), Path.Direction.CW);
        cv.drawPath(body, p);

        if (t < 0.999f) {                       // 支架：前 60% 淡出 + 微下沉
            p.setAlpha((int) (255 * Math.max(0f, 1f - t / 0.6f)));
            cv.save();
            cv.translate(0f, lerp(0f, 2.5f, t));
            cv.drawPath(stand, p);
            cv.restore();
            p.setAlpha(255);
        }
        cv.restore();
    }

    private static float lerp(float a, float b, float k) { return a + (b - a) * k; }
}
```

`Path.addRoundRect(RectF, rx, ry, Direction)` 是 API 1。架在 `CourseActivity` 的 `recBtn` 里
替换原来的 `ImageView`。**`contentDescription` 要跟着翻**（"开始录音"/"停止录音"）。
成本约 120 行。风险：低。

#### 方案 B：`☰` → `✕` —— 自绘 View + 涌现的 ±45°

morphicons 的招牌例子。关键：**±45° 不是随便定的，是 Procrustes 对「平行线 → 交叉线」
算出的最优解**（λ 挑选了最小角；平行→交叉的最小旋转就是 45°）。所以硬编码 45 恰好就是「涌现的答案」。

**为什么不用 AVD 的 pathData 形变**：AVD 的 `pathData` 形变**要求起止两条 `d` 的命令结构完全一致**
（`PathParser.canMorph` 判定：节点数相同且逐节点命令类型相同）。本项目 `ic_menu` 是
`M3,6h18v2H3zM3,11h18v2H3zM3,16h18v2H3z`（18 节点），`ic_close` 约 38 节点，**不兼容**。

**自绘，约 45 行，不需要任何 res 文件：**

```java
public final class MenuMorphView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float t = 0f;                    // 0 = ☰, 1 = ✕
    private boolean open = false;

    public void setOpen(boolean v) {
        if (open == v) return;
        open = v;
        ValueAnimator a = ValueAnimator.ofFloat(t, v ? 1f : 0f);
        a.setDuration(200);
        a.setInterpolator(Ui.EASE_STANDARD);
        a.addUpdateListener(an -> { t = (Float) an.getAnimatedValue(); invalidate(); });
        a.start();
    }

    @Override protected void onDraw(Canvas cv) {
        float s = Math.min(getWidth(), getHeight()) / 24f;
        cv.save(); cv.scale(s, s);
        final float cx = 12f, cy = 12f, half = 7.5f, th = 1.5f, pitch = 4.5f;
        for (int i = -1; i <= 1; i++) {
            float ty = cy + i * pitch * (1f - t);        // 向中心收拢
            float rot = t * (i == 0 ? 0f : (i < 0 ? 45f : -45f));   // 涌现角
            float alpha = (i == 0) ? (1f - t) : 1f;      // 中间那根消失
            cv.save();
            cv.rotate(rot, cx, cy);
            p.setAlpha((int) (255 * alpha));
            cv.drawRoundRect(new RectF(cx - half, ty - th, cx + half, ty + th), th, th, p);
            cv.restore();
        }
        p.setAlpha(255);
        cv.restore();
    }
}
```

三根 bar 绕**图标中心**旋转 → 上/下两根正好变成 X 的两条对角，中间那根淡出。
视觉上完全等价于 AVD 形变，且**打断安全**（连续点击不会跳）。

> **保留 AVD 路线的场合**：将来若做「展开/收起箭头」这类**纯直线**图标对，两条 `d` 天然是
> `M L L L Z` × 1 子路径，结构一致，AVD 更省事。AVD 的 `res/drawable/*.xml` + `res/animator/*.xml`
> 会被 `aapt2 compile --dir res` 自动编译，**不需要改构建脚本**。注意 AVD 要 `reset()` 再 `start()`，
> 否则连点会反向播放。

#### 方案 C：展开箭头（旋转）+ 置顶翻转（两段式）—— 最便宜的收益

**(1) 箭头旋转——一条链式调用**

`noteRow` 目前**没有任何展开指示器**，只靠正文变长。加一个 chevron
（新增 `ic_chevron_down.xml`，path 用 `M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6z`）：

```java
chevron.animate()
        .rotation(expanded ? 180f : 0f)
        .setDuration(Ui.DUR_BASE)
        .setInterpolator(Ui.EASE_STANDARD)
        .start();
```

规则：永远用 `.animate().rotation(绝对角)`，**不要** `setRotation()`（会跳变），
也**不要** `rotationBy()`（连点会累加成 540°）→ 这就是 native 版本的
「打断时从当前形状重新起飞」（`ViewPropertyAnimator` 从当前值重定向）。

**(2) 置顶/星标——两段式「先翻后填」**

填充/描边两态切换有个问题：中间帧没有信息。galaxy 的 `Checkboxes/elijahgummer_hard-liger-45.html`
给了现成答案（0.3s `rotateY(0→90→180)` + `translateY(0→-16px→0)` + `scale(1→1.1→1.2)`，
然后填充色**延迟 0.1s** 淡入）：

```java
AnimatorSet set = new AnimatorSet();
ObjectAnimator flip = ObjectAnimator.ofFloat(iv, "rotationY", 0f, 180f);
ObjectAnimator hop  = ObjectAnimator.ofFloat(iv, "translationY", 0f, -Ui.dp(c, 6), 0f);
ObjectAnimator grow = ObjectAnimator.ofFloat(iv, "scaleX", 1f, 1.15f);
ObjectAnimator growY= ObjectAnimator.ofFloat(iv, "scaleY", 1f, 1.15f);
set.playTogether(flip, hop, grow, growY);
set.setDuration(180);
set.setInterpolator(Ui.EASE_STANDARD);
set.addListener(new AnimatorListenerAdapter() {
    @Override public void onAnimationEnd(Animator a) {
        iv.setRotationY(0f);                       // R1：snap 回精确静止帧
        iv.setImageResource(filled ? R.drawable.ic_pin_filled : R.drawable.ic_pin);
        iv.animate().alpha(1f).setDuration(120).start();   // 第二段：填充淡入
    }
});
iv.animate().alpha(0f).setDuration(100).withEndAction(() -> set.start()).start();
```

`View.setRotationY` / `setCameraDistance` 都是 API 11/12。需要新增 `ic_pin_filled.xml`。

**(3) 用解析弹簧替代 `OvershootInterpolator`**

20 行阻尼弹簧，好处是**可打断且保速**：

```java
/** 欠阻尼弹簧 T.I.：x(k) = 1 − e^(−ζωk)·(cos(ω_d k) + ζω/ω_d·sin(ω_d k))，k∈[0,1] */
public static TimeInterpolator spring(final float zeta, final float omega) {
    final float wd = (float) (omega * Math.sqrt(1 - zeta * zeta));
    return k -> {
        if (k >= 1f) return 1f;
        double e = Math.exp(-zeta * omega * k);
        return (float) (1 - e * (Math.cos(wd * k) + zeta * omega / wd * Math.sin(wd * k)));
    };
}
// 用法：Ui.SPRING_SNAPPY = spring(0.55f, 12f)
```

### 顺带修 `ThemeSwitch` 的三个问题

1. **`postDelayed(DUR-40)` 是竞态** → 改 `withEndAction`（`ViewPropertyAnimator.withEndAction` 是 **API 16**）
2. **交叉淡入是最弱的形态。** 最低成本升级：`knob.animate().rotationBy(180f)`；
   更彻底的是把太阳做成「光线收拢 + 圆心用 `Path.op` 切出月牙」：
   ```java
   Path sun = new Path(); sun.addCircle(cx, cy, r, Path.Direction.CW);
   Path cut = new Path(); cut.addCircle(cx + d, cy - d, r, Path.Direction.CW);
   Path crescent = new Path();
   crescent.op(sun, cut, Path.Op.DIFFERENCE);   // Path.op —— API 19
   ```
   `d` 从 `2r`（完全不相交 = 满圆）动画到 `0.55r`（月牙），同时 8 条光线用 alpha + 长度收缩。
   约 70 行。
3. **`DecelerateInterpolator` → `Ui.EASE_STANDARD`**

---

## 3. uiverse-io/galaxy

**定位：风格来源，不是 API 来源。** 3000+ 个 HTML 片段各自独立、MIT、无共享 token。
实际读了 58 个样本（Cards/Checkboxes/Toggle-switches/Notifications/Tooltips/loaders/Inputs/
Radio-buttons/Buttons/Forms/Patterns）的 CSS——**跨文件唯一一致的东西就是缓动函数和时长，
这本身就是结论。**

### 8 条可借鉴手法

**1. 缓动词汇表——直接 1:1 移植**

Android 的 `PathInterpolator(x1,y1,x2,y2)`（**类 API 21**）就是同一条三次贝塞尔，
CSS 的 `cubic-bezier` 可以**逐位照抄**：

| galaxy 曲线 | 语义 | Android |
| --- | --- | --- |
| `cubic-bezier(0.23, 1, 0.32, 1)` | 通用状态变化（easeOutQuint） | `new PathInterpolator(0.23f,1f,0.32f,1f)` ← **设为默认** |
| `cubic-bezier(0.68, -0.55, 0.27, 1.55)` | 带回弹（easeInOutBack），弹层/开关 | `new PathInterpolator(0.68f,-0.55f,0.27f,1.55f)` |
| `cubic-bezier(0.5, 0.15, 0.25, 1.75)` | 大幅回弹（气泡弹出） | `new PathInterpolator(0.5f,0.15f,0.25f,1.75f)` |
| `cubic-bezier(.25,.01,.25,1)` | 输入框边框/标签变色 | focus 过渡 |

**2. 时长是 3 档，不是随手填**：`0.2s`（状态）/ `0.3–0.4s`（变换）/ `0.5–0.6s`（进入）/ `1s`（shimmer）。
原来的 200ms（Tip）和 280ms（ThemeSwitch）都落在合理区间，只是没有名字。

**3. 「图标展开成标签」——最对口的一条**

`Tooltips/csemszepp_ordinary-owl-54.html`：36×36 的纯图标格子在 hover/按下时**宽度 36px → 142px**，
标签延迟淡入（`transition: width 300ms, background-color 300ms linear 200ms`，标签本体
`fadeIn 600ms` 且前 50% 保持透明）。片段用 `white-space: nowrap; overflow: hidden` 保证文字不撑破。

> 这正好解决 `Icons.compactAction` 注释里承认的问题：「修复横向溢出：图标收窄到 15dp、
> 间距 5dp、内边距 6dp、文字 12.5sp，配合 weight=1 等宽分配，保证 4 个按钮在 375dp 内不溢出」。
> **与其把 4 个按钮压到 12.5sp，不如让它们在收起态是纯图标（40dp 圆形），展开时才动画长成图标+标签。**

**4. 「状态层是一个缩放，不只是涟漪」——全篇性价比最高**

`Checkboxes/elijahgummer_hard-liger-45.html`：48dp 圆形容器套圆形遮罩，hover 时
`scale(0.5)+opacity 0 → scale(1)+opacity 1`，按下时回到 `scale(0.8)`。
`Tooltips/Mohammad-Rahme-576_hard-starfish-64.html` 里 `:active { transform: translateY(-2px) scale(0.98) }`。

已有 `RippleDrawable` 但**没有按下缩放**。补 `Ui.pressScale()`：
- 必须 `return false`，否则 `RippleDrawable` 收不到事件
- 缩放是渲染期变换、不触发重排，在列表里安全——但要缩内容容器而不是背景持有者
- 0.97–0.98 这个值 MD3 自己也这么规定，属于「规范内」

**5. 「先翻后填」两段式开关**——见 §2 方案 C(2)。
同一文件里还有 `:checked:before { width: 1.5em→0.5em; border-radius: 0.75em→0.25em }`——
**一个元素同时改宽和圆角**，正是 mic→stop 的「参数化圆角矩形形变」。**两个独立来源、同一手法。**

**6. 「不重排的脉冲」做录音指示**

`loaders/Shoh2008_quick-fox-3.html`：8px 圆点用 5 圈 `box-shadow`（0/20/40/60/80px）做相位错开的波。
原生版：

```java
/** 三圈错相脉冲，用 scale+alpha，不改 layout。 */
private static void pulse(View ring, long delayMs) {
    ring.setScaleX(0.3f); ring.setScaleY(0.3f); ring.setAlpha(0.6f);
    ring.animate().scaleX(3f).scaleY(3f).alpha(0f)
        .setDuration(1200).setStartDelay(delayMs)
        .setInterpolator(new LinearInterpolator())
        .withEndAction(() -> pulse(ring, 0)).start();   // 自循环
}
pulse(ring1, 0); pulse(ring2, 400); pulse(ring3, 800);
```

录音状态用 `Ui.error(c)` 染红，比静态 `ic_mic` 明确得多。

**7. 「进入动效」和「注意动效」分离**

> 对 `Tip.java`：借「进入 + 一次性脉冲」，**明确拒绝 infinite 循环**
> （列表/常驻元素的无限动画是 jank 和耗电源）。退出应比进入快。

**8. shimmer 作为加载态**

`Checkboxes/elijahgummer_hard-liger-45.html`：`transform: skew(-13deg) translateX(-110% → 110%)`，
白色 30% 条，1s ease。原生版用 `setClipToOutline(true)` + `Outline`（**API 21**）：

```java
card.setClipToOutline(true);
View shine = new View(c);
shine.setBackgroundColor(0x33FFFFFF);
// ValueAnimator 驱动 shine.setTranslationX(-w → +w)，1000ms，LinearInterpolator，循环
```

> 已实施：见 `Loading.java`（用 `ObjectAnimator` + `ValueAnimator.INFINITE`，
> 并在 `Handle.stop()` 里显式 `cancel()`）。

### 明确不适合本项目的

| 手法 | 为什么不行 |
| --- | --- |
| `filter: drop-shadow()` / SVG `feGaussianBlur` 内阴影 | Android 无对应；`RenderEffect` 是 API 31+ |
| `backdrop-filter: blur()` 毛玻璃 | pre-31 没有便宜的后景模糊 |
| `transform-style: preserve-3d` 多层 `translate3d` z 分层 | `setCameraDistance` + `rotationY` 只能拿到 80% |
| `::before/::after` + `content: attr()` 伪元素 | Android 需要真实 View，成本翻倍 |
| 列表项的常驻 `float` / `pulse` 环境动画 | 耗电 + jank |
| 游戏化 "Level Up!" 通知、像素字体、`blink` | 与课堂工具调性不符 |
| `font-size: calc(...)` 流式字号 | Android 用 `sp` + 分配置资源 |
| 承载 3000+ 组件的**具体组件样式** | 碎片化、无 token、观感不统一。**只取手法，不取皮。** |

---

## 4. 落地优先级

### 第一批（已完成）

1. `Ui.java` 补 `EASE_STANDARD` / `EASE_BACK` / `DUR_FAST|BASE|SLOW`，
   `Tip` 和 `ThemeSwitch` 的 `DecelerateInterpolator` 全换掉并复用同一实例〔galaxy〕
2. `Ui.pressScale(View)`，挂到 `baseButton` / `iconTextButton`〔galaxy〕
3. 按钮 `R_FULL` → `R_S`（8dp），chip/tab/FAB 保留全圆〔Notion〕
4. `CourseActivity` 正文行高 `dp(3)` → `dp(5)`（≈1.43 → 1.57）〔Notion〕
5. 录音按钮 `tonal` → 新增的中性样式 `4`〔Cal.com〕
6. `ThemeSwitch.animateThenToggle` 的 `postDelayed(DUR-40)` → `onAnimationEnd`〔修正竞态〕

### 第二批（进行中）

7. **联网加载态**：`Loading.java` + 挂进 `aiSummary` / `transcribeThenNote`〔galaxy shimmer〕**已完成**
8. `MenuMorphView`（约 45 行）替换更多按钮〔morphicons 方案 B〕
9. `ic_chevron_down.xml` + noteRow 的 180° 旋转展开指示〔morphicons 方案 C〕
10. `renderTabs()` 改成 Cal.com 的 `nav-pill-group` 分段控件〔Cal.com〕
11. `ic_pin_filled.xml` + 置顶/星标的「先翻后填」两段动画〔galaxy〕
12. `Dialogs.sheet()` 进入动画用 sheet 自身高度 + `EASE_STANDARD`，补退出动画，
    容器加 Notion level-4 阴影〔Notion〕
13. `Ui.tintOf()/deepOf()` + 课程色点 tint 化，解决用户自选色不可读〔Notion〕

### 第三批（需单独排期）

14. `RecordMorphView`：mic→stop 参数化形变（约 120 行）〔morphicons 方案 A〕
15. 录音三圈错相脉冲〔galaxy〕
16. noteRow 操作按钮的「图标 → 图标+标签」展宽动画〔galaxy〕
17. `colors.xml` 补 `text_body` / `text_tertiary` / `text_faint`（含 dark 镜像）
    + `dark_hairline #2E2F35`，逐处按 §1 的表验收对比度〔Notion + Linear〕
18. `ThemeSwitch` 升级为「光线收拢 + `Path.op(DIFFERENCE)` 切月牙」〔morphicons〕

### 明确不要做

引入任何库/依赖（含 `androidx.dynamicanimation`、Lottie、ShapeShifter 运行时）；
用 `Path.interpolate`（API 34）或 `Path.approximate`（API 26，minSdk 22）；
照搬 galaxy 的玻璃态/滤镜/3D 拟物；把列表项做成常驻动画；
引入 `DESIGN.md` 的 YAML 格式（已有 `colors.xml` + `Ui.java` 这套等价物）。

---

## 附：API 等级核对表

来源：`sdk/platforms/android-36/data/api-versions.xml`（逐条核对，非凭记忆）

| API | since | 用途 |
| --- | --- | --- |
| `android.view.animation.PathInterpolator`（类） | **21** | 全部 CSS 缓动照抄 |
| `AnimatedVectorDrawable`（类） | **21** | 备选 AVD 形变路线 |
| `ObjectAnimator.ofArgb` | **21** | focus 描边变色 |
| `View.setClipToOutline` / `setElevation` / `setTranslationZ` | **21** | shimmer 裁剪 / 阴影 |
| `TextView.setLetterSpacing` | **21** | eyebrow 正字距 |
| `Path.op(Path,Path,Op)` | **19** | 月牙裁剪 |
| `ViewPropertyAnimator`（类）/ `withEndAction` / `setCameraDistance` / `setRotationY` | 12 / **16** / 12 / **11** | 链式动画 / 结束时切主题 / 翻转 |
| `AnimatorSet` / `TimeInterpolator` / `ObjectAnimator` / `ValueAnimator`（类） | **11** | 两段式动画 / 自定义弹簧 |
| `PathMeasure.getPosTan` / `getSegment` | **1** | 仅在真形变方案里需要 |
| ⚠️ `ValueAnimator.areAnimatorsEnabled()` | **26** | 需 `Build.VERSION.SDK_INT >= 26` 守卫；22–25 走 `Settings.Global.ANIMATOR_DURATION_SCALE` |
| ⚠️ `Path.approximate(float)` | **26** | 同上，且仅在真形变方案里需要 |
| ❌ `Path.interpolate(Path,float,Path)` | **34** | **不可用**（minSdk 22） |

---

## 出处

- [VoltAgent/awesome-design-md](https://github.com/VoltAgent/awesome-design-md) —
  [Notion](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/notion/DESIGN.md) ·
  [Linear](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/linear.app/DESIGN.md) ·
  [Cal.com](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/cal/DESIGN.md) ·
  [Superhuman](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/superhuman/DESIGN.md)
- [guillermolg00/morphicons](https://github.com/guillermolg00/morphicons) —
  [README](https://github.com/guillermolg00/morphicons/blob/main/README.md) ·
  [CLAUDE.md](https://github.com/guillermolg00/morphicons/blob/main/CLAUDE.md) ·
  [`src/core/plan.ts`](https://github.com/guillermolg00/morphicons/blob/main/src/core/plan.ts)
- [uiverse-io/galaxy](https://github.com/uiverse-io/galaxy) — 引用片段：
  `Checkboxes/gharsh11032000_nasty-panda-23.html` · `Checkboxes/elijahgummer_hard-liger-45.html` ·
  `Checkboxes/csemszepp_unlucky-mole-23.html` · `Tooltips/csemszepp_ordinary-owl-54.html` ·
  `Tooltips/elijahgummer_slimy-bobcat-84.html` · `Tooltips/Mohammad-Rahme-576_hard-starfish-64.html` ·
  `Inputs/alexruix_jolly-emu-80.html` · `loaders/Shoh2008_quick-fox-3.html` ·
  `Notifications/Fujitawa_slimy-vampirebat-16.html` · `Notifications/MijailVillegas_swift-fireant-79.html`
- [AnimatedVectorDrawable 参考](https://developer.android.google.cn/reference/android/graphics/drawable/AnimatedVectorDrawable) ·
  [PathParser 参考](https://developer.android.google.cn/reference/androidx/core/graphics/PathParser)
  —— pathData 形变的命令结构一致性要求
- 本地：`sdk/platforms/android-36/data/api-versions.xml`（API 等级核对）