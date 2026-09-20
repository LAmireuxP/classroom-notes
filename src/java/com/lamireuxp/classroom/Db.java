package com.lamireuxp.classroom;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

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
        if (course(id) == null) {
            v.put("sort", courseCount());
            getWritableDatabase().insert("courses", null, v);
        } else {
            getWritableDatabase().update("courses", v, "id=?", new String[]{id});
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
        v.put("created", n.created == 0 ? System.currentTimeMillis() : n.created);
        if (note(n.id) == null) {
            getWritableDatabase().insert("notes", null, v);
        } else {
            v.remove("created");
            getWritableDatabase().update("notes", v, "id=?", new String[]{n.id});
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
        v.put("created", t.created == 0 ? System.currentTimeMillis() : t.created);
        if (todo(t.id) == null) {
            getWritableDatabase().insert("todos", null, v);
        } else {
            v.remove("created");
            getWritableDatabase().update("todos", v, "id=?", new String[]{t.id});
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