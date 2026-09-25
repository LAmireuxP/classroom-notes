package com.lamireuxp.classroom;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据层：SQLite。相比原版的 localStorage(JSON)，
 * 大数据量下读写与查询快一个量级，且天然支持增量更新。
 */
public class Db extends SQLiteOpenHelper {

    private static final String NAME = "classroom.db";
    private static final int VERSION = 3;

    private static Db sInstance;

    /**
     * 进程内单例。SQLiteOpenHelper 本身是线程安全的，这里 synchronized 只是防止
     * 两个线程同时初始化出两个实例（后台线程读数据 + 主线程渲染是常态）。
     */
    public static synchronized Db get(Context c) {
        if (sInstance == null) sInstance = new Db(c.getApplicationContext());
        return sInstance;
    }

    /** 库文件固定在应用私有目录，不导出、不共享（备份走 Backup 的 JSON 通道）。 */
    private Db(Context c) {
        super(c, NAME, null, VERSION);
    }

    @Override
    /** 建表与索引。三张表都是「扁平 + 外键字段」结构，没有真正的外键约束——
     *  删除课程时由 deleteCourse() 手工级联（见那里的注释：不能靠 ON DELETE CASCADE，
     *  minSdk 22 上外键需要每次连接都 PRAGMA，反而更脆）。 */
    public void onCreate(SQLiteDatabase db) {
        // deleted_at：软删除标记，0 = 正常，非 0 = 已在回收站（值是删掉的时刻）。
        // 所有「正常」查询都要带 deleted_at=0，具体见各查询；删除只写这个字段而不是 DELETE，
        // 所以「误删一整门课」是可以救回来的——这是这个字段存在的唯一理由。
        db.execSQL("CREATE TABLE courses(" +
                "id TEXT PRIMARY KEY," +
                "name TEXT NOT NULL," +
                "teacher TEXT," +
                "color TEXT," +
                "sort INTEGER DEFAULT 0," +
                "deleted_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE notes(" +
                "id TEXT PRIMARY KEY," +
                "course_id TEXT NOT NULL," +
                "title TEXT," +
                "date TEXT," +
                "content TEXT," +
                "keypoints TEXT," +
                "pinned INTEGER DEFAULT 0," +
                "created INTEGER DEFAULT 0," +
                "sort INTEGER DEFAULT 0," +
                "deleted_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE todos(" +
                "id TEXT PRIMARY KEY," +
                "course_id TEXT NOT NULL," +
                "title TEXT," +
                "due TEXT," +
                "priority TEXT," +
                "completed INTEGER DEFAULT 0," +
                "created INTEGER DEFAULT 0," +
                "sort INTEGER DEFAULT 0," +
                "remind_at INTEGER DEFAULT 0," +
                "deleted_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_notes_course ON notes(course_id)");
        db.execSQL("CREATE INDEX idx_todos_course ON todos(course_id)");
    }

    @Override
    /**
     * 版本迁移。每级只加列、不改语义，老库用 ALTER TABLE 就地升级——
     * 绝不能「删表重建」，那会把用户已有的课程和笔记一起清掉。
     *
     * v2：待办加 remind_at（提醒时间，epoch 毫秒，0 = 不提醒）。
     * v3：三张表加 deleted_at（软删除标记，0 = 正常；非 0 = 在回收站）。
     */
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) db.execSQL("ALTER TABLE todos ADD COLUMN remind_at INTEGER DEFAULT 0");
        if (oldV < 3) {
            db.execSQL("ALTER TABLE courses ADD COLUMN deleted_at INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE notes ADD COLUMN deleted_at INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE todos ADD COLUMN deleted_at INTEGER DEFAULT 0");
        }
    }

    // ---------------- 事务 ----------------

    /**
     * 把一整段写操作放进同一个事务：中途抛异常整体回滚，不会留下改了一半的库。
     *
     * 事务可以嵌套——body 里再调用自己带事务的方法（如 deleteCourse）没问题，
     * 内层失败会让整个外层一起回滚。
     */
    public void transaction(Runnable body) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            body.run();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---------------- 模型 ----------------

    public static class Course {
        public String id, name, teacher, color;
        public int noteCount, todoCount, doneCount;
    }

    public static class Note {
        public String id, courseId, title, date, content;
        public List<String> keyPoints = new ArrayList<String>();
        public boolean pinned;
        public long created;
    }

    /**
     * 跨课程搜索的一行命中：笔记 + 它所属课程的名字与标识色。
     * 搜索结果必须显示「这条来自哪门课」——只给一堆标题的话，用户得逐个点进去确认，
     * 跨课程搜索反而比翻课程列表更费劲。
     */
    public static class NoteHit {
        public Note note;
        public String courseName, courseColor;
    }

    public static class Todo {
        public String id, courseId, title, due, priority;
        public boolean completed;
        public long created;
        /** 提醒时间（epoch 毫秒）。0 = 不提醒。 */
        public long remindAt;
    }

    /**
     * 跨课程待办的一行命中：待办 + 它所属课程的名字与标识色。
     * 「全部待办」页要用，理由和 NoteHit 一样——不标出处就分不清哪条属于哪门课。
     */
    public static class TodoHit {
        public Todo todo;
        public String courseName, courseColor;
    }

    // ---------------- 课程 ----------------

    public List<Course> courses() {
        List<Course> list = new ArrayList<Course>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT c.id,c.name,c.teacher,c.color," +
                        "(SELECT COUNT(*) FROM notes n WHERE n.course_id=c.id AND n.deleted_at=0)," +
                        "(SELECT COUNT(*) FROM todos t WHERE t.course_id=c.id AND t.deleted_at=0)," +
                        "(SELECT COUNT(*) FROM todos t WHERE t.course_id=c.id AND t.deleted_at=0 AND t.completed=1) " +
                        "FROM courses c WHERE c.deleted_at=0 ORDER BY c.sort ASC", null);
        try {
            while (c.moveToNext()) {
                Course x = new Course();
                x.id = c.getString(0);
                x.name = c.getString(1);
                x.teacher = c.getString(2);
                x.color = c.getString(3);
                x.noteCount = c.getInt(4);
                x.todoCount = c.getInt(5);
                x.doneCount = c.getInt(6);
                list.add(x);
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** 按 id 取单门课程；不存在返回 null（调用方要判空）。 */
    public Course course(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,teacher,color FROM courses WHERE id=? AND deleted_at=0",
                new String[]{id});
        try {
            if (c.moveToNext()) {
                Course x = new Course();
                x.id = c.getString(0);
                x.name = c.getString(1);
                x.teacher = c.getString(2);
                x.color = c.getString(3);
                return x;
            }
        } finally {
            c.close();
        }
        return null;
    }

    /** 课程总数（首页「N 门」用）。回收站里的不计。 */
    public int courseCount() {
        return count("SELECT COUNT(*) FROM courses WHERE deleted_at=0");
    }

        public void saveCourse(String id, String name, String teacher, String color) {
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("name", name);
        v.put("teacher", teacher);
        v.put("color", color);
        // update() 的返回值就是受影响行数，0 表示没有这一行——用它替掉
        // 「先 SELECT 一次判存在」。同样一条语句少一次查询，也没有
        // 「查完到写之间那行被删掉」的竞态。
        int rows = getWritableDatabase().update("courses", v, "id=?", new String[]{id});
        if (rows == 0) {
            v.put("sort", courseCount());
            getWritableDatabase().insert("courses", null, v);
        }
    }

    /**
     * 删除课程 —— **进回收站**，不是真删；连带的笔记与待办一起打上标记。
     *
     * 整批用同一个时间戳，这是恢复的关键：靠它把「这一次级联删掉的」重新凑回一组。
     * 若各写各的 now，就分不清「随课程一起删的笔记」和「用户自己单独删掉的笔记」，
     * 恢复课程时也就不知道该把哪些笔记一起放回来。
     *
     * 手工级联而不是交给外键：SQLite 的外键约束默认关闭，要在每次打开连接时
     * PRAGMA foreign_keys=ON，漏一次就会留下孤儿数据；这里显式改更可控。
     */
    public void deleteCourse(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            ContentValues v = new ContentValues();
            v.put("deleted_at", now);
            // 只标记「当前正常」的那些：已经单独删过的笔记保留它自己原本的时间戳，
            // 这样恢复课程时不会把它一起复活
            db.update("notes", v, "course_id=? AND deleted_at=0", new String[]{id});
            db.update("todos", v, "course_id=? AND deleted_at=0", new String[]{id});
            db.update("courses", v, "id=? AND deleted_at=0", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 从回收站恢复课程：课程本身 + **同一次级联删掉的**笔记与待办。
     *
     * 判据就是 deleted_at 相等（见 deleteCourse 里为什么整批写同一个值）。
     * 用户此前单独删过的笔记时间戳不同，会继续留在回收站里——
     * 恢复一门课不该顺手复活一条他早就删掉的笔记。
     */
    public void restoreCourse(String id) {
        long at = deletedAtOf(id);
        if (at <= 0) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("deleted_at", 0);
            String[] args = {id, String.valueOf(at)};
            db.update("courses", v, "id=? AND deleted_at=?", args);
            db.update("notes", v, "course_id=? AND deleted_at=?", args);
            db.update("todos", v, "course_id=? AND deleted_at=?", args);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 某一行当前的 deleted_at（0 = 正常或不存在）。表名只传本类内的字面量。 */
    private long deletedAtOf(String courseId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT deleted_at FROM courses WHERE id=?", new String[]{courseId});
        try {
            return c.moveToNext() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * 这门课是不是在回收站里。
     *
     * 恢复单条笔记 / 待办时要用：内容必须有课程做落脚处。课程不在时把它单独恢复，
     * 这条内容就成了「活着但无处可去」——课程列表里看不到（按 deleted_at 过滤掉了），
     * 从搜索结果点进去又因为课程取不到而直接结束页面。所以这种情况要把课程一起恢复。
     */
    public boolean isCourseTrashed(String courseId) {
        return courseId != null && deletedAtOf(courseId) > 0;
    }

    /**
     * 彻底删除一门课（不可恢复）：连同它的笔记与待办一起真删。
     * 只从回收站里调——正常列表里删课程走 deleteCourse（软删）。
     */
    public void purgeCourse(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("notes", "course_id=?", new String[]{id});
            db.delete("todos", "course_id=?", new String[]{id});
            db.delete("courses", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---------------- 笔记 ----------------

    public List<Note> notes(String courseId, String query) {
        List<Note> list = new ArrayList<Note>();
        String sql = "SELECT id,course_id,title,date,content,keypoints,pinned,created FROM notes" +
                " WHERE course_id=? AND deleted_at=0";
        List<String> args = new ArrayList<String>();
        args.add(courseId);
        if (query != null && query.length() > 0) {
            // 重点清单也要搜：它是笔记里最像「考点」的部分，搜不到等于这条搜索
            // 漏掉了用户最可能记住的那几个词。与 searchNotes() 保持一致。
            sql += " AND (title LIKE ? OR content LIKE ? OR keypoints LIKE ?)";
            args.add("%" + query + "%");
            args.add("%" + query + "%");
            args.add("%" + query + "%");
        }
        // sort 优先：用户拖动排序后，顺序完全由 sort 决定；没拖过的老数据 sort 全是 0，
        // 回退到 created DESC（即原来的顺序），所以升级后顺序不变。
        sql += " ORDER BY pinned DESC, sort ASC, created DESC";
        Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
        try {
            while (c.moveToNext()) list.add(readNote(c));
        } finally {
            c.close();
        }
        return list;
    }

    /**
     * 跨课程搜索笔记（首页搜索框用）：标题 / 正文 / 重点清单三处任一命中即算。
     *
     * 与 notes(courseId, query) 的分工：那条在单门课内过滤（课程页用），这条扫全库。
     * 用 LEFT JOIN 而不是 JOIN——正常情况下 deleteCourse() 会把笔记一起级联删掉，
     * 不会有孤儿笔记；万一真有，也该能被搜到，而不是从结果里凭空消失。
     *
     * 排序与课程页保持一致（置顶优先、新的在前），这样同一个关键词在两处
     * 看到的先后顺序是一样的。
     */
    public List<NoteHit> searchNotes(String query) {
        List<NoteHit> list = new ArrayList<NoteHit>();
        String q = query == null ? "" : query.trim();
        if (q.length() == 0) return list;
        String like = "%" + q + "%";
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT n.id,n.course_id,n.title,n.date,n.content,n.keypoints,n.pinned,n.created," +
                        "c.name,c.color FROM notes n LEFT JOIN courses c ON c.id=n.course_id" +
                        " WHERE n.deleted_at=0" +
                        " AND (n.title LIKE ? OR n.content LIKE ? OR n.keypoints LIKE ?)" +
                        " ORDER BY n.pinned DESC, n.created DESC",
                new String[]{like, like, like});
        try {
            while (c.moveToNext()) {
                NoteHit h = new NoteHit();
                h.note = readNote(c);            // 前 8 列的顺序与 readNote 对齐
                h.courseName = c.getString(8);
                h.courseColor = c.getString(9);
                list.add(h);
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** 按 id 取单条笔记；不存在返回 null。 */
    public Note note(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,course_id,title,date,content,keypoints,pinned,created FROM notes" +
                        " WHERE id=? AND deleted_at=0",
                new String[]{id});
        try {
            if (c.moveToNext()) return readNote(c);
        } finally {
            c.close();
        }
        return null;
    }

    /**
     * 读一行笔记，按**列序号**取值，所以顺序必须和查询里那段
     * `SELECT id,course_id,title,date,content,keypoints,pinned,created` 完全一致。
     * 以后给 notes 加字段时要四处一起改：建表、本段查询、searchNotes() 的查询、这里。
     */
    private Note readNote(Cursor c) {
        Note n = new Note();
        n.id = c.getString(0);
        n.courseId = c.getString(1);
        n.title = c.getString(2);
        n.date = c.getString(3);
        n.content = c.getString(4);
        n.keyPoints = splitString(c.getString(5));
        n.pinned = c.getInt(6) == 1;
        n.created = c.getLong(7);
        return n;
    }

        public void saveNote(Note n) {
        ContentValues v = new ContentValues();
        v.put("id", n.id);
        v.put("course_id", n.courseId);
        v.put("title", n.title);
        v.put("date", n.date);
        v.put("content", n.content);
        v.put("keypoints", joinString(n.keyPoints));
        v.put("pinned", n.pinned ? 1 : 0);
        // 更新时不写 created（保留原创建时间），只有真要插入才带上。
        int rows = getWritableDatabase().update("notes", v, "id=?", new String[]{n.id});
        if (rows == 0) {
            v.put("created", n.created == 0 ? System.currentTimeMillis() : n.created);
            // 新笔记插到最前，保住「新建的记在最上面」这个原有行为（见 topSort 的说明）。
            // 不显式设 sort 的话它默认 0，在用户拖动排序后排序值已有 0..N 的情况下，
            // 新笔记会凭 0 插到中间某个位置。
            v.put("sort", topSort("notes", "course_id=? AND deleted_at=0", n.courseId));
            getWritableDatabase().insert("notes", null, v);
        }
    }

    /** 置顶/取消置顶（只改一个字段，不动 updatedAt——置顶不该把「修改时间」刷新掉）。 */
    public void setNotePinned(String id, boolean pinned) {
        ContentValues v = new ContentValues();
        v.put("pinned", pinned ? 1 : 0);
        getWritableDatabase().update("notes", v, "id=?", new String[]{id});
    }

    /**
     * 重排笔记：传入**完整的新顺序**（id 列表），把 sort 写成 0..N-1。
     * 调用方保证 ids 只含当前课程的、未删的笔记——从渲染列表里来，不会有脏数据。
     * 置顶/未置顶的相对位置由列表本身决定（排序是 pinned DESC, sort ASC，
     * pinned 的 sort 是 0..k、未置顶是 k+1..N，各自组内顺序都对）。
     */
    public void reorderNotes(List<String> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("sort", i);
                db.update("notes", v, "id=?", new String[]{ids.get(i)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 删除单条笔记：进回收站（可恢复），不是真删。 */
    public void deleteNote(String id) {
        softDelete("notes", id);
    }

    /** 从回收站恢复一条笔记。 */
    public void restoreNote(String id) {
        restoreOne("notes", id);
    }

    /** 彻底删除一条笔记（不可恢复）。 */
    public void purgeNote(String id) {
        hardDelete("notes", id);
    }

    // ---------------- 待办 ----------------

    public List<Todo> todos(String courseId) {
        List<Todo> list = new ArrayList<Todo>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,course_id,title,due,priority,completed,created,remind_at FROM todos " +
                        "WHERE course_id=? AND deleted_at=0 ORDER BY completed ASC, sort ASC, created DESC",
                new String[]{courseId});
        try {
            while (c.moveToNext()) list.add(readTodo(c));
        } finally {
            c.close();
        }
        return list;
    }

    /**
     * 全部课程的**未完成**待办，按「先按截止日期、再按优先级」排。
     * 「全部待办」页用——回答的是「我接下来要交什么」，所以：
     *  1. 已完成的不进来（那已经不是「要做的事」了）；
     *  2. 没有截止日期的排在最后：它们没有时间压力，不该挤在眼前；
     *  3. 同日之内高优先级在前。
     * due 是 "YYYY-MM-DD" 文本，字典序就是时间序，所以直接按文本排即可。
     */
    public List<TodoHit> openTodos() {
        List<TodoHit> list = new ArrayList<TodoHit>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT t.id,t.course_id,t.title,t.due,t.priority,t.completed,t.created,t.remind_at," +
                        "c.name,c.color FROM todos t LEFT JOIN courses c ON c.id=t.course_id" +
                        " WHERE t.completed=0 AND t.deleted_at=0" +
                        " ORDER BY (CASE WHEN t.due IS NULL OR t.due='' THEN 1 ELSE 0 END)," +
                        " t.due ASC," +
                        " (CASE t.priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END)," +
                        " t.created DESC", null);
        try {
            while (c.moveToNext()) {
                TodoHit h = new TodoHit();
                h.todo = readTodo(c);        // 前 8 列的顺序与 readTodo 对齐
                h.courseName = c.getString(8);
                h.courseColor = c.getString(9);
                list.add(h);
            }
        } finally {
            c.close();
        }
        return list;
    }

    /**
     * 还没到点、且未完成的提醒（开机后重排用）。返回 [id, remind_at] 由调用方再取详情——
     * 这里只需要「哪些要排」，不必把整条待办读出来。
     */
    public List<Todo> pendingReminders(long now) {
        List<Todo> list = new ArrayList<Todo>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,course_id,title,due,priority,completed,created,remind_at FROM todos " +
                        "WHERE completed=0 AND deleted_at=0 AND remind_at>? ORDER BY remind_at ASC",
                new String[]{String.valueOf(now)});
        try {
            while (c.moveToNext()) list.add(readTodo(c));
        } finally {
            c.close();
        }
        return list;
    }

    /** 按 id 取单条待办；不存在返回 null。 */
    public Todo todo(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,course_id,title,due,priority,completed,created,remind_at FROM todos" +
                        " WHERE id=? AND deleted_at=0",
                new String[]{id});
        try {
            if (c.moveToNext()) return readTodo(c);
        } finally {
            c.close();
        }
        return null;
    }

    /**
     * 读一行待办，同样按列序号取值，顺序与查询里的
     * `SELECT id,course_id,title,due,priority,completed,created,remind_at` 对齐
     * （加字段时要五处一起改：建表、迁移、本段查询、searchNotes 之外的各查询、这里）。
     */
    private Todo readTodo(Cursor c) {
        Todo t = new Todo();
        t.id = c.getString(0);
        t.courseId = c.getString(1);
        t.title = c.getString(2);
        t.due = c.getString(3);
        t.priority = c.getString(4);
        t.completed = c.getInt(5) == 1;
        t.created = c.getLong(6);
        t.remindAt = c.getLong(7);
        return t;
    }

        public void saveTodo(Todo t) {
        ContentValues v = new ContentValues();
        v.put("id", t.id);
        v.put("course_id", t.courseId);
        v.put("title", t.title);
        v.put("due", t.due);
        v.put("priority", t.priority == null ? "medium" : t.priority);
        v.put("completed", t.completed ? 1 : 0);
        v.put("remind_at", t.remindAt);
        int rows = getWritableDatabase().update("todos", v, "id=?", new String[]{t.id});
        if (rows == 0) {
            v.put("created", t.created == 0 ? System.currentTimeMillis() : t.created);
            // 新待办插到最前，理由同 saveNote
            v.put("sort", topSort("todos", "course_id=? AND deleted_at=0", t.courseId));
            getWritableDatabase().insert("todos", null, v);
        }
    }

    /**
     * 重排待办：传入完整的新顺序（id 列表），把 sort 写成 0..N-1。
     * 「进行中 / 已完成」的分组由 ORDER BY completed ASC 决定，两组各自组内顺序按 sort。
     */
    public void reorderTodos(List<String> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("sort", i);
                db.update("todos", v, "id=?", new String[]{ids.get(i)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 某个分组里「排在最前」可用的 sort：当前最小值 -1。
     *
     * 为什么是往前插而不是往后追加：加拖动排序之前，列表是 `created DESC`——**新建的
     * 记在最上面**。这是用户已经习惯的行为，不该因为加了拖动就悄悄改成「新条目掉到末尾」
     * （长列表里新建完还得往下翻去找）。往前插保住原行为，同时顺序仍然完全可拖。
     * 取负值不影响正确性：拖动一次会把 sort 重写成 0..N-1。
     * 表名与条件只由本类内的字面量传入，不接受外部拼接。
     */
    private long topSort(String table, String where, String courseId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT IFNULL(MIN(sort), 1) - 1 FROM " + table + " WHERE " + where,
                new String[]{courseId});
        try {
            return c.moveToNext() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    /** 勾选/取消勾选待办。 */
    public void setTodoCompleted(String id, boolean completed) {
        ContentValues v = new ContentValues();
        v.put("completed", completed ? 1 : 0);
        getWritableDatabase().update("todos", v, "id=?", new String[]{id});
    }

    /** 删除单条待办：进回收站（可恢复）。 */
    public void deleteTodo(String id) {
        softDelete("todos", id);
    }

    /** 从回收站恢复一条待办。 */
    public void restoreTodo(String id) {
        restoreOne("todos", id);
    }

    /** 彻底删除一条待办（不可恢复）。 */
    public void purgeTodo(String id) {
        hardDelete("todos", id);
    }

    // ---------------- 回收站 ----------------

    /**
     * 软删除一行：只写 deleted_at，数据留在表里等用户决定恢复还是彻底删。
     * 带上 deleted_at=0 条件，重复删除不会把时间戳刷新成「刚删的」——
     * 否则连点两下删除，这一项在回收站里的「删除时间」就变成了第二次点的时间。
     */
    private void softDelete(String table, String id) {
        ContentValues v = new ContentValues();
        v.put("deleted_at", System.currentTimeMillis());
        getWritableDatabase().update(table, v, "id=? AND deleted_at=0", new String[]{id});
    }

    /** 恢复单行：把 deleted_at 清回 0。 */
    private void restoreOne(String table, String id) {
        ContentValues v = new ContentValues();
        v.put("deleted_at", 0);
        getWritableDatabase().update(table, v, "id=?", new String[]{id});
    }

    /** 真删一行，只从回收站里调（正常列表里的删除一律走软删）。 */
    private void hardDelete(String table, String id) {
        getWritableDatabase().delete(table, "id=?", new String[]{id});
    }

    /** 回收站里的一项。kind 决定恢复 / 彻底删除时找哪张表。 */
    public static class TrashItem {
        public static final String KIND_COURSE = "course";
        public static final String KIND_NOTE = "note";
        public static final String KIND_TODO = "todo";

        public String kind, id, title, subtitle;
        /** 笔记 / 待办所属的课程 id（课程项自己为空）。恢复时要用它判断课程是否也在回收站里。 */
        public String courseId;
        public long deletedAt;
    }

    /**
     * 回收站内容，按删除时间倒序（刚删的最上面）。
     *
     * 三张表分别查、在 Java 里合并排序，而不是写 UNION：三类东西的字段完全不同，
     * UNION 得先拼成同一种形状再拆开，绕一圈还更容易错。
     *
     * 笔记 / 待办的所属课程名用 LEFT JOIN 直接取，**不给 courses 加 deleted_at 过滤**：
     * 课程很可能也在回收站里（整门课一起删的），加了过滤就查不到名字，
     * 用户会看到一堆「未分类」，反而认不出哪条是哪门课的。
     */
    public List<TrashItem> trash() {
        List<TrashItem> list = new ArrayList<TrashItem>();
        SQLiteDatabase r = getReadableDatabase();

        Cursor c = r.rawQuery("SELECT id,name,deleted_at FROM courses WHERE deleted_at>0", null);
        try {
            while (c.moveToNext()) {
                TrashItem it = new TrashItem();
                it.kind = TrashItem.KIND_COURSE;
                it.id = c.getString(0);
                it.title = c.getString(1);
                it.subtitle = "整门课程（笔记与待办一并恢复）";
                it.deletedAt = c.getLong(2);
                list.add(it);
            }
        } finally {
            c.close();
        }

        c = r.rawQuery("SELECT n.id,n.title,n.date,c.name,n.deleted_at,n.course_id FROM notes n" +
                " LEFT JOIN courses c ON c.id=n.course_id WHERE n.deleted_at>0", null);
        try {
            while (c.moveToNext()) {
                TrashItem it = new TrashItem();
                it.kind = TrashItem.KIND_NOTE;
                it.id = c.getString(0);
                it.title = c.getString(1);
                it.subtitle = "笔记 · " + nzOr(c.getString(3), "未分类")
                        + (nzOr(c.getString(2), "").length() > 0 ? " · " + c.getString(2) : "");
                it.deletedAt = c.getLong(4);
                it.courseId = c.getString(5);
                list.add(it);
            }
        } finally {
            c.close();
        }

        c = r.rawQuery("SELECT t.id,t.title,t.due,t.priority,c.name,t.deleted_at,t.course_id" +
                " FROM todos t LEFT JOIN courses c ON c.id=t.course_id WHERE t.deleted_at>0", null);
        try {
            while (c.moveToNext()) {
                TrashItem it = new TrashItem();
                it.kind = TrashItem.KIND_TODO;
                it.id = c.getString(0);
                it.title = c.getString(1);
                String due = nzOr(c.getString(2), "");
                it.subtitle = "待办 · " + nzOr(c.getString(4), "未分类")
                        + (due.length() > 0 ? " · 截止 " + due : "");
                it.deletedAt = c.getLong(5);
                it.courseId = c.getString(6);
                list.add(it);
            }
        } finally {
            c.close();
        }

        // 刚删的排最前：用户进回收站十有八九是来找「刚刚误删的那个」
        java.util.Collections.sort(list, new java.util.Comparator<TrashItem>() {
            @Override public int compare(TrashItem a, TrashItem b) {
                return a.deletedAt == b.deletedAt ? 0
                        : (a.deletedAt < b.deletedAt ? 1 : -1);
            }
        });
        return list;
    }

    /** 回收站里的条目数（设置页上用来决定要不要显示入口 / 提示有多少东西待清理）。 */
    public int trashCount() {
        return count("SELECT (SELECT COUNT(*) FROM courses WHERE deleted_at>0)" +
                " + (SELECT COUNT(*) FROM notes WHERE deleted_at>0)" +
                " + (SELECT COUNT(*) FROM todos WHERE deleted_at>0)");
    }

    /**
     * 清空回收站：**不可恢复**，真删。
     * 课程要连带它的笔记待办一起删——否则课程没了、它的笔记还留在回收站里，
     * 点恢复会因为课程不存在而变成一条查不到父课程的孤儿。
     */
    public void emptyTrash() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("notes", "deleted_at>0", null);
            db.delete("todos", "deleted_at>0", null);
            db.delete("courses", "deleted_at>0", null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** null / 空串都换成默认值。 */
    private static String nzOr(String s, String fallback) {
        return (s == null || s.length() == 0) ? fallback : s;
    }

    // ---------------- 批量导入 ----------------

    /**
     * 清空 + 重建，全程一个事务。
     *
     * 和逐条 saveXxx() 的两点区别：
     *  1. 语句只编译一次（compileStatement），循环里只做 bind + execute。
     *     逐条走 saveNote() 的话，SQLite 每条都要重新解析一遍 SQL。
     *  2. 完全不查存在性。导入是「先清空再重建」，表里必然没有这些 id——
     *     原来每条都要 SELECT 一次，500 条笔记就是 500 次白跑的查询。
     *
     * 事务收在这里，调用方不必再自己包一层：deleteCourse() 内部自带事务，
     * 嵌在外层事务里一旦失败只回滚内层，外层照样提交，会留下半新半旧的库。
     */
    public void replaceAll(final List<Course> courses, final List<Note> notes,
                           final List<Todo> todos) {
        transaction(new Runnable() {
            @Override public void run() {
                SQLiteDatabase db = getWritableDatabase();
                db.delete("notes", null, null);
                db.delete("todos", null, null);
                db.delete("courses", null, null);

                SQLiteStatement sc = db.compileStatement(
                        "INSERT INTO courses(id,name,teacher,color,sort) VALUES(?,?,?,?,?)");
                for (int i = 0; i < courses.size(); i++) {
                    Course c = courses.get(i);
                    sc.clearBindings();
                    sc.bindString(1, nz(c.id));
                    sc.bindString(2, nz(c.name));
                    sc.bindString(3, nz(c.teacher));
                    sc.bindString(4, nz(c.color));
                    sc.bindLong(5, i);        // sort 按导入顺序，决定列表里的先后
                    sc.execute();
                }

                SQLiteStatement sn = db.compileStatement(
                        "INSERT INTO notes(id,course_id,title,date,content,keypoints,pinned,created)"
                                + " VALUES(?,?,?,?,?,?,?,?)");
                for (Note n : notes) {
                    sn.clearBindings();
                    sn.bindString(1, nz(n.id));
                    sn.bindString(2, nz(n.courseId));
                    sn.bindString(3, nz(n.title));
                    sn.bindString(4, nz(n.date));
                    sn.bindString(5, nz(n.content));
                    sn.bindString(6, joinString(n.keyPoints));
                    sn.bindLong(7, n.pinned ? 1 : 0);
                    sn.bindLong(8, n.created == 0 ? System.currentTimeMillis() : n.created);
                    sn.execute();
                }

                SQLiteStatement st = db.compileStatement(
                        "INSERT INTO todos(id,course_id,title,due,priority,completed,created,remind_at)"
                                + " VALUES(?,?,?,?,?,?,?,?)");
                for (Todo t : todos) {
                    st.clearBindings();
                    st.bindString(1, nz(t.id));
                    st.bindString(2, nz(t.courseId));
                    st.bindString(3, nz(t.title));
                    st.bindString(4, nz(t.due));
                    st.bindString(5, t.priority == null ? "medium" : t.priority);
                    st.bindLong(6, t.completed ? 1 : 0);
                    st.bindLong(7, t.created == 0 ? System.currentTimeMillis() : t.created);
                    st.bindLong(8, t.remindAt);
                    st.execute();
                }
            }
        });
    }

    /** null 安全取字符串：SQLite 里空字段读出来是 null，界面与拼接都按空串处理。 */
    private static String nz(String s) { return s == null ? "" : s; }

    // ---------------- 统计 ----------------

    /** 优先级的固定顺序，兼 todoByPriority() 的下标含义。 */
    public static final String[] PRIORITY_KEYS = {"high", "medium", "low"};

    /**
     * 首页统计：[笔记数, 待办总数, 已完成待办数]。
     * 三条独立 COUNT，不 JOIN——三张表的统计互不相关，JOIN 只会把行数乘起来。
     */
    public int[] totals() {
        int notes = count("SELECT COUNT(*) FROM notes WHERE deleted_at=0");
        int todos = count("SELECT COUNT(*) FROM todos WHERE deleted_at=0");
        int done = count("SELECT COUNT(*) FROM todos WHERE deleted_at=0 AND completed=1");
        return new int[]{notes, todos, done};
    }

    /**
     * 按优先级统计待办：[总数, 已完成]，下标 0/1/2 = 高 / 中 / 低。
     *
     * 用一条 GROUP BY 查出来再在 Java 里归类：库里可能留着历史数据，priority 是
     * NULL 或空串，那些按「中」算（与写入时的兜底一致）。要是按 high/medium/low
     * 查三次，这些行哪一段都不进，三段之和对不上总待办数。
     *
     * @param courseId 只统计某门课程；传 null 统计全部（主页面用）
     */
    public int[][] todoByPriority(String courseId) {
        int[][] out = new int[3][2];
        // 回收站里的待办不计入进度：它们已经不是「要做的事」了
        String sql = "SELECT priority, completed, COUNT(*) FROM todos"
                + (courseId == null ? " WHERE deleted_at=0" : " WHERE deleted_at=0 AND course_id=?")
                + " GROUP BY priority, completed";
        Cursor c = getReadableDatabase().rawQuery(sql,
                courseId == null ? null : new String[]{courseId});
        try {
            while (c.moveToNext()) {
                int i = priorityIndex(c.getString(0));
                int n = c.getInt(2);
                out[i][0] += n;
                if (c.getInt(1) == 1) out[i][1] += n;
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 全部课程的待办统计。 */
    public int[][] todoByPriority() {
        return todoByPriority(null);
    }

    /** 优先级 → 下标：high=0 / medium=1 / low=2；NULL、空串、脏值都按「中」算。 */
    public static int priorityIndex(String p) {
        if ("high".equals(p)) return 0;
        if ("low".equals(p)) return 2;
        return 1;
    }

    /** 跑一条 COUNT 查询取第一列。查询语句都是本类内的字面量，不接受外部拼接。 */
    private int count(String sql) {
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        try {
            return c.moveToNext() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    // ---------------- 工具 ----------------

    public static List<String> splitString(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.length() > 0) out.add(t);
        }
        return out;
    }

    /** 重点清单按行拼接（与 splitString 互逆，落库时用）。 */
    public static String joinString(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(list.get(i).trim());
        }
        return sb.toString();
    }
}