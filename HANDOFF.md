# 交接文档

> 写于 1.6 发版后。记录 1.5 → 1.6 期间的所有改动、关键决策、已知问题和遗留事项。
> README 是长期的用户/开发者文档；本文是**时点性**的交接记录。

## 当前状态速览

| 项目 | 值 |
| --- | --- |
| 版本 | 1.6 / versionCode 12 |
| 代码量 | 10,366 行 Java，23 个文件 |
| APK | 163,417 B（159 KB），签名指纹不变 |
| dist | `dist/classroom-1.6.apk` 已放入 |
| Git | 1 个提交未推送（`60d00e9`），其余已推 origin/main |
| 设备测试 | Redmi M2007J3SC（Android 17），全程真机验证 |

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
| 笔记配图不含在 JSON 备份里 | 低 | 配图是文件不是 DB 记录，备份导入后图片丢失。后续可在 Backup.java 里加图片的 zip 打包 |
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
