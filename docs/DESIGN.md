# 课堂笔记 · DESIGN.md

> 本项目的前端设计系统文档。学习 [VoltAgent/awesome-design-md](https://github.com/VoltAgent/awesome-design-md)
> 中 Notion / Apple / Linear 三家的 DESIGN.md 后为本 App 定稿。改任何界面先读这份。
>
> token 落地位置：颜色在 `src/res/values/colors.xml`（`light_*` / `dark_*` 成对），
> 取色统一走 `Ui.tone()`；字号/圆角/间距在 `Ui.java` 常量。

## 原则（三家共识，按对本 App 的影响排序）

1. **层级靠表面阶梯，不靠阴影。** 卡片是平的：`surface`（页面底）→ `surface_container`
   （卡片）→ `surface_container_high`。阴影只给真正浮在内容上的东西（FAB 6dp、对话框）。
2. **单一强调色。** `primary`（靛蓝）只用于：主 CTA、选中态、品牌标志、链接。
   不做大面积铺色，不做第二强调色。语义色只有 error/success 两个。
3. **墨色不是纯黑。** 浅色正文 `#37352F`（Notion 暖炭），深色正文 `#E9E9E7`。
   次级文字永远用 `on_surface_variant`，不手工调透明度。
4. **正文是「读」的字号。** 编辑器正文 17sp、行距 +5dp；重点 16sp。UI 文字
   （列表、标签）14-15sp。标题在排版主视觉处用大字（首页顶栏 28sp）。
5. **矩形几何。** 按钮 8dp（`R_S`）、卡片 12dp（`R_M`）、胶囊（`R_FULL`）只给
   分段控件和药丸提示。不要把按钮做成胶囊。
6. **无字段标签的表单靠留白与字号区分**（笔记编辑页）：标题大字 → 弱化日期 →
   正文 → 重点，全程不画分隔线、不写「标题：」「正文：」。
7. **按压反馈 = scale(0.96) + ripple**（`Ui.pressScale`），全 App 唯一按压语言。

## 表面阶梯（浅色 / 深色）

| token | 浅色 | 深色 | 用途 |
| --- | --- | --- | --- |
| surface | `#FAFAF8` | `#171717` | 页面底 |
| container_lowest | `#FFFFFF` | `#121212` | 卡片内白 |
| container_low | `#F4F3F0` | `#1E1E1E` | —— |
| container | `#F1F0EE` | `#232323` | 卡片、统计卡、输入框底 |
| container_high | `#EAE8E4` | `#2C2C2C` | 分段控件选中 |
| container_highest | `#E3E1DD` | `#363636` | 顶栏圆形按钮底 |
| outline_variant | `#D6D3CD` | `#3D3D3B` | 输入框边、明确边界 |
| hairline | =outline_variant | `#2E2E2C` | 卡片分隔（深色专用更暗一档） |

## 字号阶梯

| token | 值 | 用途 |
| --- | --- | --- |
| T_DISPLAY | 26sp | 编辑页标题、首页大标题（+2 = 28sp） |
| T_HEADLINE | 20sp | 内页顶栏标题 |
| T_TITLE | 16sp | 列表行主文字 |
| T_BODY | 14sp | 正文 UI（编辑器正文例外：17sp） |
| T_LABEL | 12sp | 元信息、日期、辅助说明 |

## 组件约定

- **课程卡**：`surface_container` 底 + 12dp 圆角 + 左缘 4×26dp 课程色条
  （Notion 数据库色条式）+ 按压回弹。无阴影、无边框。
- **输入框（表单类）**：`surface_container` 底 + 1dp `outline_variant` 边 + 8dp 圆角。
- **输入框（编辑器）**：无边框无底色（`setBackground(null)`），靠 hint 与留白。
- **底栏/顶栏分隔**：1dp hairline；编辑页顶栏不画（沉浸）。
- **空状态**：图标垫 8% 主题色 + 大字标题 + 弱化说明（`Ui.emptyState`）。
- **成功/错误提示**：底部药丸（`Tip`），成功 = inverse 底 + success 绿勾。
