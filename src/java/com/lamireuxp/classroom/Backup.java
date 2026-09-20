package com.lamireuxp.classroom;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 备份：JSON 导出/导入。
 * 通过 SAF（系统文件选择器）真正落地到用户选定位置，修掉原版在 WebView 里点了没反应的问题。
 * 导出同时提供 Markdown。
 */
public final class Backup {

    private Backup() {}

    // ---------------- JSON 备份 ----------------

    public static String exportJson(Context c) throws Exception {
        Db db = Db.get(c);
        JSONObject root = new JSONObject();
        root.put("format", "classroom-v2");
        root.put("exportedAt", System.currentTimeMillis());
        JSONArray courses = new JSONArray();
        for (Db.Course course : db.courses()) {
            JSONObject co = new JSONObject();
            co.put("id", course.id);
            co.put("name", nz(course.name));
            co.put("teacher", nz(course.teacher));
            co.put("color", nz(course.color));

            JSONArray notes = new JSONArray();
            for (Db.Note n : db.notes(course.id, null)) {
                JSONObject o = new JSONObject();
                o.put("id", n.id);
                o.put("title", nz(n.title));
                o.put("date", nz(n.date));
                o.put("content", nz(n.content));
                o.put("pinned", n.pinned);
                o.put("created", n.created);
                JSONArray kp = new JSONArray();
                for (String k : n.keyPoints) kp.put(k);
                o.put("keyPoints", kp);
                notes.put(o);
            }
            co.put("notes", notes);

            JSONArray todos = new JSONArray();
            for (Db.Todo t : db.todos(course.id)) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("title", nz(t.title));
                o.put("due", nz(t.due));
                o.put("priority", nz(t.priority));
                o.put("completed", t.completed);
                o.put("created", t.created);
                todos.put(o);
            }
            co.put("todos", todos);
            courses.put(co);
        }
        root.put("courses", courses);
        return root.toString(2);
    }

    /** 导入：清空后重建。返回导入的课程数。 */
    public static int importJson(Context c, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray courses = root.optJSONArray("courses");
        if (courses == null) throw new Exception("文件格式不正确");
        Db db = Db.get(c);

        // 清空现有数据
        List<Db.Course> old = db.courses();
        for (Db.Course o : old) db.deleteCourse(o.id);

        int count = 0;
        for (int i = 0; i < courses.length(); i++) {
            JSONObject co = courses.getJSONObject(i);
            String cid = co.optString("id", Id.gen());
            db.saveCourse(cid, co.optString("name", "未命名课程"),
                    co.optString("teacher", ""), co.optString("color", "#4f5bff"));

            JSONArray notes = co.optJSONArray("notes");
            if (notes != null) {
                for (int j = 0; j < notes.length(); j++) {
                    JSONObject o = notes.getJSONObject(j);
                    Db.Note n = new Db.Note();
                    n.id = o.optString("id", Id.gen());
                    n.courseId = cid;
                    n.title = o.optString("title", "");
                    n.date = o.optString("date", "");
                    n.content = o.optString("content", "");
                    n.pinned = o.optBoolean("pinned", false);
                    n.created = o.optLong("created", System.currentTimeMillis());
                    JSONArray kp = o.optJSONArray("keyPoints");
                    if (kp != null) {
                        for (int k = 0; k < kp.length(); k++) n.keyPoints.add(kp.optString(k, ""));
                    }
                    db.saveNote(n);
                }
            }

            JSONArray todos = co.optJSONArray("todos");
            if (todos != null) {
                for (int j = 0; j < todos.length(); j++) {
                    JSONObject o = todos.getJSONObject(j);
                    Db.Todo t = new Db.Todo();
                    t.id = o.optString("id", Id.gen());
                    t.courseId = cid;
                    t.title = o.optString("title", "");
                    t.due = o.optString("due", "");
                    t.priority = o.optString("priority", "medium");
                    t.completed = o.optBoolean("completed", false);
                    t.created = o.optLong("created", System.currentTimeMillis());
                    db.saveTodo(t);
                }
            }
            count++;
        }
        return count;
    }

    // ---------------- Markdown ----------------

    public static String exportMarkdown(Context c) {
        Db db = Db.get(c);
        StringBuilder sb = new StringBuilder();
        String[] label = {"低", "中", "高"};
        for (Db.Course course : db.courses()) {
            sb.append("# ").append(nz(course.name)).append("\n\n");
            if (course.teacher != null && course.teacher.length() > 0) {
                sb.append("> 授课教师：").append(course.teacher).append("\n\n");
            }
            List<Db.Note> notes = db.notes(course.id, null);
            for (Db.Note n : notes) {
                sb.append("## ").append(nz(n.title)).append("\n\n");
                if (n.date != null && n.date.length() > 0) {
                    sb.append("- 日期：").append(n.date).append("\n");
                }
                if (n.content != null && n.content.length() > 0) {
                    sb.append("\n").append(n.content).append("\n");
                }
                if (n.keyPoints != null && !n.keyPoints.isEmpty()) {
                    sb.append("\n**重点**\n");
                    for (String k : n.keyPoints) sb.append("- ").append(k).append("\n");
                }
                sb.append("\n");
            }
            List<Db.Todo> todos = db.todos(course.id);
            if (!todos.isEmpty()) {
                sb.append("## 待办\n\n");
                for (Db.Todo t : todos) {
                    sb.append("- [").append(t.completed ? "x" : " ").append("] ")
                            .append(nz(t.title));
                    if (t.due != null && t.due.length() > 0) sb.append("（截止 ").append(t.due).append("）");
                    sb.append("\n");
                }
                sb.append("\n");
            }
            sb.append("---\n\n");
        }
        if (sb.length() == 0) sb.append("（暂无内容）\n");
        return sb.toString();
    }

    // ---------------- 读写 ----------------

    public static void writeText(Context c, Uri uri, String text) throws Exception {
        OutputStream os = c.getContentResolver().openOutputStream(uri, "wt");
        if (os == null) throw new Exception("无法写入该位置");
        try {
            os.write(text.getBytes("UTF-8"));
            os.flush();
        } finally {
            os.close();
        }
    }

    public static String readText(Context c, Uri uri) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法读取该文件");
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}