## 把新建笔记和待办从弹窗改成独立页面

### 新增两个 Activity（都继承 BaseSettingsActivity，复用顶栏+滚动体+保存按钮的骨架）

**1. `NoteEditorActivity.java`**
- 通过 Intent 接收 `courseId`（必填）+ `noteId`（可选，有=编辑、无=新建）
- `title()` 返回「新建笔记」或「编辑笔记」
- `fillBody(body)` 用 `labeledField` 建 4 个字段：标题、日期、笔记内容（多行）、重点（多行）
- onRebuildUi 不需要特殊处理（字段是静态的，重建时从内存 state 重新填充）
- 底部 `saveButton("保存")`：校验标题非空 → `db.saveNote()` → `Tip.success` → `finish()`
- 内存 state：一个 `Db.Note` 对象，onCreate 时从 DB 加载（编辑）或新建空对象（新建）；fillBody 从 state 填字段值

**2. `TodoEditorActivity.java`**
- 通过 Intent 接收 `courseId`
- `title()` 返回「新建待办」
- `fillBody(body)` 建：任务内容字段、截止日期字段、优先级分段选择、提醒行
- 内存 state：一个 `TodoForm` 等价对象（title/due/priority/remindAt + EditText 引用）
- 选优先级 → 更新 state → `reapplyTheme()`（和 AiSettingsActivity 切协议同套路：先 capture EditText 值到 state，rebuild 后从 state 填回）
- 选提醒 → `DatePickerDialog` + `TimePickerDialog` 串联 → 更新 state → `reapplyTheme()`
- `ensureNotifyPermission()` 搬过来（API 33+ 通知权限申请）
- 底部 `saveButton("保存")`：校验任务内容非空 → `db.saveTodo()` → `Reminders.schedule()` → `Tip.success` → `finish()`

### 修改 CourseActivity.java
- `noteDialog(editing)` → `startActivity(new Intent(this, NoteEditorActivity.class) + extras)`
- `todoDialog()` → `startActivity(new Intent(this, TodoEditorActivity.class) + courseId)`
- 删除 `noteDialog`、`todoDialog`、`TodoForm`、`renderTodoForm`、`remindRow`、`pickRemind`、`saveTodo`、`ensureNotifyPermission`、`formLabel`、`formField`（这些只被 todo 表单用）
- `onResumed()` 里加 `renderTabs()`：编辑器 finish() 回来后页签计数也要刷新（原来 save 回调里手动调 renderTabs，现在改由 onResumed 统一兜底）
- `editCourse()` 仍用 Dialogs.form 弹窗，`DialogHost` 实现保留

### 修改 AndroidManifest.xml
- 新增 `NoteEditorActivity`：`parentActivityName=".CourseActivity"` + `windowSoftInputMode="adjustResize"`
- 新增 `TodoEditorActivity`：同上

### 刷新机制
编辑器 `finish()` 后，CourseActivity 的 `onResumed()` → `renderTabs()` + `renderContent()` 自动刷新列表和页签计数，不需要 startActivityForResult。

### 验证
构建 + 装机：新建笔记（4 字段填写+保存）、编辑笔记（值回填+改+保存）、新建待办（优先级切换+提醒选择+保存）、返回不保存（丢弃输入），全部正常 + 0 崩溃。