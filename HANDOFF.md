# 交接文档

> 写于 1.8 发版后。记录 1.7 → 1.8 期间的全部改动，1.6 → 1.7 与更早的段落保留作历史。
> README 是长期的用户/开发者文档；本文是**时点性**的交接记录。

## 当前状态速览

| 项目 | 值 |
| --- | --- |
| 版本 | 1.8 / versionCode 14 |
| 代码量 | 11,088 行 Java，32 个文件 |
| APK | 175,756 B（172 KB），签名指纹不变 |
| dist | `dist/classroom-1.8.apk` 已放入 |
| 设备测试 | Redmi M2007J3SC（Android 17）——色板/编辑页/待办增强均真机过，DB v5→v6 升级正常 |

## 1.7 → 1.8 做了什么

### 1. 界面整体重构（docs/DESIGN.md 设计系统）

学习 [awesome-design-md](https://github.com/VoltAgent/awesome-design-md) 里 Notion /
Apple / Linear 三家的 DESIGN.md，为本 App 定稿 `docs/DESIGN.md`（七条原则 + token 表），
然后按文档重构：

- **色板换血**（colors.xml 全量重写）：浅色 = Notion 暖纸系（`#FAFAF8` 暖白底、
  `#37352F` 暖炭字、暖灰表面阶梯）；深色 = 中性深灰 `#171717`（Notion 深色 +
  Linear「不用纯黑」）。primary 收敛成靛蓝 `#4353E8`，只给主操作/选中/品牌标识。
- **去阴影改表面阶梯**：课程卡（1.5dp）、统计卡（1dp）、分段控件选中态（1dp）的
  阴影全去掉——层级靠 `surface` → `surface_container` 色阶表达；阴影只留 FAB（6dp）
  与对话框。三家共识：「卡片是平的，色阶即层级」。
- **首页顶栏改大标题式**：动作行（logo + 主题开关 + 设置）+ 28sp 大字标题两行结构
  （Apple Notes 排版）。课程卡色点改 Notion 式左缘 4×26dp 色条。
- **圆角收敛**：卡片 16dp → 12dp（`R_M`），按钮保持 8dp。

### 2. 笔记编辑页推倒重做（沉浸式）

从「标签 + 输入框」表单结构（继承 BaseSettingsActivity）改为自建沉浸布局
（直接继承 BaseActivity）：标题 26sp 大字直接写、日期弱化小字点选、正文 17sp
宽行距铺满、重点/配图靠留白分隔（全程无分隔线无标签）；底部工具栏（日期/重点/
图片直达）+ 顶栏 ✓ 保存。数据层（Note 字段、Db.saveNote）未动。

### 3. 待办功能详细化（DB v5→v6）

- **可编辑**：TodoEditorActivity 加 `todoId` extra，课程页与全部待办页点行进编辑
  （原来建错只能删了重来）。编辑保存后 `Reminders.schedule` 重排闹钟。
- **备注**（`note` 列）：作业要求、考试范围；课程页/全部待办页行上露一行摘要。
- **完成时刻**（`done_at` 列）：`setTodoCompleted` 勾选时记录、取消清零；
  行副标题与编辑页显示「完成于 X」。
- 全部待办页行点击从「跳课程页」改为「直接进编辑」（编辑页字段完整，少两层）。
- Backup 导出/导入带 note/doneAt。

### 已知问题（1.8 时点）

见下方「已知问题 / 技术债」总表（新增：笔记分享不带配图）。

## 1.6 → 1.7 做了什么

### 1. 备份纳入配图（备份格式 .json → .zip）

1.6 加了笔记配图，但 JSON 备份只覆盖文本数据——换机/重装后笔记内容回来了，
配图全丢。1.7 把备份从纯 `.json` 改成 `.zip`：包内 `data.json`（仍是
classroom-v2 格式，与网页版互通）+ `note-img/` 下被引用的配图文件。

- **导出**（`Backup.writeZip`）：JSON 里给 note 加 `images` 字段（存**相对文件名**
  basename，设备无关），再只把被引用的图片文件打进 zip 的 `note-img/` 下。孤立
  文件（笔记已删但文件残留）不带——既缩小体积，也避免导入时复活垃圾。
- **导入**（`Backup.importBackup`）：按文件头判断格式——`PK` = zip，`{` = 旧 json。
  zip 先解图片到 `filesDir/note-img/`，再解 `data.json` 走原 `importJson`；
  旧 `.json` 走老路径（无配图，数据完整）。
- **路径转换**：DB 里 `Note.images` 继续存绝对路径（读图代码 `decodeFile` 等
  不用改）；备份 JSON 里存 basename；导入时 `resolveImagePaths` 把 basename 拼回
  当前设备的绝对路径——换机后路径自动对上。
- **路径穿越防护**：解 zip 时只取 basename，拒绝 `..`。
- **SettingsActivity**：「导出 JSON 备份」/「导入 JSON 备份」改成「导出备份」/
  「导入备份」；导出 MIME `application/json`→`application/zip`、扩展名
  `.json`→`.zip`；导入不再先读文本，确认后直接 `importBackup(uri)`（zip 不能
  读成文本）。
- **Markdown 导出不变**：仍是纯文本，不含图片。

### 2. 课程归档

上完的课退出首页但数据保留。课程菜单加「归档课程」；首页列表末尾有
「已归档课程 · N」入口（只在有归档课时出现），进 `ArchivedCoursesActivity`
（新页面，复用 BaseSettingsActivity 骨架）翻旧课、一键恢复。

- **数据层 DB v4→v5**：courses 加 `archived` 列（ALTER TABLE 就地升级，不删表）。
  与 `deleted_at`（回收站）正交——归档的课也能被删进回收站，恢复后仍是归档态。
- **口径统一**：归档课退出**所有活跃视图**——首页列表（`courses()`）、跨课程搜索
  （`searchNotes`）、统计卡（`totals` / `todoByPriority`）、「全部待办」（`openTodos`）、
  提醒（`pendingReminders` JOIN courses 过滤）。`course(id)` **不**过滤——归档课
  要能从归档列表/通知跳转打开。
- **提醒**：归档时 `Reminders.cancelCourse` 撤掉该课全部闹钟；恢复时
  `rescheduleAll` 把还没过期的补回来（`pendingReminders` 已过滤归档课，重排天然
  不含它们）。
- **备份**：导出/导入带 `archived` 字段。注意导出**改用 `allCourses()`**（含归档）——
  原来的 `courses()` 现在只返回活课，导出要是没跟着换，归档课就会在备份里静默消失。
  Markdown 导出同样含归档课（它是完整学习记录）。
- **不弹确认**：归档无损且随时可逆，确认框只会让人犹豫——和删除（动作语义更重）
  区别对待。
- **新图标**：`ic_archive`（Material archive 盒子），菜单行与归档页空状态共用。

### 3. 兼容性

- 旧 `.json` 备份（1.6 及之前，无 images 字段）：导入时 `optString` 返回空，
  笔记无配图，数据完整。
- 新 `.zip` 备份：导入时解图片 + data.json，配图回来。
- zip 里的 data.json 仍是 classroom-v2 格式——手动解 zip 拿 data.json 仍能与
  网页版互通。
- 1.6 的 App 导入不了 1.7 导出的 zip（1.6 的 importJson 读文本，zip 是二进制
  会报错）——但 1.6 用户升到 1.7 后能导。

### 4. 真机验证记录（1.7 发版时）

Redmi M2007J3SC（Android 17）实测通过：DB v4→v5 迁移正常、zip 备份导出导入、
归档/恢复、1.6 老数据直接覆盖升级。以下为当时写下的测试清单，留作参考：

<details>
<summary>原始清单</summary>

只编译通过。逻辑层面：类型对、异常处理到位、路径转换自洽。但 zip 的读写、
SAF uri 在确认框回调里延迟读、图片文件落盘时机，这些都需要真机跑一遍才能
放心。归档这边同样：DB v4→v5 迁移（老库升级不能丢数据）、归档/恢复后的列表
刷新、恢复后提醒是否真的补回来，都要真机过一遍。

**发版前必做**：
1. 备份链路：导出 zip → 卸载重装 → 导入 zip → 看配图是否回来
2. 归档链路：归档一门课 → 看首页/搜索/统计/全部待办都少了它 → 恢复 →
   看它回到原位、带提醒的待办闹钟是否恢复
3. 老库升级：1.6 的数据直接装 1.7 → 确认课程笔记待办原样都在

</details>

只编译通过。逻辑层面：类型对、异常处理到位、路径转换自洽。但 zip 的读写、
SAF uri 在确认框回调里延迟读、图片文件落盘时机，这些都需要真机跑一遍才能
放心。归档这边同样：DB v4→v5 迁移（老库升级不能丢数据）、归档/恢复后的列表
刷新、恢复后提醒是否真的补回来，都要真机过一遍。

**发版前必做**：
1. 备份链路：导出 zip → 卸载重装 → 导入 zip → 看配图是否回来
2. 归档链路：归档一门课 → 看首页/搜索/统计/全部待办都少了它 → 恢复 →
   看它回到原位、带提醒的待办闹钟是否恢复
3. 老库升级：1.6 的数据直接装 1.7 → 确认课程笔记待办原样都在

## 1.5 → 1.6 做了什么

### 1. 新建/编辑笔记、新建待办 → 独立页面

原来用 `Dialogs.form` 弹窗。改成了 `NoteEditorActivity` 和 `TodoEditorActivity`，
继承 `BaseSettingsActivity`（复用顶栏 + 滚动正文 + 底部保存按钮的骨架）。

- 笔记编辑器：`courseId` + 可选 `noteId`（有=编辑、无=新建）通过 Intent extras 传入
- 待办编辑器：`courseId` 必填；截止日期改成了日期选择器（不再手输日期字符串）
- 保存后 `finish()`，`CourseActivity.onResumed()` 自动 `renderTabs()` + `renderContent()`
- `CourseActivity` 删掉了旧弹窗代码约 227 行（noteDialog / todoDialog / TodoForm /
  renderTodoForm / remindRow / pickRemind / saveTodo / ensureNotifyPermission /
  formLabel / formField）
- `editCourse()` 仍是弹窗（只有两个字段，弹窗够用），`DialogHost` 实现保留

### 2. 笔记配图

每条笔记可以挂若干张图片（拍板书、截 PPT）。图片从相册选入后复制到 app 内部
`filesDir/note-img/`（长边压到 1080px、JPEG 85%），文件路径以分号分隔存在
`Note.images` 字段（新列，DB v3→v4 迁移）。

- **编辑页**：图片区块——横排缩略图（可逐张删除）+ 添加按钮，走系统相册选择器
- **课程页展开**：正文与重点之后纵向展示配图（长边 720px 采样解码）
- **删除清理**：`purgeNote` 和 `emptyTrash` 在删库之前先查出 images 字段、删配图文件
- **备份不含图片**：JSON 备份只覆盖文本数据（图片是文件，不在 DB 里）

### 3. 笔记分享

展开笔记的操作行加「分享」（在置顶和编辑之间），用系统 `ACTION_SEND` 把
标题 + 日期 + 正文 + 重点清单作为纯文本发到微信 / QQ / 蓝牙等任何接受
纯文本的应用。

### 4. 拖动排序推倒重做

1.5 的拖动走的是框架 `startDragAndDrop` 路线，真机上三个问题叠在一起：

1. **起拖即崩**：拖动阴影的尺寸取不到，系统抛「Drag shadow dimensions must be positive」
2. **拖到列表末尾松手落不了位**：被拖的行隐藏后列表缩短，指针已在容器之外，系统按「取消」处理
3. **待办行完全拖不动**：行本身没有点击行为，按下时触摸流被 ScrollView 截走

重写为 `DragSort.Layout`（extends LinearLayout）自己拦截触摸流：

- **长按起拖**：按下后起一个系统长按时限的定时器，期间手指滑出回弹阈值就当作滚动
- **浮动卡片**：截成位图挂到窗口层（android.R.id.content），垫卡片底 + 海拔阴影
- **占位空档**：原行就地转 INVISIBLE（不是 GONE）——列表总高不变、空档看得见
- **槽位判定**：指尖压在哪 个槽位上，空档就挪到哪（含组首尾吸附）
- **同组约束**：笔记的置顶/未置顶、待办的进行中/已完成各自成组，空档不跨组
- **边缘自动滚动**：拖到可视区上下边缘 56dp 内按帧步进滚动
- **松手落位**：浮动卡片吸附回空档（150ms），原行就地恢复，落库顺序从视图序列现读
- **无「取消」路径**：在哪儿松手都按落位处理，系统回收触摸流（ACTION_CANCEL）也一样

同时：首页课程列表也接入了拖动（`Db.reorderCourses`，数据层 sort 字段当初就留好了）；
`todoRow` 补了 `setClickable(true)`——不可点击的行收不到触摸流。

### 5. 界面细节

- 首页课程列表改卡片式（surfaceContainer + 16dp 圆角 + 1.5dp 浮起 + 按压回弹）
- 列表入场从「拍上来」变成淡入 + 上移的「浮入」（错峰 30ms，封顶 180ms）
- 顶栏补一条极细分隔线（`Ui.topBarHairline`，深色下用 dark_hairline）
- 搜索框带清除按钮（尾部 ×，有文字时淡入）
- 空状态图标垫 8% 主题色（比纯灰更有品牌感）
- 首页课程数与笔记数统一成同一段弱化文字（原先一个药丸一个裸字）
- 待办截止日期、笔记日期都改成日期选择器（不再手输日期字符串）
- 笔记编辑页的正文区域用 weight 撑满可用空间（原来是固定 maxLines）

### 6. 修复

- **回收站操作后页面空白**：清空 / 彻底删除 / 恢复的代码顺序是 `render(); buildUi();`，
  `buildUi()` 会重建整个内容视图、把 render 画好的东西丢掉且自身不渲染——1.5 就有的
  bug。改为 `buildUi(); render();`
- **待办拖动失效**：`todoRow` 没有点击行为，DOWN 穿透到 ScrollView，容器不在派发链上。
  补 `setClickable(true)`
- **DragSort 起拖崩溃**：`startDragAndDrop` 的 DragShadowBuilder 给不出尺寸就抛异常
  （1.5 的旧代码就崩，未上线）。重写后不再用框架拖动，此问题不复存在

### 7. 代码层重构

- **BaseActivity**：主题生命周期收进基类（`onCreateUi` / `onRebuildUi` / `onResumed` /
  `deferThemeRebuild` 四个钩子）。五个界面 + BaseSettingsActivity 各自复制的
  「setTheme 时机 / renderedDark 跟踪 / 跟随系统重建」样板删掉。顺带修掉
  CourseActivity 漏跟踪 renderedDark 的问题
- **reapplyTheme 模板方法**：窗口层重着色统一处理，设置页改主题、顶栏开关、
  系统切换三条入口效果一致
- **重复代码收敛**：`Db` 三个 reorder 合一；`Ui.emptyState` / `Ui.nz` 收编；
  `DragSort` 行成员判断 List.contains → HashSet
- **搜索栏只建一次**：从 refresh() 挪进 buildUi()——增删课程不再丢焦点/键盘

## 架构决策（为什么这样写）

### 为什么拖动不用框架的 startDragAndDrop

`startDragAndDrop` 三个坑是实测踩出来的：阴影尺寸崩溃、列表缩短后拖到底落不了位、
事件派发不受控。自己拦截触摸流全程可控。

### 为什么空档用 INVISIBLE 不用 GONE

GONE 不占空间 → 列表缩短 → 指针拖到底部松手时已经在容器外 → 系统按取消处理。
INVISIBLE 占着原尺寸 → 列表不缩 → 指针怎么拖都在容器里 → 落位有保障。

### 为什么 DragSort 需要行可点击

Android 的触摸事件派发：`onInterceptTouchEvent` 只在「触摸目标存在」时才被咨询。
行不可点击 → DOWN 穿透到 ScrollView → 容器不在派发链上 → 容器的拦截逻辑永远不跑。
笔记行有展开点击、课程卡有进入点击，天然可点；待办行没有行级动作，
必须显式 `setClickable(true)` 把流留在链上。

### 为什么 BaseActivity 的 reapplyTheme 是模板方法

设置页选主题、顶栏开关、跟随系统三条入口都要做「窗口层重着色 + 内容重建」。
窗口层统一在基类处理，子类只覆写 `onRebuildUi()` 做内容重建——三条入口不会漂移。

### 为什么删除了 ids.xml

里面的 `dragsort_bg` 是 1.5 旧版拖动实现存背景用的 `setTag(int)` 键，
重写后不再引用。

## 已知问题 / 技术债

| 问题 | 严重程度 | 说明 |
| --- | --- | --- |
| 笔记分享不带配图 | 低 | 分享是纯文本（标题+日期+正文+重点），配图是文件，`ACTION_SEND` 文本通道带不了；要带图得走 MULTIPLE + 流，后续可做 |
| DragSort 在极长列表（100+ 行）中的性能 | 低 | `rowAtSlot` / `moveGap` 遍历子 View 是 O(n)，`HashSet.contains` 是 O(1)，总体 O(n)——几十行没问题，100+ 行的列表拖动可能微卡 |
| `formLabel` 在两个编辑器里各有一份 | 低 | `BaseSettingsActivity.labeledField` 覆盖了单行场景，多行字段的标签暂由子类自绘；如果要消除重复可在基类加 `labeledMultilineField` |
| 音频录制在鸿蒙 NEXT 上的兼容性未知 | 信息 | `MediaRecorder` / `SpeechRecognizer` 是 Android API，在 HarmonyOS NEXT 上不可用。如果目标设备是鸿蒙 NEXT，录音功能需要用鸿蒙的 API 重写 |

## 构建 & 发版步骤

### 构建

```bash
./build-pc.sh          # 产物 build/课堂笔记-v$VER.apk
```

依赖：JDK 17 + Android SDK build-tools 34 + platforms;android-34。
SDK 路径默认 `/d/Zcode/android-sdk`，可用 `ANDROID_SDK` 环境变量覆盖。

### 发版流程

1. 改 `build-pc.sh` 里的 `VER=` **和** `src/AndroidManifest.xml` 里的
   `versionCode=` / `versionName=`（两处必须一起改）
2. `./build-pc.sh` 构建
3. 核对签名指纹：`apksigner verify --print-certs build/课堂笔记-v*.apk`
   （应恒为 `32:A8:95:D3:…:E8:B0`）
4. 复制到 `dist/classroom-v$VER.apk`
5. README：更新日志加新段、「当前版本」与下载链接指向新版本
6. `git add -A && git commit -m "release: $VER" && git push`

### 真机测试

```bash
adb install -r build/课堂笔记-v1.6.apk
```

测试设备：Redmi M2007J3SC（Android 17 / HyperOS 4）。

## 关键文件索引

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `BaseActivity.java` | 97 | 所有 Activity 的基类：主题应用 + 深浅切换重建 |
| `BaseSettingsActivity.java` | 270 | 设置类页面的骨架：顶栏 + 滚动正文 + 保存按钮 |
| `DragSort.java` | 380 | 长按拖动排序（触摸流拦截 + 浮动卡片 + 空档让位） |
| `NoteEditorActivity.java` | 225 | 笔记新建/编辑页（含配图管理） |
| `TodoEditorActivity.java` | 230 | 待办新建页（含日期选择器/优先级/提醒） |
| `CourseActivity.java` | ~1300 | 课程详情页（笔记+待办列表+录音+AI 总结） |
| `MainActivity.java` | ~700 | 首页（课程列表+搜索+统计+导入导出） |
| `Db.java` | ~950 | SQLite 数据层（含配图文件管理） |
| `Backup.java` | 443 | JSON/zip 备份导入导出（1.7 起 zip 含配图 + archived 字段） |
| `ArchivedCoursesActivity.java` | 114 | 已归档课程列表（翻旧课 + 一键恢复） |
| `Ui.java` | ~800 | 设计系统（颜色/间距/控件/按钮/输入框） |

## 图标实验记录

试了 4 版图标最后保留原版（1.3/1.4 的鹿形图标），全部回退：

1. 渐变底白鹿（56fe64a）→ 用户觉得太花哨
2. 纯蓝底白鹿（3cc4a4d）→ 用户要白底
3. 白底蓝鹿（dd9985d）→ 用户不要鹿了
4. 白底蓝麦克风（f63c674）→ 用户觉得丑，要求还原原版（8b340e3）

`tools/icon-brand.py` 也已删除（b28708c）。`icon-backup-original/` 和
`icon-review/` 在 .gitignore 里，本地保留、不推 GitHub。

注意：`icon-backup-original/` 里的图标**不是** 1.5 发布的图标——它是 1.3 版的
老资产（未压缩、9441 B vs 1.5 的 3149 B）。还原图标时应该从 git 历史取
（`git checkout c9810c3 -- src/res/mipmap-*`），不要从 icon-backup-original/ 取。
