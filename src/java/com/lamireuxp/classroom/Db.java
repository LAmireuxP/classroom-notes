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
    private static final int VERSION = 1;

    private static Db sInstance;

    public static synchronized Db get(Context c) {
        if (sInstance == null) sInstance = new Db(c.getApplicationContext());
        return sInstance;
    }

    private Db(Context c) {
        super(c, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE courses(" +
                "id TEXT PRIMARY KEY," +
                "name TEXT NOT NULL," +
                "teacher TEXT," +
                "color TEXT," +
                "sort INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE notes(" +
                "id TEXT PRIMARY KEY," +
                "course_id TEXT NOT NULL," +
                "title TEXT," +
                "date TEXT," +
                "content TEXT," +
                "keypoints TEXT," +
                "pinned INTEGER DEFAULT 0," +
                "created INTEGER DEFAULT 0," +
                "sort INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE todos(" +
                "id TEXT PRIMARY KEY," +
                "course_id TEXT NOT NULL," +
                "title TEXT," +
                "due TEXT," +
                "priority TEXT," +
                "completed INTEGER DEFAULT 0," +
                "created INTEGER DEFAULT 0," +
                "sort INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_notes_course ON notes(course_id)");
        db.execSQL("CREATE INDEX idx_todos_course ON todos(course_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v1 起步，暂无迁移
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

    public static class Todo {
        public String id, courseId, title, due, priority;
        public boolean completed;
        public long created;
    }

    // ---------------- 课程 ----------------

    public List<Course> courses() {
        List<Course> list = new ArrayList<Course>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT c.id,c.name,c.teacher,c.color," +
                        "(SELECT COUNT(*) FROM notes n WHERE n.course_id=c.id)," +
                        "(SELECT COUNT(*) FROM todos t WHERE t.course_id=c.id)," +
                        "(SELECT COUNT(*) FROM todos t WHERE t.course_id=c.id AND t.completed=1) " +
                        "FROM courses c ORDER BY c.sort ASC", null);
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

    public Course course(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,teacher,color FROM courses WHERE id=?", new String[]{id});
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

    public int courseCount() {
        return count("SELECT COUNT(*) FROM courses");
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

    public void deleteCourse(String id) {
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
        String sql = "SELECT id,course_id,title,date,content,keypoints,pinned,created FROM notes WHERE course_id=?";
        List<String> args = new ArrayList<String>();
        args.add(courseId);
        if (query != null && query.length() > 0) {
            sql += " AND (title LIKE ? OR content LIKE ?)";
            args.add("%" + query + "%");
            args.add("%" + query + "%");
        }
        sql += " ORDER BY pinned DESC, created DESC";
        Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
        try {
            while (c.moveToNext()) list.add(readNote(c));
        } finally {
            c.close();
        }
        return list;
    }

    public Note note(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,course_id,title,date,content,keypoints,pinned,created FROM notes WHERE id=?",
                new String[]{id});
        try {
            if (c.moveToNext()) return readNote(c);
        } finally {
            c.close();
        }
        return null;
    }

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
            getWritableDatabase().insert("notes", null, v);
        }
    }

    public void setNotePinned(String id, boolean pinned) {
        ContentValues v = new ContentValues();
        v.put("pinned", pinned ? 1 : 0);
        getWritableDatabase().update("notes", v, "id=?", new String[]{id});
    }

    public void deleteNote(String id) {
        getWritableDatabase().delete("notes", "id=?", new String[]{id});
    }

    // ---------------- 待办 ----------------

    public List<Todo> todos(String courseId) {
        List<Todo> list = new ArrayList<Todo>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,course_id,title,due,priority,completed,created FROM todos WHERE course_id=? " +
                        "ORDER BY completed ASC, created DESC", new String[]{courseId});
        try {
            while (c.moveToNext()) list.add(readTodo(c));
        } finally {
            c.close();
        }
        return list;
    }

    public Todo todo(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,course_id,title,due,priority,completed,created FROM todos WHERE id=?",
                new String[]{id});
        try {
            if (c.moveToNext()) return readTodo(c);
        } finally {
            c.close();
        }
        return null;
    }

    private Todo readTodo(Cursor c) {
        Todo t = new Todo();
        t.id = c.getString(0);
        t.courseId = c.getString(1);
        t.title = c.getString(2);
        t.due = c.getString(3);
        t.priority = c.getString(4);
        t.completed = c.getInt(5) == 1;
        t.created = c.getLong(6);
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
        int rows = getWritableDatabase().update("todos", v, "id=?", new String[]{t.id});
        if (rows == 0) {
            v.put("created", t.created == 0 ? System.currentTimeMillis() : t.created);
            getWritableDatabase().insert("todos", null, v);
        }
    }

    public void setTodoCompleted(String id, boolean completed) {
        ContentValues v = new ContentValues();
        v.put("completed", completed ? 1 : 0);
        getWritableDatabase().update("todos", v, "id=?", new String[]{id});
    }

    public void deleteTodo(String id) {
        getWritableDatabase().delete("todos", "id=?", new String[]{id});
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
                        "INSERT INTO todos(id,course_id,title,due,priority,completed,created)"
                                + " VALUES(?,?,?,?,?,?,?)");
                for (Todo t : todos) {
                    st.clearBindings();
                    st.bindString(1, nz(t.id));
                    st.bindString(2, nz(t.courseId));
                    st.bindString(3, nz(t.title));
                    st.bindString(4, nz(t.due));
                    st.bindString(5, t.priority == null ? "medium" : t.priority);
                    st.bindLong(6, t.completed ? 1 : 0);
                    st.bindLong(7, t.created == 0 ? System.currentTimeMillis() : t.created);
                    st.execute();
                }
            }
        });
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ---------------- 统计 ----------------

    public int[] totals() {
        int notes = count("SELECT COUNT(*) FROM notes");
        int todos = count("SELECT COUNT(*) FROM todos");
        int done = count("SELECT COUNT(*) FROM todos WHERE completed=1");
        return new int[]{notes, todos, done};
    }

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

    public static String joinString(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(list.get(i).trim());
        }
        return sb.toString();
    }
}