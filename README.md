# 课堂笔记 · Android 原生版

课程笔记、待办、语音转写、AI 总结。纯原生 Java 实现，零第三方依赖，APK 约 187 KB。
当前版本 **1.2.2**。

## 致谢

本项目源自 **[@lkx478482771-star](https://github.com/lkx478482771-star)** 的开源项目「课堂整理」：
课程、笔记、待办的数据结构与整体交互设计都来自原作者，感谢他的开源分享。

本仓库是它在 Android 上的原生实现：v2.0 的原生重写（SQLite 存储、系统 SpeechRecognizer、
Material Design 3 界面）完整保留，并在此基础上继续维护——云转写上传的 MIME 修正、
Windows 构建脚本 `build-pc.sh`，以及 1.1 的语音识别修复与设置页重构。

## 功能

- **课程**：增删改查、自定义标识色，笔记与待办计数实时同步
- **笔记**：标题 / 日期 / 正文 / 重点清单，支持置顶、搜索、展开阅读
- **待办**：优先级与截止日期，自动按「进行中 / 已完成」分组
- **语音转写**：系统语音识别实时出字；设备不支持时自动把录音上传云端转写（OpenAI 兼容接口）
- **AI 总结**：接入任意 OpenAI 兼容服务，生成摘要与重点，一键回写笔记
- **备份**：JSON 完整导入导出（数据格式与网页版互通），另可导出 Markdown

## 下载

- 应用（国内可直连）：https://lamireuxp.github.io/classroom-notes/dist/classroom-1.2.2.apk
- 发布页：https://github.com/LAmireuxP/classroom-notes/releases

从 1.0 起各版本签名一致，可直接覆盖升级，笔记数据不会丢。

## 语音识别在各家 ROM 上的差异（小米 / OPPO / vivo…）

**系统语音识别能不能用，不只取决于麦克风权限。** 国产 ROM 的识别服务普遍加了一道
「跨应用放行」判定，小米就是 `AsrService` 里的 CTA / 机型白名单（实测日志）：

```
AsrService: handleCTAAndPerms: isCTAAllow=false isRecordPermGranted=true
                                               ↑ 麦克风权限是给足的
AsrService: onStartListening: isCTAAllow=false …
AsrService: onDestroy        ← 服务不放行，随即自毁，App 收到 ERROR_SERVER_DISCONNECTED
```

同版本 HyperOS 4 的两台机器实测结果就不一样：一台正常实时出字，一台三个键
（`xiaoai_cta_change` / `soundrecorder_cta_net_accepted` / `soundrecorder_cta_permission_accepted`）
都是「没同意过」的状态，识别直接被拒。**系统设置里没有能让第三方 App 通过的开关**，
只能去厂商那一侧同意一次：

| 厂商 | 同意入口 |
| --- | --- |
| 小米 / 红米（MIUI） | 小爱同学 → 我的 → 设置（隐私 / 跨应用语音识别）。MIUI 未实测，识别服务与判定机制同澎湃 OS |
| 小米 / 红米（澎湃 OS / HyperOS） | 同上，见下方实测说明 |
| OPPO / 一加 | 小布助手 → 设置 |
| vivo | Jovi 语音 → 设置 |
| 华为 / 荣耀 | 小艺 → 设置 |
| 其他 | 系统设置 → 语音输入（`android.settings.VOICE_INPUT_SETTINGS`） |

**澎湃 OS（HyperOS）实测补充**：设备上唯一的识别服务是
`com.xiaomi.mibrain.speech/.asr.AsrService`（`isDefault=true`，且机器上没有装任何其它
RecognitionService），所以 App 里"默认服务优先、显式组件兜底"的两条路都落在同一个服务上，
它不放行就没有第二条可走。同意状态记在 `Settings.Global` 的几个键上
（`xiaoai_cta_change`、`soundrecorder_cta_net_accepted`、`soundrecorder_cta_permission_accepted`），
**不同机型 / 不同 OS 小版本取值不一样，键名也可能随版本变动**，所以 App 不去读它们猜状态，
只在识别真的被拒时把用户送到语音助手那边。

同为 HyperOS 4 / Android 17（SDK 37）的两台机器实测结果就相反，可见这是**按设备**而不是按
ROM 版本的策略：

| 机型 | OS 版本 | 结果 |
| --- | --- | --- |
| Redmi M2007J3SC | OS4.0.0.7 | 实时识别正常出字 |
| Redmi 22127RK46C | OS4.0.0.26 | `isCTAAllow=false` 直接拒绝并自毁 |

App 里的对应行为：

- 识别被设备侧拦下（**一次回调都没给过就被拒**）时，提示条上的按钮是**「去授权」**，
  点了会打开上表里的语音助手（按包名依次尝试：`com.miui.voiceassist`、`com.heytap.speechassist`、
  `com.coloros.speechassist`、`com.vivo.voiceassist`、`com.vivo.ai`、`com.huawei.vassistant`、
  `com.hihonor.vassistant`、`com.meizu.voiceassist`、`com.samsung.android.bixby.agent`，
  最后兜底到系统「语音输入」设置页）。这些入口都写进了 manifest 的 `<queries>`——
  targetSdk ≥ 30 的包可见性过滤会让没声明的包解析不到，点了等于没点。
- 「拦下」这件事只记在**进程内**：这次运行里后续录音直接走纯录音 + 云转写，不再反复去戳
  识别服务；进程重启 / 升级 / 换机后会自动重新试一次，识别真的出过字也立刻解除标记。
  只记「零回调被拒」，偶发故障（出过回调之后才断）不记，下次照样重试。
- **无论哪种设备，录音都不会因为识别失败被丢掉**：识别用不了时会话降级成纯录音继续跑，
  结束后自动走云端转写。

兜底路线（任何设备都能用）：设置 → 语音转写设置 → 服务端转写 / API 直连，把录音上传到
自建的 whisper.cpp 或任意 OpenAI 兼容转写接口。

## 更新日志

### 1.2.2

- **重做轻提示**：原来每次操作反馈都是一条通栏色带（占满屏宽、8dp 直角、48dp 大方块关闭按钮），
  一句「课程已创建」也要占近 80dp 高的紫色横幅，又重又丑。改成**底部居中的状态药丸**：
  宽度跟内容走、16dp 圆角；成功 = 深色（深主题为浅灰）药丸 + 绿对勾，错误才整条用 error 色，
  颜色只花在该注意的地方；同屏只留一条，新的顶掉旧的，不再叠罗汉。
- **操作反馈统一成语义态**：所有「写入完成」提示（创建 / 更新 / 删除 / 保存 / 导入导出）统一走
  成功态绿勾，不再和中性信息混用一种样式。
- **修复课程菜单抽屉选完不关**：点「更多 → 删除课程」时，底部抽屉不关，确认框关掉后会露出一层
  内容对不上的 stale 抽屉。现在选中动作即收起抽屉。

### 1.2.1

这一版是**代码清理与写入路径优化**，没有改动任何界面与功能行为——你在 1.2 里看到的东西，
1.2.1 一模一样。

- **修复「按下缩放」会挤掉触摸监听**：`Ui.pressScale()` 原来用 `OnTouchListener` 实现按下缩放。
  Android 没有 `getOnTouchListener()`，谁先 `setOnTouchListener` 谁就永久占住这个位置，
  第二个想监听触摸的人只能把它顶掉——两个需求只能活一个。改成 `StateListAnimator`
  （挂在 `state_pressed` 上的系统正规机制），完全退出触摸链路，涟漪 / 点击 / 将来任何手势都不再冲突。
  行为肉眼无差别。
- **批量写入优化**：
  - 单条写入 `saveCourse/saveNote/saveTodo` 不再「先 SELECT 判存在」，
    改用 `update()` 返回的**受影响行数**判定（`rows == 0` 即不存在）。每条语句少一次查询，
    同时消除了「查完到写之间那行被删掉」的竞态。
  - 新增 `Db.replaceAll()`：导入专用，清空 + 重建全程一个事务，用预编译语句（`compileStatement`）
    批量写，且完全不查存在性（导入本就先清空，表里必然没有这些 id）。
  - `Backup.importJson()` 改为调用它。顺带**去掉了外层事务里嵌套内层事务**：
    `deleteCourse()` 自带事务，嵌在外层里一旦失败只回滚内层、外层照常提交，会留下半新半旧的库。
  - 实测（10 门课 / 400 条笔记 / 100 条待办）：**82.8 ms → 39.2 ms，约 2.1×**。
- **清理死代码**：删掉 23 个无调用点的方法（`Ui.success/warning/errorContainer/tertiary/color/label/
  surfaceLow/outlinedCard/roundStrokeRipple/outlinedButton/tonalButton/button/searchBar/chip/lpMargin/
  divider/gap/vSpace`、`Icons.textButton/tint`、`Dates.shortFromMillis`、`CourseActivity.textAction` ×2），
  以及未引用的 `res/drawable/ic_stats.xml` 和 `styles.xml` 里的 `TransparentDialog`，
  外加 10 条失效 import。净减约 124 行，APK 无功能变化。

### 1.2

- **修复录音丢失**：识别服务中途失效（被拒 / 连接断开 / 启动抛异常）时，会话降级成纯录音继续跑，
  已经录下的音频不再被删。原来这类错误一律按致命处理，调用方 `cancel()` 会把录音文件一并删除——
  识别出的毛病，赔进去的是用户整段录音。真机对照：旧版在识别被拒后 **93 毫秒**就 `MediaRecorder: stop+`
  并删文件，新版继续录到用户手动结束。
- **修复导入备份丢数据**：导入改成「先全量解析、再单事务清空重建」。损坏的备份文件不再清空已有数据
  （原来先删后写，文件里任意一条记录不合法，用户拿到的是「导入失败」加一个已经被清空的库），
  并校验 `format` 字段、修掉空 id 在主键上互相覆盖的问题。真机对照：同一个损坏文件，旧版把课程
  清空并替换成半个文件，新版提示「第 2 门课程不是有效记录」且数据一字未动。
- **设备侧识别放行**：识别被厂商策略拦下时（小米 CTA / 机型白名单，见上文），提示条给「去授权」
  入口直达厂商语音助手，并把「拦下」记入进程，后续录音不再反复戳识别服务；文案不再引导用户去
  系统设置里改默认识别服务（那条路在这些机型上走不通）。新增 `VoiceAuth` 与 manifest `<queries>` 声明。
- 提示条只在真的有出路时才挂按钮：「还能继续录」的那支不再错挂「去设置」。

### 1.1

- **修复语音识别无法启动**：Android 11 起启用的包可见性过滤会让应用查不到系统识别服务，
  导致识别直接不可用。补上 `<queries>` 声明，并改为「系统默认识别服务优先、显式组件兜底」，
  兼容国内 ROM 未设置默认识别服务的情况。
- **修复误导性的权限提示**：原来把识别服务返回的错误码直接说成「缺少麦克风权限」，
  而实际权限是给了的。现在会先自查权限，真缺才说缺；识别服务无响应时如实提示，
  不再出现「界面显示正在录音、实际谁都没在录」的假面板。
- **新增主题模式**：跟随系统 / 浅色 / 深色，默认跟随系统。顶栏开关改为按下即生效。
- **设置改为独立页面**：不再使用底部抽屉，AI 设置与语音转写设置同样拆成独立页面。
- **界面与动效**：统一缓动曲线与时长档位、按钮圆角改为 8dp、分段控件选中态重做、
  笔记行补展开箭头、录音状态改为脉冲指示、联网期间补加载条。

## 构建

- Windows：`./build-pc.sh`（需 JDK 17 + Android SDK build-tools 34）
- 手机端原构建配方：`src/build.sh`（未改动）

## 签名信息

**所有版本（1.0 起）均使用同一把签名密钥**，用户可正常覆盖升级。

| 项目 | 值 |
| --- | --- |
| 文件名 | `keystore.jks` |
| 密码（storepass / keypass） | `classroom-v2` |
| alias | `classroom` |
| 算法 | RSA 2048 / SHA256withRSA |
| 有效期 | 2026-09-20 → 2056-09-12（30 年） |

**证书指纹（SHA-256）—— 发布前请核对：**

```
32:A8:95:D3:77:4D:CA:F7:83:AB:4B:F5:1A:09:7E:E0:6C:B3:63:24:64:5B:87:DA:82:C9:8C:35:09:7E:E8:B0
```

核对命令：

```bash
keytool -list -keystore keystore.jks -storepass classroom-v2 | grep SHA256
apksigner verify --print-certs build/课堂笔记-v1.2.1.apk | grep 'SHA-256 digest'
```

> ⚠️ **密钥必须长期存档（云盘 + 本地各一份）。**
> Android 只允许「签名一致」的 APK 覆盖升级；一旦密钥丢失，此后所有版本都变成
> 新签名，用户必须卸载重装并会丢失数据（除非先导出备份）。
>
> ⚠️ `build-pc.sh` 在找不到 `keystore.jks` 时会**静默生成一把新的**。
> 密钥若因故不在原目录，跑脚本会悄悄换掉签名，而 `apksigner verify` 不会报错
> —— 它只验签名有效性，不比对历史指纹。**发布前务必按上面的命令核对指纹。**