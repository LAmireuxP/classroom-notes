# 课堂整理 v2.0 —— 原生重构版

## 这是什么

对原版 `classroom.apk`（Capacitor/WebView 壳）的**从零原生重写**。

| 项目 | 原版 | v2.0 |
|---|---|---|
| 技术栈 | Capacitor + WebView + 前端 JS | **原生 Java + Android SDK** |
| 数据存储 | localStorage（JSON 整存整取） | **SQLite**（增量读写 + 索引） |
| 语音识别 | Web Speech API（**WebView 不支持 → 功能废掉**） | **系统 SpeechRecognizer** ✅ |
| 录音 | MediaRecorder（WebView 受限） | **原生 MediaRecorder** ✅ |
| 导入导出 | `a.download`（**WebView 里点了没反应**） | **SAF 系统文件选择器** ✅ |
| APK 体积 | 3.7 MB | **211 KB**（↓ 94%） |
| 第三方依赖 | Capacitor + 大量 androidx | **零依赖** |

## 已修复的原版 bug

1. **录音功能完全失效** —— 原版用 `webkitSpeechRecognition`，Android WebView 无此 API，`initRec()` 恒返回 false。→ 改用系统原生语音识别。
2. **导出备份/Markdown 无反应** —— WebView 未注册 `DownloadListener`，blob 下载静默失败。→ 改用 SAF，真正写入用户选定路径。
3. **导入备份失效** —— 无 `@capacitor/filesystem` 插件，file chooser 是空壳。→ 改用 `ACTION_OPEN_DOCUMENT`。
4. **AI 结果 JSON 解析崩溃** —— 模型多输出一句话就抛异常。→ 三级容错：去围栏 → 直解 → 截取 `{...}`，全失败则退化为文本 + 本地关键词提取。
5. **搜索框"清除"按钮常驻** —— `[hidden]` 被 `display:inline-flex` 覆盖。→ 原生实现无此问题。
6. **sw.js / manifest.webmanifest 缺失** —— 注册必然 404。→ 原生架构不需要。
7. **`android:debuggable=true` 打进正式包** —— 安全风险。→ 正式签名，debuggable 关闭。
8. **明文 HTTP 被系统拦截** —— 无法连本地 whisper 服务。→ 加 `usesCleartextTraffic`。
9. **搜索状态跨课程残留** —— 切课程后搜索词未清。→ 每个页面独立状态。
10. **重复 INTERNET 权限声明**。→ 清理。

## 架构

```
java/com/lamireuxp/classroom/
├── MainActivity.java    首页：课程列表 / 搜索 / 统计 / 导入导出
├── CourseActivity.java  详情：笔记 / 待办 / 录音 / AI 总结
├── Db.java              SQLite 数据层（3 表 + 索引）
├── Net.java             网络层：AI 总结 + 云转写（HttpURLConnection）
├── SpeechSession.java   语音：系统识别 + 原生录音 + 自动重连
├── Backup.java          JSON 备份 / Markdown 导出 / SAF 读写
├── Extract.java         本地关键词提取（AI 兜底）
├── Prefs.java           设置存储
├── Dialogs.java         统一弹窗
├── Ui.java              UI 构建工具（含主题色自动映射）
├── Icons.java           图标构建
├── Tip.java             轻量 Toast
├── Dates.java           日期工具
└── Id.java              ID 生成
```

## 功能清单

- ✅ 课程管理（增删改、7 色轮转、教师）
- ✅ 笔记（标题/日期/内容/重点、置顶、展开、搜索）
- ✅ 待办（优先级、截止日、完成勾选、分组）
- ✅ 录音（系统实时识别 + 可选云转写兜底）
- ✅ AI 总结（OpenAI 兼容接口，结果可一键应用到笔记）
- ✅ 统计（每课程笔记数 / 完成率，可视化进度条）
- ✅ 导出 JSON 备份 / Markdown
- ✅ 导入 JSON 备份
- ✅ 浅色 / 深色主题（App 内独立开关）

## 语音转写模式

| 模式 | 说明 |
|---|---|
| `off` | 仅用系统语音识别（免费、实时、离线触发） |
| `server` | 自建服务（whisper.cpp / Ollama），填 `http://x.x.x.x:8080/v1` |
| `api` | 云 API（OpenAI 兼容），填 Key |

> 原版的 `embed` 模式（浏览器内跑 HuggingFace 模型）已按要求移除 —— 原生环境无此条件。

## 构建方式（设备本地，无需 Android Studio）

本机仅需 JDK 17，工具链从 Google Maven / dl.google.com 获取：

```bash
bash build.sh    # 7 步：aapt2 compile → link → javac → d8 → 打包 → 签名
```

产物：`/sdcard/Download/Classroom综合-v2.0.apk`

## 签名信息

- keystore：`/tmp/tools/release.keystore`
- storepass / keypass：`classroom2026`
- alias：`classroom`
- 有效期：30 年

> ⚠️ 请自行保存 keystore，后续升级必须用同一签名。