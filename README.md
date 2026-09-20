# 课堂笔记 · Android 原生版

课程笔记、待办、语音转写、AI 总结。纯原生 Java 实现，零第三方依赖，APK 约 175 KB。
当前版本 **1.0**。

## 功能

- **课程**：增删改查、自定义标识色，笔记与待办计数实时同步
- **笔记**：标题 / 日期 / 正文 / 重点清单，支持置顶、搜索、展开阅读
- **待办**：优先级与截止日期，自动按「进行中 / 已完成」分组
- **语音转写**：系统语音识别实时出字；设备不支持时自动把录音上传云端转写（OpenAI 兼容接口）
- **AI 总结**：接入任意 OpenAI 兼容服务，生成摘要与重点，一键回写笔记
- **备份**：JSON 完整导入导出（数据格式与网页版互通），另可导出 Markdown

## 下载

- 应用（国内可直连）：https://lamireuxp.github.io/classroom-notes/dist/classroom-1.0.apk
- 发布页：https://github.com/LAmireuxP/classroom-notes/releases

安装前请先卸载签名不同的旧版本；笔记数据用应用内「导出备份 / 导入备份」迁移。

## 致谢

本项目源自 **[@lkx478482771-star](https://github.com/lkx478482771-star)** 的开源项目「课堂整理」：
课程、笔记、待办的数据结构与整体交互设计都来自原作者，感谢他的开源分享。

本仓库是它在 Android 上的原生实现：v2.0 的原生重写（16 个 Java 文件、SQLite 存储、
系统 SpeechRecognizer、Material Design 3 界面）完整保留，本仓库仅做少量维护——
云转写上传的 MIME 修正、版本号 2.1、Windows 构建脚本 `build-pc.sh`。

## 构建

- Windows：`./build-pc.sh`（需 JDK 17 + Android SDK build-tools 34）
- 手机端原构建配方：`src/build.sh`（未改动）